package io.freemytv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A movie or episode playing (ExoPlayer), in a card on the home screen (a preview: cropped, no subtitles, nothing
 * saved) or full screen (subtitles, the bar, the remote, your place saved to Stremio every 30 s). The same player goes
 * from one to the other without stopping. Tries Streams' list in order: a stream that fails, or that is only an
 * add-on's notice (a clip of a few seconds), gives way to the next.
 */
final class Playback extends FrameLayout {
    interface Listener {
        /** The real video is on screen. */
        void onStarted();
        /** It ended, or nothing could play it: close it. */
        void onDone(boolean failed);
    }

    private static final int JUMP = 10_000, HUD_FOR = 4000, SAVE_EVERY = 30_000;
    private static final long NOTICE = 3 * 60_000L;   // shorter than this = an add-on's notice, not the movie or episode

    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService saver = Executors.newSingleThreadExecutor();
    private final SurfaceView surface;
    private final ImageView picture;
    private final ProgressBar spinner, bar;
    private final TextView subtitle, time, titleView;
    private final LinearLayout hud;
    private ExoPlayer player;
    private Streams.Choice c;
    private String name, posterUrl;
    private VideoSize size;
    private int index = -1;
    private boolean started, ready, rendered, full, waiting;
    private long hudUntil, askedAt;

    Playback(Context ctx, Listener listener) {
        super(ctx);
        this.listener = listener;
        float dp = getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(ctx);
        addView(surface, new LayoutParams(-1, -1, Gravity.CENTER));
        picture = new ImageView(ctx);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setColorFilter(0x55000000);
        addView(picture, new LayoutParams(-1, -1));
        spinner = new ProgressBar(ctx);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Color.WHITE));
        spinner.setVisibility(GONE);
        addView(spinner, new LayoutParams(Math.round(56 * dp), Math.round(56 * dp), Gravity.CENTER));

        subtitle = new TextView(ctx);
        subtitle.setTextColor(Color.WHITE);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        subtitle.setTypeface(Ui.medium);
        subtitle.setShadowLayer(6 * dp, 0, 0, Color.BLACK);
        subtitle.setGravity(Gravity.CENTER);
        LayoutParams sp = new LayoutParams(-1, -2, Gravity.BOTTOM);
        sp.setMargins(Math.round(120 * dp), 0, Math.round(120 * dp), Math.round(48 * dp));
        addView(subtitle, sp);

        hud = new LinearLayout(ctx);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setPadding(Math.round(64 * dp), Math.round(80 * dp), Math.round(64 * dp), Math.round(40 * dp));
        hud.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
        titleView = new TextView(ctx);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        titleView.setTypeface(Ui.semibold);
        hud.addView(titleView);
        bar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Math.round(6 * dp));
        bp.topMargin = Math.round(16 * dp);
        hud.addView(bar, bp);
        time = new TextView(ctx);
        time.setTextColor(0xCCFFFFFF);
        time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        time.setTypeface(Ui.regular);
        time.setPadding(0, Math.round(10 * dp), 0, 0);
        hud.addView(time);
        hud.setVisibility(GONE);
        addView(hud, new LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    /** Starts from where you left it (Choice.startAt). The picture (or null) shows until the first frame. */
    void play(Streams.Choice choice, Bitmap poster, String title, String name, String posterUrl) {
        play(choice, poster, title, name, posterUrl, false);
    }

    /**
     * waiting: loads it ahead (connects, reads the file, goes to your place, buffers) but paused and without the
     * screen, so it holds none of the TV's video windows; reveal() then only has to show it.
     */
    void play(Streams.Choice choice, Bitmap poster, String title, String name, String posterUrl, boolean waiting) {
        stop();
        this.waiting = waiting;
        c = choice;
        this.name = name;
        this.posterUrl = posterUrl;
        titleView.setText(title);
        picture.setImageBitmap(poster);
        picture.setAlpha(1f);
        picture.setVisibility(poster == null ? GONE : VISIBLE);
        subtitle.setText("");
        started = false;
        index = -1;
        size = null;
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000);
        player = new ExoPlayer.Builder(getContext(), new DefaultRenderersFactory(getContext()).setEnableDecoderFallback(true)
                // MediaTek's decoders (this TV's) fail on 4K HEVC with ExoPlayer's asynchronous queueing.
                .forceDisableMediaCodecAsynchronousQueueing())
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(getContext(), http)))
                // Starts after 1 s buffered (not 2.5), and keeps at most 30 s / 48 MB ahead (not ~130 MB in the home's memory).
                .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 30_000, 1_000, 2_000)
                        .setTargetBufferBytes(48 * 1024 * 1024).build())
                .build();
        if (!waiting) player.setVideoSurfaceView(surface);
        player.addListener(events);
        player.setPlayWhenReady(!waiting);
        askedAt = SystemClock.uptimeMillis();
        next(c.startAt);
        handler.postDelayed(save, SAVE_EVERY);
        showState();
    }

    /** Full screen (subtitles, the bar, the remote, saving) or a preview in a card. */
    void setFull(boolean on) {
        if (full && !on) saveNow();
        full = on;
        subtitle.setVisibility(on ? VISIBLE : GONE);
        if (!on) hud.setVisibility(GONE);
        showState();
        fit();
    }

    boolean started() {
        return started;
    }

    /** Loaded ahead and not shown yet. */
    boolean waiting() {
        return player != null && waiting;
    }

    /** Shows (and plays) what was loaded ahead. */
    void reveal() {
        if (!waiting()) return;
        waiting = false;
        askedAt = SystemClock.uptimeMillis();
        AppLog.w("player: showing what was loaded ahead (" + (ready ? "ready" : "still loading") + ")");
        player.setVideoSurfaceView(surface);
        player.setPlayWhenReady(true);
    }

    long position() {
        return player == null ? 0 : player.getCurrentPosition();
    }

    long duration() {
        return player == null || player.getDuration() == C.TIME_UNSET ? 0 : player.getDuration();
    }

    /** "See from the start". */
    void restart() {
        if (player != null) player.seekTo(0);
    }

    /** Stops and lets the player go; your place is saved if you were watching it full screen. */
    void stop() {
        if (player == null) return;
        if (full) saveNow();
        handler.removeCallbacksAndMessages(null);
        player.release();
        player = null;
        hud.setVisibility(GONE);
        spinner.setVisibility(GONE);
    }

    /** The next stream in the list, from this position; none left = done. */
    private void next(long from) {
        index++;
        if (index >= c.streams.size()) {
            stop();
            listener.onDone(true);
            return;
        }
        Streams.Stream s = c.streams.get(index);
        ready = false;
        rendered = false;
        MediaItem.Builder item = new MediaItem.Builder().setUri(s.url);
        TrackSelectionParameters.Builder tracks = player.getTrackSelectionParameters().buildUpon()
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

    private final Player.Listener events = new Player.Listener() {
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
                stop();
                listener.onDone(false);
                return;
            }
            showState();
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
            size = v;
            fit();
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
        AppLog.w("player: stream " + (index + 1) + " on screen in " + (SystemClock.uptimeMillis() - askedAt) + " ms");
        picture.animate().alpha(0).setDuration(450).start();
        showState();
        listener.onStarted();
    }

    private void showState() {
        boolean buffering = player != null && (!started || player.getPlaybackState() == Player.STATE_BUFFERING);
        spinner.setVisibility(full && buffering ? VISIBLE : GONE);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        post(this::fit);
    }

    /** The whole video on screen when full; in a card, it fills the card (cropped, like a photo). */
    private void fit() {
        int w = getWidth(), h = getHeight();
        if (size == null || w == 0 || h == 0) return;
        float ratio = size.width * size.pixelWidthHeightRatio / size.height;
        boolean wider = (float) w / h > ratio;
        LayoutParams lp = (LayoutParams) surface.getLayoutParams();
        if (wider == full) {
            lp.width = Math.round(h * ratio);
            lp.height = h;
        } else {
            lp.width = w;
            lp.height = Math.round(w / ratio);
        }
        lp.gravity = Gravity.CENTER;
        surface.setLayoutParams(lp);
    }

    /** The remote, full screen: OK pauses, ← → jump 10 s (faster held down), ↑ ↓ show the bar. */
    boolean onKey(KeyEvent e) {
        if (player == null) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (!started || e.getRepeatCount() > 0) return true;
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
                return false;
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
        if (hud.getVisibility() != VISIBLE) {
            hud.setVisibility(VISIBLE);
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
                hud.setVisibility(GONE);
                return;
            }
            handler.postDelayed(this, 500);
        }
    };

    private final Runnable save = new Runnable() {
        @Override public void run() {
            if (full && player != null && player.isPlaying()) saveNow();
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
}
