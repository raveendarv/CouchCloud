package io.freemytv;

import java.util.ArrayList;
import java.util.List;

/**
 * The home screen's tabs: each is a family of TMDB queries (original languages, movies and/or series). Which ones show,
 * and in what order, comes from Home Settings on the TV (Prefs "tabs": ids, comma separated) over config.properties
 * "tabs". The ids are what Recs keys its lists by and what the recommendation profile keys its pools by.
 */
final class Tabs {
    static final class Tab {
        final String id, label, kind;   // kind: null = movies and series, "movie", "tv"
        final String[] languages;       // TMDB original_language codes
        final boolean pan;              // several languages with a quota, above the pan_india thresholds
        final boolean trending;         // TMDB's trending or top rated titles (hold OK picks the list and its window), any language
        final boolean other;            // upcoming movies, per language (Recs.fetchOther; hold OK picks the language)

        Tab(String id, String label, String kind, boolean pan, boolean trending, boolean other, String... languages) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.pan = pan;
            this.trending = trending;
            this.other = other;
            this.languages = languages;
        }

        String[] kinds() {
            return kind == null ? new String[] {"movie", "tv"} : new String[] {kind};
        }
    }

    /** Trending, then by language (movies and series; hold OK on the tab filters: Recs.tabKind), then what the TV's apps lack. */
    static final Tab[] ALL = {
        new Tab("trending", "Trending", null, false, true, false),
        new Tab("ta", "Tamil", null, false, false, false, "ta"),
        new Tab("en-movie", "English", null, false, false, false, "en"),
        new Tab("pan", "Pan-India", "movie", true, false, false, "ml", "te", "hi", "kn"),
        new Tab("other", "Upcoming", "movie", false, false, true, "en", "ta", "ml", "te", "hi", "kn"),
    };

    private Tabs() {}

    static Tab byId(String id) {
        for (Tab t : ALL) if (t.id.equals(id)) return t;
        return null;
    }

    /** The ids in `csv`, trimmed, known, without repeats (ids of tabs that are gone, "series" and "en-tv", are dropped). */
    static List<String> ids(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String id = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (id.equals("ml")) id = "pan";   // Malayalam joined the Pan-India tab (its language filter)
            if (byId(id) != null && !out.contains(id)) out.add(id);
        }
        return out;
    }

    /** The tabs on the home screen, in order; never empty (the first built-in tab when the setting names none). */
    static List<Tab> enabled() {
        List<Tab> out = new ArrayList<>();
        for (String id : ids(Prefs.tabs())) out.add(byId(id));
        if (out.isEmpty()) out.add(ALL[0]);
        return out;
    }
}
