package io.freemytv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Free My TV's settings (the app's icon, "All Apps", or the gear on the home screen), in the home screen's style.
 * "Home Screen" switches between Free My TV and the original; the switch runs in the main process (HomeMode) and this
 * screen only asks for it and follows the state file. "Language" changes the app's language without a rebuild.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    /**
     * Two panes, like the home screen: the sections on the left (the focused one in a white pill), and on the right
     * what the section shows, with its choices. Right or OK goes into the choices, Left or Back comes out.
     */
    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int HOME = 0, LANGUAGE = 1, SCREENSAVER = 2, RECS = 3, SPEED = 4, NOTICES = 5, TV = 6, SECTIONS = 7;
        private static final float NAV_X = 120, NAV_W = 560, NAV_TOP = 360, NAV_H = 64, NAV_GAP = 10;
        private static final float PANE_X = 820, PANE_R = 1800, PANE_TOP = 360, OPTION_H = 104, OPTION_GAP = 14;
        private static final int AMBER = 0xFFFFC266, DIM = 0xFFB4B4BC, FAINT = 0xFF8E8E96;

        private final SettingsActivity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] navPop = new Spring[SECTIONS], optionPop = new Spring[3];
        private final RectF r = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int section, option = -1;     // option -1: focus on the sections
        private boolean running, okDown, backDown;
        private long startedAt, lastNanos, pendingAt;
        private int w, h;
        private float k = 1;
        private Paint title, caption, navOn, navOff, navFocus, navValue, heading, body, small, optionLabel, optionSub,
                header, cellLabel, cellValue, warn, spinner, fill;
        private TextPaint wrap;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                if (!state.optString("busy").isEmpty()) pendingAt = 0;
                invalidate();
                handler.postDelayed(this, 500);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < navPop.length; i++) navPop[i] = new Spring(0.28f, 0.85f, 0);
            for (int i = 0; i < optionPop.length; i++) optionPop[i] = new Spring(0.28f, 0.85f, 0);
            setFocusable(true);
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy();
            for (Spring s : navPop) moving |= !s.settled();
            for (Spring s : optionPop) moving |= !s.settled();
            if (moving) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            title = Ui.text(Ui.displaySemibold, 56 * k, 0xFFFFFFFF);
            caption = Ui.text(Ui.regular, 24 * k, FAINT);
            navOn = Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF);
            navOff = Ui.text(Ui.semibold, 28 * k, DIM);
            navFocus = Ui.text(Ui.semibold, 28 * k, 0xFF000000);
            navValue = Ui.text(Ui.regular, 24 * k, FAINT);
            navValue.setTextAlign(Paint.Align.RIGHT);
            heading = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
            body = Ui.text(Ui.regular, 26 * k, DIM);
            wrap = new TextPaint(body);
            small = Ui.text(Ui.regular, 22 * k, FAINT);
            optionLabel = Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF);
            optionSub = Ui.text(Ui.regular, 23 * k, FAINT);
            header = Ui.text(Ui.semibold, 21 * k, FAINT);
            header.setLetterSpacing(0.08f);
            cellLabel = Ui.text(Ui.regular, 26 * k, 0xFFE4E4E7);
            cellValue = Ui.numbers(Ui.regular, 26 * k, 0xFFE4E4E7);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 24 * k, AMBER);
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        }

        private boolean busy() {
            return !state.optString("busy").isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        /** How many choices the section has on the right (0: none, OK on it does what it says). */
        private int options(int s) {
            return s == HOME ? 2 : s == LANGUAGE ? Ui.LANGUAGES.length : s == SCREENSAVER ? 1 : 0;
        }

        private String sectionName(int s) {
            switch (s) {
                case HOME: return Ui.t("Inicio", "Home Screen", "Início");
                case LANGUAGE: return Ui.t("Idioma", "Language", "Idioma");
                case SCREENSAVER: return Ui.t("Protector de pantalla", "Screensaver", "Tela de descanso");
                case RECS: return Ui.t("Recomendaciones", "Recommendations", "Recomendações");
                case SPEED: return Ui.t("Rendimiento", "Performance", "Desempenho");
                case NOTICES: return Ui.t("Avisos", "Notices", "Avisos");
                default: return Ui.t("Ajustes de la TV", "TV Settings", "Ajustes da TV");
            }
        }

        /** The section's current value, in grey at the right of its pill ("Free My TV", "Español"). */
        private String sectionValue(int s) {
            if (s == HOME) return busy() ? "" : freeActive() ? "Free My TV" : "Original";
            if (s == LANGUAGE) return Ui.languageName(Ui.LANG);
            return "";
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < navPop.length; i++) {
                navPop[i].target = i == section ? (option < 0 ? 1 : 0.5f) : 0;
                navPop[i].step(dt);
            }
            for (int i = 0; i < optionPop.length; i++) {
                optionPop[i].target = i == option ? 1 : 0;
                optionPop[i].step(dt);
            }
            float seconds = (SystemClock.uptimeMillis() - startedAt) / 1000f;
            c.drawColor(0xFF000000);

            // Top left: the title, the app and what it is.
            float x = NAV_X * k;
            c.drawText(Ui.t("Ajustes", "Settings", "Ajustes"), x + 24 * k, 228 * k, title);
            c.drawText(versionName(getContext()) + "  ·  " + Ui.t("Inicio y protector de pantalla de la TV",
                    "Your TV's home screen and screensaver", "Início e tela de descanso da TV"), x + 24 * k, 276 * k, caption);

            drawNav(c);
            fill.setColor(0x1FFFFFFF);
            c.drawRect((PANE_X - 60) * k, NAV_TOP * k, (PANE_X - 58) * k, (NAV_TOP + SECTIONS * (NAV_H + NAV_GAP)) * k, fill);
            drawPane(c, seconds);
        }

        /** The sections: focused in a white pill; the one you're in (focus on its choices) in a grey one. */
        private void drawNav(Canvas c) {
            for (int i = 0; i < SECTIONS; i++) {
                float top = (NAV_TOP + i * (NAV_H + NAV_GAP)) * k;
                r.set(NAV_X * k, top, (NAV_X + NAV_W) * k, top + NAV_H * k);
                float f = Ui.clamp(navPop[i].value);
                boolean white = i == section && option < 0;
                if (f > 0.01f) {
                    fill.setColor(white ? Ui.alpha(0xFFFFFFFF, f) : Ui.alpha(0xFFFFFFFF, 0.14f * Math.min(1, f * 2)));
                    c.drawRoundRect(r, NAV_H / 2 * k, NAV_H / 2 * k, fill);
                }
                Paint tp = white && f > 0.5f ? navFocus : i == section ? navOn : navOff;
                float base = r.centerY() + Ui.halfCap(tp);
                c.drawText(sectionName(i), r.left + 32 * k, base, tp);
                String value = sectionValue(i);
                float right = r.right - 32 * k;
                if (i == TV) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, r.centerY(), 22 * k, white && f > 0.5f ? 0x99000000 : FAINT);
                } else if (!value.isEmpty()) {
                    navValue.setColor(white && f > 0.5f ? 0x99000000 : FAINT);
                    c.drawText(value, right, r.centerY() + Ui.halfCap(navValue), navValue);
                }
            }
        }

        private void drawPane(Canvas c, float seconds) {
            float x = PANE_X * k, y = (PANE_TOP + 40) * k;
            c.drawText(sectionName(section), x, y, heading);
            y += 26 * k;
            String about;
            switch (section) {
                case HOME: about = Ui.t("Elige el inicio de la TV. Original lo deja todo como venía de fábrica.",
                        "Pick the TV's home screen. Original puts everything back the way it came.",
                        "Escolha a tela inicial da TV. Original deixa tudo como veio de fábrica."); break;
                case LANGUAGE: about = Ui.t("La app, los títulos de TMDB, el doblaje y los subtítulos que elige el reproductor.",
                        "The app, TMDB's titles, and the dubs and subtitles the player picks.",
                        "O app, os títulos do TMDB e a dublagem e as legendas que o player escolhe."); break;
                case SCREENSAVER: about = Ui.t("Fotos a pantalla completa de las recomendaciones con un reloj grande.",
                        "Full-screen photos of the picks with a big clock.",
                        "Fotos em tela cheia das sugestões com um relógio grande."); break;
                case RECS: about = Ui.t("Lo que hay en tendencia y lo nuevo en tus apps, de TMDB, cada hora.",
                        "What's trending and new on your apps, from TMDB, every hour.",
                        "O que está em alta e as novidades nos seus apps, do TMDB, a cada hora."); break;
                case SPEED: about = Ui.t("Medido en esta TV al cambiar de inicio.", "Measured on this TV when switching.",
                        "Medido nesta TV ao trocar a tela inicial."); break;
                case NOTICES: about = Ui.t("Lo último que hizo Free My TV.", "What Free My TV did last.",
                        "O que o Free My TV fez por último."); break;
                default: about = Ui.t("Imagen, sonido, red, cuentas y apps: los ajustes de siempre de la TV. OK para abrirlos.",
                        "Picture, sound, network, accounts and apps: the TV's own settings. OK to open them.",
                        "Imagem, som, rede, contas e apps: os ajustes de sempre da TV. OK para abrir.");
            }
            y = drawWrapped(c, about, x, y + 20 * k) + (options(section) > 0 ? 40 : 70) * k;

            switch (section) {
                case HOME: drawHome(c, x, y, seconds); break;
                case LANGUAGE: drawLanguage(c, x, y); break;
                case SCREENSAVER:
                    drawOption(c, 0, x, y, Ui.t("Ver ahora", "Preview", "Ver agora"),
                            Ui.t("Cualquier tecla lo cierra.", "Any key closes it.", "Qualquer tecla fecha."), false, false);
                    break;
                case RECS: drawRecs(c, x, y); break;
                case SPEED: drawComparison(c, x, y); break;
                case NOTICES: drawNotices(c, x, y, 6); break;
                default: break;
            }
        }

        private float drawWrapped(Canvas c, String text, float x, float y) {
            android.text.StaticLayout l = android.text.StaticLayout.Builder.obtain(text, 0, text.length(), wrap,
                    Math.round((PANE_R - PANE_X) * k)).setLineSpacing(6 * k, 1).build();
            c.save();
            c.translate(x, y);
            l.draw(c);
            c.restore();
            return y + l.getHeight();
        }

        /** A choice on the right: a dark card, white when focused, with a check on the one in use. */
        private void drawOption(Canvas c, int i, float x, float y, String label, String sub, boolean current, boolean dim) {
            r.set(x, y + i * (OPTION_H + OPTION_GAP) * k, PANE_R * k, y + (i * (OPTION_H + OPTION_GAP) + OPTION_H) * k);
            float f = Ui.clamp(optionPop[i].value);
            fill.setColor(blend(0xFF161618, 0xFFFFFFFF, f));
            c.drawRoundRect(r, 20 * k, 20 * k, fill);
            optionLabel.setColor(blend(dim ? FAINT : 0xFFFFFFFF, 0xFF000000, f));
            optionSub.setColor(blend(FAINT, 0x99000000, f));
            boolean hasSub = sub != null && !sub.isEmpty();
            c.drawText(label, r.left + 32 * k, hasSub ? r.top + 44 * k : r.centerY() + Ui.halfCap(optionLabel), optionLabel);
            if (hasSub) {
                c.drawText(TextUtils.ellipsize(sub, new TextPaint(optionSub), r.width() - 130 * k, TextUtils.TruncateAt.END).toString(),
                        r.left + 32 * k, r.top + 80 * k, optionSub);
            }
            if (current) {
                float cw = Symbols.width(getContext(), "check", 28 * k);
                Symbols.draw(getContext(), c, "check", r.right - 36 * k - cw / 2, r.centerY(), 28 * k, blend(0xFFFFFFFF, 0xFF000000, f));
            }
        }

        private void drawHome(Canvas c, float x, float y, float seconds) {
            boolean working = busy();
            int current = freeActive() ? 0 : 1;
            drawOption(c, 0, x, y, "Free My TV", Ui.t("Sin anuncios, con el protector de pantalla nuevo",
                    "No ads, with the new screensaver", "Sem anúncios, com a tela de descanso nova"), !working && current == 0, working);
            drawOption(c, 1, x, y, "Original", Ui.t("El inicio de fábrica de la TV",
                    "The TV's factory home screen", "O Início de fábrica da TV"), !working && current == 1, working);
            float line = y + (2 * (OPTION_H + OPTION_GAP) + 30) * k;
            String busyText = state.optString("busy"), error = state.optString("error");
            if (working) {
                // Switching: the current step, with the spinning arc.
                String text = busyText.isEmpty() ? Ui.t("Empezando…", "Starting…", "Começando…") : busyText;
                spinner.setColor(0xFFFFFFFF);
                Ui.spinner(c, x + 12 * k, line - Ui.halfCap(body), 12 * k, seconds * 1.3f, spinner);
                c.drawText(TextUtils.ellipsize(text, wrap, (PANE_R - PANE_X - 50) * k, TextUtils.TruncateAt.END).toString(),
                        x + 44 * k, line, body);
                if (busyText.contains("Permitir") || busyText.contains("Allow")) {
                    c.drawText(Ui.t("En la TV: marca “Permitir siempre” y pulsa OK.",
                            "On the TV: tick “Always allow” and press OK.", "Na TV: marque “Sempre permitir” e aperte OK."),
                            x, line + 44 * k, warn);
                }
            } else if (!error.isEmpty()) {
                c.drawText(TextUtils.ellipsize(error, new TextPaint(warn), (PANE_R - PANE_X) * k, TextUtils.TruncateAt.END).toString(), x, line, warn);
            } else {
                c.drawText(Ui.t("El cambio tarda unos segundos y la pantalla parpadea al medir.",
                        "Switching takes a few seconds; the screen blinks while it measures.",
                        "A troca leva uns segundos e a tela pisca nas medições."), x, line, small);
            }
        }

        private void drawLanguage(Canvas c, float x, float y) {
            for (int i = 0; i < Ui.LANGUAGES.length; i++) {
                String l = Ui.LANGUAGES[i];
                String sub = l.equals("es") ? "Doblaje latino primero" : l.equals("pt") ? "Dublagem do Brasil primeiro" : "Original audio";
                drawOption(c, i, x, y, Ui.languageName(l), sub, l.equals(Ui.LANG), false);
            }
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private void drawRecs(Canvas c, float x, float y) {
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.t("Sin llave de TMDB: mira el README (config.properties).",
                    "No TMDB key: see the README (config.properties).",
                    "Sem chave do TMDB: veja o README (config.properties).");
            else if (!error.isEmpty()) line = Ui.t("Error: ", "Error: ", "Erro: ") + error + ".";
            else if (count > 0) line = Ui.t("Encendidas · " + count + " títulos de las apps de la TV, 3 nuevos cada hora.",
                    "On · " + count + " titles from the TV's apps, 3 new ones every hour.",
                    "Ligadas · " + count + " títulos nos apps da TV, 3 novos a cada hora.");
            else line = Ui.t("Buscando los títulos…", "Fetching titles…", "Buscando os títulos…");
            c.drawText(Ui.t("ESTADO", "STATUS", "ESTADO"), x, y, header);
            c.drawText(TextUtils.ellipsize(line, new TextPaint(cellLabel), (PANE_R - PANE_X) * k, TextUtils.TruncateAt.END).toString(),
                    x, y + 46 * k, error.isEmpty() && hasKey ? cellLabel : warn);
            c.drawText(Ui.t("Usa la API de TMDB, sin aval ni certificación de TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB.",
                    "Usa a API do TMDB, sem endosso ou certificação do TMDB."), x, y + 100 * k, small);
        }

        private void drawComparison(Canvas c, float x, float y) {
            float colA = PANE_R * k - 260 * k, colB = PANE_R * k;
            c.drawText(Ui.t("MEDIDA", "MEASURE", "MEDIDA"), x, y, header);
            header.setTextAlign(Paint.Align.RIGHT);
            c.drawText("ORIGINAL", colA, y, header);
            c.drawText("FREE MY TV", colB, y, header);
            header.setTextAlign(Paint.Align.LEFT);
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.t("Botón de inicio", "Home button", "Botão Início"), "homeMs"},
                    {Ui.t("Memoria libre", "Free memory", "Memória livre"), "memAvailableMB"},
                    {Ui.t("Memoria del inicio", "Home screen memory", "Memória do Início"), "homePssMB"}};
            fill.setColor(0x1FFFFFFF);
            for (int i = 0; i < rows.length; i++) {
                float base = y + (60 + 58 * i) * k;
                c.drawRect(x, base - 40 * k, colB, base - 39 * k, fill);
                c.drawText(rows[i][0], x, base, cellLabel);
                c.drawText(metric(a, rows[i][1]), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1]), colB, base, cellValue);
            }
        }

        private void drawNotices(Canvas c, float x, float y, int max) {
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            TextPaint tp = new TextPaint(cellLabel);
            for (int i = 0; notices != null && i < notices.length() && shown < max; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                float base = y + shown * 84 * k;
                c.drawText(ago(o.optLong("at")).toUpperCase(Ui.LOCALE), x, base, header);
                c.drawText(TextUtils.ellipsize(o.optString("text"), tp, (PANE_R - PANE_X) * k, TextUtils.TruncateAt.END).toString(),
                        x, base + 34 * k, cellLabel);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.t("Ninguno todavía.", "None yet.", "Nenhum ainda."), x, y + 10 * k, body);
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return v + " ms";
            if (key.equals("memAvailableMB") && v >= 1024) return String.format(Ui.LOCALE, "%.2f GB", v / 1024.0);
            return v + " MB";
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.t("ahora", "now", "agora");
            if (minutes < 60) return Ui.t("hace " + minutes + " min", minutes + " min ago", "há " + minutes + " min");
            if (minutes < 48 * 60) return Ui.t("hace " + (minutes / 60) + " h",
                    (minutes / 60) + " h ago", "há " + (minutes / 60) + " h");
            return Ui.t("hace " + (minutes / 1440) + " días",
                    (minutes / 1440) + " days ago", "há " + (minutes / 1440) + " dias");
        }

        private static String versionName(Context c) {
            try {
                return "Free My TV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Free My TV";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && option > 0) option--;
                    else if (down && option < 0 && section > 0) section--;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && option >= 0 && option < options(section) - 1) option++;
                    else if (down && option < 0 && section < SECTIONS - 1) section++;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && option < 0) enter();
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (down) option = -1;
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (option < 0) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) option = -1;
                        backDown = false;
                    }
                    invalidate();
                    return true;
                default:
                    return false;
            }
        }

        /** Into the section's choices, on the one in use. */
        private void enter() {
            if (options(section) == 0 || (section == HOME && busy())) return;
            if (section == HOME) option = freeActive() ? 0 : 1;
            else if (section == LANGUAGE) option = java.util.Arrays.asList(Ui.LANGUAGES).indexOf(Ui.LANG);
            else option = 0;
            for (Spring s : optionPop) s.snap(0);
        }

        private void activate() {
            Context c = getContext();
            if (option < 0) {
                if (section == TV) activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                else enter();
            } else if (section == HOME) {
                boolean wantFree = option == 0;
                if (wantFree != freeActive() && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                option = -1;
            } else if (section == LANGUAGE) {
                Ui.pick(c, Ui.LANGUAGES[option]);   // this screen redraws in it; the home screen on its way back
            } else if (section == SCREENSAVER) {
                option = -1;
                pause();
                activity.showPreview();
            }
            invalidate();
        }
    }
}
