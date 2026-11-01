package io.freemytv;

import java.util.Arrays;
import java.util.List;

/** Run by tools/test.sh. */
public class TitlesTest {
    private static int failures;

    private static void eq(Object want, Object got, String what) {
        if (want == null ? got != null : !want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": wanted " + want + ", got " + got);
        }
    }

    public static void main(String[] args) {
        eq("The Long Night", Titles.withoutEpisode("S1 E2 The Long Night"), "leading S1 E2");
        eq("The Long Night", Titles.withoutEpisode("The Long Night - S01E02"), "trailing S01E02");
        eq("Bigg Boss Tamil", Titles.withoutEpisode("Bigg Boss Tamil: Episode 22"), "Episode n");
        eq("Bigg Boss Tamil", Titles.withoutEpisode("Bigg Boss Tamil Ep. 22"), "Ep. n");
        eq("", Titles.withoutEpisode("Episode 5"), "only the mark");
        eq("Jailer", Titles.withoutEpisode("Jailer"), "a film");
        eq("Sesame Street", Titles.withoutEpisode("Sesame Street"), "an S inside a word stays");
        eq("Steps 2", Titles.withoutEpisode("Steps 2"), "a word ending in s then a number is no episode");

        eq("bigg boss tamil", Titles.hotstarShow("https://www.hotstar.com/in/tv/bigg-boss-tamil/13485/day-22/1271345678"), "tv address");
        eq("jailer", Titles.hotstarShow("https://www.hotstar.com/in/movies/jailer/1271155444?utm=x"), "movie address");
        eq("the office", Titles.hotstarShow("https://hotstar.com/shows/the-office/1260000001"), "no country");
        eq(null, Titles.hotstarShow("hotstar://content/1271155444"), "the app's own scheme has no name");
        eq(null, Titles.hotstarShow("https://www.hotstar.com/in/1271155444"), "an id alone");
        eq(null, Titles.hotstarShow(null), "null");

        eq("hotstar://content/1260168231", Titles.hotstarShowLink("https://hses9.hotstar.com/videos/hotstarint/standingtogether/1260168231/1271668637/v3/1785727033353/c811af9e.m3u8"), "an episode's stream names its show");
        eq(null, Titles.hotstarShowLink("https://hses9-vod-jiol.cdn.hotstar.com/gsvod1/movies-prod-origin1/videos/hotstarint/v3/1271506399/1792304141689/52c00cab.m3u8"), "a film's stream has no show");
        eq("hotstar://content/1260000001", Titles.hotstarShowLink("https://www.hotstar.com/in/shows/bigg-boss-tamil/1260000001/day-22/1271345678"), "a show's web address");

        List<String> q = Titles.queries("bigg boss tamil", "Day 22 - Bigg Boss Tamil");
        eq(Arrays.asList("bigg boss tamil", "Day 22 - Bigg Boss Tamil", "Day 22"), q, "show first, then the title, then its first part; no duplicate of the show");
        eq(Arrays.asList("Game of Thrones: The Long Night", "Game of Thrones", "Game of Thrones: S1 E2 The Long Night"), Titles.queries(null, "Game of Thrones: S1 E2 The Long Night"), "bare title, its part before the colon, the title as it is");
        eq(Arrays.asList("Spider-Man: No Way Home", "Spider-Man"), Titles.queries(null, "Spider-Man: No Way Home"), "a hyphen inside a name is no separator");
        eq(Arrays.asList("Jailer"), Titles.queries(null, "Jailer"), "a plain film: one query");
        eq(Arrays.asList("Episode 5"), Titles.queries(null, "Episode 5"), "only a mark: the title as it is, nothing empty");
        eq(Titles.signature("X", "Y"), Titles.signature("x", "y"), "signature ignores case");

        if (failures > 0) {
            System.out.println(failures + " failed");
            System.exit(1);
        }
        System.out.println("TitlesTest: ok");
    }
}
