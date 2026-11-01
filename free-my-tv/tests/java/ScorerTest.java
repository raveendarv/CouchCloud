package io.freemytv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The Scorer's tests: plain Java, no framework (tools/test.sh compiles Profile, Scorer and this and runs main). */
public final class ScorerTest {
    private static int failures;
    private static final long NOW = 1_800_000_000_000L;   // 2027-01-15
    private static final long DAY = 86_400_000L;

    public static void main(String[] args) {
        seenTitlesAreLeftOut();
        hiddenTitlesAreLeftOut();
        likedActorRanksFirstWithReason();
        coldLanguageGoesByPopularity();
        watchlistWinsAndSaysSo();
        freshnessFadesOverAYear();
        nudgesChangeTheOrder();
        diverseCapsGenreAndActor();
        directorReason();
        if (failures > 0) {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
        System.out.println("ScorerTest: all passed");
    }

    private static Profile profile(Map<String, Double> w, Map<String, String> names, Set<String> seen, Set<String> watchlist, Map<String, Double> conf) {
        return new Profile(w, names, seen, watchlist, conf, NOW);
    }

    private static Scorer.Item item(String key, String lang, double pop, long released, String... features) {
        Scorer.Item it = new Scorer.Item();
        it.key = key;
        it.title = key;
        it.lang = lang;
        it.popularity = pop;
        it.releasedAt = released;
        it.features = new ArrayList<>(Arrays.asList(features));
        it.firstGenre = features.length > 0 && features[0].startsWith("g:") ? features[0] : "";
        for (String f : features) if (f.startsWith("c:") && it.leadActor.isEmpty()) it.leadActor = f;
        return it;
    }

    private static Map<String, Double> conf(String lang, double v) {
        Map<String, Double> m = new HashMap<>();
        m.put(lang, v);
        return m;
    }

    private static List<Scorer.Scored> rank(List<Scorer.Item> items, Profile p) {
        return Scorer.rank(items, p, new HashMap<>(), new HashSet<>(), NOW, Scorer.ENGLISH);
    }

    static void seenTitlesAreLeftOut() {
        Profile p = profile(new HashMap<>(), new HashMap<>(), new HashSet<>(Arrays.asList("movie:1")), new HashSet<>(), conf("ta", 1));
        List<Scorer.Scored> out = rank(Arrays.asList(item("movie:1", "ta", 10, 0), item("movie:2", "ta", 5, 0)), p);
        check("seen left out", out.size() == 1 && out.get(0).item.key.equals("movie:2"));
    }

    static void hiddenTitlesAreLeftOut() {
        Profile p = profile(new HashMap<>(), new HashMap<>(), new HashSet<>(), new HashSet<>(), conf("ta", 1));
        Set<String> hidden = new HashSet<>(Arrays.asList("tv:9"));
        List<Scorer.Scored> out = Scorer.rank(Arrays.asList(item("tv:9", "ta", 10, 0), item("tv:8", "ta", 5, 0)), p, new HashMap<>(), hidden, NOW, Scorer.ENGLISH);
        check("hidden left out", out.size() == 1 && out.get(0).item.key.equals("tv:8"));
    }

    static void likedActorRanksFirstWithReason() {
        Map<String, Double> w = new HashMap<>();
        w.put("c:100", 1.2);
        Map<String, String> names = new HashMap<>();
        names.put("c:100", "Fahadh Faasil");
        Profile p = profile(w, names, new HashSet<>(), new HashSet<>(), conf("ml", 1));
        Scorer.Item a = item("movie:1", "ml", 50, 0, "g:18", "c:7"), b = item("movie:2", "ml", 5, 0, "g:18", "c:100");
        List<Scorer.Scored> out = rank(Arrays.asList(a, b), p);
        check("liked actor first", out.get(0).item.key.equals("movie:2"));
        check("reason names the actor", out.get(0).reason.equals("Because you like Fahadh Faasil"));
    }

    static void coldLanguageGoesByPopularity() {
        Map<String, Double> w = new HashMap<>();
        w.put("c:100", 1.2);
        Map<String, String> names = new HashMap<>();
        names.put("c:100", "Someone");
        Profile p = profile(w, names, new HashSet<>(), new HashSet<>(), conf("kn", 0));   // nothing rated in Kannada
        Scorer.Item a = item("movie:1", "kn", 80, 0, "g:28"), b = item("movie:2", "kn", 5, 0, "g:28", "c:100");
        List<Scorer.Scored> out = rank(Arrays.asList(a, b), p);
        check("cold start: popular first", out.get(0).item.key.equals("movie:1"));
        check("cold start reason", out.get(0).reason.equals("Popular in Kannada"));
    }

    static void watchlistWinsAndSaysSo() {
        Profile p = profile(new HashMap<>(), new HashMap<>(), new HashSet<>(), new HashSet<>(Arrays.asList("tv:5")), conf("en", 0.5));
        Scorer.Item a = item("tv:4", "en", 500, NOW - 5 * DAY, "g:18"), b = item("tv:5", "en", 1, 0, "g:35");
        List<Scorer.Scored> out = rank(Arrays.asList(a, b), p);
        check("watchlist first", out.get(0).item.key.equals("tv:5"));
        check("watchlist reason", out.get(0).reason.equals("On your watchlist"));
    }

    static void freshnessFadesOverAYear() {
        double now = Scorer.freshness(NOW - 10 * DAY, NOW), mid = Scorer.freshness(NOW - 197 * DAY, NOW), old = Scorer.freshness(NOW - 400 * DAY, NOW);
        check("fresh within a month gets the full bonus", Math.abs(now - Scorer.FRESH_BONUS) < 1e-9);
        check("half a year gets about half", mid > 0.06 && mid < 0.09);
        check("a year gets nothing", old == 0);
        check("unknown date gets nothing", Scorer.freshness(0, NOW) == 0);
        Profile p = profile(new HashMap<>(), new HashMap<>(), new HashSet<>(), new HashSet<>(), conf("en", 0));
        Scorer.Item a = item("movie:1", "en", 50, NOW - 3 * DAY, "g:18"), b = item("movie:2", "en", 50, NOW - 700 * DAY, "g:18");
        a.service = "JioHotstar";
        List<Scorer.Scored> out = rank(Arrays.asList(a, b), p);
        check("new title first on equal popularity", out.get(0).item.key.equals("movie:1"));
        check("new title reason", out.get(0).reason.equals("New this month on JioHotstar"));
    }

    static void nudgesChangeTheOrder() {
        Profile p = profile(new HashMap<>(), new HashMap<>(), new HashSet<>(), new HashSet<>(), conf("ta", 1));
        Scorer.Item a = item("movie:1", "ta", 50, 0, "g:28", "c:1"), b = item("movie:2", "ta", 50, 0, "g:35", "c:2");
        Map<String, Double> nudges = new HashMap<>();
        nudges.put("c:2", 0.3);   // a thumbs up on another title with the same actor
        List<Scorer.Scored> out = Scorer.rank(Arrays.asList(a, b), p, nudges, new HashSet<>(), NOW, Scorer.ENGLISH);
        check("nudged title first", out.get(0).item.key.equals("movie:2"));
    }

    static void diverseCapsGenreAndActor() {
        List<Scorer.Scored> ranked = new ArrayList<>();
        String[][] rows = {{"a", "g:1", "c:1"}, {"b", "g:1", "c:2"}, {"c", "g:1", "c:3"}, {"d", "g:2", "c:1"}, {"e", "g:2", "c:4"}, {"f", "g:3", "c:5"}};
        double score = 10;
        for (String[] r : rows) ranked.add(new Scorer.Scored(item(r[0], "ta", 1, 0, r[1], r[2]), score--, ""));
        List<Scorer.Scored> out = Scorer.diverse(ranked, 4, 2, 1);
        List<String> keys = new ArrayList<>();
        for (Scorer.Scored s : out) keys.add(s.item.key);
        // a, b (genre 1 twice), c skipped (third of genre 1), d skipped (actor 1 again), e, f
        check("diverse order", keys.equals(Arrays.asList("a", "b", "e", "f")));
        List<Scorer.Scored> all = Scorer.diverse(ranked, 6, 2, 1);
        check("the rest fills up when asked for more", all.size() == 6);
    }

    static void directorReason() {
        Map<String, Double> w = new HashMap<>();
        w.put("dir:77", 0.9);
        Map<String, String> names = new HashMap<>();
        names.put("dir:77", "Lijo Jose Pellissery");
        Profile p = profile(w, names, new HashSet<>(), new HashSet<>(), conf("ml", 0.8));
        List<Scorer.Scored> out = rank(Arrays.asList(item("movie:1", "ml", 5, 0, "g:18", "dir:77")), p);
        check("director reason", out.get(0).reason.equals("Directed by Lijo Jose Pellissery"));
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
