package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
    }

    @Override protected void onPause() {
        if (home != null) home.pause();
        super.onPause();
    }

    /**
     * Home button pressed on the home screen itself: back to the entry state (focus on the first app, sheet closed).
     * Pressed in another app, it brings the home screen back as you left it (the tab and the card you came from).
     */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home != null && home.isRunning()) home.reset(true);
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    /** Google TV's voice input answered (the Search page's voice input through the remote's microphone). */
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (home != null) home.activityResult(requestCode, resultCode, data);
    }

    /** The microphone permission's answer (the Search page's voice input). */
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (home != null) home.permissionResult(requestCode, results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        int code = event.getKeyCode();
        // Which code a remote's special button sends (the Assistant button, say) is only known from here.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && !KeyEvent.isModifierKey(code)
                && code != KeyEvent.KEYCODE_BACK && (code < KeyEvent.KEYCODE_DPAD_UP || code > KeyEvent.KEYCODE_DPAD_CENTER)
                && code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            AppLog.w("key " + KeyEvent.keyCodeToString(code) + " scan " + event.getScanCode());
        }
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
