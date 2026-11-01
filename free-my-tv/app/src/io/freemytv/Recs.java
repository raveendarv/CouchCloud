package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        String link;            // opens the title in its app (JustWatch, Watchmode), or null: the app's home
        /** The title's other services on the TV ({app, pkg}), and their links (app → link, "" = none): "Watch on" picks one. */
        List<String[]> alts = new ArrayList<>();
        Map<String, String> altLinks = new HashMap<>();

        /** The package of one of its services (the first, or an alternative). */
        String pkgOf(String app) {
            if (app == null || app.equals(this.app)) return pkg;
            for (String[] a : alts) if (a[0].equals(app)) return a[1];
            return pkg;
        }

        /** The link on one of its services, or null. */
        String linkOf(String app) {
            if (app == null || app.equals(this.app)) return link;
            String l = altLinks.get(app);
            return l == null || l.isEmpty() ? null : l;
        }
        boolean detailed;
        String lang = "";       // TMDB original_language ("ta", "hi"...), for the Pan-India quota
        // For the Scorer (from the details: genres, cast, crew, keywords), and its verdict.
        List<String> features = new ArrayList<>();
        String firstGenre = "", leadActor = "", reason = "";
        double popularity;
        long releasedAt;
        /** TMDB's user score (vote_average, 0 to 10; 0 = none yet) and its text for the card ("★ 7.8", made once). */
        double score;
        String scoreText = "";
        /** JustWatch's ratings, as it shows them: IMDb score and votes, Rotten Tomatoes' meter, JustWatch's own (0 to 100). */
        double imdb;
        int imdbVotes, tomato, jw;
        boolean certifiedFresh;
        String imdbText = "", tomatoText = "", jwText = "";   // for the card and the info page, made once
        /** For the info page: the synopsis, the director (or the series' creators), the top cast, every genre, the age rating. */
        String overview = "", director = "", cast = "", genres = "", certification = "", length = "";   // length: "2h 5m", "3 seasons"
        String window = "";     // Trending tab: "day", "week" or "day,week" (the windows the title is trending in)
        int year;
        String trailer;         // the YouTube key of the trailer (TMDB /videos), or null
        String imdbId;          // IMDb's id ("tt…", TMDB external_ids), what Stremio opens a title by; null without one
        String verdict;         // your verdict on it (Feedback), looked up by the home screen; not saved here
        java.io.File photo;     // one of your own photos (Photos): no title, no caption
        String elsewhere;       // the service it streams on (subscription, free or with ads) when none of the TV's apps has it
        String rentOn;          // the store it can be rented or bought from, when it streams nowhere (the Others tab says "Rent on …")
        boolean other;          // Upcoming tab: not out yet (window = SOON); the card opens the info page
        long inCinemas, streamingAt;   // the movie's theatrical and digital release dates in REGION (TMDB release_dates), 0 = unknown

        boolean upcoming() { return other && SOON.equals(window); }
        String poster;          // search: TMDB's poster, the picture when the title has no backdrop (not saved)

        void setScore(double value) {
            score = value;
            scoreText = value > 0 ? "\u2605 " + String.format(Ui.locale(), "%.1f", value) : "";
        }

        void setRatings(double imdbScore, int votes, int tomatoMeter, boolean fresh, int jwRating) {
            imdb = imdbScore;
            imdbVotes = votes;
            tomato = tomatoMeter;
            certifiedFresh = fresh;
            jw = jwRating;
            imdbText = imdb > 0 ? "IMDb " + String.format(Ui.locale(), "%.1f", imdb) : "";
            tomatoText = tomato > 0 ? "\uD83C\uDF45 " + tomato + "%" : "";
            jwText = jw > 0 ? "JW " + jw + "%" : "";
        }

        /** "12K" or "1.2M": the IMDb votes, short, for the info page. */
        String votesText() {
            if (imdbVotes <= 0) return "";
            if (imdbVotes >= 1_000_000) return String.format(Ui.locale(), "%.1fM", imdbVotes / 1e6);
            if (imdbVotes >= 10_000) return (imdbVotes / 1000) + "K";
            if (imdbVotes >= 1000) return String.format(Ui.locale(), "%.1fK", imdbVotes / 1e3);
            return String.valueOf(imdbVotes);
        }
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    /** The backdrops: on a 720p UI, TMDB's 1280-wide copy shows the same and is lighter to download and decode. */
    private static final String IMAGES = "https://image.tmdb.org/t/p/" + (Backdrop.W <= 1280 ? "w1280" : "original");
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
    private static final int ANIMATION = 16;
    /** Cards on the home screen at once (one wide, the others tall), a new set every hour. */
    static final int CARDS = 6;
    /** Votes a series needs on TMDB to be listed (movies need 10): Indian series on JioHotstar rarely reach 10. */
    private static final int SERIES_MIN_VOTES = 3;
    private static final long LIST_EVERY = 6 * 3600_000L, THIN_EVERY = 30 * 60_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 18;   // 18: backdrops are TMDB's primary again
    /** Your TMDB account's token (a v4 user access token), in files/recs, put there by tools/recs/recs.py token. */
    static final String TOKEN_FILE = Account.TOKEN_FILE;
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"prime video", "prime", "com.amazon.firebat"},
        {"prime video", "prime", "com.amazon.avod"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"hulu", "hulu", "com.hulu.plus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"peacock", "peacock", "com.peacock.peacockfiretv"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"apple tv", "appletv", "com.apple.atve.sony.appletv"},   // Sony's own build
        {"apple tv", "appletv", "com.apple.atve.amazon.appletv"},
        {"hotstar", "hotstar", "in.startv.hotstar"},   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        {"zee5", "zee5", "com.graymatrix.did"},        // India: TMDB "Zee5" (232)
        {"sony liv", "sonyliv", "com.sonyliv"},        // India: TMDB "Sony Liv" (237)
        {"sun nxt", "sunnxt", "com.suntv.sunnxt"},     // India: TMDB "Sun Nxt" (309)
        {"aha", "aha", "ahaflix.tv"},                  // India (Telugu, Tamil): TMDB "aha" (532)
        {"okko", "okko", "ru.more.play"},
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"oneplay", "oneplay", "net.cme.voyo.cz.tvapp"},    // Czechia: Voyo and O2 TV became Oneplay, same app
    };
    static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("hotstar", "JioHotstar");
        SERVICE_NAME.put("zee5", "ZEE5");
        SERVICE_NAME.put("sonyliv", "SonyLIV");
        SERVICE_NAME.put("sunnxt", "Sun NXT");
        SERVICE_NAME.put("aha", "aha");
        SERVICE_NAME.put("okko", "Okko");
        SERVICE_NAME.put("crunchyroll", "Crunchyroll");
        SERVICE_NAME.put("oneplay", "Oneplay");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);
    /** The errors the settings say in words: TMDB lists none of the TV's apps in REGION, or the TV has none we know. */
    static final String NO_APPS = "none of the TV's streaming services are on TMDB";
    static final String NO_KNOWN_APPS = "none of the TV's streaming apps can give picks";

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** The cards' images, apart from the lists and details: a long fetch (Trending details dozens of titles) mustn't leave the cards gray. */
    private final ExecutorService imageIo = Executors.newSingleThreadExecutor();
    /** Details of several titles at once: TMDB answers each in about 0.7 s, so one after another a tab's six took 5 s. */
    private final ExecutorService detailIo = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    /** Every tab's, this hour, built and ready to draw. Access order: what the screen draws (asked for on every frame) is never the one evicted. */
    private final Map<String, Backdrop> images = new LinkedHashMap<>(64, 0.75f, true);
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    /** One list per tab (Tabs.Tab id), up to 24 titles each, and when each was fetched. */
    private final Map<String, List<Pick>> lists = new ConcurrentHashMap<>();
    private final Map<String, Long> listAts = new ConcurrentHashMap<>();
    /** When each list was really fetched in this process (listAts carries the 10 min per tab offset): for the thin-tab refetch. */
    private final Map<String, Long> fetchedAt = new ConcurrentHashMap<>();
    /** With a profile: each tab's list ranked by the Scorer (the seen and the hidden left out), best first. */
    private final Map<String, List<Pick>> ranked = new ConcurrentHashMap<>();
    /** "Other picks": how many times each tab was turned past this hour's set (saved, so a restart shows the same). */
    private final Map<String, Integer> turns = new ConcurrentHashMap<>();
    /** The tab on screen (HomeView says): its cards are the last to go when memory runs out. */
    private volatile Tabs.Tab current;
    private volatile long rankedStamp = -1;
    private volatile int period = -1;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp, linkRetryAt;
    /**
     * What failed lately ("d:" + key: details, "i:" + path: an image, "t:" + path: a thumb) and when: not asked again
     * for a while, since one title TMDB won't serve would otherwise cost a request every 15 s tick (or every drawn frame,
     * for a card's image) and hold the io thread.
     */
    private final Map<String, Long> failed = new ConcurrentHashMap<>();
    /** This hour's cards (wanted) wait the short one: a card without details has no app to open and no genre line. */
    private static final long DETAILS_WAIT = 10 * 60_000L, IMAGE_WAIT = 60_000L, WANTED_WAIT = 60_000L;

    private boolean failedLately(String what, long wait) {
        Long at = failed.get(what);
        if (at == null) return false;
        long since = System.currentTimeMillis() - at;
        if (since >= 0 && since < wait) return true;
        failed.remove(what);
        return false;
    }
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("lists", null);
            if (saved != null) {
                JSONObject all = new JSONObject(saved);
                for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                    String id = it.next();
                    if (Tabs.byId(id) == null) continue;   // a tab that's gone (Series)
                    lists.put(id, parse(all.getJSONArray(id)));
                    listAts.put(id, cache.getLong("listAt:" + id, 0));
                }
            }
            for (Tabs.Tab t : Tabs.ALL) if (cache.contains("turn:" + t.id)) turns.put(t.id, cache.getInt("turn:" + t.id, 0));
            // Format markers: a list whose shape changed is dropped and fetched again. The drop is saved at once with the
            // marker: saved only by the next successful fetch, an offline start would have kept the old list under a new marker.
            boolean dropped = false;
            if (cache.getInt("trendingFormat", 0) != 1) {   // the Trending list gained its month window: fetched again, alone
                lists.remove("trending");
                listAts.remove("trending");
                cache.edit().putInt("trendingFormat", 1).apply();
                dropped = true;
            }
            if (cache.getInt("kindFormat", 0) != 2) {   // series need 3 votes now (10 left JioHotstar's Tamil shows out): fetched again
                for (String id : new String[] {"ta", "en-movie", "series"}) {
                    lists.remove(id);
                    listAts.remove(id);
                }
                cache.edit().putInt("kindFormat", 2).apply();
                dropped = true;
            }
            if (cache.getInt("panFormat", 0) != 2) {   // Pan-India gained Malayalam (and 12 titles per language): fetched again
                lists.remove("pan");
                listAts.remove("pan");
                lists.remove("ml");
                cache.edit().putInt("panFormat", 2).apply();
                dropped = true;
            }
            period = cache.getInt("period", -1);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
            if (dropped) save();
        } catch (Exception ignored) {
        }
        io.execute(this::rankAll);   // the saved lists, in the profile's order, before the first refresh
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        // Written whole, then renamed: the settings screen (another process) may read it at any moment.
        File f = new File(context.getFilesDir(), "recs-status.json"), part = new File(f.getPath() + ".part");
        try {
            Profile profile = ProfileStore.get(context);
            JSONObject perTab = new JSONObject();
            for (Tabs.Tab t : Tabs.enabled()) perTab.put(t.id, lists.containsKey(t.id) ? lists.get(t.id).size() : 0);
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(new JSONObject().put("count", all().size()).put("tabs", perTab).put("error", error)
                        .put("profile", profile.isEmpty() ? 0 : profile.builtAt).put("account", Account.status(context)).put("at", System.currentTimeMillis())
                        .toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            part.renameTo(f);
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    /** The refresh is running (the home screen shows placeholder cards for a tab that has nothing yet). */
    boolean fetching() { return fetching; }

    static String periodLabel() {
        int p = period(System.currentTimeMillis());
        return p == MANHA ? Ui.tr("THIS MORNING") : p == TARDE ? Ui.tr("THIS AFTERNOON") : Ui.tr("TONIGHT");
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** Every tab's list, one after the other (the screensaver, the status, the cache). */
    /** The title one of our links opens (JustWatch's deep link, "hotstar://content/<id>"), or null: Continue Watching's cards. */
    Pick pickByLink(String link) {
        if (link == null || link.isEmpty()) return null;
        for (Pick p : all()) {
            if (link.equals(p.link)) return p;
            for (String l : p.altLinks.values()) if (link.equals(l)) return p;
        }
        return null;
    }

    private List<Pick> all() {
        List<Pick> out = new ArrayList<>();
        for (Tabs.Tab t : Tabs.ALL) {
            List<Pick> l = lists.get(t.id);
            if (l != null) out.addAll(l);
        }
        return out;
    }

    /** This hour's picks of the first tab (the first is the featured one). */
    List<Pick> now() {
        List<Tabs.Tab> tabs = Tabs.enabled();
        return now(tabs.get(0));
    }

    /**
     * This hour's CARDS picks of a tab: the ones with their app on the TV, never two from the same franchise ("Law &
     * Order" and "Law & Order: Special…"), and on a Pan-India tab never more than CARDS - 2 from one language when the
     * list has others.
     */
    List<Pick> now(Tabs.Tab tab) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context) || tab == null) return list;
        List<Pick> source = lists.get(tab.id);
        if (source == null) return list;
        Set<String> langs = new HashSet<>();
        Set<String> rated = Account.seen(context), profileSeen = ProfileStore.get(context).seen;   // rated (👎 too) or favorited: gone
        String window = tab.trending ? trendingWindow() : null, language = tab.pan ? panLanguage() : "", kind = tabKind(tab);
        for (Pick p : source) {
            String k = key(p);
            if (rated.contains(k) || profileSeen.contains(k)) continue;
            if (window != null && !inWindow(p.window, window)) continue;
            if (tab.other && !otherLanguage().isEmpty() && !p.lang.equals(otherLanguage())) continue;   // Upcoming: its language
            if (!language.isEmpty() && !p.lang.equals(language)) continue;
            if (!kind.isEmpty() && !p.kind.equals(kind)) continue;
            // Whether its details are in doesn't matter here: the row must not change as they arrive (they fill the card in
            // place; detailAll fetches the row's first). Any service: Stremio finds a stream for what the TV's apps lack.
            list.add(p);
            langs.add(p.lang);
        }
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L + turnOf(tab);
        List<Pick> best = ranked.get(tab.id);
        if (best != null && !best.isEmpty()) {   // a profile: the top 2 × CARDS, this hour's half of it
            List<Pick> fit = new ArrayList<>();
            for (Pick p : best) if (list.contains(p)) fit.add(p);
            int start = (int) ((hour % 2) * CARDS);
            List<Pick> out = new ArrayList<>();
            for (int i = 0; i < Math.min(CARDS, fit.size()); i++) out.add(fit.get((start + i) % fit.size()));
            if (!out.isEmpty()) return out;
        }
        int perLanguage = tab.pan && langs.size() > 1 && language.isEmpty() ? CARDS - 2 : Integer.MAX_VALUE;
        List<Pick> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Integer> byLang = new HashMap<>();
        for (int i = 0; i < list.size() && out.size() < CARDS; i++) {
            Pick p = list.get((int) ((hour * CARDS + i) % list.size()));
            int n = byLang.containsKey(p.lang) ? byLang.get(p.lang) : 0;
            if (n >= perLanguage) continue;
            if (seen.add(franchise(p.title))) {
                out.add(p);
                byLang.put(p.lang, n + 1);
            }
        }
        return out;
    }

    private int turnOf(Tabs.Tab tab) {
        Integer n = turns.get(tab.id);
        return n == null ? 0 : n;
    }

    /**
     * "Other picks": the tab moves on to its next set of CARDS (as the next hour would), and their details and images
     * are fetched right away. The 6-hour list stays; only the window over it turns.
     */
    void turn(Tabs.Tab tab) {
        if (tab == null) return;
        int n = turnOf(tab) + 1;
        turns.put(tab.id, n);
        cache.edit().putInt("turn:" + tab.id, n).apply();
        refreshIfStale();
    }

    /** HomeView: the tab on screen, whose cards trim() keeps. */
    void setCurrent(Tabs.Tab tab) { current = tab; }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (failedLately("i:" + key, IMAGE_WAIT) || !pending.add(key)) return null;   // asked every frame: a failure waits a minute
        }
        imageIo.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        // Your TMDB account: what you did here that TMDB hasn't got (the outbox's own backoff says when), and the pull of
        // what you rated and want to watch, every 6 hours.
        if (!syncing && Account.due(context, now)) syncSoon();
        long stamp = sourceStamp();   // a new key, country, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean all = newKey || p != period || !listLanguage.equals(Ui.language);
        List<Tabs.Tab> stale = new ArrayList<>();
        for (Tabs.Tab t : Tabs.enabled()) {
            long at = listAts.containsKey(t.id) ? listAts.get(t.id) : 0;
            List<Pick> l = lists.get(t.id);
            // A thin tab (you rated most of its row away, or its filter leaves few): fetched again after 30 min, not 6 h.
            long fetched = fetchedAt.containsKey(t.id) ? fetchedAt.get(t.id) : 0;
            boolean thin = l != null && !l.isEmpty() && now(t).size() < CARDS && now - fetched > THIN_EVERY;
            if (thin) AppLog.w("recommendations: " + t.id + " shows " + now(t).size() + " cards: fetched again");
            if (all || l == null || l.isEmpty() || now - at > LIST_EVERY || now < at || thin) stale.add(t);
        }
        boolean missing = false;
        for (Pick pick : wanted()) {
            if (!pick.detailed) missing |= !failedLately("d:" + key(pick), WANTED_WAIT);
            else if (!hasImage(pick)) missing |= !failedLately("i:" + pick.backdrop, IMAGE_WAIT);
            missing |= needsLink(pick);
        }
        Profile profile = ProfileStore.get(context);
        List<Pick> toDetail = new ArrayList<>();
        if (!profile.isEmpty()) {   // the scorer needs every candidate's details, not just this hour's
            for (Tabs.Tab t : Tabs.enabled()) {
                List<Pick> l = lists.get(t.id);
                if (l != null) for (Pick pick : l) if (!pick.detailed && !failedLately("d:" + key(pick), DETAILS_WAIT)) toDetail.add(pick);
            }
        }
        boolean rerank = rankedStamp != profile.builtAt;
        if (stale.isEmpty() && !missing && toDetail.isEmpty() && !rerank) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                // Each tab's list is its own fetch, 10 minutes apart in age, so later refreshes don't all come due at once.
                // The tab on screen comes first, and each tab is shown as soon as its list and its cards' details are in
                // (one after another, with the Trending tab last, the screen stayed empty for a minute after a cold start).
                // One tab's failure (TMDB returned nothing for it, a reset mid-way) doesn't stop the other tabs' lists; only
                // when every stale tab failed does the whole refresh count as failed (10 min before the next try).
                List<Tabs.Tab> order = new ArrayList<>(stale);
                Tabs.Tab shown = current != null ? current : Tabs.enabled().get(0);
                if (order.remove(shown)) order.add(0, shown);
                int listFailures = 0;
                String listError = "";
                for (int i = 0; i < order.size(); i++) {
                    Tabs.Tab t = order.get(i);
                    long started = System.currentTimeMillis();
                    try {
                        fetchList(t, p, stale.indexOf(t) * 10 * 60_000L);
                    } catch (Exception e) {
                        listFailures++;
                        listError = e.getMessage() == null ? e.toString() : e.getMessage();
                        AppLog.w("recommendations: " + t.id + ": " + listError);
                        continue;
                    }
                    detailTab(t);
                    AppLog.w("recommendations: " + t.id + " ready in " + (System.currentTimeMillis() - started) / 1000 + " s");
                    save();
                    notifyListeners();
                }
                if (!stale.isEmpty() && listFailures == stale.size()) throw new IOException(listError);
                if (!stale.contains(shown)) detailTab(shown);   // its list was here already: its row's details, if any are missing, first
                if (!stale.isEmpty()) {
                    StringBuilder sizes = new StringBuilder("lists:");
                    for (Tabs.Tab t : Tabs.enabled()) sizes.append(' ').append(t.id).append(' ').append(lists.containsKey(t.id) ? lists.get(t.id).size() : 0);
                    AppLog.w(sizes.toString());
                }
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                // The cards' links (JustWatch, about 0.8 s each) are needed only when OK is pressed: after the cards are up.
                for (Pick pick : wanted()) if (pick.detailed) findLink(pick);
                if (!profile.isEmpty()) {   // the scorer needs every candidate's details, not just this hour's
                    List<Pick> candidates = new ArrayList<>();
                    for (Tabs.Tab t : Tabs.enabled()) {
                        List<Pick> l = lists.get(t.id);
                        if (l == null) continue;
                        for (Pick pick : l) {
                            if (pick.detailed || failedLately("d:" + key(pick), DETAILS_WAIT)) continue;
                            if (t.trending && !inWindow(pick.window, trendingWindow())) continue;   // the other lists' titles: when they are on screen
                            candidates.add(pick);
                        }
                    }
                    detailAll(candidates, DETAILS_WAIT);
                    rankAll();
                }
                for (Pick pick : wanted()) {
                    if (!pick.detailed && failedLately("d:" + key(pick), WANTED_WAIT)) continue;
                    if (pick.detailed && !hasImage(pick) && failedLately("i:" + pick.backdrop, IMAGE_WAIT) && !needsLink(pick)) continue;
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        download(pick);
                    } catch (Exception e) {
                        failed.put(pick.detailed ? "i:" + pick.backdrop : "d:" + key(pick), System.currentTimeMillis());
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                        AppLog.w((pick.detailed ? "image “" : "details “") + pick.title + "”: " + last);
                    }
                    findLink(pick);
                }
                save();
                // Titles failing is said (Settings) but doesn't pause the refresh: each waits its own minute(s); a list
                // failing (fetchList threw above) does pause it, 10 minutes.
                error = tried > 0 && failures == tried ? last : "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /**
     * Details for this hour's cards of a tab, several at once, so the tab can be shown (the home screen fetches their
     * images itself). A title that turns out not to play here leaves the row and another takes its place: a few rounds.
     */
    private void detailTab(Tabs.Tab t) {
        List<Pick> todo = new ArrayList<>();
        for (Pick pick : now(t)) if (!pick.detailed && !failedLately("d:" + key(pick), WANTED_WAIT)) todo.add(pick);
        if (!todo.isEmpty()) detailAll(todo, WANTED_WAIT);
    }

    /**
     * Details for these titles, in batches of 8 on four threads. Before each batch, whatever the row on screen still lacks
     * goes first (the tab or the Trending list may have changed meanwhile), and the screen is told as soon as it's in.
     * Errors are logged and remembered for `wait`, not thrown: the rest goes on.
     */
    private void detailAll(List<Pick> picks, long wait) {
        java.util.ArrayDeque<Pick> queue = new java.util.ArrayDeque<>(picks);
        while (true) {
            List<Pick> batch = new ArrayList<>();
            Tabs.Tab t = current;
            if (t != null) for (Pick pick : now(t)) if (!pick.detailed && !failedLately("d:" + key(pick), WANTED_WAIT) && !batch.contains(pick)) batch.add(pick);
            boolean onScreen = !batch.isEmpty();
            while (batch.size() < 8 && !queue.isEmpty()) {
                Pick pick = queue.poll();
                if (!pick.detailed && !batch.contains(pick)) batch.add(pick);
            }
            if (batch.isEmpty()) return;
            List<Future<?>> jobs = new ArrayList<>();
            for (Pick pick : batch) {
                jobs.add(detailIo.submit(() -> {
                    try {
                        fetchDetails(pick);
                    } catch (Exception e) {
                        failed.put("d:" + key(pick), System.currentTimeMillis());
                        AppLog.w("details “" + pick.title + "”: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                    }
                }));
            }
            for (Future<?> job : jobs) {
                try {
                    job.get();
                } catch (Exception ignored) {   // its own failure is logged above
                }
            }
            if (onScreen) notifyListeners();
        }
    }

    /** True when what you do here reaches your TMDB account (its token is on the TV). */
    boolean hasAccount() {
        return Account.token(context) != null;
    }

    /** TMDB refused the token on the TV: nothing goes out until recs.py token puts a new one. */
    boolean tokenRefused() {
        return Account.tokenRefused(context);
    }

    private volatile boolean syncing;

    /** After a verdict on the TV, and on the tick when Account.due: the outbox out, the account in (in the background). */
    void syncSoon() {
        if (syncing) return;
        syncing = true;
        io.execute(() -> {
            try {
                if (Account.sync(context)) rankAll();   // the seen or the watchlist changed: the rows follow
            } finally {
                syncing = false;
                writeStatus();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            for (Backdrop b : images.values()) retire(b);
            images.clear();
        }
    }

    /**
     * Frees a backdrop's GPU copies on the main thread: the screen takes a backdrop from `images` on every frame and
     * draws it in that same frame, so what is gone from the map by the next frame is drawn no more.
     */
    private void retire(final Backdrop b) {
        main.post(b::release);
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        for (Pick p : all()) if (p.detailed && hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : all()) if (!p.detailed || !hasImage(p)) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks of every tab on the home screen, without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Tabs.Tab t : Tabs.enabled()) {
            for (Pick p : now(t)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    /**
     * One tab's list: for each of its languages and kinds, what is popular now and what is new (movies from the past
     * year, series with an episode in the past 3 months), on any service (the card says where it streams; Stremio finds
     * a stream for what the TV's apps lack), merged round-robin over the languages and kinds so each gets its share, up to 24. Pan-India languages also need pan_min_votes and
     * pan_min_popularity (config.properties). /trending can't filter by language, so popularity on /discover stands
     * in for "trending"; the details (and the app each title is on) come later, as for upstream's new releases.
     */
    private void fetchList(Tabs.Tab tab, int p, long ageOffset) throws Exception {
        if (tab.trending) {
            fetchTrending(tab, p, ageOffset);
            return;
        }
        if (tab.other) {
            fetchOther(tab, p, ageOffset);
            return;
        }
        String common = "&language=" + lang() + "&include_adult=false&sort_by=popularity.desc" + (Config.HIDE_ANIMATION ? "&without_genres=" + ANIMATION : "");
        List<List<Pick>> popular = new ArrayList<>(), fresh = new ArrayList<>();
        Set<String> skip = seenKeys();
        for (String language : tab.languages) {
            // The Pan-India thresholds are for Telugu, Hindi and Kannada (TMDB's crowds there); Malayalam, which had its
            // own tab before joining, keeps the plain ones (20 votes left it with next to nothing).
            boolean strict = tab.pan && !language.equals("ml");
            for (String kind : tab.kinds()) {
                // Series get far fewer TMDB votes than movies (JioHotstar's Tamil shows sit at 2 to 7): a lower bar for them.
                int minVotes = strict ? Config.PAN_MIN_VOTES : kind.equals("tv") ? SERIES_MIN_VOTES : 10;
                String base = "/discover/" + kind + "?with_original_language=" + language + "&vote_count.gte=" + minVotes + common;
                String window = kind.equals("movie")
                        ? "&primary_release_date.gte=" + day(-365) + "&primary_release_date.lte=" + day(0)
                        : "&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0);
                List<Pick> hot = new ArrayList<>(), recent = new ArrayList<>();
                pages(hot, base, kind, language, strict, skip);
                pages(recent, base + window, kind, language, strict, skip);
                popular.add(hot);
                fresh.add(recent);
            }
        }
        // New first (what to see this month), then the popular ones, round-robin over languages and kinds, up to 24.
        List<Pick> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int max = tab.pan ? 12 * tab.languages.length : 24;   // Pan-India filtered to one language still has 12
        roundRobin(list, seen, fresh, max);
        roundRobin(list, seen, popular, max);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations for " + tab.label);
        // A title the old list had keeps its details (and its card can be opened at once; the 6-hour refresh fetched them all again).
        List<Pick> old = lists.get(tab.id);
        Map<String, Pick> known = new HashMap<>();
        if (old != null) for (Pick x : old) if (x.detailed) known.put(key(x), x);
        for (int i = 0; i < list.size(); i++) {
            Pick k = known.get(key(list.get(i)));
            if (k == null) continue;
            k.popularity = list.get(i).popularity;
            list.set(i, k);
        }
        lists.put(tab.id, list);
        listAts.put(tab.id, System.currentTimeMillis() - ageOffset);
        fetchedAt.put(tab.id, System.currentTimeMillis());
        period = p;
        listLanguage = Ui.language;
    }

    /** A movies-and-series tab's filter: "movie", "tv" or "" for both. Kept across restarts. */
    String tabKind(Tabs.Tab tab) {
        return tab.kind != null ? "" : cache.getString("kind:" + tab.id, "");
    }

    void setTabKind(Tabs.Tab tab, String kind) {
        cache.edit().putString("kind:" + tab.id, kind).apply();
        chosen();
    }

    /** After a choice on a tab (its list, window, language or kind): the row follows at once, and its details are fetched now. */
    private void chosen() {
        notifyListeners();
        main.post(this::refreshIfStale);   // on the UI thread, as the tick; a running job picks the row up itself (detailAll)
    }

    /** The Pan-India tab's language filter: a TMDB code ("te") or "" for all of them. Kept across restarts. */
    String panLanguage() {
        return cache.getString("panLanguage", "");
    }

    void setPanLanguage(String language) {
        cache.edit().putString("panLanguage", language).apply();
        chosen();
    }

    /** TMDB's trending titles today and this week: the Trending tab shows one window at a time (OK on the switch). */
    String trendingWindow() {
        return cache.getString("trendingWindow", "day");
    }

    void setTrendingWindow(String window) {
        cache.edit().putString("trendingWindow", window).apply();
        chosen();
    }

    /**
     * The Trending tab's lists (hold OK on the tab): trending today, this week, this month and popular overall (TREND), or
     * the best rated of the month, the year and all time (RATED). Pick.window holds the ones a title is in, comma separated.
     */
    static final String[] TREND = {"day", "week", "month", "all"}, RATED = {"r-month", "r-year", "r-all"};
    static final String[] WINDOWS = {"day", "week", "month", "all", "r-month", "r-year", "r-all"};
    private static final int PER_WINDOW = 12;

    static boolean rated(String window) { return window.startsWith("r-"); }

    /** Is `window` one of the comma-separated `windows` ("all" is not "r-all"). */
    private static boolean inWindow(String windows, String window) {
        return ("," + windows + ",").contains("," + window + ",");
    }

    /**
     * The Trending tab: for each of its lists, 24 movies and series (half each when both have enough), all at once so the
     * switch is instant; any language, any service. Today and this week are TMDB's trending lists; this month and
     * popular overall are TMDB's popularity (discover over the past 30 days, /popular); top rated is TMDB's vote average
     * over titles with enough votes (discover over the past 30 days, the past year, or all time). Each title's details
     * come later, card by card (detailTab).
     */
    private void fetchTrending(Tabs.Tab tab, int p, long ageOffset) throws Exception {
        Map<String, Pick> found = new LinkedHashMap<>();
        Set<String> skip = seenKeys();
        List<Pick> old = lists.get(tab.id);
        Map<String, Pick> known = new HashMap<>();
        if (old != null) for (Pick x : old) known.put(key(x), x);
        String common = "&language=" + lang() + "&include_adult=false" + (Config.HIDE_ANIMATION ? "&without_genres=" + ANIMATION : "");
        for (String window : WINDOWS) {
            List<List<Pick>> perKind = new ArrayList<>();
            for (String kind : new String[] {"movie", "tv"}) {
                boolean movie = kind.equals("movie");
                String path;
                switch (window) {
                    case "day": case "week": path = "/trending/" + kind + "/" + window + "?language=" + lang(); break;
                    case "month": path = "/discover/" + kind + "?sort_by=popularity.desc&vote_count.gte=10" + common + between(movie, -30); break;
                    case "all": path = "/" + kind + "/popular?language=" + lang(); break;
                    // Top rated: TMDB's vote average over titles with enough votes (its /top_rated list, and low floors, are
                    // taken over by brand-new releases with a few hundred fans' tens).
                    case "r-month": path = "/discover/" + kind + "?sort_by=vote_average.desc&vote_count.gte=" + (movie ? 200 : 50) + common + between(movie, -30); break;
                    case "r-year": path = "/discover/" + kind + "?sort_by=vote_average.desc&vote_count.gte=" + (movie ? 1000 : 300) + common + between(movie, -365); break;
                    default: path = "/discover/" + kind + "?sort_by=vote_average.desc&vote_count.gte=" + (movie ? 5000 : 2000) + common; break;
                }
                List<Pick> l = new ArrayList<>();
                JSONArray a = get(path).getJSONArray("results");
                for (int i = 0; i < a.length(); i++) add(l, a.getJSONObject(i), kind, a.getJSONObject(i).optString("original_language", ""), false, skip);
                perKind.add(l);
            }
            List<Pick> merged = new ArrayList<>();
            roundRobin(merged, new HashSet<>(), perKind, 2 * PER_WINDOW);
            for (Pick x : merged) place(found, known, x, window);
        }
        List<Pick> list = new ArrayList<>(found.values());
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations for " + tab.label);
        lists.put(tab.id, list);
        listAts.put(tab.id, System.currentTimeMillis() - ageOffset);
        fetchedAt.put(tab.id, System.currentTimeMillis());
        period = p;
        listLanguage = Ui.language;
    }

    /** Released (movies) or with an episode aired (series) in the past `days` days, up to today. */
    private static String between(boolean movie, int days) {
        String field = movie ? "primary_release_date" : "air_date";
        return "&" + field + ".gte=" + day(days) + "&" + field + ".lte=" + day(0);
    }

    /** The Upcoming tab: per language, this many movies coming out in the next this many days. */
    private static final int OTHER_SOON = 12, OTHER_SOON_DAYS = 90;
    /** The Upcoming tab's titles' window (Pick.window): not out yet. */
    static final String SOON = "soon";

    /** The Upcoming tab's language filter: a TMDB code ("ta") or "" for all of them. Kept across restarts. */
    String otherLanguage() {
        return cache.getString("otherLanguage", "");
    }

    void setOtherLanguage(String language) {
        cache.edit().putString("otherLanguage", language).apply();
        chosen();
    }

    /**
     * The Upcoming tab: the most popular movies in each of its languages coming out in the next OTHER_SOON_DAYS days
     * (the dates on the card come with the details); hold OK on the tab picks a language. A card opens the info page
     * (trailer, "Remind me": your TMDB watchlist).
     */
    private void fetchOther(Tabs.Tab tab, int p, long ageOffset) throws Exception {
        Set<String> skip = seenKeys();
        List<Pick> old = lists.get(tab.id);
        Map<String, Pick> known = new HashMap<>();
        if (old != null) for (Pick x : old) known.put(key(x), x);
        String common = "&language=" + lang() + "&include_adult=false&sort_by=popularity.desc" + (Config.HIDE_ANIMATION ? "&without_genres=" + ANIMATION : "")
                + "&primary_release_date.gte=" + day(1) + "&primary_release_date.lte=" + day(OTHER_SOON_DAYS);
        List<List<Pick>> groups = new ArrayList<>();
        for (String language : tab.languages) {
            List<Pick> l = new ArrayList<>();
            JSONArray a = get("/discover/movie?with_original_language=" + language + common).getJSONArray("results");
            for (int i = 0; i < a.length() && l.size() < OTHER_SOON; i++) add(l, a.getJSONObject(i), "movie", language, false, skip);
            groups.add(l);
        }
        List<Pick> list = new ArrayList<>();
        roundRobin(list, new HashSet<>(), groups, OTHER_SOON * tab.languages.length);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations for " + tab.label);
        for (int i = 0; i < list.size(); i++) {
            Pick x = list.get(i), k = known.get(key(x));
            if (k != null && k.detailed) {   // keeps its details
                k.popularity = x.popularity;
                list.set(i, x = k);
            }
            x.other = true;
            x.window = SOON;
        }
        lists.put(tab.id, list);
        listAts.put(tab.id, System.currentTimeMillis() - ageOffset);
        fetchedAt.put(tab.id, System.currentTimeMillis());
        period = p;
        listLanguage = Ui.language;
    }

    private static void place(Map<String, Pick> found, Map<String, Pick> known, Pick x, String window) {
        Pick have = found.get(key(x));
        if (have == null) {
            Pick k = known.get(key(x));
            if (k != null && k.detailed) {
                k.popularity = x.popularity;
                x = k;
            }
            x.window = window;
            found.put(key(x), x);
        } else if (!inWindow(have.window, window)) {
            have.window += "," + window;
        }
    }

    /** One from each list in turn (the lists' own order kept), without repeats, up to `max`. */
    private static void roundRobin(List<Pick> out, Set<String> seen, List<List<Pick>> lists, int max) {
        boolean more = true;
        for (int i = 0; more && out.size() < max; i++) {
            more = false;
            for (List<Pick> l : lists) {
                if (i < l.size()) {
                    more = true;
                    if (out.size() < max && seen.add(key(l.get(i)))) out.add(l.get(i));
                }
            }
        }
    }

    /** Language of titles and genres (TMDB's "fr-FR", "zh-CN"…); the services come from the region. */
    static String lang() {
        String lang = Ui.language;
        if (lang.equals("en")) return "en-US";
        if (lang.equals("pt")) return "pt-BR";
        if (lang.equals("es")) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (lang.equals("de")) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        if (lang.indexOf('-') > 0) return lang;
        return lang + "-" + Ui.locale().getCountry();
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    /** Rated (👎 too) or favorited on TMDB, or seen by the profile: never fetched into a list. */
    private Set<String> seenKeys() {
        Set<String> out = new HashSet<>(Account.seen(context));
        out.addAll(ProfileStore.get(context).seen);
        return out;
    }

    /** How many unseen titles a discover query should yield (half a tab's list for one language and kind). */
    private static final int PER_QUERY = 12, MAX_PAGES = 3;

    /**
     * The query's results, page after page (MAX_PAGES at most) until PER_QUERY unseen titles are in: TMDB's first page
     * has 20, and once you've rated many of them the tab ran dry (Tamil series showed 2 cards).
     */
    private void pages(List<Pick> out, String query, String kind, String language, boolean strict, Set<String> skip) throws Exception {
        for (int page = 1, total = 1; page <= total && page <= MAX_PAGES && out.size() < PER_QUERY; page++) {
            JSONObject r = get(query + "&page=" + page);
            total = r.optInt("total_pages", 1);
            JSONArray a = r.getJSONArray("results");
            for (int i = 0; i < a.length(); i++) add(out, a.getJSONObject(i), kind, language, strict, skip);
        }
    }

    /** `skip`: the titles you rated or favorited (Account, the profile): left out, so the pool refills as you rate instead of shrinking. */
    private static void add(List<Pick> list, JSONObject o, String kind, String language, boolean pan, Set<String> skip) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        if (skip.contains(kind + ":" + o.optInt("id"))) return;
        if (Config.HIDE_ANIMATION && animated(o)) return;
        if (pan && o.optDouble("popularity", 0) < Config.PAN_MIN_POPULARITY) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        p.lang = o.optString("original_language", language);
        p.popularity = o.optDouble("popularity", 0);
        p.setScore(o.optDouble("vote_average", 0));
        p.releasedAt = date(o.optString(kind.equals("movie") ? "release_date" : "first_air_date"));
        list.add(p);
    }

    /** "2026-03-14" → epoch ms (0 when empty or odd). */
    static long date(String ymd) {
        try {
            String[] parts = ymd.split("-");
            Calendar cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            cal.clear();
            cal.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
            return cal.getTimeInMillis();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images,credits,keywords,videos,external_ids," + (p.kind.equals("movie") ? "release_dates" : "content_ratings")
                + "&include_image_language=null," + Ui.language.replaceAll("-.*", "") + ",en"
                + "&include_video_language=" + p.lang + "," + Ui.language.replaceAll("-.*", "") + ",en,null");   // TMDB answers only the asked languages: a Tamil film's trailer is tagged "ta"
        p.title = title(p, o);
        if (o.has("vote_average")) p.setScore(o.optDouble("vote_average", p.score));
        p.trailer = trailer(o.optJSONObject("videos"));
        JSONObject ids = o.optJSONObject("external_ids");
        String imdb = ids == null ? "" : ids.optString("imdb_id");
        p.imdbId = imdb.startsWith("tt") ? imdb : null;
        features(p, o);
        about(p, o);
        String primary = o.optString("backdrop_path", "");
        if ("null".equals(primary)) primary = "";
        String best = bestBackdrop(o.optJSONObject("images"), primary);
        if (best != null) p.backdrop = best;
        else if (!primary.isEmpty()) p.backdrop = primary;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(genre(name));
        }
        p.length = "";
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0) p.length = minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes);
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) p.length = Ui.plural("plural|one:{n} season|other:{n} seasons", seasons);
        }
        if (!p.length.isEmpty()) parts.add(p.length);
        long now = System.currentTimeMillis();
        if (p.kind.equals("movie")) releaseDates(p, o);
        if (p.releasedAt > now || p.inCinemas > now) {   // not out yet (the Others tab's Upcoming side): its dates instead of a service
            if (p.inCinemas > now) parts.add(Ui.trf("In cinemas {date}", "date", shortDate(p.inCinemas)));
            if (p.streamingAt > now) parts.add(Ui.trf("Streaming {date}", "date", shortDate(p.streamingAt)));
            if (p.inCinemas <= now && p.streamingAt <= now) parts.add(Ui.trf("Coming {date}", "date", shortDate(p.releasedAt)));
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        p.alts.clear();
        p.elsewhere = p.rentOn = null;
        for (String offer : new String[] {"flatrate", "free", "ads", "rent", "buy"}) {
            JSONArray flat = br == null ? null : br.optJSONArray(offer);
            boolean streaming = !offer.equals("rent") && !offer.equals("buy");
            for (int i = 0; flat != null && i < flat.length(); i++) {
                String name = flat.getJSONObject(i).optString("provider_name");
                String[] s = streaming ? service(name, flat.getJSONObject(i).optInt("provider_id")) : null;
                if (s == null || !installed(s[2])) {
                    if (name.isEmpty() || name.toLowerCase(Locale.ROOT).contains(" channel")) continue;
                    if (streaming && p.elsewhere == null) p.elsewhere = name;
                    if (!streaming && p.rentOn == null) p.rentOn = name;
                    continue;
                }
                if (p.app == null) {
                    p.app = s[1];
                    p.pkg = s[2];
                } else if (!s[1].equals(p.app)) {   // on another of the TV's services too: "Watch on" lets you pick
                    boolean known = false;
                    for (String[] a : p.alts) known |= a[0].equals(s[1]);
                    if (!known) p.alts.add(new String[] {s[1], s[2]});
                }
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        else if (p.elsewhere != null) {   // a title the TV can't play (search, the Others tab): where it is, and since when
            parts.add(p.elsewhere);
            if (p.streamingAt > 0 && p.streamingAt <= now) parts.add(Ui.trf("since {date}", "date", shortDate(p.streamingAt)));
        } else if (p.rentOn != null) {
            parts.add(Ui.trf("Rent on {service}", "service", p.rentOn));
        }
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /** What the info page says: synopsis, director or creators, the top 5 of the cast, every genre, the age rating. */
    static void about(Pick p, JSONObject o) {
        p.overview = o.optString("overview", "");
        JSONObject credits = o.optJSONObject("credits");
        List<String> names = new ArrayList<>();
        if (p.kind.equals("movie")) {
            JSONArray crew = credits == null ? null : credits.optJSONArray("crew");
            for (int i = 0; crew != null && i < crew.length(); i++) {
                JSONObject m = crew.optJSONObject(i);
                if (m != null && "Director".equals(m.optString("job")) && !names.contains(m.optString("name"))) names.add(m.optString("name"));
            }
        } else {
            JSONArray by = o.optJSONArray("created_by");
            for (int i = 0; by != null && i < by.length(); i++) names.add(by.optJSONObject(i).optString("name"));
        }
        p.director = String.join(", ", names);
        names.clear();
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        for (int i = 0; cast != null && i < Math.min(5, cast.length()); i++) names.add(cast.optJSONObject(i).optString("name"));
        p.cast = String.join(", ", names);
        names.clear();
        JSONArray genres = o.optJSONArray("genres");
        for (int i = 0; genres != null && i < genres.length(); i++) names.add(genre(genres.optJSONObject(i).optString("name")));
        p.genres = String.join(" · ", names);
        String date = o.optString(p.kind.equals("movie") ? "release_date" : "first_air_date");
        if (date.length() >= 4) {
            try {
                p.year = Integer.parseInt(date.substring(0, 4));
            } catch (NumberFormatException ignored) {
            }
        }
        p.certification = certification(p.kind, o);
    }

    /** "14 Nov 2026" in the TV's language, for the cards' lines. */
    private static String shortDate(long at) {
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Ui.locale()).format(new java.util.Date(at));
    }

    /**
     * A movie's theatrical (TMDB type 3; 2 is a limited one) and digital (type 4) release dates in your country, else
     * the US, else anywhere: the Others tab says "In cinemas …", "Streaming …" and "since …" from them.
     */
    private static void releaseDates(Pick p, JSONObject o) {
        JSONObject block = o.optJSONObject("release_dates");
        JSONArray results = block == null ? null : block.optJSONArray("results");
        int bestRank = 0;
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String country = r.optString("iso_3166_1");
            int rank = country.equals(REGION) ? 3 : country.equals("US") ? 2 : 1;
            if (rank < bestRank) continue;
            long cinemas = 0, digital = 0;
            JSONArray dates = r.optJSONArray("release_dates");
            for (int j = 0; dates != null && j < dates.length(); j++) {
                JSONObject d = dates.optJSONObject(j);
                if (d == null) continue;
                long at = date(d.optString("release_date", "").substring(0, Math.min(10, d.optString("release_date", "").length())));
                int type = d.optInt("type");
                if (at <= 0) continue;
                if (type == 3) cinemas = at;                           // the wide release
                else if (type == 2 && cinemas == 0) cinemas = at;      // a limited one, until a wide one shows
                else if (type == 4 && (digital == 0 || at < digital)) digital = at;
            }
            if (cinemas == 0 && digital == 0) continue;
            bestRank = rank;
            p.inCinemas = cinemas;
            p.streamingAt = digital;
        }
    }

    /** The age rating in your country (else the US one): a movie's release_dates, a series' content_ratings. */
    private static String certification(String kind, JSONObject o) {
        JSONObject block = o.optJSONObject(kind.equals("movie") ? "release_dates" : "content_ratings");
        JSONArray results = block == null ? null : block.optJSONArray("results");
        String home = "", us = "";
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String country = r.optString("iso_3166_1"), rating = "";
            if (kind.equals("movie")) {
                JSONArray dates = r.optJSONArray("release_dates");
                for (int j = 0; dates != null && j < dates.length() && rating.isEmpty(); j++) rating = dates.optJSONObject(j).optString("certification", "");
            } else {
                rating = r.optString("rating", "");
            }
            if (rating.isEmpty()) continue;
            if (country.equals(REGION)) home = rating;
            else if (country.equals("US")) us = rating;
        }
        return home.isEmpty() ? us : home;
    }

    /**
     * The Scorer's features of a title, the same the profile builder (tools/recs/features.py) makes: genres, original
     * language, decade, the top 5 of the cast, the director (movies) or creators (series), keywords, movie or series.
     */
    static void features(Pick p, JSONObject o) throws org.json.JSONException {
        List<String> f = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        for (int i = 0; genres != null && i < genres.length(); i++) f.add("g:" + genres.getJSONObject(i).optInt("id"));
        p.firstGenre = genres != null && genres.length() > 0 ? "g:" + genres.getJSONObject(0).optInt("id") : "";
        String lang = o.optString("original_language", p.lang);
        if (!lang.isEmpty()) {
            f.add("l:" + lang);
            p.lang = lang;
        }
        String date = o.optString(p.kind.equals("movie") ? "release_date" : "first_air_date");
        if (date.length() >= 4) {
            try {
                f.add("d:" + (Integer.parseInt(date.substring(0, 4)) / 10 * 10));
            } catch (NumberFormatException ignored) {
            }
            if (p.releasedAt == 0) p.releasedAt = date(date);
        }
        if (o.has("popularity")) p.popularity = o.optDouble("popularity", p.popularity);
        JSONObject credits = o.optJSONObject("credits");
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        for (int i = 0; cast != null && i < Math.min(5, cast.length()); i++) {
            String id = "c:" + cast.getJSONObject(i).optInt("id");
            f.add(id);
            if (i == 0) p.leadActor = id;
        }
        if (p.kind.equals("movie")) {
            JSONArray crew = credits == null ? null : credits.optJSONArray("crew");
            for (int i = 0; crew != null && i < crew.length(); i++) {
                if ("Director".equals(crew.getJSONObject(i).optString("job"))) f.add("dir:" + crew.getJSONObject(i).optInt("id"));
            }
        } else {
            JSONArray by = o.optJSONArray("created_by");
            for (int i = 0; by != null && i < by.length(); i++) f.add("dir:" + by.getJSONObject(i).optInt("id"));
        }
        JSONObject kw = o.optJSONObject("keywords");
        JSONArray words = kw == null ? null : kw.optJSONArray(p.kind.equals("movie") ? "keywords" : "results");
        for (int i = 0; words != null && i < words.length(); i++) f.add("k:" + words.getJSONObject(i).optInt("id"));
        f.add("kind:" + p.kind);
        p.features = f;
    }

    /** The Scorer's view of a pick. */
    Scorer.Item item(Pick p) {
        Scorer.Item it = new Scorer.Item();
        it.key = key(p);
        it.title = p.title;
        it.lang = p.lang;
        it.service = serviceName(p);
        it.features = p.features;
        it.firstGenre = p.firstGenre;
        it.leadActor = p.leadActor;
        it.popularity = p.popularity;
        it.releasedAt = p.releasedAt;
        return it;
    }

    private final Scorer.Words words = new Scorer.Words() {
        @Override public String because(String name) { return Ui.trf("Because you like {name}", "name", name); }
        @Override public String directedBy(String name) { return Ui.trf("Directed by {name}", "name", name); }
        @Override public String popularIn(String language) { return Ui.trf("Popular in {language}", "language", Ui.tr(language)); }
        @Override public String newOn(String service) { return service.isEmpty() ? Ui.tr("New this month") : Ui.trf("New this month on {service}", "service", service); }
        @Override public String watchlist() { return Ui.tr("On your watchlist"); }
    };

    /**
     * Ranks every tab's list with the profile (ProfileStore) and your feedback (Feedback): the Scorer's order, then
     * some variety (at most 2 of a genre and 1 of a lead actor in the top 2 × CARDS). Without a profile, nothing.
     */
    void rankAll() {
        Profile profile = ProfileStore.get(context);
        rankedStamp = profile.builtAt;
        if (profile.isEmpty()) {
            ranked.clear();
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, Double> nudges = Feedback.nudges(context, now);
        Set<String> hidden = Account.seen(context), watchlist = Account.watchlist(context);
        for (Tabs.Tab t : Tabs.ALL) {
            List<Pick> l = lists.get(t.id);
            if (l == null) continue;
            List<Scorer.Item> items = new ArrayList<>();
            Map<String, Pick> byKey = new HashMap<>();
            for (Pick p : l) {
                if (!p.detailed) continue;
                items.add(item(p));
                byKey.put(key(p), p);
            }
            List<Scorer.Scored> order = Scorer.diverse(Scorer.rank(items, profile, nudges, hidden, watchlist, now, words), 2 * CARDS, 2, 1);
            List<Pick> out = new ArrayList<>();
            for (Scorer.Scored sc : order) {
                Pick p = byKey.get(sc.item.key);
                p.reason = sc.reason;
                out.add(p);
            }
            ranked.put(t.id, out);
        }
        notifyListeners();
    }

    /** After a verdict on the TV: ranks again (in the background) so the change shows at once. */
    void rerank() {
        io.execute(this::rankAll);
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        String lang = Ui.language.replaceAll("-.*", "");   // TMDB's original_language has no country: "zh", not "zh-CN"
        if (lang.equals("en") || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || lang.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in other languages (pt-BR, es, de…). */
    private static String genre(String name) {
        switch (name) {
            case "Action & Adventure": return Ui.tr("genre|Action & Adventure");
            case "Sci-Fi & Fantasy": return Ui.tr("genre|Sci-Fi & Fantasy");
            case "War & Politics": return Ui.tr("genre|War & Politics");
            case "Kids": return Ui.tr("genre|Kids");
            case "News": return Ui.tr("genre|News");
            case "Soap": return Ui.tr("genre|Soap");
            case "Talk": return Ui.tr("genre|Talk");
            default: return name;
        }
    }

    /** The trailer's YouTube key: an official Trailer first, then any Trailer, then a Teaser; null without one. */
    static String trailer(JSONObject videos) {
        JSONArray list = videos == null ? null : videos.optJSONArray("results");
        String best = null;
        int bestRank = 0;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject v = list.optJSONObject(i);
            if (v == null || !"YouTube".equals(v.optString("site")) || v.optString("key").isEmpty()) continue;
            String type = v.optString("type");
            int rank = type.equals("Trailer") ? (v.optBoolean("official") ? 3 : 2) : type.equals("Teaser") ? 1 : 0;
            if (rank > bestRank) {
                bestRank = rank;
                best = v.optString("key");
            }
        }
        return best;
    }

    /**
     * The title's backdrop: TMDB's own choice (`backdrop_path`, the one its members voted up, usually the key art) when
     * no text is written over it; otherwise the best-voted one without text (our own text goes on top of it), at least
     * 1280 wide when there is one. Null: nothing without text, keep TMDB's choice. The width never outranks the votes:
     * "closest to 1920" swapped a 4K key art with 6 votes for an unvoted 1920 frame (Retro, and most Tamil titles).
     */
    private static String bestBackdrop(JSONObject images, String primary) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            String path = b == null ? "" : b.optString("file_path", "");
            if (path.isEmpty() || !b.isNull("iso_639_1")) continue;
            if (path.equals(primary)) return primary;
            double score = (b.optInt("width") >= 1280 ? 1e4 : 0) + b.optDouble("vote_average", 0) * 100 + Math.min(b.optInt("vote_count"), 99);
            if (score > bestScore) {
                bestScore = score;
                best = path;
            }
        }
        return best;
    }

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private String[] service(String name, int providerId) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        // "HBO Max Amazon Channel", "Apple TV Amazon Channel"...: a subscription sold inside another app, not this one.
        if (n.contains(" channel")) return null;
        for (String[] s : SERVICES) {
            // Apple TV: only the streaming service (TMDB provider 350); provider 2 is the Apple TV Store (rent or buy).
            if (s[1].equals("appletv") && providerId != 350) continue;
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    /** Services named in hide_services (config.properties), trimmed and lowercased: never a source of picks. */
    private static final Set<String> HIDDEN = new HashSet<>();
    static {
        for (String s : Config.HIDE_SERVICES.split(",")) {
            String key = s.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) HIDDEN.add(key);
        }
    }

    /** The service's app is on the TV, and the service isn't hidden (an app the TV ships without a subscription, say). */
    /** Whether the app is on the TV, asked of the package manager at most every 30 s (now() asks for every tab on each tick). */
    private boolean installed(String pkg) {
        if (!HIDDEN.isEmpty()) for (String[] s : SERVICES) if (s[2].equals(pkg) && HIDDEN.contains(s[1])) return false;
        long now = android.os.SystemClock.uptimeMillis();
        long[] known = installedCache.get(pkg);
        if (known != null && now - known[0] < 30_000) return known[1] != 0;
        boolean on = HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
        installedCache.put(pkg, new long[] {now, on ? 1 : 0});
        return on;
    }
    private final Map<String, long[]> installedCache = new ConcurrentHashMap<>();   // package → {when asked, 1 = installed}

    private long sourceStamp() {
        long stamp = (Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode()) * 31L + (Config.HIDE_ANIMATION ? 1 : 0);
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        return stamp;
    }

    // MARK: - Links (the title's own page in its app): JustWatch first (TMDB's own "where to watch" source, which also
    // gives each offer's Android TV intent: hotstar://content/…, sunnxt://deeplink/…, Netflix with source=30), then
    // Watchmode with your key (its web addresses, which most apps take). Looked up once per title and saved.

    private static String linkKey(Pick p, String app) {
        return "lk:" + p.kind + p.id + app;   // lk: since JustWatch (wm, wm2: Watchmode alone)
    }

    /** JustWatch's ratings of a title, saved with its links ("" when JustWatch doesn't know it). */
    private static String scoreKey(Pick p) {
        return "sc:" + p.kind + p.id;
    }

    /** The title's services on the TV: the first one, then the alternatives. */
    private static List<String> servicesOf(Pick p) {
        List<String> out = new ArrayList<>();
        out.add(p.app);
        for (String[] a : p.alts) out.add(a[0]);
        return out;
    }

    private boolean needsLink(Pick p) {
        if (p.pkg == null || System.currentTimeMillis() < linkRetryAt) return false;
        if (cache.getString(scoreKey(p), null) == null) return true;
        for (String app : servicesOf(p)) if (cache.getString(linkKey(p, app), null) == null) return true;
        return false;
    }

    /** Puts the saved ratings on the pick ("sc:" entry: "imdb,votes,tomato,fresh,jw"). True if there was an entry. */
    private boolean loadScores(Pick p) {
        String saved = cache.getString(scoreKey(p), null);
        if (saved == null) return false;
        String[] f = saved.split(",");
        if (f.length == 5) {
            try {
                p.setRatings(Double.parseDouble(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3].equals("1"), Integer.parseInt(f[4]));
            } catch (NumberFormatException ignored) {
            }
        }
        return true;
    }

    private static String scoresText(Pick p) {
        return p.imdb + "," + p.imdbVotes + "," + p.tomato + "," + (p.certifiedFresh ? "1" : "0") + "," + p.jw;
    }

    /** Watchmode: a key is set and it hasn't refused it (a refused key isn't tried again until it changes). */
    private boolean watchmodeOn() {
        String key = Config.WATCHMODE_KEY.trim();
        return !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode();
    }

    private static void setLink(Pick p, String app, String link) {
        if (app.equals(p.app)) p.link = link.isEmpty() ? null : link;
        else p.altLinks.put(app, link);
    }

    /** Sets the links of the title's services from what was looked up before, or looks them up (JustWatch, then Watchmode). */
    private void findLink(Pick p) {
        if (p.pkg == null) return;
        List<String> apps = servicesOf(p);
        boolean missing = !loadScores(p);
        for (String app : apps) {
            String saved = cache.getString(linkKey(p, app), null);
            if (saved != null) setLink(p, app, saved); else missing = true;
        }
        if (!missing || System.currentTimeMillis() < linkRetryAt) return;
        Map<String, String> links;
        String from = "JustWatch";
        try {
            links = justwatchLinks(p, apps);
        } catch (Exception e) {
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;   // network, or the API changed: not again right away
            AppLog.w("justwatch: " + e.getMessage());
            return;
        }
        if (!links.containsKey(p.app) && watchmodeOn()) {
            try {
                for (Map.Entry<String, String> entry : watchmodeLinks(p, apps).entrySet()) {
                    if (!links.containsKey(entry.getKey())) {
                        links.put(entry.getKey(), entry.getValue());
                        from = "Watchmode";
                    }
                }
            } catch (Exception e) {
                if ("Watchmode returned HTTP 401".equals(e.getMessage())) {
                    cache.edit().putInt("wm:refused", Config.WATCHMODE_KEY.trim().hashCode()).apply();
                }
                linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;   // quota or network: not again right away
                AppLog.w("watchmode: " + e.getMessage());
                return;
            }
        }
        SharedPreferences.Editor e = cache.edit().putString(scoreKey(p), scoresText(p));
        StringBuilder log = new StringBuilder("link “" + p.title + "”:");
        for (String app : apps) {
            String link = links.containsKey(app) ? links.get(app) : "";
            e.putString(linkKey(p, app), link);
            setLink(p, app, link);
            log.append(' ').append(SERVICE_NAME.get(app)).append(' ').append(link.isEmpty() ? "none" : describe(link) + " (" + from + ")");
        }
        e.apply();
        AppLog.w(log.toString());
    }

    /** For the log: the scheme of an Android intent ("hotstar://"), or the host of a web address. */
    private static String describe(String link) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(";scheme=([a-z0-9.+-]+)").matcher(link);
        if (link.startsWith("intent:") && m.find()) return m.group(1) + "://";
        return link.replaceAll("^https?://([^/]+).*$", "$1");
    }

    /** Which of the title's services an offer's or source's name is ("Amazon Prime Video" → "prime"), or null. */
    private static String serviceOf(String name, List<String> apps) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains(" channel")) return null;   // a subscription sold inside another app
        n = n.replace(" ", "");
        for (String[] s : SERVICES) {
            if (!apps.contains(s[1])) continue;
            String want = s[0].replace(" ", "");
            if (want.equals("max") ? n.equals("max") : n.contains(want)) return s[1];
        }
        return null;
    }

    private static final String JUSTWATCH = "https://apis.justwatch.com/graphql";
    private static final String JW_QUERY = "query Search($country: Country!, $language: Language!, $q: String!) {"
            + " popularTitles(country: $country, first: 8, filter: {searchQuery: $q}) { edges { node { ... on MovieOrShow {"
            + " objectType content(country: $country, language: $language) { title originalReleaseYear externalIds { tmdbId }"
            + " scoring { imdbScore imdbVotes tomatoMeter certifiedFresh jwRating } }"
            + " offers(country: $country, platform: WEB) { monetizationType standardWebURL deeplinkURL(platform: ANDROID_TV)"
            + " package { clearName } } } } } } }";

    /**
     * JustWatch: the title found by name and matched by TMDB id (or by name and year); its offers on the title's services
     * (subscription, ads or free): the Android TV intent when there is one, else the web address. Service → link.
     */
    private Map<String, String> justwatchLinks(Pick p, List<String> apps) throws IOException, org.json.JSONException {
        Map<String, String> out = new HashMap<>();
        JSONObject body = new JSONObject().put("query", JW_QUERY)
                .put("variables", new JSONObject().put("country", REGION).put("language", "en").put("q", p.title));
        JSONObject res = post(JUSTWATCH, body.toString());
        if (res.has("errors")) throw new IOException("JustWatch: " + res.getJSONArray("errors").optJSONObject(0).optString("message"));
        JSONObject data = res.optJSONObject("data");
        JSONObject titles = data == null ? null : data.optJSONObject("popularTitles");
        JSONArray edges = titles == null ? null : titles.optJSONArray("edges");
        String wantKind = p.kind.equals("movie") ? "MOVIE" : "SHOW";
        Calendar cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTimeInMillis(p.releasedAt);
        int year = p.releasedAt == 0 ? 0 : cal.get(Calendar.YEAR);
        JSONObject match = null, byName = null;
        for (int i = 0; edges != null && i < edges.length(); i++) {
            JSONObject node = edges.getJSONObject(i).optJSONObject("node");
            if (node == null || !wantKind.equals(node.optString("objectType"))) continue;
            JSONObject content = node.optJSONObject("content");
            JSONObject ids = content == null ? null : content.optJSONObject("externalIds");
            if (ids != null && String.valueOf(ids.opt("tmdbId")).equals(String.valueOf(p.id))) {
                match = node;
                break;
            }
            if (byName == null && content != null && content.optString("title").equalsIgnoreCase(p.title)
                    && (year == 0 || Math.abs(content.optInt("originalReleaseYear") - year) <= 1)) byName = node;
        }
        if (match == null) match = byName;
        if (match == null) {
            AppLog.w("justwatch: no match for “" + p.title + "” among " + (edges == null ? 0 : edges.length()));
            return out;
        }
        JSONObject content = match.optJSONObject("content");
        JSONObject sc = content == null ? null : content.optJSONObject("scoring");
        if (sc != null) {
            p.setRatings(sc.optDouble("imdbScore", 0), sc.optInt("imdbVotes", 0), sc.optInt("tomatoMeter", 0),
                    sc.optBoolean("certifiedFresh", false), (int) Math.round(sc.optDouble("jwRating", 0) * 100));
        }
        JSONArray offers = match.optJSONArray("offers");
        for (int j = 0; offers != null && j < offers.length(); j++) {
            JSONObject o = offers.getJSONObject(j);
            String type = o.optString("monetizationType");
            if (!type.equals("FLATRATE") && !type.equals("ADS") && !type.equals("FREE")) continue;
            JSONObject pack = o.optJSONObject("package");
            String app = serviceOf(pack == null ? "" : pack.optString("clearName"), apps);
            if (app == null) continue;
            String intent = o.optString("deeplinkURL"), url = o.optString("standardWebURL"), have = out.get(app);
            if (intent.startsWith("intent:")) out.put(app, intent);   // the intent beats a web address
            else if ((have == null || have.isEmpty()) && url.startsWith("https://")) out.put(app, url);
        }
        return out;
    }

    private static JSONObject post(String url, String json) throws IOException, org.json.JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "FreeMyTV/1.0 (Android TV)");
        try {
            c.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("JustWatch returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    /** Watchmode (2 requests of the free plan's 2,500 a month): the title's page on each of its services. */
    private Map<String, String> watchmodeLinks(Pick p, List<String> apps) throws Exception {
        Map<String, String> out = new HashMap<>();
        JSONArray found = new JSONObject(watchmode("/search/?search_field=tmdb_" + p.kind + "_id&search_value=" + p.id))
                .optJSONArray("title_results");
        if (found == null || found.length() == 0) return out;
        JSONArray sources = new JSONArray(watchmode("/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + REGION));
        for (int i = 0; i < sources.length(); i++) {
            JSONObject src = sources.getJSONObject(i);
            String app = serviceOf(src.optString("name"), apps);
            // "sub" is the subscription; "free" is the ad-supported tier of the same app (JioHotstar lists many titles so).
            String type = src.optString("type");
            if (app != null && !out.containsKey(app) && (type.equals("sub") || type.equals("free"))
                    && src.optString("web_url").startsWith("https://")) out.put(app, src.optString("web_url"));
        }
        return out;
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(Config.WATCHMODE_KEY.trim(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Watchmode returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** A TMDB call for the other parts of the app (Continue Watching looks a title up by name). */
    JSONObject api(String path) throws IOException, org.json.JSONException {
        return get(path);
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        // Some evenings this home line's TMDB connections are reset halfway: one more try before giving up.
        try {
            return getOnce(path);
        } catch (java.net.SocketException | javax.net.ssl.SSLException e) {
            return getOnce(path);
        }
    }

    private JSONObject getOnce(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Config.TMDB_KEY.trim();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, enough for every tab's whole list; this hour's kept ready in memory)

    /** Every tab's 24 and some room: about 200 KB each at w1280 (a 1080p TV), so the screensaver's rounds don't download again. */
    private static final int IMAGES_ON_DISK = Tabs.ALL.length * 24 + 8;

    /** Synchronized: the fetch job (io) and an image build (imageIo) may want the same file; the second finds it there. */
    private synchronized void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + p.backdrop).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            if (c.getResponseCode() != 200) throw new IOException("image HTTP " + c.getResponseCode());   // an error page isn't a picture
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
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the photo (≥ the UI's width) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            long started = System.nanoTime();
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight + " → " + Backdrop.W + "×" + Backdrop.H
                    + " in " + (System.nanoTime() - started) / 1_000_000 + " ms" + (backdrop.wide != null && backdrop.wide.getConfig() == Bitmap.Config.HARDWARE ? ", cards on the GPU" : ""));
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                // Every tab's cards of this hour stay ready (switching tabs or coming back home draws them at once);
                // the full-photo design holds this tab's and the last tab's.
                int keep = forLight ? Math.max(12, Tabs.enabled().size() * CARDS) : 6;
                while (images.size() > keep) retire(images.remove(images.keySet().iterator().next()));
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            failed.put("i:" + p.backdrop, System.currentTimeMillis());
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    void trim() {
        // The tab on screen keeps its cards (the first tab's when none was told), so they are not gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        Tabs.Tab tab = current;
        for (Pick p : tab == null ? now() : now(tab)) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            for (java.util.Iterator<Map.Entry<String, Backdrop>> it = images.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Backdrop> e = it.next();
                if (keep.contains(e.getKey())) continue;
                retire(e.getValue());
                it.remove();
            }
        }
    }

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= IMAGES_ON_DISK) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - IMAGES_ON_DISK; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - Search (the Search page): TMDB's multi search, then each result's details and links, as the picks get them

    /** A result's small picture (16:9, the All Apps card's size) and its shader for the rounded corners. */
    static final class Thumb {
        final Bitmap bitmap;
        final android.graphics.BitmapShader shader;

        Thumb(Bitmap b) {
            bitmap = b;
            shader = new android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
        }
    }

    private static final int SEARCH_MAX = 15, THUMBS = 24;
    /** Their own threads: a search mustn't wait behind five tabs' details, and the pictures load alongside the details. */
    private final ExecutorService searchIo = Executors.newSingleThreadExecutor(), thumbIo = Executors.newSingleThreadExecutor();
    private final List<Pick> results = new ArrayList<>();   // guarded by itself
    private final Map<String, Thumb> thumbs = new LinkedHashMap<String, Thumb>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Thumb> eldest) { return size() > THUMBS; }
    };
    private final Set<String> thumbPending = new HashSet<>();   // guarded by thumbs
    private volatile int searchSeq;
    private volatile boolean searching;
    private volatile String searchQuery = "", searchError = "";

    /**
     * Looks the name up (movies and series, in the TV's language), shows what TMDB found at once, then fills in each
     * result's details (the app it is on, genre, runtime, score, trailer) and link; the ones on the TV's apps end up
     * first. Listeners hear about each step. A new search cancels the previous one's remaining steps.
     */
    void search(String query) {
        final String q = query.trim();
        final int seq = ++searchSeq;
        searchQuery = q;
        searchError = "";
        if (q.isEmpty()) {
            synchronized (results) { results.clear(); }
            searching = false;
            notifyListeners();
            return;
        }
        searching = true;
        searchIo.execute(() -> {
            try {
                JSONArray a = get("/search/multi?query=" + URLEncoder.encode(q, "UTF-8") + "&language=" + lang()
                        + "&include_adult=false").getJSONArray("results");
                List<Pick> found = new ArrayList<>();
                for (int i = 0; i < a.length() && found.size() < SEARCH_MAX; i++) {
                    JSONObject o = a.getJSONObject(i);
                    String kind = o.optString("media_type");
                    if (!kind.equals("movie") && !kind.equals("tv")) continue;
                    if (Config.HIDE_ANIMATION && animated(o)) continue;
                    Pick p = new Pick();
                    p.id = o.optInt("id");
                    p.kind = kind;
                    p.title = o.optString(kind.equals("movie") ? "title" : "name");
                    String backdrop = o.optString("backdrop_path", ""), poster = o.optString("poster_path", "");
                    p.backdrop = backdrop.isEmpty() || "null".equals(backdrop) ? null : backdrop;
                    p.poster = poster.isEmpty() || "null".equals(poster) ? null : poster;
                    p.lang = o.optString("original_language", "");
                    p.popularity = o.optDouble("popularity", 0);
                    p.setScore(o.optDouble("vote_average", 0));
                    p.releasedAt = date(o.optString(kind.equals("movie") ? "release_date" : "first_air_date"));
                    found.add(p);
                }
                if (seq != searchSeq) return;
                synchronized (results) {
                    results.clear();
                    results.addAll(found);
                }
                notifyListeners();
                for (Pick p : found) {
                    if (seq != searchSeq) return;
                    try {
                        fetchDetails(p);
                        p.verdict = Account.verdict(context, key(p));
                        if (p.pkg != null) findLink(p);
                    } catch (Exception e) {
                        AppLog.w("search details “" + p.title + "”: " + e.getMessage());
                    }
                    if (seq != searchSeq) return;
                    // The ones the TV can play first, TMDB's order kept among them.
                    List<Pick> order = new ArrayList<>();
                    for (Pick x : found) if (x.pkg != null) order.add(x);
                    for (Pick x : found) if (x.pkg == null) order.add(x);
                    synchronized (results) {
                        results.clear();
                        results.addAll(order);
                    }
                    notifyListeners();
                }
                AppLog.w("search “" + q + "”: " + found.size() + " found");
            } catch (Exception e) {
                if (seq == searchSeq) searchError = e.getMessage() == null ? e.toString() : e.getMessage();
                AppLog.w("search: " + e.getMessage());
            } finally {
                if (seq == searchSeq) {
                    searching = false;
                    notifyListeners();
                }
            }
        });
    }

    /** The results so far (a copy), the ones the TV can play first. */
    List<Pick> results() {
        synchronized (results) { return new ArrayList<>(results); }
    }

    String searchQuery() { return searchQuery; }
    boolean searching() { return searching; }
    String searchError() { return searchError; }

    /** A result's picture if it is ready (else null: it loads in the background and the listeners hear). */
    Thumb thumb(Pick p) {
        if (p == null || (p.backdrop == null && p.poster == null)) return null;
        final String key = p.backdrop != null ? p.backdrop : p.poster;   // a poster's middle band, when there is no backdrop
        synchronized (thumbs) {
            Thumb t = thumbs.get(key);
            if (t != null) return t;
            if (failedLately("t:" + key, IMAGE_WAIT) || !thumbPending.add(key)) return null;
        }
        thumbIo.execute(() -> {
            try {
                // TMDB's 500-wide copy, brought down to the card's own size (384 × 216; 256 × 144 on a 720p UI).
                HttpURLConnection c = (HttpURLConnection) new URL("https://image.tmdb.org/t/p/w500" + key).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                Bitmap b;
                try (InputStream in = c.getInputStream()) {
                    b = BitmapFactory.decodeStream(in);
                } finally {
                    c.disconnect();
                }
                if (b == null) throw new IOException("unreadable image");
                int tw = Backdrop.W <= 1280 ? 256 : HomeApps.TILE_W, th = tw * 9 / 16;
                Thumb t = new Thumb(Ui.downscale(Backdrop.center(b, 16 / 9f), tw, th));
                synchronized (thumbs) { thumbs.put(key, t); }
                notifyListeners();
            } catch (IOException | RuntimeException e) {
                failed.put("t:" + key, System.currentTimeMillis());
                AppLog.w("search image: " + e.getMessage());
            } finally {
                synchronized (thumbs) { thumbPending.remove(key); }
            }
        });
        return null;
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONObject all = new JSONObject();
            for (Map.Entry<String, List<Pick>> entry : lists.entrySet()) {
                JSONArray a = new JSONArray();
                for (Pick p : entry.getValue()) {
                    a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop).put("link", p.link == null ? "" : p.link)
                            .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                            .put("detailed", p.detailed).put("lang", p.lang).put("features", new JSONArray(p.features))
                            .put("genre", p.firstGenre).put("actor", p.leadActor).put("pop", p.popularity).put("released", p.releasedAt)
                            .put("score", p.score).put("trailer", p.trailer == null ? "" : p.trailer).put("imdb", p.imdbId == null ? "" : p.imdbId).put("alts", alts(p))
                            .put("ratings", scoresText(p)).put("overview", p.overview).put("director", p.director)
                            .put("cast", p.cast).put("genres", p.genres).put("cert", p.certification).put("year", p.year).put("length", p.length).put("win", p.window)
                            .put("other", p.other).put("where", p.elsewhere == null ? "" : p.elsewhere).put("rent", p.rentOn == null ? "" : p.rentOn).put("cin", p.inCinemas).put("dig", p.streamingAt));
                }
                all.put(entry.getKey(), a);
            }
            SharedPreferences.Editor e = cache.edit().putString("lists", all.toString()).putInt("period", period)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION);
            for (Map.Entry<String, Long> entry : listAts.entrySet()) e.putLong("listAt:" + entry.getKey(), entry.getValue());
            // Watchmode links of titles no longer in the list go (the refused-key mark stays).
            Set<String> keep = new HashSet<>();
            for (Pick p : all()) {
                keep.add(scoreKey(p));
                for (String app : servicesOf(p)) keep.add(linkKey(p, app));
            }
            for (String k : cache.getAll().keySet()) if ((k.startsWith("wm:") || k.startsWith("wm2:") || k.startsWith("lk:") || k.startsWith("sc:")) && !k.equals("wm:refused") && !keep.contains(k)) e.remove(k);
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static JSONArray alts(Pick p) {
        JSONArray a = new JSONArray();
        for (String[] alt : p.alts) a.put(new JSONArray().put(alt[0]).put(alt[1]));
        return a;
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop").isEmpty() ? null : o.optString("backdrop");   // "" would name the cache folder itself
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            p.lang = o.optString("lang");
            JSONArray fs = o.optJSONArray("features");
            for (int j = 0; fs != null && j < fs.length(); j++) p.features.add(fs.optString(j));
            p.firstGenre = o.optString("genre");
            p.leadActor = o.optString("actor");
            p.popularity = o.optDouble("pop", 0);
            p.setScore(o.optDouble("score", 0));
            p.trailer = o.optString("trailer").isEmpty() ? null : o.optString("trailer");
            p.imdbId = o.optString("imdb").isEmpty() ? null : o.optString("imdb");
            JSONArray alts = o.optJSONArray("alts");
            for (int j = 0; alts != null && j < alts.length(); j++) {
                JSONArray alt = alts.optJSONArray(j);
                if (alt != null && alt.length() == 2) p.alts.add(new String[] {alt.optString(0), alt.optString(1)});
            }
            p.releasedAt = o.optLong("released", 0);
            p.overview = o.optString("overview");
            p.director = o.optString("director");
            p.cast = o.optString("cast");
            p.genres = o.optString("genres");
            p.certification = o.optString("cert");
            p.year = o.optInt("year", 0);
            p.length = o.optString("length");
            p.window = o.optString("win");
            p.other = o.optBoolean("other");
            p.elsewhere = o.optString("where").isEmpty() ? null : o.optString("where");
            p.rentOn = o.optString("rent").isEmpty() ? null : o.optString("rent");
            p.inCinemas = o.optLong("cin", 0);
            p.streamingAt = o.optLong("dig", 0);
            String[] f = o.optString("ratings").split(",");
            if (f.length == 5) {
                try {
                    p.setRatings(Double.parseDouble(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3].equals("1"), Integer.parseInt(f[4]));
                } catch (NumberFormatException ignored) {
                }
            }
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
