package io.freemytv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scores TMDB candidates against the Profile, on the TV. Plain Java (no Android): tools/test.sh runs its tests on a JDK.
 *
 * score = confidence(lang) × taste + (1 − confidence(lang)) × popularity + freshness (+ 1 on the watchlist), where
 * taste is the sum of the profile's weights over the title's features (plus the feedback's recent nudges), squashed to
 * -1..1; popularity is the title's TMDB popularity against the best in the pool (0..1); and freshness is a bonus of
 * FRESH_BONUS within FRESH_FULL_DAYS of release, fading to nothing at FRESH_END_DAYS. Titles you rated or hid are out.
 * Each pick gets a one-line reason: the feature that contributed most, or what else won.
 */
final class Scorer {
    static final double FRESH_BONUS = 0.15, FRESH_FULL_DAYS = 30, FRESH_END_DAYS = 365, WATCHLIST_BONUS = 1.0;
    /** Taste weights sum: this much counts as a full 1 (a title sharing many liked features saturates). */
    static final double TASTE_SCALE = 3.0;
    static final double LOW_CONFIDENCE = 0.35;

    /** What the scorer needs to know about a title (Recs fills it from TMDB's details). */
    static final class Item {
        String key;             // Recs.key: "movie:123"
        String title = "";
        String lang = "";       // original language
        String service = "";    // "JioHotstar"
        List<String> features = new ArrayList<>();
        String firstGenre = "", leadActor = "";   // for the diversity rules
        double popularity;
        long releasedAt;        // epoch ms, 0 = unknown
    }

    static final class Scored {
        final Item item;
        final double score;
        final String reason;

        Scored(Item item, double score, String reason) {
            this.item = item;
            this.score = score;
            this.reason = reason;
        }
    }

    /** The words of a reason (Recs translates them with Ui.tr; the tests see the English). */
    interface Words {
        String because(String name);     // "Because you like {name}"
        String directedBy(String name);
        String popularIn(String language);
        String newOn(String service);
        String watchlist();
    }

    static final Words ENGLISH = new Words() {
        @Override public String because(String name) { return "Because you like " + name; }
        @Override public String directedBy(String name) { return "Directed by " + name; }
        @Override public String popularIn(String language) { return "Popular in " + language; }
        @Override public String newOn(String service) { return service.isEmpty() ? "New this month" : "New this month on " + service; }
        @Override public String watchlist() { return "On your watchlist"; }
    };

    private Scorer() {}

    /** All items ranked best first; the seen and the hidden left out. `nudges`: feature → feedback delta (Feedback). */
    static List<Scored> rank(List<Item> items, Profile profile, Map<String, Double> nudges, Set<String> hidden,
                             long now, Words words) {
        return rank(items, profile, nudges, hidden, Collections.<String>emptySet(), now, words);
    }

    /** The same, with your TMDB account's watchlist as the TV knows it (Account), fresher than the profile's. */
    static List<Scored> rank(List<Item> items, Profile profile, Map<String, Double> nudges, Set<String> hidden,
                             Set<String> watchlist, long now, Words words) {
        double maxPop = 1e-9;
        for (Item it : items) maxPop = Math.max(maxPop, it.popularity);
        List<Scored> out = new ArrayList<>();
        for (Item it : items) {
            if (profile.seen.contains(it.key) || hidden.contains(it.key)) continue;
            out.add(score(it, profile, nudges, watchlist, maxPop, now, words));
        }
        Collections.sort(out, (a, b) -> Double.compare(b.score, a.score));
        return out;
    }

    static Scored score(Item it, Profile profile, Map<String, Double> nudges, Set<String> watchlist, double maxPop, long now, Words words) {
        double sum = 0, best = 0;
        String bestFeature = null;
        for (String f : it.features) {
            double w = profile.weight(f);
            Double d = nudges == null ? null : nudges.get(f);
            if (d != null) w += d;
            sum += w;
            if (w > best && profile.names.containsKey(f)) {
                best = w;
                bestFeature = f;
            }
        }
        double taste = Math.tanh(sum / TASTE_SCALE);
        double popularity = it.popularity <= 0 ? 0 : Math.log1p(it.popularity) / Math.log1p(maxPop);
        double confidence = profile.confidence(it.lang);
        double freshness = freshness(it.releasedAt, now);
        double score = confidence * taste + (1 - confidence) * popularity + freshness;
        String reason;
        if (profile.watchlist.contains(it.key) || watchlist.contains(it.key)) {
            score += WATCHLIST_BONUS;
            reason = words.watchlist();
        } else if (bestFeature != null && confidence >= LOW_CONFIDENCE && best * confidence >= freshness) {
            String name = profile.names.get(bestFeature);
            reason = bestFeature.startsWith("dir:") ? words.directedBy(name) : words.because(name);
        } else if (freshness >= FRESH_BONUS / 2) {   // out for less than about half a year
            reason = words.newOn(it.service);
        } else {
            reason = words.popularIn(languageName(it.lang));
        }
        return new Scored(it, score, reason);
    }

    static double freshness(long releasedAt, long now) {
        if (releasedAt <= 0) return 0;
        double days = (now - releasedAt) / 86_400_000.0;
        if (days < 0) return 0;
        if (days <= FRESH_FULL_DAYS) return FRESH_BONUS;
        if (days >= FRESH_END_DAYS) return 0;
        return FRESH_BONUS * (FRESH_END_DAYS - days) / (FRESH_END_DAYS - FRESH_FULL_DAYS);
    }

    /**
     * The first `n` of a ranking with some variety: at most `perGenre` with the same first genre and `perActor` with the
     * same lead actor; when the rules leave too few, the best of the rest fill up.
     */
    static List<Scored> diverse(List<Scored> ranked, int n, int perGenre, int perActor) {
        List<Scored> out = new ArrayList<>();
        Map<String, Integer> genres = new HashMap<>(), actors = new HashMap<>();
        Set<Scored> taken = new HashSet<>();
        for (Scored s : ranked) {
            if (out.size() >= n) break;
            String g = s.item.firstGenre, a = s.item.leadActor;
            int ng = g.isEmpty() ? 0 : count(genres, g), na = a.isEmpty() ? 0 : count(actors, a);
            if (ng >= perGenre || na >= perActor) continue;
            out.add(s);
            taken.add(s);
            if (!g.isEmpty()) genres.put(g, ng + 1);
            if (!a.isEmpty()) actors.put(a, na + 1);
        }
        for (Scored s : ranked) {
            if (out.size() >= n) break;
            if (!taken.contains(s)) out.add(s);
        }
        return out;
    }

    private static int count(Map<String, Integer> m, String k) {
        Integer v = m.get(k);
        return v == null ? 0 : v;
    }

    /** "ta" → "Tamil" (the languages the tabs use; others as the code). */
    static String languageName(String code) {
        switch (code) {
            case "ta": return "Tamil";
            case "ml": return "Malayalam";
            case "te": return "Telugu";
            case "hi": return "Hindi";
            case "kn": return "Kannada";
            case "en": return "English";
            default: return code;
        }
    }
}
