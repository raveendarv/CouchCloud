package io.freemytv;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What to ask TMDB for a title an app plays, when the app hands no picture (JioHotstar, Stremio's sessions): the name
 * of a series or film, not of its episode. Plain Java, so tests/java/TitlesTest.java can run it.
 */
final class Titles {
    private Titles() {}

    // No "[:" inside a class: Android's ICU regex reads it as a Unicode property ("[:alpha:]") and the pattern fails to compile.
    private static final Pattern EPISODE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])(S\\d{1,2}\\s*[ .:]?\\s*E\\d{1,3}|Season\\s*\\d+[,:]?\\s*Episode\\s*\\d+|Episode\\s*\\d+|Ep\\.?\\s*\\d+)(?![A-Za-z0-9])");
    private static final Pattern EDGES = Pattern.compile("^[\\s:.,\\-–—|]+|[\\s:.,\\-–—|]+$");
    private static final Pattern SEPARATOR = Pattern.compile("\\s+[-–—|]\\s+|:\\s+");   // a dash needs spaces around it (Spider-Man); a colon only after
    /** JioHotstar's web addresses: /in/tv/<show>/<id>/<episode>/<id>, /in/movies/<film>/<id>, /in/shows/<show>/<id>. */
    private static final Pattern HOTSTAR = Pattern.compile("(?i)https?://(?:www\\.)?hotstar\\.com/(?:[a-z]{2}/)?(?:tv|shows|movies|sports|specials|series)/([^/?#]+)/\\d+");
    /**
     * Where JioHotstar's episodes stream from: .../videos/hotstarint/<episode slug>/<show id>/<episode id>/v3/..., or a show's web
     * address .../shows/<slug>/<show id>. A film's stream has one id only (.../videos/hotstarint/v3/<film id>/...).
     */
    private static final Pattern HOTSTAR_SHOW_ID = Pattern.compile("(?i)https?://[^/]*hotstar\\.com/(?:videos/[^/]+/[^/]+/(\\d{6,})/\\d{6,}/|(?:[a-z]{2}/)?(?:tv|shows|series)/[^/]+/(\\d{6,}))");

    /** The title without its episode marks: "S1 E2 The Long Night" → "The Long Night"; "" when it was only marks. */
    static String withoutEpisode(String title) {
        if (title == null) return "";
        String s = EPISODE.matcher(title).replaceAll(" ");
        s = EDGES.matcher(s).replaceAll("");
        return s.replaceAll("\\s{2,}", " ").trim();
    }

    /** The series' name in a JioHotstar web address ("bigg-boss-tamil" → "bigg boss tamil"), or null. */
    static String hotstarShow(String uri) {
        if (uri == null) return null;
        Matcher m = HOTSTAR.matcher(uri);
        if (!m.find()) return null;
        String slug = m.group(1).replace('-', ' ').replace('+', ' ').trim();
        if (slug.isEmpty() || slug.matches("\\d+")) return null;
        try {
            slug = java.net.URLDecoder.decode(slug, "UTF-8");
        } catch (Exception ignored) {
        }
        return slug;
    }

    /** The show an episode belongs to, as JustWatch links it for the Android TV app ("hotstar://content/<show id>"), or null. */
    static String hotstarShowLink(String uri) {
        if (uri == null) return null;
        Matcher m = HOTSTAR_SHOW_ID.matcher(uri);
        if (!m.find()) return null;
        String id = m.group(1) != null ? m.group(1) : m.group(2);
        return "hotstar://content/" + id;
    }

    /**
     * What to search, in order: the show the app named apart from the episode; the title without episode marks; its
     * first part before a dash or colon ("Bigg Boss Tamil - Day 22", "The Office: Diversity Day"); the title as it is.
     * No duplicates (case aside), nothing shorter than 2 characters.
     */
    static List<String> queries(String show, String title) {
        List<String> out = new ArrayList<>();
        add(out, show);
        String bare = withoutEpisode(title);
        add(out, bare);
        String[] parts = SEPARATOR.split(bare);
        if (parts.length > 1) add(out, EDGES.matcher(parts[0]).replaceAll(""));
        add(out, title);
        return out;
    }

    private static void add(List<String> out, String q) {
        if (q == null) return;
        q = q.trim();
        if (q.length() < 2) return;
        for (String x : out) if (x.equalsIgnoreCase(q)) return;
        out.add(q);
    }

    /** One line for the lookups file: tells a lookup made with other words (the title or show changed) from this one. */
    static String signature(String show, String title) {
        return String.join("\n", queries(show, title)).toLowerCase(Locale.ROOT);
    }
}
