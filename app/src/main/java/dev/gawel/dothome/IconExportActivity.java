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
import android.widget.TextView;
import dev.gawel.dothome.core.AppFilter;
import dev.gawel.dothome.core.MonoIcon;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Exports every launcher app's icon as a white glyph PNG (192px) for the icon pack.
 * Reads icons through PackageManager only; it never starts, binds to, or queries any other app.
 */
public class IconExportActivity extends Activity {
    private static final int SIZE = 192;
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
            int count = 0, auto = 0;
            Writer index = new OutputStreamWriter(new FileOutputStream(new File(dir, "index.tsv")), "UTF-8");
            try {
                for (int i = 0; i < apps.size(); i++) {
                    Drawable icon = apps.get(i).loadIcon(pm);
                    boolean mono = false;
                    int[] px;
                    if (Build.VERSION.SDK_INT >= 33 && icon instanceof AdaptiveIconDrawable
                            && ((AdaptiveIconDrawable) icon).getMonochrome() != null) {
                        px = MonoIcon.fromMonochromeLayer(render(((AdaptiveIconDrawable) icon).getMonochrome(), false));
                        mono = true;
                    } else {
                        px = MonoIcon.fromFullIcon(render(icon, true), SIZE, SIZE);
                        auto++;
                    }
                    Bitmap bmp = Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888);
                    FileOutputStream out = new FileOutputStream(new File(dir, names.get(i) + ".png"));
                    try {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                    } finally {
                        out.close();
                    }
                    index.write(components.get(i) + "\t" + names.get(i) + "\t" + (mono ? "mono" : "auto") + "\n");
                    count++;
                }
            } finally {
                index.close();
            }
            return "Exported " + count + " icons (" + auto + " auto)";
        } catch (Exception e) {
            return "Export failed: " + e;
        }
    }

    /**
     * Renders to 192x192. Adaptive layers live on a 108dp canvas with a 72dp safe zone, so they are
     * drawn 1.25x oversize to fill the tile. Full icons get an opaque black backing so transparent
     * corners read as background; the monochrome layer keeps its alpha.
     */
    private static int[] render(Drawable d, boolean opaqueBlack) {
        Bitmap bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        if (opaqueBlack) c.drawColor(Color.BLACK);
        int pad = (d instanceof AdaptiveIconDrawable || !opaqueBlack) ? -SIZE / 8 : 0;
        d.setBounds(pad, pad, SIZE - pad, SIZE - pad);
        d.draw(c);
        int[] px = new int[SIZE * SIZE];
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE);
        return px;
    }
}
