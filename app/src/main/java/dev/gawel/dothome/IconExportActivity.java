package dev.gawel.dothome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.TextView;
import dev.gawel.dothome.core.AppFilter;
import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.MonoIcon;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports every launcher app's icon for the icon pack, as two aligned 432px PNGs per app:
 * {@code <name>.png}, the glyph shape in soft white (monochrome layer, or extracted from the full
 * icon), and {@code <name>.tone.png}, the full-colour icon's Rec. 709 luma with alpha = coverage,
 * drawn with exactly the same geometry as the shape. tools/iconfix.py stipples shape x tone.
 * Reads the apps' own icon drawables through PackageManager and their resources only; it never
 * starts, binds to, or queries any other app.
 */
public class IconExportActivity extends Activity {
    private static final int SIZE = 432;
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        status = new TextView(this);
        status.setTextColor(DotStyle.L100);
        status.setBackgroundColor(DotStyle.GROUND);
        status.setTextSize(18);
        status.setPadding(48, 96, 48, 48);
        status.setText("Exporting icons...");
        setContentView(status);
        if (b != null) return; // recreated (e.g. rotation): the first run already did the export
        new Thread(new Runnable() {
            @Override public void run() {
                final String msg = export();
                runOnUiThread(new Runnable() {
                    @Override public void run() { status.setText(msg); }
                });
            }
        }).start();
    }

    private String export() {
        try {
            PackageManager pm = getPackageManager();
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = pm.queryIntentActivities(q, 0);
            List<String> components = new ArrayList<String>();
            for (ResolveInfo ri : apps) {
                components.add(ri.activityInfo.packageName + "/" + ri.activityInfo.name);
            }
            List<String> names = AppFilter.drawableNames(components);
            File dir = getExternalFilesDir("icons");
            if (dir == null) return "No external files dir";
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (f.getName().endsWith(".png")) f.delete();
            int mono = 0, auto = 0, failed = 0, fallback = 0;
            Writer index = new OutputStreamWriter(new FileOutputStream(new File(dir, "index.tsv")), "UTF-8");
            try {
                for (int i = 0; i < apps.size(); i++) {
                    String name = names.get(i);
                    try {
                        ResolveInfo ri = apps.get(i);
                        Drawable icon = unthemedIcon(pm, ri);
                        boolean themed = icon == null;
                        if (themed) {
                            icon = ri.loadIcon(pm);
                            fallback++;
                        }
                        Drawable monoLayer = null;
                        if (Build.VERSION.SDK_INT >= 33 && icon instanceof AdaptiveIconDrawable) {
                            monoLayer = ((AdaptiveIconDrawable) icon).getMonochrome();
                        }
                        // colour: the full icon on transparent, framed exactly like the shape below,
                        // so <name>.tone.png lines up with <name>.png pixel for pixel.
                        int[] shape, colour;
                        if (icon instanceof AdaptiveIconDrawable) {
                            // Raw, unmasked layers, so mask corners are the background colour, not black.
                            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) icon;
                            colour = render(new Drawable[] {ad.getBackground(), ad.getForeground()}, true);
                            shape = monoLayer != null
                                ? MonoIcon.fromMonochromeLayer(render(new Drawable[] {monoLayer}, true))
                                : MonoIcon.fromFullIcon(MonoIcon.overBlack(colour), SIZE, SIZE);
                        } else {
                            colour = render(new Drawable[] {icon}, false);
                            shape = MonoIcon.fromFullIcon(MonoIcon.overBlack(colour), SIZE, SIZE);
                        }
                        writePng(new File(dir, name + ".png"), MonoIcon.flatten(shape, DotStyle.L100));
                        // A themed icon is our own pack: its tone would feed our dots back in. Without
                        // a tone layer iconfix uses its default level.
                        if (!themed) writePng(new File(dir, name + ".tone.png"), MonoIcon.tone(colour));
                        CharSequence lab = ri.loadLabel(pm);
                        String label = (lab == null ? "" : lab.toString()).replaceAll("[\\t\\r\\n]+", " ").trim();
                        index.write(components.get(i) + "\t" + name + "\t" + (monoLayer != null ? "mono" : "auto")
                            + "\t" + label + "\n");
                        if (monoLayer != null) mono++; else auto++;
                    } catch (Exception e) {
                        failed++;
                        new File(dir, name + ".png").delete();
                        new File(dir, name + ".tone.png").delete();
                        Log.w("DotHome", "icon export failed for " + components.get(i), e);
                    }
                }
            } finally {
                index.close();
            }
            return MonoIcon.exportStatus(mono, auto, failed, fallback);
        } catch (Exception e) {
            return "Export failed: " + e;
        }
    }

    /**
     * The app's own icon, read from its resources at xxxhdpi. ResolveInfo.loadIcon goes through the
     * launcher icon theme (Theme Park), which would hand back our own icon pack. Null on any failure;
     * the caller then falls back to loadIcon.
     */
    private static Drawable unthemedIcon(PackageManager pm, ResolveInfo ri) {
        try {
            int res = ri.getIconResource();
            if (res == 0) return null;
            Resources r = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
            return r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
        } catch (Exception e) {
            Log.w("DotHome", "unthemed icon unavailable for " + ri.activityInfo.packageName, e);
            return null;
        }
    }

    private static void writePng(File f, int[] px) throws IOException {
        Bitmap bmp = Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888);
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                out.close();
            }
        } finally {
            bmp.recycle();
        }
    }

    /**
     * Renders to 432x432 on transparent; returns unpremultiplied ARGB. Raw adaptive layers live on a
     * 108dp canvas with a 72dp safe zone, so they are drawn oversize (~1.25x) to fill the tile; legacy
     * icons are drawn 1:1. The shape and the tone of one icon always use the same oversize flag.
     */
    private static int[] render(Drawable[] layers, boolean oversize) {
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        int pad = oversize ? -SIZE / 8 : 0;
        for (Drawable d : layers) {
            if (d == null) continue;
            d.setBounds(pad, pad, SIZE - pad, SIZE - pad);
            d.draw(c);
        }
        int[] px = new int[SIZE * SIZE];
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
        bmp.recycle();
        return px;
    }
}
