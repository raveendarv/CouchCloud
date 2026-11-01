package io.freemytv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Your TMDB account on the TV (Outbox has the rules; this is the disk, the token and the wire). The token (a v4 user
 * access token, put in files/recs/tmdb-token by tools/recs/recs.py token, as JSON with the account id or bare: the
 * account id is then the token's "sub") sends what you do here (POST/DELETE /{kind}/{id}/rating, POST
 * /account/{id}/watchlist) and pulls the account (GET /4/account/{id}/{movie|tv}/{rated|watchlist|favorites}) every
 * PULL_EVERY into account.json; outbox.json holds what hasn't landed. Only the home screen's process writes them;
 * status() reads them fresh for the main process (RecsInbox, Settings).
 */
final class Account {
    static final String TOKEN_FILE = "tmdb-token", ACCOUNT_FILE = "account.json", OUTBOX_FILE = "outbox.json";
    static final long PULL_EVERY = 6 * 3600_000L;
    /** After a failed pull (every page is a request), the next try waits this long. */
    private static final long PULL_RETRY = 10 * 60_000L;
    private static final String API3 = "https://api.themoviedb.org/3", API4 = "https://api.themoviedb.org/4";
    static final String REFUSED = "TMDB refused the token";

    static final class Token {
        final String access, accountId;
        final long stamp;   // the file's: a new push changes it

        Token(String access, String accountId, long stamp) {
            this.access = access;
            this.accountId = accountId;
            this.stamp = stamp;
        }
    }

    private static final Object lock = new Object();
    private static Outbox state;
    /** The token's stamp when TMDB answered 401/403 to it: no more tries until another token is pushed. */
    private static long refusedStamp;
    private static long pullTriedAt;
    private static String lastError = "";
    private static Token token;
    private static long tokenStamp = -1;

    private Account() {}

    // MARK: - The token

    /** Your account's token, or null (then what you do here waits in the outbox). */
    static Token token(Context c) {
        File f = new File(ProfileStore.dir(c), TOKEN_FILE);
        long stamp = f.exists() ? f.lastModified() * 31 + f.length() : 0;
        synchronized (lock) {
            if (stamp == tokenStamp) return token;
            tokenStamp = stamp;
            token = stamp == 0 ? null : readToken(f, stamp);
            return token;
        }
    }

    private static Token readToken(File f, long stamp) {
        try (FileInputStream in = new FileInputStream(f)) {
            String text = read(in).trim();
            if (text.isEmpty()) return null;
            String access = text, id = "";
            if (text.startsWith("{")) {
                JSONObject o = new JSONObject(text);
                access = o.optString("access_token");
                id = o.optString("account_id");
            }
            if (id.isEmpty()) id = subject(access);
            return access.isEmpty() || id.isEmpty() ? null : new Token(access, id, stamp);
        } catch (IOException | RuntimeException | org.json.JSONException e) {
            AppLog.w("tmdb token: " + e.getMessage());
            return null;
        }
    }

    /** A v4 access token is a JWT whose "sub" is the account's id. */
    private static String subject(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return "";
            byte[] payload = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            return new JSONObject(new String(payload, StandardCharsets.UTF_8)).optString("sub");
        } catch (RuntimeException | org.json.JSONException e) {
            return "";
        }
    }

    /** TMDB refused the token on the TV (401/403): the ratings wait for a new one (recs.py token). */
    static boolean tokenRefused(Context c) {
        Token t = token(c);
        synchronized (lock) {
            load(c);
            return t != null && refusedStamp == t.stamp;
        }
    }

    // MARK: - What the home screen asks every tick (cheap)

    static String verdict(Context c, String key) {
        synchronized (lock) {
            return load(c).verdict(key);
        }
    }

    static int watchedRating(Context c, String key) {
        synchronized (lock) {
            return load(c).watchedRating(key);
        }
    }

    /** Rated or favorited on TMDB: out of the rows. */
    static Set<String> seen(Context c) {
        synchronized (lock) {
            return load(c).seen();
        }
    }

    static Set<String> watchlist(Context c) {
        synchronized (lock) {
            return new HashSet<>(load(c).watchlist);
        }
    }

    static int pending(Context c) {
        synchronized (lock) {
            return load(c).ops.size();
        }
    }

    // MARK: - What you do on the TV

    static void rate(Context c, String key, String title, double value) {
        synchronized (lock) {
            load(c).rate(key, title, value, System.currentTimeMillis());
            save(c);
        }
    }

    static void unrate(Context c, String key, String title) {
        synchronized (lock) {
            load(c).unrate(key, title, System.currentTimeMillis());
            save(c);
        }
    }

    static void want(Context c, String key, String title, boolean on) {
        synchronized (lock) {
            load(c).want(key, title, on, System.currentTimeMillis());
            save(c);
        }
    }

    // MARK: - Talking to TMDB (Recs's io thread)

    /** Something to send whose wait is over, or a pull that's due, and a token that may work. */
    static boolean due(Context c, long now) {
        Token t = token(c);
        if (t == null) return false;
        synchronized (lock) {
            Outbox o = load(c);
            if (refusedStamp == t.stamp) return false;
            if (!o.due(now).isEmpty()) return true;
            boolean pullDue = now - o.pulledAt > PULL_EVERY || now < o.pulledAt;
            return pullDue && (now - pullTriedAt > PULL_RETRY || now < pullTriedAt);
        }
    }

    /**
     * Sends the due ops one by one, then pulls the account when it's due. Not after a send: TMDB's lists lag a few
     * seconds behind its writes, and what landed lately is laid over the pulls anyway (Outbox.recent). Returns true when
     * the seen or the watchlist changed (the rows are ranked again).
     */
    static boolean sync(Context c) {
        Token t = token(c);
        if (t == null) return false;
        long now = System.currentTimeMillis();
        boolean changed = false;
        synchronized (lock) {
            if (refusedStamp == t.stamp) return false;
            // Offline: not a try (no backoff), the next tick looks again, so what you did goes out the moment the line is back.
            if (!online(c)) return false;
        }
        boolean sent = false;
        for (Outbox.Op op : snapshotDue(c, now)) {
            try {
                int code = send(t, op);
                synchronized (lock) {
                    Outbox o = load(c);
                    if (code == 200 || code == 201) {
                        AppLog.w("tmdb: " + op.op + " “" + op.title + "”" + (Outbox.RATE.equals(op.op) ? " " + ratingText(op.value) : ""));
                        o.done(op, now);
                        sent = true;
                    } else if (code == 401 || code == 403) {
                        refusedStamp = t.stamp;
                        lastError = REFUSED;
                        AppLog.w("tmdb: " + REFUSED + " (HTTP " + code + "): run  recs.py token  again");
                        save(c);
                        return false;
                    } else if (code == 404 || (code >= 400 && code < 429)) {   // gone from TMDB, or a request it can't take: no retry helps
                        AppLog.w("tmdb: " + op.op + " “" + op.title + "” dropped (HTTP " + code + ")");
                        o.drop(op);
                    } else {
                        o.failed(op, "HTTP " + code, now);
                    }
                    save(c);
                }
            } catch (IOException e) {
                String error = e.getMessage() == null ? e.toString() : e.getMessage();
                synchronized (lock) {   // the line is down: every due op waits
                    load(c).failedAll(error, now);
                    lastError = error;
                    save(c);
                }
                AppLog.w("tmdb: " + op.op + " “" + op.title + "”: " + error + " (retry in " + Outbox.backoff(op.tries) / 60_000L + " min)");
                return false;
            }
        }
        boolean pullDue;
        synchronized (lock) {
            Outbox o = load(c);
            pullDue = (now - o.pulledAt > PULL_EVERY || now < o.pulledAt) && (now - pullTriedAt > PULL_RETRY || now < pullTriedAt);
        }
        if (pullDue) {
            synchronized (lock) {
                pullTriedAt = now;
            }
            try {
                Map<String, Double> rated = new HashMap<>();
                Set<String> watchlist = new HashSet<>(), favorites = new HashSet<>();
                for (String kind : new String[] {"movie", "tv"}) {
                    for (String what : new String[] {"rated", "watchlist", "favorites"}) {
                        for (int page = 1, pages = 1; page <= pages && page <= 50; page++) {
                            JSONObject r = get(t, API4 + "/account/" + t.accountId + "/" + kind + "/" + what + "?page=" + page);
                            pages = r.optInt("total_pages", 1);
                            JSONArray a = r.optJSONArray("results");
                            for (int i = 0; a != null && i < a.length(); i++) {
                                JSONObject it = a.getJSONObject(i);
                                String key = kind + ":" + it.optInt("id");
                                if (what.equals("rated")) {
                                    JSONObject ar = it.optJSONObject("account_rating");
                                    rated.put(key, ar == null ? 0 : ar.optDouble("value", 0));
                                } else if (what.equals("watchlist")) {
                                    watchlist.add(key);
                                } else {
                                    favorites.add(key);
                                }
                            }
                        }
                    }
                }
                synchronized (lock) {
                    Outbox o = load(c);
                    Set<String> seenBefore = o.seen(), watchBefore = new HashSet<>(o.watchlist);
                    o.pulled(rated, watchlist, favorites, System.currentTimeMillis());
                    changed = !seenBefore.equals(o.seen()) || !watchBefore.equals(o.watchlist);
                    lastError = "";
                    save(c);
                }
                AppLog.w("tmdb account: " + rated.size() + " rated, " + watchlist.size() + " on the watchlist, " + favorites.size() + " favorites");
            } catch (IOException | org.json.JSONException e) {
                String error = e.getMessage() == null ? e.toString() : e.getMessage();
                synchronized (lock) {
                    if (error.contains("HTTP 401") || error.contains("HTTP 403")) {
                        refusedStamp = t.stamp;
                        error = REFUSED;
                    }
                    lastError = error;
                    save(c);
                }
                AppLog.w("tmdb account: " + error);
            }
        }
        return changed || sent;
    }

    private static List<Outbox.Op> snapshotDue(Context c, long now) {
        synchronized (lock) {
            return load(c).due(now);
        }
    }

    private static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm == null || cm.getActiveNetwork() != null;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** POST or DELETE the op; the HTTP code (TMDB answers 200 or 201 when it took it). */
    private static int send(Token t, Outbox.Op op) throws IOException {
        String url, method, body;
        switch (op.op) {
            case Outbox.RATE:
                url = API3 + "/" + op.kind() + "/" + op.id() + "/rating";
                method = "POST";
                body = "{\"value\":" + ratingText(op.value) + "}";
                break;
            case Outbox.UNRATE:
                url = API3 + "/" + op.kind() + "/" + op.id() + "/rating";
                method = "DELETE";
                body = null;
                break;
            default:
                url = API3 + "/account/" + t.accountId + "/watchlist";
                method = "POST";
                body = "{\"media_type\":\"" + op.kind() + "\",\"media_id\":" + op.id() + ",\"watchlist\":" + Outbox.WATCHLIST.equals(op.op) + "}";
                break;
        }
        try {
            return request(t, method, url, body, null);
        } catch (java.net.SocketException | javax.net.ssl.SSLException e) {   // this home line's TMDB resets: once more
            return request(t, method, url, body, null);
        }
    }

    private static JSONObject get(Token t, String url) throws IOException, org.json.JSONException {
        StringBuilder out = new StringBuilder();
        int code;
        try {
            code = request(t, "GET", url, null, out);
        } catch (java.net.SocketException | javax.net.ssl.SSLException e) {
            out.setLength(0);
            code = request(t, "GET", url, null, out);
        }
        if (code != 200) throw new IOException("HTTP " + code + " for " + url.substring(0, url.indexOf('?') > 0 ? url.indexOf('?') : url.length()));
        return new JSONObject(out.toString());
    }

    private static int request(Token t, String method, String url, String body, StringBuilder out) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + t.access);
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json;charset=utf-8");
                c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (out != null && code == 200) out.append(read(c.getInputStream()));
            return code;
        } finally {
            c.disconnect();
        }
    }

    /** 8 → "8", 0.5 → "0.5" (TMDB wants halves). */
    static String ratingText(double v) {
        return v == Math.rint(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    // MARK: - Disk

    private static File dir(Context c) {
        return ProfileStore.dir(c);
    }

    /** Lazy: the first use reads both files; the old feedback.json's verdicts move into the outbox once (no outbox.json yet). */
    private static Outbox load(Context c) {
        if (state != null) return state;
        Outbox o = new Outbox();
        File dir = dir(c), account = new File(dir, ACCOUNT_FILE), outbox = new File(dir, OUTBOX_FILE);
        JSONObject a = readJson(account), ob = readJson(outbox);
        if (a != null) {
            JSONObject r = a.optJSONObject("rated");
            for (Iterator<String> it = r == null ? new HashSet<String>().iterator() : r.keys(); it.hasNext(); ) {
                String k = it.next();
                o.rated.put(k, r.optDouble(k, 0));
            }
            JSONArray w = a.optJSONArray("watchlist"), f = a.optJSONArray("favorites");
            for (int i = 0; w != null && i < w.length(); i++) o.watchlist.add(w.optString(i));
            for (int i = 0; f != null && i < f.length(); i++) o.favorites.add(f.optString(i));
            o.pulledAt = a.optLong("at", 0);
        }
        if (ob != null) {
            JSONArray ops = ob.optJSONArray("ops");
            for (int i = 0; ops != null && i < ops.length(); i++) {
                JSONObject j = ops.optJSONObject(i);
                if (j == null) continue;
                Outbox.Op op = new Outbox.Op();
                op.op = j.optString("op");
                op.key = j.optString("key");
                op.title = j.optString("title");
                op.value = j.optDouble("value", 0);
                op.at = j.optLong("at");
                op.nextAt = j.optLong("nextAt");
                op.tries = j.optInt("tries");
                op.error = j.optString("error");
                if (op.key.contains(":") && !op.op.isEmpty()) o.ops.add(op);
            }
            JSONArray recent = ob.optJSONArray("recent");
            for (int i = 0; recent != null && i < recent.length(); i++) {
                JSONObject j = recent.optJSONObject(i);
                if (j == null) continue;
                Outbox.Op op = new Outbox.Op();
                op.op = j.optString("op");
                op.key = j.optString("key");
                op.title = j.optString("title");
                op.value = j.optDouble("value", 0);
                op.doneAt = j.optLong("doneAt");
                if (op.key.contains(":") && !op.op.isEmpty()) o.recent.add(op);
            }
            refusedStamp = ob.optLong("refusedStamp", 0);
            lastError = ob.optString("error");
        } else {
            long now = System.currentTimeMillis();
            int moved = 0;
            for (Feedback.Entry e : Feedback.all(c)) {   // before the outbox, feedback.json was the record: what it holds goes to TMDB
                if (Feedback.WATCHED.equals(e.verdict) && e.rating > 0) {
                    if (e.synced) o.rated.put(e.key, (double) e.rating);
                    else o.rate(e.key, e.title, e.rating, now);
                } else if (Feedback.UP.equals(e.verdict)) {
                    o.want(e.key, e.title, true, now);
                } else if (Feedback.DOWN.equals(e.verdict) || Feedback.HIDE.equals(e.verdict)) {
                    o.rate(e.key, e.title, Outbox.DOWN_RATING, now);
                } else {
                    continue;
                }
                moved++;
            }
            if (moved > 0) AppLog.w("tmdb: " + moved + " verdicts from feedback.json into the outbox");
            state = o;
            save(c);
        }
        state = o;
        return o;
    }

    private static void save(Context c) {
        Outbox o = state;
        if (o == null) return;
        try {
            JSONObject rated = new JSONObject();
            for (Map.Entry<String, Double> e : o.rated.entrySet()) rated.put(e.getKey(), e.getValue());
            writeJson(new File(dir(c), ACCOUNT_FILE), new JSONObject().put("at", o.pulledAt).put("rated", rated)
                    .put("watchlist", new JSONArray(o.watchlist)).put("favorites", new JSONArray(o.favorites)));
            JSONArray ops = new JSONArray();
            for (Outbox.Op op : o.ops) {
                ops.put(new JSONObject().put("op", op.op).put("key", op.key).put("title", op.title).put("value", op.value)
                        .put("at", op.at).put("nextAt", op.nextAt).put("tries", op.tries).put("error", op.error));
            }
            JSONArray recent = new JSONArray();
            for (Outbox.Op op : o.recent) {
                recent.put(new JSONObject().put("op", op.op).put("key", op.key).put("title", op.title).put("value", op.value).put("doneAt", op.doneAt));
            }
            writeJson(new File(dir(c), OUTBOX_FILE), new JSONObject().put("ops", ops).put("recent", recent).put("refusedStamp", refusedStamp).put("error", lastError));
        } catch (org.json.JSONException e) {
            AppLog.w("tmdb account: " + e.getMessage());
        }
    }

    /**
     * For the main process (RecsInbox's status.json, the settings screen), read fresh from disk: {token, refused,
     * pending, pulledAt, rated, watchlist, favorites, error}.
     */
    static JSONObject status(Context c) {
        JSONObject out = new JSONObject();
        File dir = dir(c), tokenFile = new File(dir, TOKEN_FILE);
        JSONObject a = readJson(new File(dir, ACCOUNT_FILE)), ob = readJson(new File(dir, OUTBOX_FILE));
        long stamp = tokenFile.exists() ? tokenFile.lastModified() * 31 + tokenFile.length() : 0;
        try {
            out.put("token", stamp != 0);
            out.put("refused", stamp != 0 && ob != null && ob.optLong("refusedStamp", -1) == stamp);
            out.put("pending", ob == null || ob.optJSONArray("ops") == null ? 0 : ob.optJSONArray("ops").length());
            out.put("error", ob == null ? "" : ob.optString("error"));
            out.put("pulledAt", a == null ? 0 : a.optLong("at", 0));
            out.put("rated", a == null || a.optJSONObject("rated") == null ? 0 : a.optJSONObject("rated").length());
            out.put("watchlist", a == null || a.optJSONArray("watchlist") == null ? 0 : a.optJSONArray("watchlist").length());
            out.put("favorites", a == null || a.optJSONArray("favorites") == null ? 0 : a.optJSONArray("favorites").length());
        } catch (org.json.JSONException ignored) {
        }
        return out;
    }

    private static JSONObject readJson(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (IOException | RuntimeException | org.json.JSONException e) {
            AppLog.w(f.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private static void writeJson(File f, JSONObject o) {
        try {
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            File part = new File(f.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!part.renameTo(f)) throw new IOException("couldn't save " + f);
        } catch (IOException e) {
            AppLog.w(f.getName() + ": " + e.getMessage());
        }
    }

    private static String read(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
}
