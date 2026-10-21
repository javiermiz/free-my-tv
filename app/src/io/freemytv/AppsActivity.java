package io.freemytv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every app, input and setting in one grid (from the sidebar's Apps), with a search box on top: type (or speak, with
 * the TV's keyboard) and only the ones whose name matches stay. Your shelf's apps come first.
 */
public class AppsActivity extends Activity {
    private final List<HomeApps.Item> all = new ArrayList<>(), shown = new ArrayList<>();
    private BaseAdapter adapter;
    private String query = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(dp, 64), px(dp, 40), px(dp, 64), 0);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Ui.semibold);
        head.addView(title, new LinearLayout.LayoutParams(-2, -2));
        EditText search = new EditText(this);
        search.setHint(FlixView.es("Buscar apps", "Search apps"));
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0xFFA1A1AA);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        search.setTypeface(Ui.medium);
        search.setPadding(px(dp, 20), px(dp, 10), px(dp, 20), px(dp, 10));
        GradientDrawable box = new GradientDrawable();
        box.setColor(0x1FFFFFFF);
        box.setCornerRadius(24 * dp);
        search.setBackground(box);
        search.setOnFocusChangeListener((v, has) -> box.setStroke(has ? px(dp, 2) : 0, Color.WHITE));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = plain(s.toString());
                filter();
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(px(dp, 420), -2);
        sp.leftMargin = px(dp, 32);
        head.addView(search, sp);
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));

        GridView grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setHorizontalSpacing(px(dp, 20));
        grid.setVerticalSpacing(px(dp, 20));
        grid.setPadding(px(dp, 6), px(dp, 28), px(dp, 6), px(dp, 40));
        grid.setClipToPadding(false);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(px(dp, 3), Color.WHITE);
        ring.setCornerRadius(14 * dp);
        grid.setSelector(ring);
        grid.setDrawSelectorOnTop(true);
        grid.setCacheColorHint(Color.TRANSPARENT);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                LinearLayout cell = (LinearLayout) v;
                if (cell == null) {
                    cell = new LinearLayout(AppsActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setPadding(px(dp, 6), px(dp, 6), px(dp, 6), px(dp, 8));
                    ImageView img = new ImageView(AppsActivity.this);
                    img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    img.setClipToOutline(true);
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(0xFF1C1C1F);
                    bg.setCornerRadius(10 * dp);
                    img.setBackground(bg);
                    int w = (parent.getWidth() - px(dp, 12) - 4 * px(dp, 20)) / 5 - px(dp, 12);
                    cell.addView(img, new LinearLayout.LayoutParams(-1, Math.max(1, w * 9 / 16)));
                    TextView label = new TextView(AppsActivity.this);
                    label.setTextColor(0xFFD4D4D8);
                    label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                    label.setTypeface(Ui.medium);
                    label.setSingleLine(true);
                    label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    label.setPadding(0, px(dp, 8), 0, 0);
                    cell.addView(label);
                }
                HomeApps.Item it = shown.get(i);
                ((ImageView) cell.getChildAt(0)).setImageBitmap(it.tile);
                ((TextView) cell.getChildAt(1)).setText(it.label);
                return cell;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, view, i, id) -> {
            HomeApps.Item it = shown.get(i);
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            if (!HomeApps.open(this, it, view, at[0], at[1], view.getWidth(), view.getHeight()))
                Toast.makeText(this, FlixView.es("No se pudo abrir ", "Couldn't open ") + it.label, Toast.LENGTH_SHORT).show();
        });
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        grid.requestFocus();
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));

        // The cards take a moment to build (the apps' TV art): in the background.
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            List<HomeApps.Item> list = new ArrayList<>(HomeApps.shelf(app, false));
            list.addAll(HomeApps.others(app));
            list.addAll(HomeApps.allInputs(app));
            list.add(HomeApps.settings());
            list.add(HomeApps.action(HomeApps.ANDROID_SETTINGS, "tv-settings", FlixView.es("Ajustes de la TV", "TV Settings"), "tv_settings"));
            runOnUiThread(() -> {
                all.clear();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (HomeApps.Item it : list) if (seen.add(it.key)) all.add(it);
                filter();
            });
        }).start();
    }

    private void filter() {
        shown.clear();
        for (HomeApps.Item it : all) if (query.isEmpty() || plain(it.label).contains(query)) shown.add(it);
        adapter.notifyDataSetChanged();
    }

    /** "Configuración" → "configuracion": accents and case don't matter when searching. */
    static String plain(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private static int px(float dp, int v) {
        return Math.round(v * dp);
    }
}
