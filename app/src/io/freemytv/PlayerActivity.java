package io.freemytv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * "Watch" on a title: our own player full screen over the home screen, opening on the title's picture, which fades
 * into the video at the first frame, from where you left it. BACK closes.
 */
public class PlayerActivity extends Activity implements Playback.Listener {
    /** Handed over by the home screen (same process) right before it opens this. */
    static Streams.Choice choice;
    static String title, name, posterUrl;
    static Bitmap poster;

    private Playback playback;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (choice == null || choice.streams.isEmpty()) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        playback = new Playback(this, this);
        setContentView(playback);
        playback.setFull(true);
        playback.play(choice, poster, title, name, posterUrl);
    }

    @Override public void onStarted() {}

    @Override public void onDone(boolean failed) {
        if (failed) Toast.makeText(this, Ui.t("No se pudo reproducir",
                "Couldn't play it", "Não deu para reproduzir"), Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            // The panel, the episodes or the next episode's card close first.
            if (e.getAction() == KeyEvent.ACTION_DOWN && (playback == null || !playback.onBack())) finish();
            return true;
        }
        if (playback != null && playback.onKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override protected void onPause() {
        // Leaving (BACK, Home, another app): your place is saved and it stops, never keeps playing behind.
        if (playback != null) playback.stop();
        super.onPause();
        finish();
    }

    @Override protected void onDestroy() {
        if (playback != null) playback.stop();
        poster = null;
        choice = null;
        super.onDestroy();
    }
}
