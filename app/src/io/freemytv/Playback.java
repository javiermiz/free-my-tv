package io.freemytv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A movie or episode playing (ExoPlayer), in a card on the home screen (a preview: cropped, no subtitles, nothing
 * saved) or full screen (subtitles, the controls, the remote, your place saved to Stremio every 30 s). The same player
 * goes from one to the other without stopping. Tries Streams' list in order: a stream that fails, or that is only an
 * add-on's notice (a clip of a few seconds), gives way to the next.
 *
 * Full screen (PlayerHud draws it): ← → move through the video and OK pauses; ↓ goes to the buttons (−10, play,
 * +10, Audio and subtitles, Episodes, Next). Near the end of an episode the next one is offered, and plays by itself.
 */
final class Playback extends FrameLayout {
    interface Listener {
        /** The real video is on screen. */
        void onStarted();
        /** It ended, or nothing could play it: close it. */
        void onDone(boolean failed);
    }

    private static final int JUMP = 10_000, HUD_FOR = 5000, SAVE_EVERY = 30_000;
    private static final long NOTICE = 3 * 60_000L;   // shorter than this = an add-on's notice, not the movie or episode
    /** The next episode is offered this long before the end, and plays by itself after COUNTDOWN. */
    private static final long NEXT_BEFORE = 60_000, COUNTDOWN = 15_000;   // Netflix counts 15 s

    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService saver = Executors.newSingleThreadExecutor(), images = Executors.newSingleThreadExecutor();
    private final SurfaceView surface;
    private final ImageView picture;
    private final ProgressBar spinner;
    private final TextView subtitle;
    private final PlayerHud hud;
    private ExoPlayer player;
    private Streams.Choice c;
    private String name, posterUrl, title;
    private VideoSize size;
    private int index = -1;
    private boolean started, ready, rendered, full, waiting;
    private long hudUntil, askedAt;
    private boolean userTracks;   // you chose an audio or subtitles: no more choosing for you

    // The show's episodes (Stremio's list), this one and the next.
    private List<PlayerHud.Ep> allEps = new ArrayList<>();
    private String epsFor;
    private PlayerHud.Ep current, nextEp;
    private boolean nextDismissed;
    private long nextAt;
    private Streams.Choice nextChoice;   // the next episode's streams, looked up while its card shows
    private PlayerHud.Ep nextChoiceFor;
    private int switchAsk;               // a newer switch (or stop) makes an older one's result stale

    Playback(Context ctx, Listener listener) {
        super(ctx);
        this.listener = listener;
        float dp = getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(ctx);
        addView(surface, new LayoutParams(-1, -1, Gravity.CENTER));
        picture = new ImageView(ctx);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setColorFilter(0x88000000);
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

        hud = new PlayerHud(ctx);
        hud.setVisibility(GONE);
        addView(hud, new LayoutParams(-1, -1));
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
        release();
        this.waiting = waiting;
        c = choice;
        this.name = name;
        this.posterUrl = posterUrl;
        this.title = title;
        picture.setImageBitmap(poster);
        picture.setAlpha(1f);
        picture.setVisibility(poster == null ? GONE : VISIBLE);
        subtitle.setText("");
        started = false;
        index = -1;
        size = null;
        userTracks = false;
        nextDismissed = false;
        nextChoice = null;
        nextChoiceFor = null;
        hud.mode = PlayerHud.NONE;
        hud.loading = true;
        hud.loadingFrom = c.startAt > 5000 ? FlixView.es("Sigue desde ", "From ") + clock(c.startAt) : "";
        titles();
        loadSeries();
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
        handler.post(often);
        showState();
    }

    /** Full screen (subtitles, the controls, the remote, saving) or a preview in a card. */
    void setFull(boolean on) {
        if (full && !on) saveNow();
        full = on;
        subtitle.setVisibility(on ? VISIBLE : GONE);
        hud.setVisibility(on ? VISIBLE : GONE);
        if (!on) hud.mode = PlayerHud.NONE;
        hud.invalidate();
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
        switchAsk++;   // an episode being looked up won't start after this
        release();
    }

    private void release() {
        if (player == null) return;
        if (full) saveNow();
        handler.removeCallbacksAndMessages(null);
        player.release();
        player = null;
        hud.mode = PlayerHud.NONE;
        hud.loading = false;
        hud.invalidate();
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
        // Spanish subtitles ready either way; whether they show is settled by the audio that plays (onTracksChanged),
        // the stream's name only guesses until then.
        TrackSelectionParameters.Builder tracks = player.getTrackSelectionParameters().buildUpon().clearOverrides()
                .setPreferredAudioLanguages("es-419", "es").setPreferredTextLanguages("es-419", "es")
                .setIgnoredTextSelectionFlags(0).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, s.spanishAudio);
        if (c.subtitles != null && new File(c.subtitles).length() > 0) {
            item.setSubtitleConfigurations(Collections.singletonList(
                    new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(new File(c.subtitles)))
                            .setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("es")
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT | C.SELECTION_FLAG_FORCED).build()));
        }
        player.setTrackSelectionParameters(tracks.build());
        player.setMediaItem(item.build(), from);
        player.prepare();
        loadingLines();
        top();
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
                if (full && nextEp != null) {   // the credits ran out: on to the next one
                    playEpisode(nextEp);
                    return;
                }
                stop();
                listener.onDone(false);
                return;
            }
            showState();
        }

        /** Subtitles off only when the audio playing is Spanish (by the file's own label; by the name if it has none). */
        @Override public void onTracksChanged(Tracks t) {
            if (player == null || index < 0 || index >= c.streams.size()) return;
            top();
            if (hud.mode == PlayerHud.PANEL) panelItems();
            if (userTracks) return;
            String lang = null;
            for (Tracks.Group g : t.getGroups()) {
                if (g.getType() != C.TRACK_TYPE_AUDIO || !g.isSelected()) continue;
                for (int i = 0; i < g.length; i++) if (g.isTrackSelected(i)) lang = g.getTrackFormat(i).language;
            }
            boolean labeled = lang != null && !lang.equals("und");
            boolean off = labeled ? Streams.spanish(lang) : c.streams.get(index).spanishAudio;
            TrackSelectionParameters now = player.getTrackSelectionParameters();
            if (now.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) == off) return;
            AppLog.w("player: audio " + (labeled ? lang : "unlabeled") + ", subtitles " + (off ? "off" : "on"));
            player.setTrackSelectionParameters(now.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, off).build());
        }

        @Override public void onPlayerError(PlaybackException e) {
            // What failed, for the decoder errors (the codec, the format, and whether it was a card or full screen).
            StringBuilder why = new StringBuilder();
            for (Throwable x = e.getCause(); x != null && why.length() < 600; x = x.getCause())
                why.append(" ← ").append(x.getClass().getSimpleName()).append(": ").append(x.getMessage());
            Format f = player.getVideoFormat();
            AppLog.w("player: stream " + (index + 1) + " failed (" + e.getErrorCodeName() + ") in " + (full ? "full screen" : waiting ? "a card, loaded ahead" : "a card")
                    + (f == null ? "" : ", video " + f.sampleMimeType + " " + f.codecs + " " + f.width + "×" + f.height) + why);
            AppLog.w("player: stream " + (index + 1) + " failed (" + e.getErrorCodeName() + "), next");
            next(started ? Math.max(0, player.getCurrentPosition()) : c.startAt);
        }

        @Override public void onRenderedFirstFrame() {
            rendered = true;
            if (ready) begin();
        }

        @Override public void onIsPlayingChanged(boolean playing) {
            hud.playing = playing;
            hud.invalidate();
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
        hud.loading = false;
        hud.invalidate();
        showState();
        listener.onStarted();
    }

    private void showState() {
        boolean buffering = player != null && started && player.getPlaybackState() == Player.STATE_BUFFERING;
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

    // MARK: - The remote, full screen

    /** BACK: closes the panel, the episodes, the next episode's card or the controls; false = nothing open (the host closes). */
    boolean onBack() {
        switch (hud.mode) {
            case PlayerHud.PANEL:
            case PlayerHud.EPISODES:
                showBar(false);
                return true;
            case PlayerHud.NEXT:
                dismissNext();
                return true;
            case PlayerHud.BAR:
                hideBar();
                return true;
            default:
                return false;
        }
    }

    boolean onKey(KeyEvent e) {
        if (player == null) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        int code = e.getKeyCode();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean left = code == KeyEvent.KEYCODE_DPAD_LEFT, right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
        boolean up = code == KeyEvent.KEYCODE_DPAD_UP, down = code == KeyEvent.KEYCODE_DPAD_DOWN;
        long by = JUMP * (1 + e.getRepeatCount() / 5);   // faster held down
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                togglePlay();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                jump(-by);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                jump(by);
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (nextEp != null) playEpisode(nextEp);
                return true;
            default:
                break;
        }
        if (!ok && !left && !right && !up && !down) return false;
        if (ok && e.getRepeatCount() > 0) return true;
        switch (hud.mode) {
            case PlayerHud.PANEL: panelKey(ok, up, down, left, right); return true;
            case PlayerHud.EPISODES: episodesKey(ok, up, left, right); return true;
            case PlayerHud.NEXT: nextKey(ok, left, right); return true;
            default: break;
        }
        if (hud.mode == PlayerHud.NONE) {
            // Hidden: OK or ↓ only bring the controls (it keeps playing); ← → also jump; ↑ does nothing.
            if (up) return true;
            showBar(!down);
            if (left || right) jump(left ? -by : by);
            return true;
        }
        hudUntil = SystemClock.uptimeMillis() + HUD_FOR;
        if (up) {   // ↑ (or BACK) puts the controls away
            hideBar();
            return true;
        }
        if (hud.barFocused) {
            if (left || right) jump(left ? -by : by);
            else if (ok) togglePlay();
            else if (down) {
                hud.barFocused = false;
                hud.button = Math.max(0, hud.buttons.indexOf(PlayerHud.B_PLAY));
            }
        } else {
            if (left || right) hud.button = Math.max(0, Math.min(hud.buttons.size() - 1, hud.button + (left ? -1 : 1)));
            else if (ok) press(hud.buttons.get(hud.button));
        }
        hud.invalidate();
        return true;
    }

    private void press(int b) {
        switch (b) {
            case PlayerHud.B_BACK: jump(-JUMP); break;
            case PlayerHud.B_FWD: jump(JUMP); break;
            case PlayerHud.B_PLAY: togglePlay(); break;
            case PlayerHud.B_AUDIO: openPanel(); break;
            case PlayerHud.B_EPISODES: openEpisodes(); break;
            default: if (nextEp != null) playEpisode(nextEp); break;
        }
    }

    private void togglePlay() {
        if (!started) return;
        player.setPlayWhenReady(!player.getPlayWhenReady());
        if (!player.getPlayWhenReady()) saveNow();
        if (hud.mode == PlayerHud.NONE) showBar(true);   // the media key's pause shows where you are
        hudUntil = SystemClock.uptimeMillis() + HUD_FOR;
    }

    private void jump(long by) {
        if (!started) return;
        long d = player.getDuration();
        long to = Math.max(0, player.getCurrentPosition() + by);
        if (d != C.TIME_UNSET) to = Math.min(d - 1000, to);
        player.seekTo(to);
        hudUntil = SystemClock.uptimeMillis() + HUD_FOR;
        times();
    }

    /** The controls: on the progress bar (barFocused) or on the buttons (the play button first). */
    private void showBar(boolean onBar) {
        buttons();
        hud.playing = player != null && player.getPlayWhenReady();   // a preview turned full screen missed the event
        hud.mode = PlayerHud.BAR;
        hud.barFocused = onBar;
        if (!onBar) hud.button = Math.max(0, hud.buttons.indexOf(PlayerHud.B_PLAY));
        hudUntil = SystemClock.uptimeMillis() + HUD_FOR;
        times();
        hud.invalidate();
    }

    private void hideBar() {
        hud.mode = PlayerHud.NONE;
        hud.barFocused = true;
        hud.invalidate();
    }

    private void buttons() {
        hud.buttons.clear();
        Collections.addAll(hud.buttons, PlayerHud.B_BACK, PlayerHud.B_PLAY, PlayerHud.B_FWD, PlayerHud.B_AUDIO);
        if (current != null) hud.buttons.add(PlayerHud.B_EPISODES);
        if (nextEp != null) hud.buttons.add(PlayerHud.B_NEXT);
        hud.button = Math.min(hud.button, hud.buttons.size() - 1);
    }

    /** Several times a second: the bar's time, hiding it, and the next episode near the end. */
    private final Runnable often = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            long now = SystemClock.uptimeMillis();
            if (hud.mode == PlayerHud.BAR) {
                times();
                if (player.getPlayWhenReady() && now > hudUntil) hud.mode = PlayerHud.NONE;
                hud.invalidate();
            }
            long d = player.getDuration(), pos = player.getCurrentPosition();
            if (full && started && nextEp != null && !nextDismissed && hud.mode != PlayerHud.NEXT && hud.mode != PlayerHud.PANEL
                    && d != C.TIME_UNSET && d >= NOTICE && d - pos <= NEXT_BEFORE && player.isPlaying()) {
                showNext();
            }
            if (hud.mode == PlayerHud.NEXT) {
                long left = nextAt - now;
                if (left <= 0) {
                    playEpisode(nextEp);
                    return;
                }
                hud.countdown = (int) ((left + 999) / 1000);
                hud.countdownPart = 1 - left / (float) COUNTDOWN;
                hud.invalidate();
            }
            handler.postDelayed(this, 250);
        }
    };

    private void times() {
        if (player == null) return;
        long pos = player.getCurrentPosition(), d = player.getDuration();
        if (d == C.TIME_UNSET || d <= 0) d = 1;
        hud.progress = Math.min(1, pos / (float) d);
        hud.buffered = Math.min(1, player.getBufferedPosition() / (float) d);
        hud.elapsed = clock(pos);
        hud.left = "−" + clock(Math.max(0, d - pos));
        long wall = System.currentTimeMillis();
        hud.clock = Ui.time(wall) + "   ·   " + FlixView.es("termina ", "ends ") + Ui.time(wall + Math.max(0, d - pos));
    }

    // MARK: - Titles and what's playing

    /** "UN AMOR DISTINTO AL TUYO" over "T1 · E7  Episodio 7" for an episode; the movie's title for a movie. */
    private void titles() {
        boolean series = "series".equals(c.type);
        hud.kicker = series && name != null ? name.toUpperCase(Locale.ROOT) : "";
        if (series && current != null) {
            hud.title = FlixView.es("T", "S") + current.season + " · E" + current.episode
                    + (current.name.isEmpty() ? "" : "  " + current.name);
        } else if (series) {
            String[] v = c.video.split(":");
            hud.title = v.length >= 3 && c.id.startsWith("tt") ? FlixView.es("T", "S") + v[v.length - 2] + " · E" + v[v.length - 1]
                    : title == null ? "" : title;
        } else {
            hud.title = title == null ? "" : title;
        }
        hud.invalidate();
    }

    /** Top left: what's playing ("Coreano · subtítulos en español · 1080p"). */
    private void top() {
        if (player == null || index < 0 || index >= c.streams.size()) return;
        Streams.Stream s = c.streams.get(index);
        hud.source = "Stremio";
        String audio = null, text = null;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSelected(i)) continue;
                if (g.getType() == C.TRACK_TYPE_AUDIO) audio = language(g.getTrackFormat(i));
                if (g.getType() == C.TRACK_TYPE_TEXT) text = language(g.getTrackFormat(i));
            }
        }
        boolean subs = text != null && !player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        StringBuilder sb = new StringBuilder(audio != null ? audio : kind(s));
        sb.append(" · ").append(subs ? FlixView.es("Subtítulos: ", "Subtitles: ") + text : FlixView.es("Sin subtítulos", "No subtitles"));
        if (!s.quality.isEmpty()) sb.append(" · ").append(s.quality);
        hud.version = sb.toString();
        hud.invalidate();
    }

    /** What the loading card says: no Latin American dub (if so), the version tried, and how many are left. */
    private void loadingLines() {
        hud.loadingLines.clear();
        if (c.latino == 0) hud.loadingLines.add(FlixView.es("Doblaje latino: no hay para este título", "Latin American dub: none for this title"));
        hud.loadingNow = hud.loadingLines.size();
        hud.loadingLines.add(describe(c.streams.get(index)));
        int left = c.streams.size() - index - 1;
        if (left > 0) hud.loadingLines.add(FlixView.es("Si falla: " + left + (left == 1 ? " versión más" : " versiones más"),
                "If it fails: " + left + " more"));
        hud.invalidate();
    }

    private String kind(Streams.Stream s) {
        return s.kind == 0 ? FlixView.es("Español latino", "Latin American Spanish")
                : s.kind == 2 ? FlixView.es("Español (España)", "Spanish (Spain)")
                : c.subtitles != null || s.embeddedSubs ? FlixView.es("Original con subtítulos", "Original with subtitles") : FlixView.es("Original", "Original");
    }

    private String describe(Streams.Stream s) {
        return kind(s) + (s.quality.isEmpty() ? "" : " · " + s.quality) + (s.size.isEmpty() ? "" : " · " + s.size);
    }

    /** A track's language in words ("Coreano", "Español (Latinoamérica)"), with its label and "forced" if so. */
    private static String language(Format f) {
        String l = f.language, out;
        Locale ui = new Locale(FlixView.es("es", "en"));
        if (l == null || l.isEmpty() || l.equals("und")) {
            out = f.label != null && !f.label.isEmpty() ? f.label : FlixView.es("Sin idioma", "Unlabeled");
        } else {
            Locale loc = Locale.forLanguageTag(l);
            String d = loc.getDisplayLanguage(ui);
            out = d.isEmpty() ? l : Character.toUpperCase(d.charAt(0)) + d.substring(1);
            if ("es".equals(loc.getLanguage())) {
                String country = loc.getCountry();
                if (country.equals("ES")) out += FlixView.es(" (España)", " (Spain)");
                else if (!country.isEmpty()) out += FlixView.es(" (Latinoamérica)", " (Latin America)");
            }
            // The file's own name for it ("Español de España", "Latino") says more than the language; else both.
            String label = f.label == null ? "" : f.label.trim();
            String first = out.split(" ")[0].toLowerCase(Locale.ROOT);
            if (label.toLowerCase(Locale.ROOT).contains(first)) out = label;
            else if (!label.isEmpty() && !label.equalsIgnoreCase(out)) out += " · " + label;
        }
        if ((f.selectionFlags & C.SELECTION_FLAG_FORCED) != 0) out += FlixView.es(" (forzados)", " (forced)");
        return out;
    }

    // MARK: - Audio and subtitles

    private void openPanel() {
        panelItems();
        hud.col = 0;
        hud.row = Math.max(0, chosen(hud.cols.get(0)));
        hud.mode = PlayerHud.PANEL;
        hud.invalidate();
    }

    private static int chosen(List<PlayerHud.Item> list) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).chosen) return i;
        return 0;
    }

    /** The panel's columns from what the file has: its audio tracks, its subtitles (and Off), and the other versions. */
    private void panelItems() {
        List<PlayerHud.Item> audio = new ArrayList<>(), text = new ArrayList<>(), versions = new ArrayList<>();
        for (final Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                final int at = i;
                audio.add(item(language(f), f.channelCount >= 8 ? "7.1" : f.channelCount >= 6 ? "5.1" : "",
                        g.isTrackSelected(i), () -> pickTrack(g, at, C.TRACK_TYPE_AUDIO)));
            }
        }
        boolean off = player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT), any = false;
        for (final Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                final int at = i;
                boolean on = !off && g.isTrackSelected(i);
                any |= on;
                text.add(item(language(g.getTrackFormat(i)), "", on, () -> pickTrack(g, at, C.TRACK_TYPE_TEXT)));
            }
        }
        text.add(item(FlixView.es("Desactivados", "Off"), "", !any, this::subtitlesOff));
        for (int i = 0; i < c.streams.size(); i++) {
            final int at = i;
            Streams.Stream s = c.streams.get(i);
            versions.add(item(kind(s) + (s.quality.isEmpty() ? "" : " · " + s.quality), s.size, i == index, () -> switchStream(at)));
        }
        hud.cols.clear();
        hud.colTitles.clear();
        hud.cols.add(audio);
        hud.colTitles.add(FlixView.es("AUDIO", "AUDIO"));
        hud.cols.add(text);
        hud.colTitles.add(FlixView.es("SUBTÍTULOS", "SUBTITLES"));
        hud.cols.add(versions);
        hud.colTitles.add(FlixView.es("VERSIÓN", "VERSION"));
        hud.col = Math.min(hud.col, hud.cols.size() - 1);
        hud.row = Math.max(0, Math.min(hud.row, hud.cols.get(hud.col).size() - 1));
        hud.invalidate();
    }

    private static PlayerHud.Item item(String text, String sub, boolean chosen, Runnable pick) {
        PlayerHud.Item it = new PlayerHud.Item();
        it.text = text;
        it.sub = sub;
        it.chosen = chosen;
        it.pick = pick;
        return it;
    }

    /** ← → between Audio, Subtitles and Version (on the chosen one), ↑ ↓ inside, OK picks; ↑ on the first row closes. */
    private void panelKey(boolean ok, boolean up, boolean down, boolean left, boolean right) {
        if (left || right) {
            int to = hud.col + (left ? -1 : 1);
            while (to >= 0 && to < hud.cols.size() && hud.cols.get(to).isEmpty()) to += left ? -1 : 1;
            if (to >= 0 && to < hud.cols.size()) {
                hud.col = to;
                hud.row = chosen(hud.cols.get(to));
            }
        } else if (up && hud.row == 0) {
            showBar(false);
            return;
        } else if (up || down) {
            hud.row = Math.max(0, Math.min(hud.cols.get(hud.col).size() - 1, hud.row + (up ? -1 : 1)));
        } else if (ok && hud.row < hud.cols.get(hud.col).size()) {
            hud.cols.get(hud.col).get(hud.row).pick.run();
        }
        hud.invalidate();
    }

    private void pickTrack(Tracks.Group g, int i, int type) {
        userTracks = true;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(type, false)
                .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), i)).build());
    }

    private void subtitlesOff() {
        userTracks = true;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build());
        panelItems();
    }

    /** Another version, from where you are. */
    private void switchStream(int i) {
        if (i == index) return;
        AppLog.w("player: version " + (i + 1) + " chosen");
        userTracks = false;
        long at = started ? player.getCurrentPosition() : c.startAt;
        index = i - 1;
        next(at);
        panelItems();
    }

    // MARK: - The season's episodes

    /** The show's episodes from Stremio (Cinemeta, or the Kitsu add-on for anime), once per show. */
    private void loadSeries() {
        if (!"series".equals(c.type)) {
            allEps = new ArrayList<>();
            current = nextEp = null;
            return;
        }
        if (c.id.equals(epsFor)) {
            locate();
            return;
        }
        final String id = c.id;
        current = nextEp = null;
        images.execute(() -> {
            List<PlayerHud.Ep> list = new ArrayList<>();
            try {
                JSONArray videos = Streams.meta(id).optJSONArray("videos");
                for (int i = 0; videos != null && i < videos.length(); i++) {
                    JSONObject o = videos.getJSONObject(i);
                    PlayerHud.Ep e = new PlayerHud.Ep();
                    e.id = o.optString("id");
                    e.season = o.optInt("season");
                    e.episode = o.optInt("episode", o.optInt("number"));
                    if (e.season <= 0 || e.id.isEmpty()) continue;   // specials
                    e.name = o.optString("name", o.optString("title", "")).trim();
                    if (e.name.matches("(?i)(episode|episodio)\\s*\\d+")) e.name = FlixView.es("Episodio ", "Episode ") + e.episode;
                    e.label = e.episode + ". " + (e.name.isEmpty() ? FlixView.es("Episodio ", "Episode ") + e.episode : e.name);
                    e.thumb = o.optString("thumbnail", "");
                    list.add(e);
                }
                Collections.sort(list, (a, b) -> a.season != b.season ? a.season - b.season : a.episode - b.episode);
            } catch (Exception e) {
                AppLog.w("player: episodes: " + e.getMessage());
            }
            handler.post(() -> {
                if (c == null || !id.equals(c.id)) return;
                allEps = list;
                epsFor = id;
                locate();
            });
        });
    }

    /** This one in the list, and the one after it. */
    private void locate() {
        current = nextEp = null;
        for (int i = 0; i < allEps.size(); i++) {
            if (!allEps.get(i).id.equals(c.video)) continue;
            current = allEps.get(i);
            nextEp = i + 1 < allEps.size() ? allEps.get(i + 1) : null;
        }
        titles();
        if (hud.mode == PlayerHud.BAR) buttons();
        if (hud.mode == PlayerHud.EPISODES) openEpisodes();
    }

    private void openEpisodes() {
        List<PlayerHud.Ep> season = new ArrayList<>();
        int s = current == null ? 1 : current.season;
        for (PlayerHud.Ep e : allEps) if (e.season == s) season.add(e);
        hud.eps = season;
        hud.currentId = current == null ? "" : current.id;
        hud.ep = Math.max(0, season.indexOf(current));
        hud.seasonLabel = FlixView.es("Temporada ", "Season ") + s;
        for (PlayerHud.Ep e : season) loadImage(e);
        hud.mode = PlayerHud.EPISODES;
        hud.invalidate();
    }

    private void episodesKey(boolean ok, boolean up, boolean left, boolean right) {
        if (up) {
            showBar(false);
            return;
        }
        if (left || right) hud.ep = Math.max(0, Math.min(hud.eps.size() - 1, hud.ep + (left ? -1 : 1)));
        else if (ok && hud.ep < hud.eps.size()) {
            PlayerHud.Ep e = hud.eps.get(hud.ep);
            if (e != current) playEpisode(e);
            else showBar(false);
            return;
        }
        hud.invalidate();
    }

    /** An episode's picture (Stremio's thumbnail), small, in the background. */
    private void loadImage(final PlayerHud.Ep e) {
        if (e == null || e.image != null || e.thumb == null || !e.thumb.startsWith("http")) return;
        images.execute(() -> {
            if (e.image != null) return;
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(e.thumb).openConnection();
                h.setConnectTimeout(8000);
                h.setReadTimeout(10000);
                byte[] bytes;
                try (InputStream in = h.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    bytes = out.toByteArray();
                } finally {
                    h.disconnect();
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                o.inJustDecodeBounds = false;
                o.inSampleSize = 1;
                while (o.outWidth / (o.inSampleSize * 2) >= 400) o.inSampleSize *= 2;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
                handler.post(() -> {
                    e.image = b;
                    hud.invalidate();
                });
            } catch (Exception x) {
                AppLog.w("player: episode picture: " + x.getMessage());
            }
        });
    }

    // MARK: - The next episode

    /** Near the end: the next episode's card, which plays it after COUNTDOWN; its streams are looked up meanwhile. */
    private void showNext() {
        final PlayerHud.Ep e = nextEp;
        hud.mode = PlayerHud.NEXT;
        hud.next = e;
        hud.nextFocus = 0;
        hud.nextTitle = FlixView.es("T", "S") + e.season + " · E" + e.episode + (e.name.isEmpty() ? "" : "  " + e.name);
        hud.nextMeta = "";
        nextAt = SystemClock.uptimeMillis() + COUNTDOWN;
        loadImage(e);
        final String link = "stremio:///detail/series/" + c.id + "/" + e.id;
        final File cache = getContext().getCacheDir();
        final int ask = switchAsk;
        new Thread(() -> {
            final Streams.Choice ch = Streams.best(link, cache);
            handler.post(() -> {
                if (ask != switchAsk || nextEp != e) return;
                nextChoice = ch;
                nextChoiceFor = e;
                hud.nextMeta = ch == null ? FlixView.es("No encontré versiones de este episodio", "No versions found")
                        : ch.latino > 0 ? FlixView.es("Doblaje latino disponible", "Latin American dub available")
                        : FlixView.es("Sin doblaje latino · original con subtítulos", "No Latin American dub · original with subtitles");
                hud.invalidate();
            });
        }).start();
        hud.invalidate();
    }

    private void nextKey(boolean ok, boolean left, boolean right) {
        if (left || right) hud.nextFocus = left ? 0 : 1;
        else if (ok) {
            if (hud.nextFocus == 0) playEpisode(nextEp);
            else dismissNext();
            return;
        }
        hud.invalidate();
    }

    /** "See the credits": the card goes; at the very end the next one still plays. */
    private void dismissNext() {
        nextDismissed = true;
        hud.mode = PlayerHud.NONE;
        hud.invalidate();
    }

    /** Another episode of this show: this one's place is saved, the new one's streams found, and it plays here. */
    private void playEpisode(final PlayerHud.Ep e) {
        if (e == null || c == null) return;
        final String show = name, poster = posterUrl, id = c.id;
        final String epTitle = show + "  ·  " + FlixView.es("T", "S") + e.season + " E" + e.episode;
        final Streams.Choice ready = nextChoiceFor == e ? nextChoice : null;
        AppLog.w("player: on to " + e.id);
        release();   // saves where this one was (near the end: watched, and Stremio moves on to the next)
        final int ask = ++switchAsk;
        // While the next one is looked up: its picture, and the loading card.
        current = e;
        titles();
        picture.setImageBitmap(e.image);
        picture.setAlpha(1f);
        picture.setVisibility(e.image == null ? GONE : VISIBLE);
        subtitle.setText("");
        hud.loading = true;
        hud.loadingFrom = "";
        hud.loadingLines.clear();
        hud.loadingLines.add(FlixView.es("Buscando versiones de este episodio", "Looking for this episode's versions"));
        hud.loadingNow = 0;
        hud.invalidate();
        if (ready != null) {
            play(ready, e.image, epTitle, show, poster);
            return;
        }
        final File cache = getContext().getCacheDir();
        new Thread(() -> {
            final Streams.Choice ch = Streams.best("stremio:///detail/series/" + id + "/" + e.id, cache);
            handler.post(() -> {
                if (ask != switchAsk) return;   // closed, or another one chosen meanwhile
                if (ch == null) {
                    hud.loading = false;
                    hud.invalidate();
                    listener.onDone(true);
                    return;
                }
                play(ch, e.image, epTitle, show, poster);
            });
        }).start();
    }

    // MARK: - Saving your place

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
        final Streams.Choice ch = c.copy();   // a copy: playEpisode moves c on to the next episode
        final String n = name, p = posterUrl;
        saver.execute(() -> Streams.saveProgress(ch, pos, dur, n, p));
    }

    private static String clock(long ms) {
        long s = ms / 1000, h = s / 3600, m = s / 60 % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60)
                : String.format(Locale.US, "%d:%02d", m, s % 60);
    }
}
