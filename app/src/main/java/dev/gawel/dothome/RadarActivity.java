package dev.gawel.dothome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
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

import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.Place;

/**
 * Full-screen radar: the dotted map with the header (title, place, Local/Region pills) and credits laid over it.
 * EXTRA_REGION (from the radar widget) opens it in that view; without it the view is local, or stays as it was.
 */
public class RadarActivity extends Activity {
    private static final String TAG = "DotHome";
    static final String EXTRA_REGION = "dev.gawel.dothome.extra.RADAR_REGION";
    static final String CREDITS =
        "Radar: NWS NEXRAD via Iowa Environmental Mesonet · Weather data by Open-Meteo.com · Map: Natural Earth";

    private RadarView radar;
    private TextView subtitle, pillLocal, pillRegion, credits;
    private LinearLayout titles, controls;
    private float dp;
    private static final String KEY_REGION = "region", KEY_LAT = "lat", KEY_LON = "lon", KEY_NAME = "name";
    private String placeName = "";
    private boolean placed;

    @Override
    @SuppressWarnings("deprecation") // pre-30 inset getters
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(DotStyle.GROUND);
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

        radar = new RadarView(this);
        radar.setContentDescription("Weather radar map drawn in dots. Tap the map to switch between Local and Region. Play button and scrubber step through the last hour.");
        radar.setOnClickListener(v -> setRegion(!radar.isRegion()));
        root.addView(radar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout header = new FrameLayout(this);
        header.setPadding(px(16), px(12), px(16), px(12));
        titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Radar", 20, DotStyle.L65, Fonts.interMedium(this));
        subtitle = text("", 13, DotStyle.L65, Fonts.inter(this));
        titles.setClickable(true); // taps on the header text must not toggle the map
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));

        controls = new LinearLayout(this);
        pillLocal = pill("Local");
        pillRegion = pill("Region");
        controls.addView(pillLocal);
        controls.addView(pillRegion);
        header.addView(controls, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        root.addView(header, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        credits = text(CREDITS, 13, DotStyle.L46, Fonts.inter(this));
        credits.setPadding(px(16), px(8), px(16), px(8));
        credits.setClickable(true);
        root.addView(credits, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        // the map keeps its dots out from behind the header text, the controls and the credits
        View.OnLayoutChangeListener push = (v, l, t, r, b, ol, ot, or, ob) -> radar.setOverlays(
            rectIn(titles), rectIn(controls), rectIn(credits));
        titles.addOnLayoutChangeListener(push);
        controls.addOnLayoutChangeListener(push);
        credits.addOnLayoutChangeListener(push);

        pillLocal.setOnClickListener(v -> setRegion(false));
        pillRegion.setOnClickListener(v -> setRegion(true));
        if (getIntent() != null && getIntent().hasExtra(EXTRA_REGION)) {
            radar.setRegion(getIntent().getBooleanExtra(EXTRA_REGION, false)); // the widget's view
        }
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

    /** singleTask: a widget tap while the radar is open switches it to that widget's view. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra(EXTRA_REGION)) setRegion(intent.getBooleanExtra(EXTRA_REGION, false));
    }

    @Override
    protected void onDestroy() {
        radar.release(); // cancels any tile download still running
        if (isFinishing()) RadarTiles.clearCache(); // the cache is static (~36 MB) and would stay in the wallpaper process
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
        stylePill(pillLocal, !r);
        stylePill(pillRegion, r);
    }

    private void stylePill(TextView t, boolean on) {
        float r = px(20);
        ShapeDrawable bg = new ShapeDrawable(new RoundRectShape(new float[] {r, r, r, r, r, r, r, r}, null, null));
        bg.getPaint().setColor(on ? DotStyle.L10 : Color.TRANSPARENT);
        t.setBackground(bg);
        t.setTextColor(on ? DotStyle.L100 : DotStyle.L46);
    }

    private TextView pill(String label) {
        TextView t = text(label, 13, DotStyle.L46, Fonts.interMedium(this));
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(px(80));
        t.setMinHeight(px(40));
        t.setPadding(px(16), 0, px(16), 0);
        t.setClickable(true);
        return t;
    }

    /** v's bounds in the radar view's coordinates (both sit in the same root), or null before layout. */
    private float[] rectIn(View v) {
        if (v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        float x = 0, y = 0;
        View c = v;
        while (c != null && c != radar.getParent()) {
            x += c.getLeft();
            y += c.getTop();
            c = c.getParent() instanceof View ? (View) c.getParent() : null;
        }
        return new float[] {x - radar.getLeft(), y - radar.getTop(), v.getWidth(), v.getHeight()};
    }

    private TextView text(String s, float sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    private int px(float v) { return Math.round(v * dp); }
}
