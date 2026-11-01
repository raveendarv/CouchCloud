package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps "Continue Watching" (Watching) from the TV's media sessions: every app that plays video holds one, with the
 * title, the picture, the position and the duration as far as it publishes them. Reading other apps' sessions needs
 * notification access, which HomeMode gives this service once over the TV's own ADB (cmd notification allow_listener);
 * the notifications themselves are ignored. Runs in the main process (the home screen's process lives only while it
 * is on screen); the system keeps it bound while the access is on, so it must stay small: no bitmaps kept, writes at
 * most every 10 s while something plays.
 */
public class PlaybackListener extends NotificationListenerService {
    /** Sessions never worth a card: the system's, the Assistant's, music. */
    private static final Set<String> IGNORED = new HashSet<>(Arrays.asList("io.freemytv", "android", "com.android.bluetooth",
            "com.google.android.tts", "com.google.android.katniss", "com.google.android.apps.tv.launcherx",
            "com.google.android.youtube.tvmusic", "com.spotify.tv.android", "com.google.android.music", "com.amazon.mp3",
            "com.android.systemui"));
    private static final long POLL_MS = 20_000, WRITE_MS = 10_000, RESUME_MS = 3 * 60_000L;
    private static final int KEEP = 30;   // entries in sessions.json (the row shows Watching.MAX)

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private MediaSessionManager manager;
    private final Map<MediaSession.Token, Tracked> tracked = new HashMap<>();
    /** The entry each app's session is filling in now (pkg → entry). */
    private final Map<String, Watching.Entry> current = new HashMap<>();
    private List<Watching.Entry> entries = new ArrayList<>();
    private final Map<String, String> labels = new HashMap<>();
    private final Set<String> logged = new HashSet<>();
    private long writtenAt;
    private boolean dirty, polling;

    private final class Tracked extends MediaController.Callback {
        final MediaController controller;
        final String pkg;
        long artFp;   // fingerprint of the picture last saved from this session (0: none)

        Tracked(MediaController c) {
            controller = c;
            pkg = c.getPackageName();
        }

        @Override public void onPlaybackStateChanged(PlaybackState state) { update(this); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { update(this); }
        @Override public void onSessionDestroyed() { stop(this); }
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::onSessions;

    @Override public void onCreate() {
        super.onCreate();
        entries = Watching.readEntries(Watching.sessionsFile(this));
        // Prime Video titles recorded before their name was looked up (or while offline).
        for (Watching.Entry e : entries) {
            if (e.title == null && e.pickTitle == null && e.mediaId != null && e.mediaId.startsWith("amzn1.dv.gti.") && named.add(e.mediaId)) primeName(e, e.mediaId);
        }
    }

    @Override public void onListenerConnected() {
        manager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (manager == null) return;
        ComponentName me = new ComponentName(this, PlaybackListener.class);
        try {
            manager.addOnActiveSessionsChangedListener(sessions, me, handler);
            onSessions(manager.getActiveSessions(me));
            AppLog.w("playback listener on");
        } catch (SecurityException e) {
            AppLog.w("playback listener: no access to the media sessions: " + e.getMessage());
        }
    }

    @Override public void onListenerDisconnected() {
        if (manager != null) {
            try { manager.removeOnActiveSessionsChangedListener(sessions); } catch (RuntimeException ignored) {}
        }
        for (Tracked t : new ArrayList<>(tracked.values())) stop(t);
        flush(true);
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {}
    @Override public void onNotificationRemoved(StatusBarNotification sbn) {}

    private void onSessions(List<MediaController> list) {
        Set<MediaSession.Token> seen = new HashSet<>();
        if (list != null) {
            for (MediaController c : list) {
                if (IGNORED.contains(c.getPackageName())) continue;
                seen.add(c.getSessionToken());
                if (tracked.containsKey(c.getSessionToken())) continue;
                Tracked t = new Tracked(c);
                tracked.put(c.getSessionToken(), t);
                c.registerCallback(t, handler);
                update(t);
            }
        }
        for (Tracked t : new ArrayList<>(tracked.values())) if (!seen.contains(t.controller.getSessionToken())) stop(t);
    }

    /** The session went away (the app closed its player): the entry keeps its last position. */
    private void stop(Tracked t) {
        tracked.remove(t.controller.getSessionToken());
        try { t.controller.unregisterCallback(t); } catch (RuntimeException ignored) {}
        Watching.Entry e = current.remove(t.pkg);
        if (e != null) {
            PlaybackState st = null;
            try { st = t.controller.getPlaybackState(); } catch (RuntimeException ignored) {}
            long pos = position(st);
            if (pos > 0) e.position = pos;
            if (e.finished()) entries.remove(e);
            flush(true);
        }
    }

    private void update(Tracked t) {
        PlaybackState st;
        MediaMetadata md;
        try {
            st = t.controller.getPlaybackState();
            md = t.controller.getMetadata();
        } catch (RuntimeException e) {
            return;
        }
        if (st == null) return;
        int s = st.getState();
        boolean active = s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_BUFFERING
                || s == PlaybackState.STATE_FAST_FORWARDING || s == PlaybackState.STATE_REWINDING;
        long pos = position(st);
        String pkg = t.pkg, app = label(pkg);
        if (md != null && logged.add(pkg)) AppLog.w("playback " + pkg + ": metadata " + new ArrayList<>(md.keySet()));
        if (!active) {
            Watching.Entry e = current.remove(pkg);
            if (e != null) {
                if (pos > 0) e.position = pos;
                if (e.finished()) entries.remove(e);
                flush(true);
            }
            schedulePoll();
            return;
        }
        String title = clean(first(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE), app, pkg);
        String subtitle = clean(first(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST), app, pkg);
        String mediaId = md == null ? null : md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (mediaId != null && mediaId.trim().isEmpty()) mediaId = null;
        String mediaUri = first(md, MediaMetadata.METADATA_KEY_MEDIA_URI);
        String description = first(md, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
        long duration = md == null ? 0 : Math.max(0, md.getLong(MediaMetadata.METADATA_KEY_DURATION));
        if (title == null && pos <= 0) return;   // nothing to say yet (Netflix before its first position)
        // Nameless and barely started: YouTube's home page playing a preview, an app's intro. Not something to continue.
        if (title == null && current.get(pkg) == null && (pos < 20_000 || pkg.contains("youtube"))) return;
        long now = System.currentTimeMillis();
        Watching.Entry e = current.get(pkg);
        boolean other = e != null && ((mediaId != null && e.mediaId != null && !mediaId.equals(e.mediaId))
                || (title != null && e.title != null && !title.equalsIgnoreCase(e.title)));
        boolean fresh = false;
        if (e == null || other) {
            e = start(pkg, app, title, mediaId, now);
            current.put(pkg, e);
            fresh = true;
            AppLog.w("playback " + pkg + ": " + (title != null ? "“" + title + "”" : "no title") + (subtitle != null ? " / “" + subtitle + "”" : "")
                    + (mediaId != null ? ", media id" : "") + (mediaUri != null ? ", at " + (mediaUri.length() > 120 ? mediaUri.substring(0, 120) + "…" : mediaUri) : "")
                    + (description != null ? ", described “" + (description.length() > 80 ? description.substring(0, 80) + "…" : description) + "”" : "")
                    + (duration > 0 ? ", " + duration / 60000 + " min long" : "") + (e.pickTitle != null ? ", opened as “" + e.pickTitle + "”" : ""));
        }
        if (title != null) e.title = title;
        if (subtitle != null && (e.subtitle == null || title != null)) e.subtitle = subtitle;
        if (mediaId != null) e.mediaId = mediaId;
        if (duration > 0) e.duration = duration;
        if (pos > 0 || s == PlaybackState.STATE_PLAYING) e.position = Math.max(0, pos);
        e.at = now;
        e.app = app;
        // The app's own id of the title gives the card a page to open: YouTube's video, Prime Video's title (its GTI).
        boolean ytVideo = mediaId != null && pkg.contains("youtube") && mediaId.matches("[A-Za-z0-9_-]{11}");
        if (ytVideo) {
            if (e.uri == null) e.uri = "https://www.youtube.com/watch?v=" + mediaId;
            // The video's own thumbnail, never the session's picture: when YouTube moves to the next video, its metadata
            // changes in steps, and the first one still carries the picture of the one before.
            e.artUrl = "https://i.ytimg.com/vi/" + mediaId + "/hqdefault.jpg";   // its 4:3 bars crop away on a 16:9 card
            e.art = null;
        }
        if (e.uri == null && mediaId != null && mediaId.startsWith("amzn1.dv.gti.")) {
            e.uri = "https://app.primevideo.com/detail?gti=" + mediaId;
        }
        if (e.uri == null && mediaId != null && pkg.equals("in.startv.hotstar") && mediaId.matches("\\d{4,}")) {
            e.uri = "hotstar://content/" + mediaId;   // JioHotstar's own scheme (the one JustWatch gives for its Android TV app)
        }
        // JioHotstar hands no picture, and for an episode its title names the episode; its web address names the show,
        // which the home screen looks up on TMDB for the card's picture (Watching.lookup).
        if (e.show == null) e.show = Titles.hotstarShow(mediaUri);
        if (e.showLink == null && pkg.equals("in.startv.hotstar")) e.showLink = Titles.hotstarShowLink(mediaUri);
        if (e.title == null && e.pickTitle == null && mediaId != null && mediaId.startsWith("amzn1.dv.gti.") && named.add(mediaId)) primeName(e, mediaId);
        if (md != null && !ytVideo) art(t, e, md, fresh);
        if (e.finished()) {
            entries.remove(e);
            current.remove(pkg);
            flush(true);
        } else {
            flush(fresh);
        }
        schedulePoll();
    }

    /** The entry a session starts filling: a known one (the same title again), or a new one named by our launch. */
    private Watching.Entry start(String pkg, String app, String title, String mediaId, long now) {
        JSONObject launched = launched(pkg, title, now);
        String key;
        if (mediaId != null) key = pkg + "|m:" + mediaId;
        else if (launched != null) key = pkg + "|p:" + launched.optString("kind") + launched.optInt("id");
        else if (title != null) key = pkg + "|t:" + title.toLowerCase(java.util.Locale.ROOT);
        else key = pkg + "|~";
        Watching.Entry e = null;
        for (Watching.Entry x : entries) if (x.key.equals(key)) e = x;
        // Nameless (Netflix from its own menus): the same viewing going on after a short stop keeps its card.
        if (e == null && title == null && launched == null) {
            for (Watching.Entry x : entries) if (x.pkg.equals(pkg) && now - x.at < RESUME_MS && (e == null || x.at > e.at)) e = x;
        }
        if (e == null) {
            e = new Watching.Entry();
            e.key = key;
            e.pkg = pkg;
            e.app = app;
            entries.add(0, e);
        }
        if (launched != null) {
            e.pickKind = launched.optString("kind", "");
            e.pickId = launched.optInt("id");
            e.pickTitle = launched.optString("title", "");
            e.pickBackdrop = launched.has("backdrop") ? launched.optString("backdrop") : null;
            if (launched.has("link")) e.uri = launched.optString("link");
            if (e.pickKind.isEmpty() || e.pickId == 0) { e.pickKind = null; e.pickId = 0; }
        }
        return e;
    }

    /** The title opened from our cards in this app a moment ago, when the session may be it (same name, or none). */
    private JSONObject launched(String pkg, String title, long now) {
        String text = Watching.read(Watching.launchedFile(this));
        if (text.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(text);
            if (!pkg.equals(o.optString("pkg")) || now - o.optLong("at") > Watching.LAUNCH_MS) return null;
            String ours = o.optString("title", "");
            if (title != null && !ours.isEmpty()) {
                String a = title.toLowerCase(java.util.Locale.ROOT), b = ours.toLowerCase(java.util.Locale.ROOT);
                if (!a.contains(b) && !b.contains(a)) return null;   // the session is another title (the app moved on)
            }
            return o;
        } catch (JSONException e) {
            return null;
        }
    }

    private final Set<String> named = new HashSet<>();   // Prime Video titles already looked up (by GTI)

    /**
     * Prime Video's session names only the app ("PrimeVideo") but carries the title's id (its GTI), and the title's
     * public page says its name and shows its picture: read once per title.
     */
    private void primeName(final Watching.Entry e, final String gti) {
        io.execute(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://www.primevideo.com/detail/" + gti).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120 Safari/537.36");
                c.setRequestProperty("Accept-Language", "en");
                String head;
                try (java.io.InputStream in = c.getInputStream()) {
                    byte[] b = new byte[300 * 1024];   // the title and og:image are in the page's head
                    int n = 0, r;
                    while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
                    head = new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8);
                } finally {
                    c.disconnect();
                }
                java.util.regex.Matcher t = java.util.regex.Pattern.compile("<title>\\s*Prime Video:\\s*([^<]+?)\\s*</title>").matcher(head);
                java.util.regex.Matcher i = java.util.regex.Pattern.compile("property=\"og:image\" content=\"([^\"]+)\"").matcher(head);
                final String title = t.find() ? android.text.Html.fromHtml(t.group(1), 0).toString().trim() : null;
                final String image = i.find() ? i.group(1) : null;
                if (title == null) throw new java.io.IOException("no title on the page");
                handler.post(() -> {
                    if (e.title == null) e.title = title;
                    if (e.art == null && e.artUrl == null && image != null && image.startsWith("http")) e.artUrl = image;
                    flush(true);
                });
                AppLog.w("prime video title " + gti.substring(gti.length() - 8) + ": named");
            } catch (java.io.IOException | RuntimeException ex) {
                handler.post(() -> named.remove(gti));   // another try next time it plays
                AppLog.w("prime video title: " + ex.getMessage());
            }
        });
    }

    /**
     * The session's picture: saved small (the app may hand a big one) when the entry is new or the app hands another
     * picture than the one saved. An app's metadata can change in steps when it moves to the next title, the picture
     * after the name: the first one saved may be the title before's, so a changed picture is saved again. The file is
     * named after the entry and the picture, so the home screen (which caches pictures by file name) sees the new one.
     */
    private void art(Tracked t, final Watching.Entry e, MediaMetadata md, boolean fresh) {
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (b == null) {
            if (e.art != null && !fresh) return;
            String uri = first(md, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            if (uri != null && uri.startsWith("http")) {
                e.artUrl = uri;
                e.art = null;
            }
            return;
        }
        long fp = fingerprint(b);
        if (!fresh && e.art != null && fp == t.artFp) return;
        t.artFp = fp;
        final Bitmap copy = b;
        final String name = "s" + Integer.toHexString(e.key.hashCode()) + "-" + Long.toHexString(fp) + ".jpg";
        final Context c = this;
        io.execute(() -> {
            if (Watching.saveArt(c, copy, name)) {
                handler.post(() -> {
                    if (e.art != null && !e.art.equals(name)) {
                        final File old = new File(Watching.artDir(c), e.art);
                        //noinspection ResultOfMethodCallIgnored
                        io.execute(old::delete);
                    }
                    e.art = name;
                    e.artUrl = null;
                    flush(true);
                });
            }
        });
    }

    /** Tells one picture from another cheaply: its size and a 4×4 grid of its pixels. */
    private static long fingerprint(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        long fp = w * 31L + h;
        if (w == 0 || h == 0) return fp;
        try {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 4; x++) fp = fp * 1_000_003L + b.getPixel(w * (2 * x + 1) / 8, h * (2 * y + 1) / 8);
            }
        } catch (IllegalStateException ignored) {   // a hardware bitmap: its pixels can't be read; size alone tells
        }
        return fp;
    }

    /** The position now: the app's last report moved on by the time since, while playing. */
    private static long position(PlaybackState st) {
        if (st == null) return 0;
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING && st.getLastPositionUpdateTime() > 0) {
            pos += Math.round((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
        }
        return Math.max(0, pos);
    }

    private static String first(MediaMetadata md, String... keys) {
        if (md == null) return null;
        for (String k : keys) {
            String v = md.getString(k);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    /** A text that only names the app (Prime Video's "PrimeVideo") is no title. */
    private static String clean(String text, String app, String pkg) {
        if (text == null) return null;
        String t = text.replaceAll("[\\s_-]", "").toLowerCase(java.util.Locale.ROOT);
        String a = app.replaceAll("[\\s_-]", "").toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty() || t.equals(a) || t.equals(pkg.toLowerCase(java.util.Locale.ROOT)) || t.equals("primevideo") || t.equals("netflix")) return null;
        return text;
    }

    private String label(String pkg) {
        String l = labels.get(pkg);
        if (l != null) return l;
        l = HomeApps.serviceLabel(pkg);
        if (l == null) {
            try {
                ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
                l = String.valueOf(getPackageManager().getApplicationLabel(info));
            } catch (PackageManager.NameNotFoundException e) {
                l = pkg;
            }
        }
        labels.put(pkg, l);
        return l;
    }

    /** Positions move without the app saying so: while something plays, every 20 s. */
    private void schedulePoll() {
        boolean playing = false;
        for (Tracked t : tracked.values()) {
            try {
                PlaybackState st = t.controller.getPlaybackState();
                playing |= st != null && st.getState() == PlaybackState.STATE_PLAYING;
            } catch (RuntimeException ignored) {
            }
        }
        if (playing == polling) return;
        polling = playing;
        handler.removeCallbacks(poll);
        if (playing) handler.postDelayed(poll, POLL_MS);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            polling = false;
            for (Tracked t : new ArrayList<>(tracked.values())) update(t);
            if (!polling) schedulePoll();
        }
    };

    /** Writes sessions.json, now or (while a position only moves on) at most every 10 s. */
    private void flush(boolean now) {
        dirty = true;
        long up = SystemClock.uptimeMillis();
        if (!now && up - writtenAt < WRITE_MS) {
            handler.removeCallbacks(write);
            handler.postDelayed(write, WRITE_MS - (up - writtenAt));
            return;
        }
        handler.removeCallbacks(write);
        write.run();
    }

    private final Runnable write = new Runnable() {
        @Override public void run() {
            if (!dirty) return;
            dirty = false;
            writtenAt = SystemClock.uptimeMillis();
            long old = System.currentTimeMillis() - Watching.KEEP_MS;
            Collections.sort(entries, (a, b) -> Long.compare(b.at, a.at));
            List<Watching.Entry> keep = new ArrayList<>();
            for (Watching.Entry e : entries) if (e.at >= old && keep.size() < KEEP) keep.add(e);
            entries = keep;
            final org.json.JSONArray a = new org.json.JSONArray();
            try {
                for (Watching.Entry e : keep) a.put(e.toJson());
            } catch (JSONException ignored) {
            }
            final List<Watching.Entry> snapshot = new ArrayList<>(keep);
            final Context c = PlaybackListener.this;
            io.execute(() -> {
                Watching.write(Watching.sessionsFile(c), a.toString());
                Watching.pruneArt(c, snapshot);
            });
        }
    };
}
