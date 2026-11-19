package dev.gawel.dothome;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

import dev.gawel.dothome.core.BlueLineStations;
import dev.gawel.dothome.core.FireMode;
import dev.gawel.dothome.core.HueGroup;
import dev.gawel.dothome.core.HueRows;

/**
 * Setup checklist: permissions, wallpaper, fire mode, rock style, the Blue Line key and station, and pairing the Hue
 * bridge with the rooms Dot Hue shows. Done states refresh in onResume.
 */
public class SetupActivity extends Activity {
    private static final int REQ_LOCATION = 1, REQ_AUDIO = 2;

    private Switch beatSwitch;

    private TextView stepLocation, stepBackground, stepNotifications, stepWallpaper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        PrefsStore prefs = new PrefsStore(this);

        stepLocation = findViewById(R.id.step_location);
        stepBackground = findViewById(R.id.step_background);
        stepNotifications = findViewById(R.id.step_notifications);
        stepWallpaper = findViewById(R.id.step_wallpaper);
        ((TextView) findViewById(R.id.step_fire)).setText("✓  5. Fire mode");

        findViewById(R.id.btn_location).setOnClickListener(v ->
            requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION));
        findViewById(R.id.btn_background).setOnClickListener(v -> safeStart(
            new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        findViewById(R.id.btn_notifications).setOnClickListener(v ->
            safeStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        findViewById(R.id.btn_wallpaper).setOnClickListener(v -> {
            Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(this, DotWallpaperService.class));
            safeStart(i);
        });

        android.widget.Button export = findViewById(R.id.btn_export);
        export.setOnClickListener(v -> {
            export.setEnabled(false);
            LockStills.export(this, msg -> {
                export.setText(msg);
                export.setEnabled(true);
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
            });
        });

        FireMode[] modes = {FireMode.TAP_TOUCH, FireMode.TAP_COMMAND, FireMode.AUTO};
        String[] names = {"Tap (raw touch) - recommended", "Tap (home screen tap)", "Auto-fire"};
        FireMode current;
        try {
            current = FireMode.valueOf(prefs.get("fire.mode", FireMode.TAP_TOUCH.name()));
        } catch (IllegalArgumentException e) {
            current = FireMode.TAP_TOUCH;
        }
        RadioGroup group = findViewById(R.id.fire_group);
        for (int i = 0; i < modes.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(i + 1);
            rb.setText(names[i]);
            rb.setTextColor(0xFFFFFFFF);
            group.addView(rb);
            if (modes[i] == current) group.check(rb.getId());
        }
        group.setOnCheckedChangeListener((g, id) -> {
            if (id >= 1 && id <= modes.length) prefs.put("fire.mode", modes[id - 1].name());
        });

        // rock style: the wallpaper reads it when it next becomes visible
        String[] styles = {PrefsStore.ROCK_LATTICE, PrefsStore.ROCK_OUTLINE};
        String[] styleNames = {"Dots on grid", "Outline"};
        int styleId0 = 11;
        String style = prefs.outlineRocks() ? PrefsStore.ROCK_OUTLINE : PrefsStore.ROCK_LATTICE;
        RadioGroup rocks = findViewById(R.id.rock_group);
        for (int i = 0; i < styles.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(styleId0 + i);
            rb.setText(styleNames[i]);
            rb.setTextColor(0xFFFFFFFF);
            rocks.addView(rb);
            if (styles[i].equals(style)) rocks.check(rb.getId());
        }
        rocks.setOnCheckedChangeListener((g, id) -> {
            if (id >= styleId0 && id < styleId0 + styles.length) prefs.put(PrefsStore.KEY_ROCK_STYLE, styles[id - styleId0]);
        });

        Switch backdrop = new Switch(this);
        backdrop.setText("Glass widget backgrounds");
        backdrop.setTextColor(0xFFFFFFFF);
        backdrop.setChecked(WidgetArt.backdropOn(this));
        backdrop.setOnCheckedChangeListener((b, on) -> {
            prefs.put("widget.backdrop", on ? "true" : "false");
            WidgetUpdater.updateAll(this);
            WeatherJob.refreshSoon(this); // the radar widget re-renders in that job
            LiveFeeds.kick(this); // Dot Bears and Dot Blue Line redraw now
        });
        android.view.ViewGroup parent = (android.view.ViewGroup) rocks.getParent();
        parent.addView(backdrop, parent.indexOfChild(rocks) + 1);

        Switch lockLive = new Switch(this);
        lockLive.setText("Lock screen shows the game (beta; turning off keeps the last image)");
        lockLive.setTextColor(0xFFFFFFFF);
        lockLive.setChecked("true".equals(prefs.get(PrefsStore.KEY_LOCK_LIVE, "false")));
        lockLive.setOnCheckedChangeListener((b, on) -> prefs.put(PrefsStore.KEY_LOCK_LIVE, on ? "true" : "false"));
        parent.addView(lockLive, parent.indexOfChild(backdrop) + 1);

        beatSwitch = new Switch(this);
        beatSwitch.setText("Stars pulse to music (uses the microphone permission)");
        beatSwitch.setTextColor(0xFFFFFFFF);
        beatSwitch.setChecked("true".equals(prefs.get(PrefsStore.KEY_BEAT, "false"))
                && hasPermission(Manifest.permission.RECORD_AUDIO));
        beatSwitch.setOnCheckedChangeListener((b, on) -> {
            BeatPulse.clearFailure();
            prefs.put(PrefsStore.KEY_BEAT, on ? "true" : "false");
            if (on && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            }
        });
        parent.addView(beatSwitch, parent.indexOfChild(lockLive) + 1);

        setupBlueLine();
        setupHue();

        WeatherJob.schedule(this);
        WeatherJob.refreshSoon(this);
    }

    /** CTA key (password field, never pre-filled with the stored key) and the station pin. */
    private void setupBlueLine() {
        EditText keyField = findViewById(R.id.cta_key);
        keyHint(keyField);
        findViewById(R.id.btn_cta_save).setOnClickListener(v -> {
            String key = keyField.getText().toString().trim();
            if (key.isEmpty()) { // an empty field never clears a saved key
                android.widget.Toast.makeText(this, "Type a key first", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            CtaRepo.saveKey(this, key);
            keyField.setText("");
            keyHint(keyField);
            LiveFeeds.kick(this);
            android.widget.Toast.makeText(this, "CTA key saved", android.widget.Toast.LENGTH_SHORT).show();
        });

        int n = BlueLineStations.IDS.length;
        String[] labels = new String[n + 1];
        labels[0] = "Nearest (auto)";
        for (int i = 0; i < n; i++) labels[i + 1] = BlueLineStations.label(BlueLineStations.IDS[i]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        Spinner station = findViewById(R.id.cta_station);
        station.setAdapter(adapter);
        int pin = CtaRepo.pinned(this);
        int sel = pin == -1 ? 0 : BlueLineStations.index(pin) + 1;
        station.setSelection(sel, false);
        station.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private int last = sel; // the initial selection callback is not a change

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == last) return;
                last = position;
                CtaRepo.pin(SetupActivity.this, position == 0 ? -1 : BlueLineStations.IDS[position - 1]);
                LiveFeeds.kick(SetupActivity.this);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private TextView hueStatus;
    private LinearLayout hueRooms;
    private boolean huePairing;

    /** Hue: pair (find the bridge, then the user presses its button), forget, and pick up to 4 rooms in tap order. */
    private void setupHue() {
        hueStatus = findViewById(R.id.hue_status);
        hueRooms = findViewById(R.id.hue_rooms);
        findViewById(R.id.btn_hue_pair).setOnClickListener(v -> pairHue());
        findViewById(R.id.btn_hue_forget).setOnClickListener(v -> {
            HueRepo.forget(this);
            HueWidget.redraw(this, true).run();
            showHue(null);
        });
        showHue(null);
        if (HueRepo.paired(this)) refreshHueRooms();
    }

    /** Re-reads the bridge's rooms, then redraws the widget and this list. */
    private void refreshHueRooms() {
        Runnable widget = HueWidget.redraw(this, true);
        HueRepo.kick(this, true, () -> {
            widget.run();
            runOnUiThread(() -> showHue(null));
        });
    }

    /** The Hue section: status (or `message`), Forget when paired, and a checkbox per room and zone. */
    private void showHue(String message) {
        if (isFinishing() || isDestroyed()) return;
        boolean paired = HueRepo.paired(this);
        String status = paired ? "Paired. Tick up to 4 rooms for the Dot Hue widget, in the order you want them."
            : "Pair your Hue bridge so the Dot Hue widget can switch and dim your rooms. You'll press the button on top of the bridge.";
        hueStatus.setText(message != null ? message : status);
        findViewById(R.id.btn_hue_forget).setVisibility(paired ? View.VISIBLE : View.GONE);
        hueRooms.removeAllViews();
        if (!paired) return;
        List<HueGroup> groups = HueRepo.groups(this);
        List<String> sel = HueRepo.selection(this);
        if (groups.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Loading rooms…");
            t.setTextColor(0xFFAAAAAA);
            hueRooms.addView(t);
            return;
        }
        for (HueGroup g : groups) {
            int at = sel.indexOf(g.id);
            CheckBox cb = new CheckBox(this);
            cb.setText((at >= 0 ? (at + 1) + ". " : "") + g.name + (g.zone ? " (zone)" : ""));
            cb.setTextColor(0xFFFFFFFF);
            cb.setChecked(at >= 0);
            cb.setOnClickListener(v -> {
                List<String> now = HueRepo.selection(this), next = HueRows.toggleSelection(now, g.id);
                if (next.equals(now)) android.widget.Toast.makeText(this, "Up to 4 rooms", android.widget.Toast.LENGTH_SHORT).show();
                HueRepo.setSelection(this, next);
                HueWidget.redraw(this, true).run();
                showHue(null);
            });
            hueRooms.addView(cb);
        }
    }

    private void pairHue() {
        if (huePairing) return;
        huePairing = true;
        showHue("Looking for your Hue bridge…");
        HueDiscovery.find(this, null, 8_000, new HueDiscovery.Callback() {
            @Override
            public void onFound(String host, String bridgeId) {
                if (bridgeId == null) {
                    huePairing = false;
                    showHue("That bridge didn't identify itself. Try again.");
                    return;
                }
                showHue("Press the round button on top of your Hue bridge now (you have 30 seconds).");
                pollPair(host, bridgeId, System.currentTimeMillis() + 30_000);
            }

            @Override
            public void onNone() {
                huePairing = false;
                showHue("No Hue bridge found on this Wi-Fi.");
            }
        });
    }

    /** Asks the bridge for a key once a second until its button is pressed or the time is up (worker thread). */
    private void pollPair(String host, String bridgeId, long until) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            String[] r = null;
            while (System.currentTimeMillis() < until) {
                r = HueBridge.pair(host, bridgeId);
                if (r != null && r.length == 2) break;
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            final String[] res = r;
            boolean ok = res != null && res.length == 2;
            if (ok) HueRepo.savePairing(app, host, bridgeId, res[0], res[1]);
            runOnUiThread(() -> {
                huePairing = false;
                if (ok) {
                    showHue("Paired. Loading your rooms…");
                    refreshHueRooms();
                } else {
                    showHue(res == null ? "Couldn't pair with the bridge. Try again."
                        : "The bridge's button wasn't pressed in time. Try again.");
                }
            });
        }, "dothome-hue-pair").start();
    }

    private void keyHint(EditText keyField) {
        keyField.setHint(CtaRepo.hasKey(this) ? "Key saved (type a new one to replace it)" : "CTA Train Tracker key");
    }

    @Override
    protected void onResume() {
        super.onResume();
        stepLocation.setText(mark(hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) + "1. Location"
            + (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ? " (precise)" : " (approximate: tap for precise)"));
        stepBackground.setText(mark(hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            + "2. Background location");
        stepNotifications.setText(mark(notificationAccessEnabled()) + "3. Notification access");
        stepWallpaper.setText(mark(wallpaperIsOurs()) + "4. Set wallpaper");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            onResume();
            WeatherJob.refreshSoon(this);
        } else if (requestCode == REQ_AUDIO) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                new PrefsStore(this).put(PrefsStore.KEY_BEAT, "false");
                if (beatSwitch != null) beatSwitch.setChecked(false);
            }
        }
    }

    private static String mark(boolean done) { return done ? "✓  " : "○  "; }

    private boolean hasPermission(String p) {
        if (p.equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION) && Build.VERSION.SDK_INT < 29) {
            return hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notificationAccessEnabled() {
        if (Build.VERSION.SDK_INT >= 27) {
            try {
                NotificationManager nm = getSystemService(NotificationManager.class);
                return nm != null && nm.isNotificationListenerAccessGranted(new ComponentName(this, MediaWatcher.class));
            } catch (RuntimeException e) {
                return false;
            }
        }
        try {
            String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            if (s == null) return false;
            ComponentName me = new ComponentName(this, MediaWatcher.class);
            for (String part : s.split(":")) {
                if (me.equals(ComponentName.unflattenFromString(part))) return true;
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean wallpaperIsOurs() {
        try {
            android.app.WallpaperInfo info = WallpaperManager.getInstance(this).getWallpaperInfo();
            return info != null && getPackageName().equals(info.getPackageName())
                && DotWallpaperService.class.getName().equals(info.getServiceName());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            Log.w("DotHome", "cannot open settings: " + e);
        }
    }
}
