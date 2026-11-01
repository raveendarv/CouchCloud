package io.freemytv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The recent verdicts' nudge on the profile, until the next build on the computer: "up", "down" and a "watched" rating
 * move the title's features by up to ±NUDGE, fading with a 30-day half-life, so one evening doesn't bend the profile for
 * good. Kept in files/recs/feedback.json ({"items":[{"key","title","verdict","rating","at","features":[…]}]}): a
 * cache, not the record. What you rated or put on the watchlist lives in your TMDB account (Account, Outbox); before
 * the outbox existed this file was the record, and Account moves its verdicts over once.
 */
final class Feedback {
    static final String UP = Outbox.UP, DOWN = Outbox.DOWN, HIDE = "hide", WATCHED = Outbox.WATCHED;
    static final double NUDGE = 0.3;
    static final double HALF_LIFE_MS = 30L * 86_400_000L;

    static final class Entry {
        String key, title, verdict;
        int rating;          // WATCHED: 1 to 10 (TMDB's scale)
        boolean synced;      // only in files from before the outbox: the rating had reached TMDB
        long at;
        List<String> features = new ArrayList<>();
    }

    private static final Object lock = new Object();
    private static List<Entry> items;

    private Feedback() {}

    static File file(Context c) {
        return new File(ProfileStore.dir(c), "feedback.json");
    }

    static List<Entry> all(Context c) {
        synchronized (lock) {
            if (items == null) items = read(file(c));
            return new ArrayList<>(items);
        }
    }

    /** Records a verdict (the last one for a title wins) and saves. */
    static void add(Context c, Scorer.Item item, String verdict) {
        add(c, item, verdict, 0);
    }

    /** Records "watched" with a rating (1 to 10). */
    static void add(Context c, Scorer.Item item, String verdict, int rating) {
        synchronized (lock) {
            if (items == null) items = read(file(c));
            items.removeIf(e -> e.key.equals(item.key));
            Entry e = new Entry();
            e.key = item.key;
            e.title = item.title;
            e.verdict = verdict;
            e.rating = rating;
            e.at = System.currentTimeMillis();
            e.features = new ArrayList<>(item.features);
            items.add(e);
            write(file(c), items);
        }
    }

    /** Takes a verdict back: the profile forgets the nudge. */
    static void remove(Context c, String key) {
        synchronized (lock) {
            if (items == null) items = read(file(c));
            if (items.removeIf(e -> e.key.equals(key))) write(file(c), items);
        }
    }

    /**
     * Feature → delta from the recent verdicts: +NUDGE per "up", −NUDGE per "down", a rating's distance from 5.5 (so
     * 8 is +NUDGE, 3 is −NUDGE), halved every 30 days.
     */
    static Map<String, Double> nudges(Context c, long now) {
        Map<String, Double> out = new HashMap<>();
        for (Entry e : all(c)) {
            if (HIDE.equals(e.verdict)) continue;
            if (WATCHED.equals(e.verdict) && e.rating <= 0) continue;
            double decay = Math.pow(0.5, Math.max(0, now - e.at) / HALF_LIFE_MS);
            double strength = WATCHED.equals(e.verdict) ? Math.max(-1, Math.min(1, (e.rating - 5.5) / 2.5)) : UP.equals(e.verdict) ? 1 : -1;
            double d = NUDGE * strength * decay;
            for (String f : e.features) {
                Double v = out.get(f);
                out.put(f, (v == null ? 0 : v) + d);
            }
        }
        return out;
    }

    private static List<Entry> read(File f) {
        List<Entry> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int n = 0;
            while (n < data.length) {
                int r = in.read(data, n, data.length - n);
                if (r < 0) break;
                n += r;
            }
            JSONArray a = new JSONObject(new String(data, 0, n, StandardCharsets.UTF_8)).optJSONArray("items");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.key = o.optString("key");
                e.title = o.optString("title");
                e.verdict = o.optString("verdict");
                e.rating = o.optInt("rating", 0);
                e.synced = o.optBoolean("synced", false);
                e.at = o.optLong("at");
                JSONArray fs = o.optJSONArray("features");
                for (int j = 0; fs != null && j < fs.length(); j++) e.features.add(fs.optString(j));
                if (!e.key.isEmpty()) out.add(e);
            }
        } catch (IOException | RuntimeException | org.json.JSONException e) {
            AppLog.w("feedback: " + e.getMessage());
        }
        return out;
    }

    private static void write(File f, List<Entry> list) {
        try {
            JSONArray a = new JSONArray();
            for (Entry e : list) {
                a.put(new JSONObject().put("key", e.key).put("title", e.title).put("verdict", e.verdict).put("at", e.at)
                        .put("rating", e.rating).put("features", new JSONArray(e.features)));
            }
            //noinspection ResultOfMethodCallIgnored
            f.getParentFile().mkdirs();
            File part = new File(f.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(new JSONObject().put("items", a).toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!part.renameTo(f)) throw new IOException("couldn't save " + f);
        } catch (IOException | RuntimeException | org.json.JSONException e) {
            AppLog.w("feedback: " + e.getMessage());
        }
    }
}
