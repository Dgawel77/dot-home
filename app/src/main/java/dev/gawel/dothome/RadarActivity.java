package dev.gawel.dothome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.gawel.dothome.core.Place;

/** Full-screen radar: header (title, place, Area/Region pills), the dotted map, and the credits line. */
public class RadarActivity extends Activity {
    private static final String TAG = "DotHome";
    static final String CREDITS =
        "Radar: NWS NEXRAD via Iowa Environmental Mesonet · Weather data by Open-Meteo.com · Map: Natural Earth";

    private RadarView radar;
    private TextView subtitle, pillArea, pillRegion;
    private float dp;
    private static final String KEY_REGION = "region", KEY_LAT = "lat", KEY_LON = "lon", KEY_NAME = "name";
    private String placeName = "";
    private boolean placed;

    @Override
    @SuppressWarnings("deprecation") // pre-30 inset getters
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        // Keep the header clear of the status bar and camera cutout, and the credits clear of the gesture bar.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });

        FrameLayout header = new FrameLayout(this);
        header.setPadding(px(20), px(14), px(16), px(10));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Radar", 20, Color.WHITE, Fonts.interMedium(this));
        subtitle = text("", 13, 0xFFA6A6A6, Fonts.inter(this));
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));

        LinearLayout seg = new LinearLayout(this);
        GradientDrawable segBg = new GradientDrawable();
        segBg.setColor(0xFF101010);
        segBg.setStroke(Math.max(1, px(1)), 0xFF2B2B2B);
        segBg.setCornerRadius(px(22));
        seg.setBackground(segBg);
        seg.setPadding(px(3), px(3), px(3), px(3));
        pillArea = pill("Area");
        pillRegion = pill("Region");
        seg.addView(pillArea);
        seg.addView(pillRegion);
        header.addView(seg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        radar = new RadarView(this);
        radar.setContentDescription("Weather radar map drawn in dots. Tap to switch between Area and Region.");
        radar.setOnClickListener(v -> setRegion(!radar.isRegion()));
        root.addView(radar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView credits = text(CREDITS, 10, 0xFF808080, Fonts.inter(this));
        credits.setPadding(px(20), px(8), px(20), px(10));
        root.addView(credits, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        pillArea.setOnClickListener(v -> setRegion(false));
        pillRegion.setOnClickListener(v -> setRegion(true));
        styleToggle();

        if (savedInstanceState != null) {
            radar.setRegion(savedInstanceState.getBoolean(KEY_REGION, false));
            styleToggle();
            if (savedInstanceState.containsKey(KEY_LAT)) {
                placeName = savedInstanceState.getString(KEY_NAME, "");
                showPlace(savedInstanceState.getDouble(KEY_LAT), savedInstanceState.getDouble(KEY_LON));
                return;
            }
        }
        new Thread(() -> {
            Place p = null;
            try {
                p = LocationSource.current(getApplicationContext());
            } catch (RuntimeException e) {
                Log.w(TAG, "radar place failed: " + e);
            }
            final double lat = p == null ? 41.88 : p.lat, lon = p == null ? -87.63 : p.lon;
            runOnUiThread(() -> {
                if (!isDestroyed()) showPlace(lat, lon);
            });
            // the Geocoder can hang; the radar is already loading, so the name only fills in the subtitle
            String name = "";
            try {
                if (p != null) name = LocationSource.placeName(getApplicationContext(), p);
            } catch (RuntimeException e) {
                Log.w(TAG, "radar place name failed: " + e);
            }
            final String n = name == null ? "" : name;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                placeName = n;
                subtitle.setText(subtitleText());
            });
        }, "dothome-radar-place").start();
    }

    private void showPlace(double lat, double lon) {
        placed = true;
        subtitle.setText(subtitleText());
        radar.setPlace(lat, lon);
    }

    private String subtitleText() {
        return placeName.isEmpty() ? "Live NEXRAD, last hour" : placeName + " \u00b7 live, last hour";
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(KEY_REGION, radar.isRegion());
        if (placed) {
            out.putDouble(KEY_LAT, radar.lat());
            out.putDouble(KEY_LON, radar.lon());
            out.putString(KEY_NAME, placeName);
        }
    }

    @Override
    protected void onDestroy() {
        radar.release(); // cancels any tile download still running
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        radar.start();
    }

    @Override
    protected void onPause() {
        radar.stop();
        super.onPause();
    }

    private void setRegion(boolean region) {
        radar.setRegion(region);
        styleToggle();
    }

    private void styleToggle() {
        boolean r = radar.isRegion();
        stylePill(pillArea, !r);
        stylePill(pillRegion, r);
    }

    private void stylePill(TextView t, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(19));
        bg.setColor(on ? Color.WHITE : Color.TRANSPARENT);
        t.setBackground(bg);
        t.setTextColor(on ? Color.BLACK : 0xFFBDBDBD);
    }

    private TextView pill(String label) {
        TextView t = text(label, 13, 0xFFBDBDBD, Fonts.interMedium(this));
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(px(78));
        t.setMinHeight(px(38));
        t.setPadding(px(16), 0, px(16), 0);
        t.setClickable(true);
        return t;
    }

    private TextView text(String s, float sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int px(float v) { return Math.round(v * dp); }
}
