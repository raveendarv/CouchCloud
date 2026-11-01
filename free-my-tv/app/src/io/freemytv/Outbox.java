package io.freemytv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The TV's copy of your TMDB account (what you rated, your watchlist, your favorites) and the outbox of what you did
 * here that TMDB hasn't got yet. TMDB is the record; this is a cache that Account keeps on disk and refreshes from the
 * account. Plain Java (no Android), so tools/test.sh runs its tests on a JDK.
 *
 * Verdicts: Watched → a rating of 4 to 10 (and off the watchlist); 👎 → a rating of DOWN_RATING (TMDB's lowest, never one
 * the Watched pills give, so it reads as "not for me, didn't watch"); 👍 → the watchlist. Rated or favorited = seen (out of
 * the rows); on the watchlist = shown first. A new op on a title replaces the pending one of its family (rating or
 * watchlist), the state here changes at once, and the pending ops are laid over every pull until they land; the ones that
 * landed stay laid over for RECENT_MS too, since TMDB's account lists lag a few seconds behind a write (a pull right after
 * answered without the rating just given, and the card came back).
 */
final class Outbox {
    static final double DOWN_RATING = 0.5;
    /** The verdicts as the home screen names them (Feedback uses the same). */
    static final String UP = "up", DOWN = "down", WATCHED = "watched";
    static final String RATE = "rate", UNRATE = "unrate", WATCHLIST = "watchlist", UNWATCHLIST = "unwatchlist";
    /** Retries: 1 min after the first failure, doubling to an hour. */
    static final long BACKOFF_MIN = 60_000L, BACKOFF_MAX = 3600_000L;
    /** How long an op that landed keeps winning over what a pull says. */
    static final long RECENT_MS = 15 * 60_000L;

    static final class Op {
        String op, key, title = "", error = "";
        double value;       // RATE: 0.5 to 10
        long at, nextAt;    // when you did it; when to try (again)
        long doneAt;        // when TMDB took it (recent list only)
        int tries;

        String kind() { return key.substring(0, key.indexOf(':')); }
        String id() { return key.substring(key.indexOf(':') + 1); }
        boolean isRating() { return RATE.equals(op) || UNRATE.equals(op); }
    }

    final Map<String, Double> rated = new HashMap<>();
    final Set<String> watchlist = new HashSet<>(), favorites = new HashSet<>();
    final List<Op> ops = new ArrayList<>();
    /** What landed lately (doneAt within RECENT_MS), still laid over the pulls. */
    final List<Op> recent = new ArrayList<>();
    long pulledAt;

    /** Watched (or 👎 with DOWN_RATING): the title leaves the watchlist too, on TMDB as well. */
    void rate(String key, String title, double value, long now) {
        rated.put(key, value);
        if (watchlist.remove(key)) enqueue(UNWATCHLIST, key, title, 0, now);
        enqueue(RATE, key, title, value, now);
    }

    void unrate(String key, String title, long now) {
        rated.remove(key);
        enqueue(UNRATE, key, title, 0, now);
    }

    /** 👍 on, or taken back. A 👎 (DOWN_RATING) on the same title goes when the title joins the watchlist. */
    void want(String key, String title, boolean on, long now) {
        if (on) {
            Double r = rated.get(key);
            if (r != null && r <= DOWN_RATING) unrate(key, title, now);
            watchlist.add(key);
        } else {
            watchlist.remove(key);
        }
        enqueue(on ? WATCHLIST : UNWATCHLIST, key, title, 0, now);
    }

    private void enqueue(String op, String key, String title, double value, long now) {
        boolean rating = RATE.equals(op) || UNRATE.equals(op);
        ops.removeIf(o -> o.key.equals(key) && o.isRating() == rating);
        Op o = new Op();
        o.op = op;
        o.key = key;
        o.title = title == null ? "" : title;
        o.value = value;
        o.at = now;
        ops.add(o);
    }

    /** The ops to send now (their wait is over), oldest first. */
    List<Op> due(long now) {
        List<Op> out = new ArrayList<>();
        for (Op o : ops) if (o.nextAt <= now) out.add(o);
        return out;
    }

    /** TMDB took it: it keeps winning over the pulls for RECENT_MS (TMDB's lists lag behind its writes). */
    void done(Op op, long now) {
        ops.remove(op);
        op.doneAt = now;
        recent.removeIf(o -> o.key.equals(op.key) && o.isRating() == op.isRating());
        recent.add(op);
    }

    /** TMDB can never take it (a title gone from TMDB, a request it refuses): forgotten. */
    void drop(Op op) {
        ops.remove(op);
    }

    /** TMDB didn't take it this time: waits BACKOFF_MIN × 2^(tries−1), an hour at most. */
    void failed(Op op, String error, long now) {
        op.tries++;
        op.error = error == null ? "" : error;
        op.nextAt = now + backoff(op.tries);
    }

    /** Nothing gets through (offline, TMDB down): every due op waits, without trying each one. */
    void failedAll(String error, long now) {
        for (Op o : due(now)) failed(o, error, now);
    }

    static long backoff(int tries) {
        return Math.min(BACKOFF_MAX, BACKOFF_MIN << Math.min(6, Math.max(0, tries - 1)));
    }

    /** A pull from TMDB replaces the state; what's still in the outbox is laid over it, so nothing flips back meanwhile. */
    void pulled(Map<String, Double> newRated, Set<String> newWatchlist, Set<String> newFavorites, long now) {
        rated.clear();
        rated.putAll(newRated);
        watchlist.clear();
        watchlist.addAll(newWatchlist);
        favorites.clear();
        favorites.addAll(newFavorites);
        recent.removeIf(o -> now - o.doneAt > RECENT_MS || now < o.doneAt);
        for (Op o : recent) apply(o);
        for (Op o : ops) apply(o);
        pulledAt = now;
    }

    private void apply(Op o) {
        switch (o.op) {
            case RATE: rated.put(o.key, o.value); break;
            case UNRATE: rated.remove(o.key); break;
            case WATCHLIST: watchlist.add(o.key); break;
            case UNWATCHLIST: watchlist.remove(o.key); break;
            default: break;
        }
    }

    /** DOWN for a DOWN_RATING, WATCHED for any other rating, UP on the watchlist, else null. */
    String verdict(String key) {
        Double r = rated.get(key);
        if (r != null) return r <= DOWN_RATING ? DOWN : WATCHED;
        return watchlist.contains(key) ? UP : null;
    }

    /** The Watched rating (1 to 10) given to a title, or 0 (none, or a 👎). */
    int watchedRating(String key) {
        Double r = rated.get(key);
        return r == null || r <= DOWN_RATING ? 0 : (int) Math.round(r);
    }

    /** Rated (any value) or favorited: not to be shown. */
    Set<String> seen() {
        Set<String> out = new HashSet<>(rated.keySet());
        out.addAll(favorites);
        return Collections.unmodifiableSet(out);
    }
}
