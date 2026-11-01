package io.freemytv;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The Outbox's tests: plain Java, no framework (tools/test.sh compiles Outbox and this). */
public final class OutboxTest {
    private static int failures;
    private static final long NOW = 1_800_000_000_000L;

    public static void main(String[] args) {
        watchedRatesAndLeavesTheWatchlist();
        downIsHalfAStarAndUpTakesItBack();
        newOpReplacesThePendingOneOfItsFamily();
        backoffDoublesToAnHour();
        offlineWaitsEveryDueOp();
        pullKeepsThePendingOps();
        if (failures > 0) {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
        System.out.println("OutboxTest: all passed");
    }

    static void watchedRatesAndLeavesTheWatchlist() {
        Outbox o = new Outbox();
        o.want("movie:1", "A", true, NOW);
        o.rate("movie:1", "A", 8, NOW + 1);
        check("rated", o.watchedRating("movie:1") == 8);
        check("verdict watched", Outbox.WATCHED.equals(o.verdict("movie:1")));
        check("off the watchlist", !o.watchlist.contains("movie:1"));
        check("seen", o.seen().contains("movie:1"));
        List<Outbox.Op> due = o.due(NOW + 2);
        check("two ops: unwatchlist then rate", due.size() == 2 && due.get(0).op.equals(Outbox.UNWATCHLIST) && due.get(1).op.equals(Outbox.RATE));
        check("rate value", due.get(1).value == 8 && due.get(1).kind().equals("movie") && due.get(1).id().equals("1"));
    }

    static void downIsHalfAStarAndUpTakesItBack() {
        Outbox o = new Outbox();
        o.rate("tv:2", "B", Outbox.DOWN_RATING, NOW);
        check("down verdict", Outbox.DOWN.equals(o.verdict("tv:2")));
        check("down is not a watched rating", o.watchedRating("tv:2") == 0);
        check("down is seen", o.seen().contains("tv:2"));
        o.want("tv:2", "B", true, NOW + 1);
        check("up verdict", Outbox.UP.equals(o.verdict("tv:2")));
        check("not seen any more", !o.seen().contains("tv:2"));
        List<Outbox.Op> due = o.due(NOW + 2);
        check("unrate then watchlist", due.size() == 2 && due.get(0).op.equals(Outbox.UNRATE) && due.get(1).op.equals(Outbox.WATCHLIST));
    }

    static void newOpReplacesThePendingOneOfItsFamily() {
        Outbox o = new Outbox();
        o.rate("movie:3", "C", 6, NOW);
        o.rate("movie:3", "C", 10, NOW + 1);
        o.want("movie:3", "C", true, NOW + 2);
        o.want("movie:3", "C", false, NOW + 3);
        check("one rating op, one watchlist op", o.ops.size() == 2);
        check("the last rating wins", o.ops.get(0).value == 10 && o.watchedRating("movie:3") == 10);
        check("the last watchlist op wins", o.ops.get(1).op.equals(Outbox.UNWATCHLIST));
        o.done(o.ops.get(0), NOW + 4);
        check("done removes", o.ops.size() == 1 && o.recent.size() == 1);
        o.drop(o.ops.get(0));
        check("drop removes, not recent", o.ops.isEmpty() && o.recent.size() == 1);
    }

    static void backoffDoublesToAnHour() {
        check("1 min", Outbox.backoff(1) == 60_000L);
        check("2 min", Outbox.backoff(2) == 120_000L);
        check("1 h cap", Outbox.backoff(20) == 3600_000L);
        Outbox o = new Outbox();
        o.rate("movie:4", "D", 8, NOW);
        Outbox.Op op = o.ops.get(0);
        o.failed(op, "HTTP 503", NOW);
        check("not due yet", o.due(NOW + 30_000L).isEmpty());
        check("due after a minute", o.due(NOW + 60_000L).size() == 1 && op.tries == 1 && op.error.equals("HTTP 503"));
    }

    static void offlineWaitsEveryDueOp() {
        Outbox o = new Outbox();
        o.rate("movie:5", "E", 8, NOW);
        o.want("tv:6", "F", true, NOW);
        o.failedAll("offline", NOW);
        check("all waiting", o.due(NOW).isEmpty() && o.ops.size() == 2);
        check("all back after a minute", o.due(NOW + 60_000L).size() == 2);
    }

    static void pullKeepsThePendingOps() {
        Outbox o = new Outbox();
        o.rate("movie:7", "G", 8, NOW);        // pending
        o.want("tv:8", "H", false, NOW);       // pending: TMDB still lists it
        Map<String, Double> rated = new HashMap<>();
        rated.put("movie:9", 4.0);
        Set<String> wl = new HashSet<>(Collections.singletonList("tv:8")), fav = new HashSet<>(Collections.singletonList("movie:10"));
        o.pulled(rated, wl, fav, NOW + 5);
        check("pulled rating", o.watchedRating("movie:9") == 4);
        check("pending rating kept", o.watchedRating("movie:7") == 8);
        check("pending removal kept", !o.watchlist.contains("tv:8"));
        check("favorite seen", o.seen().contains("movie:10") && o.verdict("movie:10") == null);
        check("pulledAt", o.pulledAt == NOW + 5);
        o.done(o.ops.get(0), NOW + 6);
        o.done(o.ops.get(0), NOW + 6);
        o.pulled(rated, wl, fav, NOW + 7);   // TMDB's lists lag: the pull still lacks them
        check("landed lately still wins", o.watchedRating("movie:7") == 8 && !o.watchlist.contains("tv:8"));
        o.pulled(rated, wl, fav, NOW + 7 + Outbox.RECENT_MS + 1);
        check("after a while, TMDB's word", o.watchedRating("movie:7") == 0 && o.watchlist.contains("tv:8") && o.recent.isEmpty());
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}
