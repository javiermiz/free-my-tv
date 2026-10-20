package io.freemytv;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Our own player (ExoPlayer) over the home screen: it opens on the title's picture, which fades into the video at the
 * first frame, from where you left it. Tries Streams' list in order: a stream that fails, or that is only an add-on's
 * notice (a clip of a few seconds), gives way to the next. Saves your place to your Stremio library every 30 s.
 * OK pauses, ← → jump 10 s (faster held down), ↑ ↓ show the bar, BACK closes.
 */
public class PlayerActivity extends Activity {
    /** Handed over by the home screen (same process) right before it opens this. */
    static Streams.Choice choice;
    static String title, name, posterUrl;
    static Bitmap poster;

    private static final int JUMP = 10_000, HUD_FOR = 4000, SAVE_EVERY = 30_000;
    private static final long NOTICE = 3 * 60_000L;   // shorter than this = an add-on's notice, not the movie or episode

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService saver = Executors.newSingleThreadExecutor();
    private Streams.Choice c;
    private ExoPlayer player;
    private FrameLayout root;
    private SurfaceView surface;
    private ImageView picture;
    private ProgressBar spinner, bar;
    private TextView subtitle, time;
    private LinearLayout hud;
    private int index = -1;
    private boolean started, ready, rendered;
    private long hudUntil;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        c = choice;
        if (c == null || c.streams.isEmpty()) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildViews();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000);
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http)))
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(listener);
        player.setPlayWhenReady(true);
        next(c.startAt);
        handler.postDelayed(save, SAVE_EVERY);
    }

    /** The next stream in the list, from this position; none left = back to the home screen. */
    private void next(long from) {
        index++;
        if (index >= c.streams.size()) {
            Toast.makeText(this, FlixView.es("No se pudo reproducir", "Couldn't play it"), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Streams.Stream s = c.streams.get(index);
        ready = false;
        rendered = false;
        MediaItem.Builder item = new MediaItem.Builder().setUri(s.url);
        androidx.media3.common.TrackSelectionParameters.Builder tracks = player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguages("es-419", "es");
        if (s.spanishAudio) {
            tracks.setPreferredTextLanguages().setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT);
        } else {
            tracks.setPreferredTextLanguages("es-419", "es").setIgnoredTextSelectionFlags(0);
            if (c.subtitles != null && new File(c.subtitles).length() > 0) {
                item.setSubtitleConfigurations(Collections.singletonList(
                        new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(new File(c.subtitles)))
                                .setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("es")
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT | C.SELECTION_FLAG_FORCED).build()));
            }
        }
        player.setTrackSelectionParameters(tracks.build());
        player.setMediaItem(item.build(), from);
        player.prepare();
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY && !ready) {
                ready = true;
                long d = player.getDuration();
                if (d != C.TIME_UNSET && d < NOTICE) {
                    AppLog.w("player: stream " + (index + 1) + " is a notice (" + d / 1000 + " s), next");
                    next(c.startAt);
                    return;
                }
                if (rendered) begin();
            }
            if (state == Player.STATE_ENDED) {
                saveNow();
                finish();
                return;
            }
            spinner.setVisibility(!started || state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
        }

        @Override public void onPlayerError(PlaybackException e) {
            AppLog.w("player: stream " + (index + 1) + " failed (" + e.getErrorCodeName() + "), next");
            next(started ? Math.max(0, player.getCurrentPosition()) : c.startAt);
        }

        @Override public void onRenderedFirstFrame() {
            rendered = true;
            if (ready) begin();
        }

        @Override public void onVideoSizeChanged(VideoSize v) {
            if (v.width == 0 || v.height == 0) return;
            float ratio = v.width * v.pixelWidthHeightRatio / v.height;
            int w = root.getWidth(), h = root.getHeight();
            if (w == 0 || h == 0) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
            if ((float) w / h > ratio) {
                lp.width = Math.round(h * ratio);
                lp.height = h;
            } else {
                lp.width = w;
                lp.height = Math.round(w / ratio);
            }
            surface.setLayoutParams(lp);
        }

        @Override public void onCues(CueGroup cues) {
            StringBuilder sb = new StringBuilder();
            for (Cue cue : cues.cues) {
                if (cue.text == null) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(cue.text);
            }
            subtitle.setText(sb);
        }
    };

    /** The real video (a frame on screen, and long enough not to be a notice): the picture fades into it. */
    private void begin() {
        if (started) return;
        started = true;
        spinner.setVisibility(View.GONE);
        picture.animate().alpha(0).setDuration(450).start();
    }

    private void buildViews() {
        float dp = getResources().getDisplayMetrics().density;
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setImageBitmap(poster);
        picture.setColorFilter(0x55000000);
        root.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
        root.addView(spinner, new FrameLayout.LayoutParams(Math.round(56 * dp), Math.round(56 * dp), Gravity.CENTER));

        subtitle = new TextView(this);
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        subtitle.setTypeface(Ui.medium);
        subtitle.setShadowLayer(6 * dp, 0, 0, Color.BLACK);
        subtitle.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        sp.setMargins(Math.round(120 * dp), 0, Math.round(120 * dp), Math.round(48 * dp));
        root.addView(subtitle, sp);

        hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setPadding(Math.round(64 * dp), Math.round(80 * dp), Math.round(64 * dp), Math.round(40 * dp));
        hud.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTypeface(Ui.semibold);
        hud.addView(t);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Math.round(6 * dp));
        bp.topMargin = Math.round(16 * dp);
        hud.addView(bar, bp);
        time = new TextView(this);
        time.setTextColor(0xCCFFFFFF);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        time.setTypeface(Ui.regular);
        time.setPadding(0, Math.round(10 * dp), 0, 0);
        hud.addView(time);
        hud.setVisibility(View.GONE);
        root.addView(hud, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (player == null || e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                finish();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (!started) return true;
                player.setPlayWhenReady(!player.getPlayWhenReady());
                if (!player.getPlayWhenReady()) saveNow();
                showHud();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                jump(-JUMP * (1 + e.getRepeatCount() / 5));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                jump(JUMP * (1 + e.getRepeatCount() / 5));
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showHud();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void jump(long by) {
        if (!started) return;
        long d = player.getDuration();
        long to = Math.max(0, player.getCurrentPosition() + by);
        if (d != C.TIME_UNSET) to = Math.min(d - 1000, to);
        player.seekTo(to);
        showHud();
    }

    private void showHud() {
        hudUntil = SystemClock.uptimeMillis() + HUD_FOR;
        if (hud.getVisibility() != View.VISIBLE) {
            hud.setVisibility(View.VISIBLE);
            handler.post(tick);
        }
    }

    /** The bar and the time while the HUD shows; it hides itself after a few seconds (never while paused). */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            long pos = player.getCurrentPosition(), dur = Math.max(1, player.getDuration());
            bar.setProgress((int) (1000L * pos / dur));
            time.setText(clock(pos) + " / " + clock(dur));
            if (player.getPlayWhenReady() && SystemClock.uptimeMillis() > hudUntil) {
                hud.setVisibility(View.GONE);
                return;
            }
            handler.postDelayed(this, 500);
        }
    };

    private final Runnable save = new Runnable() {
        @Override public void run() {
            if (player != null && player.isPlaying()) saveNow();
            handler.postDelayed(this, SAVE_EVERY);
        }
    };

    /** Your place to your Stremio library, in the background (only once the real video is playing). */
    private void saveNow() {
        if (player == null || !started) return;
        final long pos = player.getCurrentPosition(), dur = player.getDuration();
        if (dur == C.TIME_UNSET || dur < NOTICE) return;
        final Streams.Choice ch = c;
        final String n = name, p = posterUrl;
        saver.execute(() -> Streams.saveProgress(ch, pos, dur, n, p));
    }

    private static String clock(long ms) {
        long s = ms / 1000, h = s / 3600, m = s / 60 % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s % 60)
                : String.format(java.util.Locale.US, "%d:%02d", m, s % 60);
    }

    @Override protected void onPause() {
        // Leaving (BACK, Home, another app): your place is saved and it stops, never keeps playing behind.
        saveNow();
        super.onPause();
        finish();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) player.release();
        player = null;
        saver.shutdown();   // the last save still goes out
        poster = null;
        choice = null;
        super.onDestroy();
    }
}
