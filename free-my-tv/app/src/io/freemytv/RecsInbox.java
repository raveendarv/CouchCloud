package io.freemytv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * The recommendation profile's way in and the feedback's way out, for tools/recs/recs.py, since Android 11+ keeps adb
 * out of the app's folders: `adb exec-in content write --uri content://io.freemytv.recs/profile.json?size=<bytes>`
 * and `adb exec-out content read --uri content://io.freemytv.recs/feedback.json`. Only adb's shell (or root) may.
 * The files live in files/recs (ProfileStore, Feedback).
 */
public class RecsInbox extends ContentProvider {
    private static final int SHELL_UID = 2000;

    @Override public boolean onCreate() {
        return true;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int caller = Binder.getCallingUid();
        if (caller != SHELL_UID && caller != 0) throw new SecurityException("only adb");
        String name = uri.getLastPathSegment();
        File dir = ProfileStore.dir(getContext());
        // The profile, and your TMDB account's token (Recs.TOKEN_FILE: the ratings given on the TV go to TMDB with it;
        // it stays in the app's private files, never in the log).
        if (("profile.json".equals(name) || Recs.TOKEN_FILE.equals(name)) && mode.contains("w")) {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new FileNotFoundException("can't create " + dir);
            long expected = -1;
            try {
                expected = Long.parseLong(uri.getQueryParameter("size"));
            } catch (RuntimeException ignored) {
            }
            File part = new File(dir, "." + name), done = new File(dir, name);
            long size = expected;
            try {
                AppLog.w(name + ": incoming, " + size + " bytes announced");
                return ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE, new Handler(Looper.getMainLooper()), e -> {
                            // adb's shell may drop the pipe once it has written everything ("Remote side is dead"): the
                            // announced size, when it was given, is what says the file is whole.
                            boolean whole = size >= 0 ? part.length() == size : e == null;
                            if (!whole || !part.renameTo(done)) {
                                AppLog.w(name + ": dropped (" + part.length() + " of " + size + " bytes" + (e == null ? "" : ", " + e.getMessage()) + ")");
                                part.delete();
                            } else {
                                AppLog.w(name + ": received " + done.length() + " bytes");
                            }
                        });
            } catch (IOException e) {
                throw new FileNotFoundException(e.getMessage());
            }
        }
        if ("feedback.json".equals(name) && mode.equals("r")) {
            File f = Feedback.file(getContext());
            if (!f.exists()) throw new FileNotFoundException("no feedback yet");
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if ("status.json".equals(name) && mode.equals("r")) {   // for recs.py: the token, the outbox, the account's pull (Account.status)
            String status = Account.status(getContext()).toString() + "\n";
            File f = new File(getContext().getCacheDir(), "recs-inbox-status.json");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                out.write(status.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new FileNotFoundException(e.getMessage());
            }
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        // Continue Watching's files, to read what the TV recorded: watching-sessions.json, watching-stremio.json, watching-hidden.json
        if (name.startsWith("watching-") && name.endsWith(".json") && mode.equals("r")) {
            File f = new File(Watching.dir(getContext()), name.substring("watching-".length()));
            if (!f.exists()) throw new FileNotFoundException("nothing recorded yet");
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        throw new FileNotFoundException("content://io.freemytv.recs/profile.json or tmdb-token (write), feedback.json, status.json or watching-*.json (read)");
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
