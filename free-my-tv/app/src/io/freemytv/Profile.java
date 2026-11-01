package io.freemytv;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Your taste, built on the computer by tools/recs (profile.json) and sent to the TV: a weight per feature ("c:12345"
 * an actor, "g:18" a genre, "k:9715" a keyword, "l:ta" a language, "d:2010" a decade, "dir:…" a director,
 * "kind:tv"), already scaled by how rare the feature is; the titles you rated or favorited (never shown again); your
 * watchlist (shown first); and per language how much to trust the weights against plain popularity. Plain Java: no
 * Android, so the scorer's tests run on any JDK (tools/test.sh). ProfileStore reads the JSON.
 */
final class Profile {
    static final String KIND_SEEN = "seen", KIND_WATCHLIST = "watchlist";

    final Map<String, Double> weights;
    /** Feature → what to call it in a reason ("Fahadh Faasil", "Thriller", "time travel"). */
    final Map<String, String> names;
    /** Recs.key (kind:id) of titles already rated or favorited. */
    final Set<String> seen;
    final Set<String> watchlist;
    /** Language ("ta") → how much the weights can be trusted there, 0 (nothing rated) to 1. */
    final Map<String, Double> confidence;
    final long builtAt;

    Profile(Map<String, Double> weights, Map<String, String> names, Set<String> seen, Set<String> watchlist,
            Map<String, Double> confidence, long builtAt) {
        this.weights = Collections.unmodifiableMap(weights);
        this.names = Collections.unmodifiableMap(names);
        this.seen = Collections.unmodifiableSet(seen);
        this.watchlist = Collections.unmodifiableSet(watchlist);
        this.confidence = Collections.unmodifiableMap(confidence);
        this.builtAt = builtAt;
    }

    static Profile empty() {
        return new Profile(new HashMap<>(), new HashMap<>(), new HashSet<>(), new HashSet<>(), new HashMap<>(), 0);
    }

    boolean isEmpty() {
        return weights.isEmpty() && watchlist.isEmpty();
    }

    double weight(String feature) {
        Double w = weights.get(feature);
        return w == null ? 0 : w;
    }

    /** 0 to 1: with nothing rated in a language the picks there go by popularity (cold start). */
    double confidence(String language) {
        Double c = confidence.get(language);
        if (c == null) c = confidence.get("*");
        return c == null ? 0 : Math.max(0, Math.min(1, c));
    }
}
