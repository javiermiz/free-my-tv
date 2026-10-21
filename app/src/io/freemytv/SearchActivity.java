package io.freemytv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Search by name (the sidebar's Search): type, or speak with the TV's keyboard, and movies and shows from all of
 * TMDB come up as posters; OK opens it as a card on the home screen would (a show's page, a movie plays).
 */
public class SearchActivity extends Activity implements Recs.Listener {
    private final List<Recs.Pick> shown = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BaseAdapter adapter;
    private Recs recs;
    private TextView status;
    private String query = "";
    private int asked;
    private final Runnable search = this::search;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        recs = Recs.get(this);
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(dp, 64), px(dp, 40), px(dp, 64), 0);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        EditText box = new EditText(this);
        box.setHint(FlixView.es("Busca una película o serie", "Search a movie or show"));
        box.setSingleLine(true);
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.setTextColor(Color.WHITE);
        box.setHintTextColor(0xFFA1A1AA);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        box.setTypeface(Ui.medium);
        box.setPadding(px(dp, 24), px(dp, 12), px(dp, 24), px(dp, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x1FFFFFFF);
        bg.setCornerRadius(28 * dp);
        box.setBackground(bg);
        box.setOnFocusChangeListener((v, has) -> bg.setStroke(has ? px(dp, 2) : 0, Color.WHITE));
        box.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString();
                handler.removeCallbacks(search);
                handler.postDelayed(search, 450);   // while typing, once you pause
            }
        });
        head.addView(box, new LinearLayout.LayoutParams(px(dp, 640), -2));
        status = new TextView(this);
        status.setTextColor(0xFFA1A1AA);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setTypeface(Ui.regular);
        status.setPadding(px(dp, 24), 0, 0, 0);
        head.addView(status);
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));

        GridView grid = new GridView(this);
        grid.setNumColumns(7);
        grid.setHorizontalSpacing(px(dp, 18));
        grid.setVerticalSpacing(px(dp, 18));
        grid.setPadding(px(dp, 6), px(dp, 28), px(dp, 6), px(dp, 40));
        grid.setClipToPadding(false);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(px(dp, 3), Color.WHITE);
        ring.setCornerRadius(12 * dp);
        grid.setSelector(ring);
        grid.setDrawSelectorOnTop(true);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                LinearLayout cell = (LinearLayout) v;
                if (cell == null) {
                    cell = new LinearLayout(SearchActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setPadding(px(dp, 5), px(dp, 5), px(dp, 5), px(dp, 8));
                    ImageView img = new ImageView(SearchActivity.this);
                    img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    img.setClipToOutline(true);
                    GradientDrawable pbg = new GradientDrawable();
                    pbg.setColor(0xFF1C1C1F);
                    pbg.setCornerRadius(8 * dp);
                    img.setBackground(pbg);
                    int w = (parent.getWidth() - px(dp, 12) - 6 * px(dp, 18)) / 7 - px(dp, 10);
                    cell.addView(img, new LinearLayout.LayoutParams(-1, Math.max(1, w * 3 / 2)));
                    TextView title = new TextView(SearchActivity.this);
                    title.setTextColor(0xFFE4E4E7);
                    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    title.setTypeface(Ui.medium);
                    title.setSingleLine(true);
                    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    title.setPadding(0, px(dp, 8), 0, 0);
                    cell.addView(title);
                    TextView meta = new TextView(SearchActivity.this);
                    meta.setTextColor(0xFFA1A1AA);
                    meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    meta.setTypeface(Ui.regular);
                    meta.setSingleLine(true);
                    cell.addView(meta);
                }
                Recs.Pick p = shown.get(i);
                ((ImageView) cell.getChildAt(0)).setImageBitmap(recs.image(p.poster, "w342"));
                ((TextView) cell.getChildAt(1)).setText(p.title);
                ((TextView) cell.getChildAt(2)).setText(("tv".equals(p.kind) ? FlixView.es("Serie", "Show") : FlixView.es("Película", "Movie"))
                        + (p.year == null || p.year.isEmpty() ? "" : " · " + p.year));
                return cell;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, i, id) -> open(shown.get(i)));
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        box.requestFocus();
        box.postDelayed(() -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(box, 0), 300);
        recs.addListener(this);
    }

    private void search() {
        final String q = query.trim();
        final int ask = ++asked;
        if (q.length() < 2) {
            shown.clear();
            adapter.notifyDataSetChanged();
            status.setText("");
            return;
        }
        status.setText(FlixView.es("Buscando…", "Searching…"));
        new Thread(() -> {
            List<Recs.Pick> found;
            try {
                found = recs.search(q);
            } catch (Exception e) {
                AppLog.w("search: " + e.getMessage());
                found = null;
            }
            final List<Recs.Pick> result = found;
            handler.post(() -> {
                if (ask != asked) return;   // you kept typing
                shown.clear();
                if (result != null) shown.addAll(result);
                adapter.notifyDataSetChanged();
                status.setText(result == null ? FlixView.es("No se pudo buscar", "Couldn't search")
                        : result.isEmpty() ? FlixView.es("Nada con ese nombre", "Nothing by that name") : "");
            });
        }).start();
    }

    /** Which app has it, then back to the home screen, which opens it there. */
    private void open(Recs.Pick p) {
        status.setText(FlixView.es("Abriendo…", "Opening…"));
        recs.prepare(p, ready -> {
            if (ready.pkg == null) {
                status.setText("");
                Toast.makeText(this, FlixView.es("Ninguna app de la TV lo tiene", "No app on the TV has it"), Toast.LENGTH_SHORT).show();
                return;
            }
            FlixView.pendingOpen = ready;
            finish();
        });
    }

    @Override public void onRecs() {
        adapter.notifyDataSetChanged();   // a poster came in
    }

    @Override protected void onDestroy() {
        recs.removeListener(this);
        super.onDestroy();
    }

    private static int px(float dp, int v) {
        return Math.round(v * dp);
    }
}
