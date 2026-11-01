package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Shader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Continue Watching": what you were watching in each app and where you stopped, as a row under the shelf. Two
 * sources, each a file in files/watching with one writer, so two processes never write the same file:
 *  - sessions.json (PlaybackListener, the main process): the TV's media sessions while an app plays: the title, the
 *    picture, the position and the duration as the app publishes them (JioHotstar, YouTube and Stremio give it all;
 *    Netflix and Prime Video only the position). A title opened from one of our cards is noted in launched.json (the
 *    home screen writes it): the listener names a nameless session by it.
 *  - stremio.json (the home screen's process): Stremio's library from its account (config.properties
 *    stremio_auth_key, from tools/stremio_login.py), with the episode and the position; with it, Stremio's sessions are
 *    left out (the account knows more).
 * hidden.json (the home screen): the cards you removed, key → when; a newer play of the same title shows it again.
 * Nothing here reads Android's own Watch Next table: on a retail TV only Google's home screen may.
 */
final class Watching {
    /** One card: a title in an app, and how far you got. */
    static final class Entry {
        String key = "";          // pkg|mediaId, pkg|pick, pkg|title or pkg|~ (nameless)
        String pkg = "", app = "";
        String title, subtitle;   // subtitle: the episode ("S1 E2"), YouTube's channel; null when the app gives none
        String show;              // the series' name when the app tells it apart from the episode (JioHotstar's web address)
        String showLink;          // the series' deep link as JustWatch gives it (JioHotstar: hotstar://content/<show id>): matched to our picks
        String art;               // the picture's file name in files/watching/art, or null
        String artUrl;            // a picture to fetch (Stremio's poster) when art is null
        String uri;               // what OK opens: the title's page in the app (a deep link); null = the app itself
        String mediaId;           // the app's own id of what plays (YouTube: the video)
        long position, duration;  // ms; duration 0 = unknown (no progress bar)
        long at;                  // when it was last playing
        boolean stremio;          // from the Stremio account
        // The TMDB title it was opened as (from our cards): its picture and page when the app gives none.
        String pickKind, pickTitle, pickBackdrop;
        int pickId;
        // Made by the home screen once per entry (nothing is built while drawing).
        transient String line1, line2;
        transient Recs.Pick pick;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject().put("key", key).put("pkg", pkg).put("app", app).put("position", position)
                    .put("duration", duration).put("at", at).put("stremio", stremio).put("pickId", pickId);
            if (title != null) o.put("title", title);
            if (subtitle != null) o.put("subtitle", subtitle);
            if (show != null) o.put("show", show);
            if (showLink != null) o.put("showLink", showLink);
            if (art != null) o.put("art", art);
            if (artUrl != null) o.put("artUrl", artUrl);
            if (uri != null) o.put("uri", uri);
            if (mediaId != null) o.put("mediaId", mediaId);
            if (pickKind != null) o.put("pickKind", pickKind);
            if (pickTitle != null) o.put("pickTitle", pickTitle);
            if (pickBackdrop != null) o.put("pickBackdrop", pickBackdrop);
            return o;
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.key = o.optString("key", "");
            e.pkg = o.optString("pkg", "");
            e.app = o.optString("app", "");
            e.title = o.has("title") ? o.optString("title") : null;
            e.subtitle = o.has("subtitle") ? o.optString("subtitle") : null;
            e.show = o.has("show") ? o.optString("show") : null;
            e.showLink = o.has("showLink") ? o.optString("showLink") : null;
            e.art = o.has("art") ? o.optString("art") : null;
            e.artUrl = o.has("artUrl") ? o.optString("artUrl") : null;
            e.uri = o.has("uri") ? o.optString("uri") : null;
            e.mediaId = o.has("mediaId") ? o.optString("mediaId") : null;
            e.position = o.optLong("position");
            e.duration = o.optLong("duration");
            e.at = o.optLong("at");
            e.stremio = o.optBoolean("stremio");
            e.pickKind = o.has("pickKind") ? o.optString("pickKind") : null;
            e.pickTitle = o.has("pickTitle") ? o.optString("pickTitle") : null;
            e.pickBackdrop = o.has("pickBackdrop") ? o.optString("pickBackdrop") : null;
            e.pickId = o.optInt("pickId");
            // A YouTube video's card shows the video's own thumbnail (entries saved before this carried the session's
            // picture, which could be the video before's).
            if (e.mediaId != null && e.pkg.contains("youtube") && e.mediaId.matches("[A-Za-z0-9_-]{11}")) {
                e.artUrl = "https://i.ytimg.com/vi/" + e.mediaId + "/hqdefault.jpg";
                e.art = null;
            }
            return e;
        }

        /** The TMDB title it was opened as, for its picture (Recs.thumb) and page. */
        Recs.Pick pick() {
            if (pick == null && pickKind != null && pickId != 0) {
                pick = new Recs.Pick();
                pick.id = pickId;
                pick.kind = pickKind;
                pick.title = pickTitle == null ? title : pickTitle;
                pick.backdrop = pickBackdrop;
            }
            return pick;
        }

        /**
         * Played to the end, or as good as: not something to continue. 92 % in (a film: its credits; the user's number), or
         * under a minute left, which is what counts for a short video (a trailer near its end stayed in the row: the last
         * position the app published came up to a poll, 20 s, before the end).
         */
        boolean finished() {
            return duration > 0 && (position >= duration * 0.92 || duration - position <= 60_000);
        }

        /** The title the card says: the app's name when the app gave none. */
        String shownTitle() {
            if (title != null && !title.isEmpty()) return title;
            if (pickTitle != null && !pickTitle.isEmpty()) return pickTitle;
            return app;
        }
    }

    /** A card's picture, ready to draw with rounded corners. */
    static final class Art {
        final Bitmap bitmap;
        final BitmapShader shader;

        Art(Bitmap b) {
            bitmap = b;
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
    }

    static final int MAX = 12;                      // cards in the row
    static final int PER_APP = 2;                   // of one app: its two latest, enough to pick up where you left
    static final long KEEP_MS = 21 * 24 * 3600_000L;   // older plays drop off
    /** A title opened from our cards names a nameless session that starts within this long. */
    static final long LAUNCH_MS = 30 * 60_000L;
    static final String STREMIO_PKG = "com.stremio.one";
    private static final String STREMIO_API = "https://api.strem.io/api/";
    private static final long STREMIO_EVERY = 10 * 60_000L;
    private static final int ART_W = 480;           // saved pictures: enough for a 390-wide card on a 1080p UI

    private static Watching instance;

    static synchronized Watching get(Context c) {
        if (instance == null) instance = new Watching(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, Art> arts = new LinkedHashMap<String, Art>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Art> eldest) { return size() > MAX + 4; }
    };
    private final Set<String> artPending = new HashSet<>();   // guarded by arts
    private final Map<String, Long> artFailed = new HashMap<>();
    private List<Entry> merged = new ArrayList<>();
    private long sessionsStamp = -1, stremioStamp = -1, hiddenStamp = -1, lookupsStamp = -1;
    /** Titles looked up on TMDB by name for their picture (key → the title found, {} when none); looking: in progress. */
    private Map<String, JSONObject> lookups = new HashMap<>();
    private final Set<String> looking = new HashSet<>();
    private volatile long stremioAt;
    private volatile boolean stremioBusy;
    private volatile String stremioError = "";
    private Runnable listener;

    private Watching(Context c) {
        context = c;
    }

    /** Called (on the main thread) when a picture is ready or the Stremio list came in. */
    void setListener(Runnable r) { listener = r; }

    private void notifyListener() {
        Runnable r = listener;
        if (r != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    // MARK: - Files

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "watching");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File artDir(Context c) {
        File d = new File(dir(c), "art");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File sessionsFile(Context c) { return new File(dir(c), "sessions.json"); }
    static File stremioFile(Context c) { return new File(dir(c), "stremio.json"); }
    static File hiddenFile(Context c) { return new File(dir(c), "hidden.json"); }
    static File launchedFile(Context c) { return new File(dir(c), "launched.json"); }
    static File lookupsFile(Context c) { return new File(dir(c), "lookups.json"); }

    static String read(File f) {
        if (!f.exists()) return "";
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Writes the whole file at once (a reader in the other process never sees half of it). */
    static void write(File f, String text) {
        File part = new File(f.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            AppLog.w("watching: couldn't write " + f.getName() + ": " + e.getMessage());
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        if (!part.renameTo(f)) part.delete();
    }

    static List<Entry> readEntries(File f) {
        List<Entry> out = new ArrayList<>();
        String text = read(f);
        if (text.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(text);
            for (int i = 0; i < a.length(); i++) out.add(Entry.fromJson(a.getJSONObject(i)));
        } catch (JSONException e) {
            AppLog.w("watching: " + f.getName() + " unreadable: " + e.getMessage());
        }
        return out;
    }

    static void writeEntries(File f, List<Entry> list) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : list) a.put(e.toJson());
        } catch (JSONException ignored) {
        }
        write(f, a.toString());
    }

    // MARK: - The row (the home screen's process)

    /** The cards, newest first: the sessions and Stremio's library, less the hidden, the finished and the old. */
    synchronized List<Entry> list() {
        File s = sessionsFile(context), st = stremioFile(context), hd = hiddenFile(context), lk = lookupsFile(context);
        long ss = s.lastModified(), ts = st.lastModified(), hs = hd.lastModified(), ls = lk.lastModified();
        if (ss == sessionsStamp && ts == stremioStamp && hs == hiddenStamp && ls == lookupsStamp) return merged;
        sessionsStamp = ss;
        stremioStamp = ts;
        hiddenStamp = hs;
        if (ls != lookupsStamp) {
            lookupsStamp = ls;
            lookups = new HashMap<>();
            String text = read(lk);
            if (!text.isEmpty()) {
                try {
                    JSONObject o = new JSONObject(text);
                    for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                        String k = it.next();
                        lookups.put(k, o.optJSONObject(k) == null ? new JSONObject() : o.optJSONObject(k));
                    }
                } catch (JSONException ignored) {
                }
            }
        }
        Map<String, Long> hidden = readHidden();
        boolean account = hasStremioAccount();
        List<Entry> all = new ArrayList<>();
        for (Entry e : readEntries(s)) if (!(account && STREMIO_PKG.equals(e.pkg))) all.add(e);
        if (account) all.addAll(readEntries(st));
        long now = System.currentTimeMillis();
        List<Entry> out = new ArrayList<>();
        Map<String, Integer> perApp = new HashMap<>();
        Collections.sort(all, (a, b) -> Long.compare(b.at, a.at));
        for (Entry e : all) {
            if (e.finished() || now - e.at > KEEP_MS) continue;
            if (e.title == null && e.pickTitle == null && e.position < 20_000) continue;   // a nameless preview or intro
            Long hiddenAt = hidden.get(e.key);
            if (hiddenAt != null && hiddenAt >= e.at) continue;
            int n = perApp.containsKey(e.pkg) ? perApp.get(e.pkg) : 0;
            if (n >= PER_APP) continue;
            perApp.put(e.pkg, n + 1);
            // The same entry object stays while nothing about it changed: its texts and picture stay made.
            for (Entry old : merged) {
                if (old.key.equals(e.key) && old.position == e.position && old.at == e.at && String.valueOf(old.title).equals(String.valueOf(e.title))
                        && String.valueOf(old.art).equals(String.valueOf(e.art))) {
                    e = old;
                    break;
                }
            }
            if (e.pickBackdrop == null && e.art == null && e.artUrl == null && e.showLink != null) matchLink(e);
            if (needsLookup(e)) applyLookup(e);
            out.add(e);
            if (out.size() >= MAX) break;
        }
        merged = out;
        return out;
    }

    // MARK: - A picture by name (TMDB): for an app that names what plays but hands no picture (JioHotstar, Stremio's sessions)

    /** The way the words to search are chosen (Titles.queries): a result found another way is looked up again. */
    private static final int LOOKUP_VERSION = 2;

    private static boolean needsLookup(Entry e) {
        return e.art == null && e.artUrl == null && e.pickBackdrop == null && !Titles.queries(e.show, e.title).isEmpty()
                && !e.pkg.contains("youtube") && !Config.TMDB_KEY.trim().isEmpty();
    }

    /** JioHotstar names an episode, never its show; its stream's address carries the show's id, which JustWatch's links carry too. */
    private void matchLink(Entry e) {
        Recs.Pick p = Recs.get(context).pickByLink(e.showLink);
        if (p == null || p.backdrop == null) return;
        e.pickKind = p.kind;
        e.pickId = p.id;
        e.pickBackdrop = p.backdrop;
        if (e.pickTitle == null) e.pickTitle = p.title;
        e.pick = null;
    }

    private void applyLookup(Entry e) {
        JSONObject r = lookups.get(e.key);
        // Not looked up yet, or with other words (an older way; the show's name came after the first look): again.
        if (r == null || r.optInt("v") != LOOKUP_VERSION || !Titles.signature(e.show, e.title).equals(r.optString("q"))) {
            lookup(e);
            return;
        }
        if (!r.has("id")) return;   // looked up: TMDB knows no such title
        e.pickKind = r.optString("kind");
        e.pickId = r.optInt("id");
        e.pickBackdrop = r.optString("backdrop");
        e.pick = null;
    }

    private void lookup(final Entry e) {
        synchronized (looking) {
            if (!looking.add(e.key)) return;
        }
        final String key = e.key, title = e.title, show = e.show;
        // The show's name first, then the title without its episode ("S2 E5", "Episode 3"): JioHotstar names the episode.
        final List<String> queries = Titles.queries(show, title);
        final String signature = Titles.signature(show, title);
        io.execute(() -> {
            JSONObject found = null;
            try {
                found = new JSONObject().put("v", LOOKUP_VERSION).put("q", signature);
                String asked = "";
                for (String q : queries) {
                    asked = q;
                    JSONArray a = Recs.get(context).api("/search/multi?query=" + java.net.URLEncoder.encode(q, "UTF-8") + "&language=" + Recs.lang()
                            + "&include_adult=false").getJSONArray("results");
                    for (int i = 0; i < a.length() && !found.has("id"); i++) {
                        JSONObject o = a.getJSONObject(i);
                        String kind = o.optString("media_type");
                        if (!kind.equals("movie") && !kind.equals("tv")) continue;
                        String backdrop = o.optString("backdrop_path", ""), poster = o.optString("poster_path", "");
                        String picture = !backdrop.isEmpty() && !"null".equals(backdrop) ? backdrop : !poster.isEmpty() && !"null".equals(poster) ? poster : "";
                        if (picture.isEmpty()) continue;
                        found.put("kind", kind).put("id", o.optInt("id")).put("backdrop", picture);
                    }
                    if (found.has("id")) break;
                }
                AppLog.w("watching picture by name “" + title + "”: " + (found.has("id") ? "found as “" + asked + "”" : "unknown to TMDB (" + queries.size() + " tried)"));
            } catch (Exception ex) {
                found = null;
                AppLog.w("watching picture by name “" + title + "”: " + ex.getMessage());   // not saved: another try next time
            }
            synchronized (looking) { looking.remove(key); }
            if (found == null) return;
            synchronized (this) {
                JSONObject all = new JSONObject();
                try {
                    String text = read(lookupsFile(context));
                    if (!text.isEmpty()) all = new JSONObject(text);
                    all.put(key, found);
                    // Only the last few dozen: the rest have dropped off the row.
                    if (all.length() > 80) {
                        List<String> keys = new ArrayList<>();
                        for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) keys.add(it.next());
                        for (int i = 0; i < keys.size() - 60; i++) all.remove(keys.get(i));
                    }
                } catch (JSONException ignored) {
                }
                write(lookupsFile(context), all.toString());
            }
            notifyListener();
        });
    }

    private Map<String, Long> readHidden() {
        Map<String, Long> out = new HashMap<>();
        String text = read(hiddenFile(context));
        if (text.isEmpty()) return out;
        try {
            JSONObject o = new JSONObject(text);
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                out.put(k, o.optLong(k));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    /** Takes a card off the row (until the title plays again). */
    synchronized void hide(Entry e) {
        Map<String, Long> hidden = readHidden();
        hidden.put(e.key, System.currentTimeMillis());
        // Only the last few dozen: a key hidden long ago has dropped off the row by itself.
        if (hidden.size() > 60) {
            List<Map.Entry<String, Long>> rows = new ArrayList<>(hidden.entrySet());
            Collections.sort(rows, (a, b) -> Long.compare(b.getValue(), a.getValue()));
            hidden.clear();
            for (int i = 0; i < 40; i++) hidden.put(rows.get(i).getKey(), rows.get(i).getValue());
        }
        write(hiddenFile(context), new JSONObject(hidden).toString());
        List<Entry> out = new ArrayList<>(merged);
        out.remove(e);
        merged = out;
    }

    /**
     * A title is being opened in an app from one of our cards: the listener names the app's next session by it (Netflix
     * and Prime Video publish none), and its picture and page serve the card.
     */
    static void launched(Context c, Recs.Pick p, String pkg, String link) {
        if (p == null || pkg == null) return;
        try {
            JSONObject o = new JSONObject().put("pkg", pkg).put("at", System.currentTimeMillis()).put("title", p.title == null ? "" : p.title)
                    .put("kind", p.kind == null ? "" : p.kind).put("id", p.id);
            if (p.backdrop != null) o.put("backdrop", p.backdrop);
            if (link != null) o.put("link", link);
            write(launchedFile(c), o.toString());
        } catch (JSONException ignored) {
        }
    }

    // MARK: - Pictures

    /** The card's picture, if ready (else null: it loads in the background, and the listener hears). */
    Art art(Entry e) {
        String file = e.art;
        if (file == null && e.artUrl != null) file = "u" + Integer.toHexString(e.artUrl.hashCode()) + ".jpg";
        if (file == null) return null;
        final String name = file;
        synchronized (arts) {
            Art a = arts.get(name);
            if (a != null) return a;
            Long failed = artFailed.get(name);
            if (failed != null && System.currentTimeMillis() - failed < 10 * 60_000L) return null;
            if (!artPending.add(name)) return null;
        }
        final String url = e.art == null ? e.artUrl : null;
        io.execute(() -> {
            try {
                File f = new File(artDir(context), name);
                if (!f.exists() && url != null) download(url, f);
                Bitmap b = decode(f, ART_W);
                if (b == null) throw new IOException("unreadable picture");
                Art a = new Art(b);
                synchronized (arts) { arts.put(name, a); }
                notifyListener();
            } catch (IOException | RuntimeException e2) {
                synchronized (arts) { artFailed.put(name, System.currentTimeMillis()); }
                AppLog.w("watching picture: " + e2.getMessage());
            } finally {
                synchronized (arts) { artPending.remove(name); }
            }
        });
        return null;
    }

    /** Reads a picture no wider than `maxW` (sampled down while decoding). */
    static Bitmap decode(File f, int maxW) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= maxW) opts.inSampleSize *= 2;
        return BitmapFactory.decodeFile(f.getPath(), opts);
    }

    static void download(String url, File f) throws IOException {
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
            if (c.getResponseCode() != 200) throw new IOException("picture HTTP " + c.getResponseCode());
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the picture");
    }

    /** Saves a picture the app gave (no wider than ART_W) under `name`; returns false when it couldn't. */
    static boolean saveArt(Context c, Bitmap b, String name) {
        if (b == null || b.getWidth() <= 0 || b.getHeight() <= 0) return false;
        Bitmap small = b;
        if (b.getWidth() > ART_W) small = Bitmap.createScaledBitmap(b, ART_W, Math.max(1, b.getHeight() * ART_W / b.getWidth()), true);
        File f = new File(artDir(c), name), part = new File(f.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            small.compress(Bitmap.CompressFormat.JPEG, 86, out);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        } finally {
            if (small != b) small.recycle();
        }
        return part.renameTo(f);
    }

    /** Pictures no card uses any more. */
    static void pruneArt(Context c, List<Entry> keep) {
        Set<String> names = new HashSet<>();
        for (Entry e : keep) if (e.art != null) names.add(e.art);
        File[] files = artDir(c).listFiles();
        if (files == null) return;
        long stale = System.currentTimeMillis() - 2 * 24 * 3600_000L;
        for (File f : files) {
            if (f.getName().startsWith("u")) {   // fetched pictures (Stremio's posters): by age
                //noinspection ResultOfMethodCallIgnored
                if (f.lastModified() < stale - KEEP_MS) f.delete();
            } else if (!names.contains(f.getName()) && f.lastModified() < stale) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    // MARK: - Stremio's account

    static boolean hasStremioAccount() {
        return !Config.STREMIO_AUTH_KEY.trim().isEmpty();
    }

    String stremioError() { return stremioError; }

    /** Fetches Stremio's library when it is 10 minutes old (or on the first call); the listener hears when it changed. */
    void refreshStremioIfStale() {
        if (!hasStremioAccount() || stremioBusy) return;
        long now = System.currentTimeMillis();
        if (stremioAt != 0 && now - stremioAt < STREMIO_EVERY) return;
        stremioBusy = true;
        io.execute(() -> {
            try {
                List<Entry> list = fetchStremio();
                List<Entry> before = readEntries(stremioFile(context));
                boolean same = before.size() == list.size();
                for (int i = 0; same && i < list.size(); i++) {
                    Entry a = before.get(i), b = list.get(i);
                    same = a.key.equals(b.key) && a.position == b.position && a.at == b.at;
                }
                if (!same) {
                    writeEntries(stremioFile(context), list);
                    notifyListener();
                }
                stremioError = "";
                AppLog.w("stremio library: " + list.size() + " to continue" + (same ? "" : " (changed)"));
            } catch (Exception e) {
                stremioError = e.getMessage() == null ? e.toString() : e.getMessage();
                AppLog.w("stremio library: " + stremioError);
            } finally {
                stremioAt = System.currentTimeMillis();
                stremioBusy = false;
            }
        });
    }

    /** Stremio's library items with a position (what its own Continue Watching shows), newest first. */
    private List<Entry> fetchStremio() throws IOException, JSONException {
        JSONObject body = new JSONObject().put("authKey", Config.STREMIO_AUTH_KEY.trim()).put("collection", "libraryItem")
                .put("ids", new JSONArray()).put("all", true);
        JSONObject reply = postJson(STREMIO_API + "datastoreGet", body);
        if (reply.has("error")) {
            JSONObject err = reply.getJSONObject("error");
            throw new IOException(err.optString("message", "Stremio refused the request") + (err.optInt("code") == 1 ? " (sign in again with tools/stremio_login.py)" : ""));
        }
        JSONArray items = reply.optJSONArray("result");
        List<Entry> out = new ArrayList<>();
        if (items == null) return out;
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            // "removed" and "temp" mark a title played without being added to the library: Stremio's own Continue
            // Watching shows those too, so they stay.
            JSONObject state = o.optJSONObject("state");
            if (state == null) continue;
            long offset = state.optLong("timeOffset"), duration = state.optLong("duration");
            if (offset <= 0) continue;
            String type = o.optString("type", "movie"), id = o.optString("_id", ""), name = o.optString("name", "");
            if (id.isEmpty() || name.isEmpty() || !(type.equals("movie") || type.equals("series"))) continue;
            Entry e = new Entry();
            e.stremio = true;
            e.pkg = STREMIO_PKG;
            e.app = "Stremio";
            e.title = name;
            e.position = offset;
            e.duration = duration;
            e.at = Math.max(isoDate(state.optString("lastWatched", "")), isoDate(o.optString("_mtime", "")));
            String video = state.optString("video_id", "");
            e.subtitle = episode(video);
            if (e.subtitle == null && state.optInt("season") > 0 && state.optInt("episode") > 0) {
                e.subtitle = Ui.trf("S{s} E{e}", "s", state.optInt("season"), "e", state.optInt("episode"));
            }
            e.mediaId = video.isEmpty() ? id : video;
            e.key = STREMIO_PKG + "|" + e.mediaId;
            String background = o.optString("background", ""), poster = o.optString("poster", "");   // the backdrop fits a 16:9 card; the poster is the fallback
            if (background.startsWith("http")) e.artUrl = background;
            else if (poster.startsWith("http")) e.artUrl = poster;
            e.uri = "stremio:///detail/" + type + "/" + id + (video.isEmpty() ? "" : "/" + video);
            if (e.finished()) continue;
            out.add(e);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.at, a.at));
        while (out.size() > MAX) out.remove(out.size() - 1);
        return out;
    }

    /** "tt0903747:1:2" (Stremio's video id of an episode) → "S1 E2"; null for a movie. */
    static String episode(String video) {
        int a = video.indexOf(':');
        if (a < 0) return null;
        String[] parts = video.substring(a + 1).split(":");
        if (parts.length != 2) return null;
        try {
            return Ui.trf("S{s} E{e}", "s", Integer.parseInt(parts[0]), "e", Integer.parseInt(parts[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long isoDate(String iso) {
        if (iso.isEmpty()) return 0;
        iso = iso.replaceAll("(\\.\\d{3})\\d+Z$", "$1Z");   // Stremio writes nanoseconds
        for (String pattern : new String[] {"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                return f.parse(iso).getTime();
            } catch (ParseException ignored) {
            }
        }
        return 0;
    }

    static JSONObject postJson(String url, JSONObject body) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + code);
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            try {
                while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
            } finally {
                in.close();
            }
            return new JSONObject(buffer.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }
}
