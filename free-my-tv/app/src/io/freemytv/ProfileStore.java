package io.freemytv;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * profile.json on the TV: written by tools/recs/recs.py push (through RecsInbox), read here, again whenever the file
 * changes. {"version":1,"built":<epoch ms>,"weights":{feature:w},"names":{feature:name},"seen":[key],
 * "watchlist":[key],"confidence":{lang:0..1}}.
 */
final class ProfileStore {
    private static Profile profile = Profile.empty();
    private static long loadedStamp = -1;

    private ProfileStore() {}

    static File dir(Context c) {
        return new File(c.getFilesDir(), "recs");
    }

    static File file(Context c) {
        return new File(dir(c), "profile.json");
    }

    /** The current profile (empty without a file). Cheap: re-reads only when the file's size or time changed. */
    static synchronized Profile get(Context c) {
        File f = file(c);
        long stamp = f.exists() ? f.lastModified() * 31 + f.length() : 0;
        if (stamp == loadedStamp) return profile;
        loadedStamp = stamp;
        profile = stamp == 0 ? Profile.empty() : read(f);
        return profile;
    }

    private static Profile read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int n = 0;
            while (n < data.length) {
                int r = in.read(data, n, data.length - n);
                if (r < 0) break;
                n += r;
            }
            JSONObject o = new JSONObject(new String(data, 0, n, StandardCharsets.UTF_8));
            Map<String, Double> weights = new HashMap<>();
            JSONObject w = o.optJSONObject("weights");
            for (Iterator<String> it = w == null ? emptyKeys() : w.keys(); it.hasNext(); ) {
                String k = it.next();
                weights.put(k, w.optDouble(k, 0));
            }
            Map<String, String> names = new HashMap<>();
            JSONObject nm = o.optJSONObject("names");
            for (Iterator<String> it = nm == null ? emptyKeys() : nm.keys(); it.hasNext(); ) {
                String k = it.next();
                names.put(k, nm.optString(k));
            }
            Set<String> seen = new HashSet<>(), watchlist = new HashSet<>();
            org.json.JSONArray s = o.optJSONArray("seen");
            for (int i = 0; s != null && i < s.length(); i++) seen.add(s.optString(i));
            org.json.JSONArray wl = o.optJSONArray("watchlist");
            for (int i = 0; wl != null && i < wl.length(); i++) watchlist.add(wl.optString(i));
            Map<String, Double> confidence = new HashMap<>();
            JSONObject cf = o.optJSONObject("confidence");
            for (Iterator<String> it = cf == null ? emptyKeys() : cf.keys(); it.hasNext(); ) {
                String k = it.next();
                confidence.put(k, cf.optDouble(k, 0));
            }
            AppLog.w("profile: " + weights.size() + " features, " + seen.size() + " seen, " + watchlist.size() + " on the watchlist");
            return new Profile(weights, names, seen, watchlist, confidence, o.optLong("built", f.lastModified()));
        } catch (IOException | RuntimeException | org.json.JSONException e) {
            AppLog.w("profile: " + e.getMessage());
            return Profile.empty();
        }
    }

    private static Iterator<String> emptyKeys() {
        return new HashSet<String>().iterator();
    }
}
