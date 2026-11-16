package dev.gawel.dothome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import dev.gawel.dothome.core.AppFilter;
import dev.gawel.dothome.core.DotStyle;
import dev.gawel.dothome.core.MonoIcon;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports every launcher app's icon as a white glyph PNG (432px, soft white) for the icon pack.
 * Reads icons through PackageManager only; it never starts, binds to, or queries any other app.
 */
public class IconExportActivity extends Activity {
    private static final int SIZE = 432;
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(Color.BLACK);
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
            int count = 0, auto = 0, failed = 0;
            Writer index = new OutputStreamWriter(new FileOutputStream(new File(dir, "index.tsv")), "UTF-8");
            try {
                for (int i = 0; i < apps.size(); i++) {
                    try {
                        boolean mono = false;
                        int[] px;
                        Drawable icon = apps.get(i).loadIcon(pm);
                        Drawable monoLayer = null;
                        if (Build.VERSION.SDK_INT >= 33 && icon instanceof AdaptiveIconDrawable) {
                            monoLayer = ((AdaptiveIconDrawable) icon).getMonochrome();
                        }
                        if (monoLayer != null) {
                            px = MonoIcon.fromMonochromeLayer(render(new Drawable[] {monoLayer}, false));
                            mono = true;
                        } else if (icon instanceof AdaptiveIconDrawable) {
                            // Unmasked layers, so mask corners are the background colour, not black.
                            AdaptiveIconDrawable ad = (AdaptiveIconDrawable) icon;
                            px = MonoIcon.fromFullIcon(
                                render(new Drawable[] {ad.getBackground(), ad.getForeground()}, true), SIZE, SIZE);
                        } else {
                            px = MonoIcon.fromFullIcon(render(new Drawable[] {icon}, true, false), SIZE, SIZE);
                        }
                        px = MonoIcon.flatten(px, DotStyle.FG);
                        Bitmap bmp = Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888);
                        try {
                            FileOutputStream out = new FileOutputStream(new File(dir, names.get(i) + ".png"));
                            try {
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                            } finally {
                                out.close();
                            }
                        } finally {
                            bmp.recycle();
                        }
                        CharSequence lab = apps.get(i).loadLabel(pm);
                        String label = (lab == null ? "" : lab.toString()).replaceAll("[\\t\\r\\n]+", " ").trim();
                        index.write(components.get(i) + "\t" + names.get(i) + "\t" + (mono ? "mono" : "auto")
                            + "\t" + label + "\n");
                        count++;
                        if (!mono) auto++;
                    } catch (Exception e) {
                        failed++;
                        new File(dir, names.get(i) + ".png").delete();
                        Log.w("DotHome", "icon export failed for " + components.get(i), e);
                    }
                }
            } finally {
                index.close();
            }
            return "Exported " + count + " icons (" + auto + " auto, " + failed + " failed)";
        } catch (Exception e) {
            return "Export failed: " + e;
        }
    }

    private static int[] render(Drawable[] layers, boolean opaqueBlack) {
        return render(layers, opaqueBlack, true);
    }

    /**
     * Renders to 432x432. Raw adaptive layers live on a 108dp canvas with a 72dp safe zone, so they
     * are drawn oversize (~1.25x) to fill the tile; legacy icons are drawn 1:1. Opaque mode puts a
     * black backing under the layers; the monochrome layer keeps its alpha.
     */
    private static int[] render(Drawable[] layers, boolean opaqueBlack, boolean oversize) {
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        if (opaqueBlack) c.drawColor(Color.BLACK);
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
