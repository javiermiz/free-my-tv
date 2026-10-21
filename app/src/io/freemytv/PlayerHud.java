package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * What shows over the video full screen, drawn on one Canvas in a 1920×1080 space: the bar (where it comes from,
 * the title, the progress, the buttons), the Audio and subtitles panel, the season's episodes, the next episode at
 * the credits, and what's being tried while it loads. Playback holds the player and fills these fields; this only
 * draws them (texts are made before, never while drawing).
 */
final class PlayerHud extends View {
    static final int NONE = 0, BAR = 1, PANEL = 2, EPISODES = 3, NEXT = 4;
    static final int B_BACK = 0, B_PLAY = 1, B_FWD = 2, B_AUDIO = 3, B_EPISODES = 4, B_NEXT = 5;
    static final int ACCENT = 0xFFF5A524;
    private static final String[] COUNT = new String[61];   // the countdown's numbers, made once
    static {
        for (int i = 0; i < COUNT.length; i++) COUNT[i] = String.valueOf(i);
    }

    /** Something to choose in the Audio and subtitles panel. */
    static final class Item {
        String text, sub = "";
        boolean chosen;
        Runnable pick;
    }

    /** An episode of the season (Stremio's video id, its number, name and picture). */
    static final class Ep {
        String id, name, thumb, label;
        int season, episode;
        Bitmap image;
    }

    int mode;
    boolean loading, playing, barFocused = true;   // barFocused: ← → move through the video; else through the buttons
    String kicker = "", title = "", source = "", version = "", clock = "", elapsed = "", left = "";
    float progress, buffered;
    final List<Integer> buttons = new ArrayList<>();
    int button = 1;
    // The panel: three columns (Audio, Subtitles, Version); ← → between them, ↑ ↓ inside one.
    final List<List<Item>> cols = new ArrayList<>();
    final List<String> colTitles = new ArrayList<>();
    int col, row;
    List<Ep> eps = new ArrayList<>();
    String seasonLabel = "", currentId = "";
    int ep;
    // At the credits: the next one, and the seconds before it plays by itself.
    Ep next;
    String nextTitle = "", nextMeta = "";
    int countdown, nextFocus;
    float countdownPart;
    // While it loads: what's tried.
    String loadingFrom = "";
    final List<String> loadingLines = new ArrayList<>();
    int loadingNow = -1;

    private float k = 1;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint kickerP, titleP, smallP, smallStrong, buttonP, panelTitleP, headerP, itemP, epP, epMetaP, bigP, loadP, loadDim;
    private Shader topShade, bottomShade, sideShade;
    private Paint countP, jumpP;
    private final RectF r = new RectF(), o = new RectF(), arc = new RectF();
    private final Rect src = new Rect();
    private final Path path = new Path();

    PlayerHud(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = w / 1920f;
        kickerP = Ui.text(Ui.semibold, 24 * k, ACCENT);
        kickerP.setLetterSpacing(0.08f);
        titleP = Ui.text(Ui.semibold, 52 * k, 0xFFFFFFFF);
        smallP = Ui.text(Ui.regular, 22 * k, 0xFFD4D4D8);
        smallStrong = Ui.text(Ui.semibold, 22 * k, 0xFFFFFFFF);
        buttonP = Ui.text(Ui.semibold, 24 * k, 0xFFFFFFFF);
        panelTitleP = Ui.text(Ui.semibold, 34 * k, 0xFFFFFFFF);
        headerP = Ui.text(Ui.semibold, 20 * k, 0xFFA1A1AA);
        headerP.setLetterSpacing(0.1f);
        itemP = Ui.text(Ui.medium, 24 * k, 0xFFFFFFFF);
        epP = Ui.text(Ui.semibold, 20 * k, 0xFFFFFFFF);
        epMetaP = Ui.text(Ui.regular, 18 * k, 0xFFA1A1AA);
        bigP = Ui.text(Ui.semibold, 48 * k, 0xFFFFFFFF);
        loadP = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
        loadDim = Ui.text(Ui.regular, 24 * k, 0xFFA1A1AA);
        countP = Ui.text(Ui.semibold, 16 * k, 0xFFFFFFFF);
        jumpP = Ui.text(Ui.semibold, 15 * 0.85f * k, 0xFFFFFFFF);
        topShade = new LinearGradient(0, 0, 0, 200 * k, 0xB3000000, 0, Shader.TileMode.CLAMP);
        bottomShade = new LinearGradient(0, 480 * k, 0, h, 0, 0xEB000000, Shader.TileMode.CLAMP);
        sideShade = new LinearGradient(860 * k, 0, 1200 * k, 0, 0, 0xE6000000, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas c) {
        if (kickerP == null) return;
        if (loading) drawLoading(c);
        if (mode == NONE) return;
        if (mode == NEXT) {
            drawNext(c);
            return;
        }
        shade(c, topShade, 0, 0, getWidth(), 200 * k);
        shade(c, bottomShade, 0, 480 * k, getWidth(), getHeight());
        drawTop(c);
        if (mode == EPISODES) drawEpisodes(c);
        else if (mode == PANEL) drawPanel(c);
        else drawBar(c);
    }

    private void shade(Canvas c, Shader s, float l, float t, float rr, float b) {
        fill.setColor(0xFF000000);   // opaque: a leftover alpha (the buttons') would fade the shade
        fill.setShader(s);
        c.drawRect(l, t, rr, b, fill);
        fill.setShader(null);
    }

    private float x(float v) { return v * k; }

    // MARK: - The bar

    private void drawTop(Canvas c) {
        float cy = x(80);
        float w = smallStrong.measureText(source) + x(24);
        fill.setColor(0x24FFFFFF);
        r.set(x(80), cy - x(20), x(80) + w, cy + x(20));
        c.drawRoundRect(r, x(8), x(8), fill);
        c.drawText(source, x(92), cy + Ui.halfCap(smallStrong), smallStrong);
        c.drawText(version, x(80) + w + x(16), cy + Ui.halfCap(smallP), smallP);
        c.drawText(clock, x(1840) - smallP.measureText(clock), cy + Ui.halfCap(smallP), smallP);
    }

    private void drawBar(Canvas c) {
        if (!kicker.isEmpty()) c.drawText(kicker, x(80), x(752), kickerP);
        c.drawText(title, x(80), x(816), titleP);
        // Progress: what's loaded, where you are, and the knob (bigger when ← → move it).
        float l = x(80), rr = x(1840), y = x(872), h = x(10);
        fill.setColor(0x40FFFFFF);
        r.set(l, y - h / 2, rr, y + h / 2);
        c.drawRoundRect(r, h / 2, h / 2, fill);
        fill.setColor(0x73FFFFFF);
        r.set(l, y - h / 2, l + (rr - l) * buffered, y + h / 2);
        c.drawRoundRect(r, h / 2, h / 2, fill);
        fill.setColor(ACCENT);
        float at = l + (rr - l) * progress;
        r.set(l, y - h / 2, at, y + h / 2);
        c.drawRoundRect(r, h / 2, h / 2, fill);
        if (barFocused) {
            fill.setColor(0x40FFFFFF);
            c.drawCircle(at, y, x(20), fill);
        }
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(at, y, x(barFocused ? 14 : 9), fill);
        c.drawText(elapsed, l, x(918), smallP);
        c.drawText(left, rr - smallP.measureText(left), x(918), smallP);
        // Buttons: −10, play/pause, +10 on the left; the others on the right.
        float cy = x(990), right = x(1840);
        float bx = x(80);
        for (int i = 0; i < buttons.size(); i++) {
            int b = buttons.get(i);
            boolean focus = !barFocused && i == button;
            if (b <= B_FWD) {
                float rad = x(b == B_PLAY ? 52 : 36);
                float cx = bx + rad;
                if (focus) {
                    fill.setColor(0x38FFFFFF);
                    c.drawCircle(cx, cy, rad + x(8), fill);
                }
                fill.setColor(focus ? 0xFFFFFFFF : 0x24FFFFFF);
                c.drawCircle(cx, cy, rad, fill);
                int ink = focus ? 0xFF0B0B0F : 0xFFFFFFFF;
                if (b == B_PLAY) playIcon(c, cx, cy, x(22), ink, playing);
                else jumpIcon(c, cx, cy, x(15), ink, b == B_FWD);
                bx = cx + rad + x(20);
            }
        }
        for (int i = buttons.size() - 1; i >= 0; i--) {
            int b = buttons.get(i);
            if (b <= B_FWD) continue;
            boolean focus = !barFocused && i == button;
            String label = label(b);
            float w = x(26) + x(28) + x(12) + buttonP.measureText(label) + x(26);
            r.set(right - w, cy - x(32), right, cy + x(32));
            fill.setColor(focus ? 0xFFFFFFFF : 0x24FFFFFF);
            c.drawRoundRect(r, x(32), x(32), fill);
            float pillLeft = r.left;   // the icons below reuse r
            int ink = focus ? 0xFF0B0B0F : 0xFFFFFFFF;
            float ix = pillLeft + x(26) + x(14);
            if (b == B_AUDIO) subtitlesIcon(c, ix, cy, x(14), ink);
            else if (b == B_EPISODES) episodesIcon(c, ix, cy, x(14), ink);
            else nextIcon(c, ix, cy, x(12), ink);
            buttonP.setColor(ink);
            c.drawText(label, ix + x(14) + x(12), cy + Ui.halfCap(buttonP), buttonP);
            buttonP.setColor(0xFFFFFFFF);
            right = pillLeft - x(16);
        }
    }

    static String label(int b) {
        return b == B_AUDIO ? FlixView.es("Audio y subtítulos", "Audio & subtitles")
                : b == B_EPISODES ? FlixView.es("Episodios", "Episodes") : FlixView.es("Siguiente", "Next");
    }

    // MARK: - Audio and subtitles

    private void drawPanel(Canvas c) {
        float top = x(500);
        fill.setColor(0xF2121418);
        c.drawRect(0, top, getWidth(), getHeight(), fill);
        c.drawText(FlixView.es("Audio y subtítulos", "Audio & subtitles"), x(80), top + x(70), panelTitleP);
        float colW = x(560), gap = x(48), rowH = x(60), listTop = top + x(150);
        int visible = 6;
        for (int ci = 0; ci < cols.size(); ci++) {
            List<Item> list = cols.get(ci);
            float l = x(80) + ci * (colW + gap);
            c.drawText(colTitles.get(ci), l + x(16), top + x(126), headerP);
            int focusRow = ci == col ? row : chosenRow(list);
            int first = Math.max(0, Math.min(focusRow - visible + 2, list.size() - visible));
            if (first > 0) c.drawText("…", l + x(16), listTop - x(4), headerP);
            for (int i = first; i < list.size() && i < first + visible; i++) {
                Item it = list.get(i);
                boolean focus = ci == col && i == row;
                float y = listTop + (i - first) * (rowH + x(8));
                r.set(l, y, l + colW, y + rowH);
                if (focus || it.chosen) {
                    fill.setColor(focus ? 0xFFFFFFFF : 0x1FFFFFFF);
                    c.drawRoundRect(r, x(14), x(14), fill);
                }
                float right = r.right - x(18);
                if (it.chosen) {
                    checkIcon(c, right - x(12), r.centerY(), x(11), focus ? 0xFF0B0B0F : ACCENT);
                    right -= x(36);
                }
                if (!it.sub.isEmpty()) {
                    epMetaP.setColor(focus ? 0xFF3F3F46 : 0xFFA1A1AA);
                    float sw = epMetaP.measureText(it.sub);
                    c.drawText(it.sub, right - sw, r.centerY() + Ui.halfCap(epMetaP), epMetaP);
                    epMetaP.setColor(0xFFA1A1AA);
                    right -= sw + x(16);
                }
                itemP.setColor(focus ? 0xFF0B0B0F : it.chosen ? 0xFFFFFFFF : 0xFFD4D4D8);
                c.drawText(ellipsize(it.text, itemP, right - r.left - x(20)), r.left + x(20), r.centerY() + Ui.halfCap(itemP), itemP);
                itemP.setColor(0xFFFFFFFF);
            }
            if (first + visible < list.size()) c.drawText("…", l + x(16), listTop + visible * (rowH + x(8)) + x(24), headerP);
        }
    }

    private static int chosenRow(List<Item> list) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).chosen) return i;
        return 0;
    }

    // MARK: - The season's episodes

    private void drawEpisodes(Canvas c) {
        float y = x(700);
        float w = smallStrong.measureText(seasonLabel) + x(40);
        r.set(x(80), y, x(80) + w, y + x(44));
        fill.setColor(0xFFFFFFFF);
        c.drawRoundRect(r, x(22), x(22), fill);
        smallStrong.setColor(0xFF0B0B0F);
        c.drawText(seasonLabel, x(100), r.centerY() + Ui.halfCap(smallStrong), smallStrong);
        smallStrong.setColor(0xFFFFFFFF);
        if (eps.isEmpty()) {
            c.drawText(FlixView.es("Cargando episodios…", "Loading episodes…"), x(80), x(840), smallP);
            return;
        }
        float cw = x(282), ch = x(159), gap = x(20), top = x(776);
        int visible = 6;
        int first = Math.max(0, Math.min(ep - 2, eps.size() - visible));
        for (int i = first; i < eps.size() && i < first + visible; i++) {
            Ep e = eps.get(i);
            float l = x(80) + (i - first) * (cw + gap);
            r.set(l, top, l + cw, top + ch);
            if (i == ep) {
                stroke.setColor(0xFFFFFFFF);
                stroke.setStrokeWidth(x(4));
                o.set(r);
                o.inset(-x(8), -x(8));
                c.drawRoundRect(o, x(18), x(18), stroke);
            }
            c.save();
            path.reset();
            path.addRoundRect(r, x(14), x(14), Path.Direction.CW);
            c.clipPath(path);
            fill.setColor(0xFF2F3842);
            c.drawRect(r, fill);
            if (e.image != null) {
                src.set(0, 0, e.image.getWidth(), e.image.getHeight());
                c.drawBitmap(e.image, src, r, fill);
            }
            if (e.id.equals(currentId)) {
                fill.setColor(ACCENT);
                c.drawRect(r.left, r.bottom - x(6), r.right, r.bottom, fill);
            }
            c.restore();
            c.drawText(ellipsize(e.label, epP, cw), l, top + ch + x(36), epP);
            c.drawText(e.id.equals(currentId) ? FlixView.es("Viendo", "Watching") : "", l, top + ch + x(64), epMetaP);
        }
    }

    private static String ellipsize(String s, Paint p, float w) {
        if (p.measureText(s) <= w) return s;
        int n = p.breakText(s, true, w - p.measureText("…"), null);
        return s.substring(0, Math.max(0, n)) + "…";
    }

    // MARK: - At the credits: the next episode

    private void drawNext(Canvas c) {
        shade(c, sideShade, 860 * k, 0, getWidth(), getHeight());
        fill.setColor(0xD9000000);
        c.drawRect(x(1200), 0, getWidth(), getHeight(), fill);
        float l = x(1040);
        kickerP.setLetterSpacing(0.12f);
        c.drawText(FlixView.es("SIGUIENTE EPISODIO", "NEXT EPISODE"), l, x(140), kickerP);
        kickerP.setLetterSpacing(0.08f);
        r.set(l, x(170), l + x(800), x(620));
        c.save();
        path.reset();
        path.addRoundRect(r, x(20), x(20), Path.Direction.CW);
        c.clipPath(path);
        fill.setColor(0xFF2F3842);
        c.drawRect(r, fill);
        if (next != null && next.image != null) {
            src.set(0, 0, next.image.getWidth(), next.image.getHeight());
            c.drawBitmap(next.image, src, r, fill);
        }
        c.restore();
        c.drawText(nextTitle, l, x(690), bigP);
        c.drawText(nextMeta, l, x(736), smallP);
        // Watch now (with the seconds left on a ring), See the credits.
        float cy = x(820);
        String now = FlixView.es("Ver ahora", "Watch now"), credits = FlixView.es("Ver créditos", "See credits");
        float w1 = x(36) + x(40) + x(16) + buttonP.measureText(now) + x(36);
        r.set(l, cy - x(38), l + w1, cy + x(38));
        boolean f0 = nextFocus == 0;
        if (f0) {
            fill.setColor(0x38FFFFFF);
            o.set(r);
            o.inset(-x(8), -x(8));
            c.drawRoundRect(o, x(46), x(46), fill);
        }
        fill.setColor(f0 ? 0xFFFFFFFF : 0x24FFFFFF);
        c.drawRoundRect(r, x(38), x(38), fill);
        int ink = f0 ? 0xFF0B0B0F : 0xFFFFFFFF;
        float rx = r.left + x(36) + x(20);
        stroke.setStrokeWidth(x(4));
        stroke.setColor(f0 ? 0xFFD4D4D8 : 0x66FFFFFF);
        c.drawCircle(rx, cy, x(17), stroke);
        stroke.setColor(ink);
        arc.set(rx - x(17), cy - x(17), rx + x(17), cy + x(17));
        c.drawArc(arc, -90, 360 * countdownPart, false, stroke);
        countP.setColor(ink);
        String secs = COUNT[Math.max(0, Math.min(COUNT.length - 1, countdown))];
        c.drawText(secs, rx - countP.measureText(secs) / 2, cy + Ui.halfCap(countP), countP);
        buttonP.setColor(ink);
        c.drawText(now, rx + x(20) + x(16), cy + Ui.halfCap(buttonP), buttonP);
        buttonP.setColor(0xFFFFFFFF);
        float w2 = x(32) * 2 + buttonP.measureText(credits);
        boolean f1 = nextFocus == 1;
        r.set(l + w1 + x(16), cy - x(38), l + w1 + x(16) + w2, cy + x(38));
        fill.setColor(f1 ? 0xFFFFFFFF : 0x24FFFFFF);
        c.drawRoundRect(r, x(38), x(38), fill);
        buttonP.setColor(f1 ? 0xFF0B0B0F : 0xFFFFFFFF);
        c.drawText(credits, r.left + x(32), cy + Ui.halfCap(buttonP), buttonP);
        buttonP.setColor(0xFFFFFFFF);
    }

    // MARK: - While it loads

    private void drawLoading(Canvas c) {
        float l = x(120), top = x(380);
        if (!kicker.isEmpty()) c.drawText(kicker, l, top, kickerP);
        c.drawText(title, l, top + x(66), titleP);
        if (!loadingFrom.isEmpty()) c.drawText(loadingFrom, l, top + x(112), smallP);
        float boxTop = top + x(150), rowH = x(44);
        r.set(l, boxTop, l + x(820), boxTop + x(100) + rowH * loadingLines.size());
        fill.setColor(0xE0121418);
        c.drawRoundRect(r, x(24), x(24), fill);
        stroke.setStrokeWidth(x(4));
        stroke.setColor(ACCENT);
        float sx = l + x(48), sy = boxTop + x(52);
        float t = (android.os.SystemClock.uptimeMillis() % 1000) / 1000f;
        arc.set(sx - x(14), sy - x(14), sx + x(14), sy + x(14));
        c.drawArc(arc, t * 360, 270, false, stroke);
        c.drawText(FlixView.es("Buscando la mejor versión en español", "Finding the best version"), sx + x(34), sy + Ui.halfCap(loadP), loadP);
        for (int i = 0; i < loadingLines.size(); i++) {
            float y = boxTop + x(100) + rowH * i;
            boolean now = i == loadingNow, done = i < loadingNow;
            fill.setColor(now ? ACCENT : done ? 0xFF71717A : 0x00000000);
            c.drawCircle(sx, y, x(7), fill);
            if (!now && !done) {
                stroke.setColor(0xFF71717A);
                stroke.setStrokeWidth(x(2));
                c.drawCircle(sx, y, x(6), stroke);
            }
            Paint p = now ? smallStrong : loadDim;
            c.drawText(loadingLines.get(i), sx + x(34), y + Ui.halfCap(p), p);
        }
        postInvalidateDelayed(32);
    }

    // MARK: - Icons (drawn, so they take any color)

    private void playIcon(Canvas c, float cx, float cy, float s, int color, boolean pause) {
        fill.setColor(color);
        if (pause) {
            r.set(cx - s * 0.75f, cy - s, cx - s * 0.2f, cy + s);
            c.drawRoundRect(r, s * 0.15f, s * 0.15f, fill);
            r.set(cx + s * 0.2f, cy - s, cx + s * 0.75f, cy + s);
            c.drawRoundRect(r, s * 0.15f, s * 0.15f, fill);
        } else {
            path.reset();
            path.moveTo(cx - s * 0.6f, cy - s);
            path.lineTo(cx + s, cy);
            path.lineTo(cx - s * 0.6f, cy + s);
            path.close();
            c.drawPath(path, fill);
        }
    }

    private void jumpIcon(Canvas c, float cx, float cy, float s, int color, boolean forward) {
        stroke.setColor(color);
        stroke.setStrokeWidth(x(3));
        arc.set(cx - s, cy - s, cx + s, cy + s);
        c.drawArc(arc, forward ? -60 : -120, forward ? 300 : -300, false, stroke);
        float ax = forward ? cx + s * 0.5f : cx - s * 0.5f, ay = cy - s * 0.87f;
        path.reset();
        path.moveTo(ax + (forward ? -s * 0.45f : s * 0.45f), ay - s * 0.4f);
        path.lineTo(ax, ay);
        path.lineTo(ax + (forward ? -s * 0.45f : s * 0.45f), ay + s * 0.4f);
        c.drawPath(path, stroke);
        jumpP.setColor(color);
        c.drawText("10", cx - jumpP.measureText("10") / 2, cy + Ui.halfCap(jumpP), jumpP);
    }

    private void subtitlesIcon(Canvas c, float cx, float cy, float s, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(x(2.5f));
        r.set(cx - s, cy - s * 0.75f, cx + s, cy + s * 0.75f);
        c.drawRoundRect(r, s * 0.3f, s * 0.3f, stroke);
        c.drawLine(cx - s * 0.55f, cy + s * 0.25f, cx + s * 0.1f, cy + s * 0.25f, stroke);
        c.drawLine(cx + s * 0.3f, cy + s * 0.25f, cx + s * 0.55f, cy + s * 0.25f, stroke);
        c.drawLine(cx - s * 0.55f, cy - s * 0.2f, cx - s * 0.2f, cy - s * 0.2f, stroke);
        c.drawLine(cx, cy - s * 0.2f, cx + s * 0.55f, cy - s * 0.2f, stroke);
    }

    private void episodesIcon(Canvas c, float cx, float cy, float s, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(x(2.5f));
        r.set(cx - s, cy - s * 0.7f, cx + s, cy + s * 0.4f);
        c.drawRoundRect(r, s * 0.2f, s * 0.2f, stroke);
        c.drawLine(cx - s * 0.6f, cy + s * 0.8f, cx + s * 0.6f, cy + s * 0.8f, stroke);
    }

    private void nextIcon(Canvas c, float cx, float cy, float s, int color) {
        fill.setColor(color);
        path.reset();
        path.moveTo(cx - s, cy - s);
        path.lineTo(cx + s * 0.5f, cy);
        path.lineTo(cx - s, cy + s);
        path.close();
        c.drawPath(path, fill);
        r.set(cx + s * 0.55f, cy - s, cx + s, cy + s);
        c.drawRoundRect(r, s * 0.1f, s * 0.1f, fill);
    }

    private void checkIcon(Canvas c, float cx, float cy, float s, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(x(3.5f));
        path.reset();
        path.moveTo(cx - s, cy);
        path.lineTo(cx - s * 0.3f, cy + s * 0.7f);
        path.lineTo(cx + s, cy - s * 0.7f);
        c.drawPath(path, stroke);
    }
}
