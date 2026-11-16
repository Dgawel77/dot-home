package dev.gawel.dothome;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import dev.gawel.dothome.core.FireMode;

/** Launcher stub with the fire-mode selector; the full setup screen arrives in a later task. */
public class SetupActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PrefsStore prefs = new PrefsStore(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView tv = new TextView(this);
        tv.setText("Dot Home");
        tv.setTextSize(24);
        root.addView(tv);

        TextView label = new TextView(this);
        label.setText("Fire mode");
        label.setPadding(0, pad, 0, 0);
        root.addView(label);

        FireMode[] modes = {FireMode.TAP_COMMAND, FireMode.TAP_TOUCH, FireMode.AUTO};
        String[] names = {"Tap (home screen tap)", "Tap (raw touch)", "Auto-fire"};
        FireMode current;
        try {
            current = FireMode.valueOf(prefs.get("fire.mode", FireMode.TAP_COMMAND.name()));
        } catch (IllegalArgumentException e) {
            current = FireMode.TAP_COMMAND;
        }
        RadioGroup group = new RadioGroup(this);
        for (int i = 0; i < modes.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(i + 1);
            rb.setText(names[i]);
            group.addView(rb);
            if (modes[i] == current) group.check(rb.getId());
        }
        group.setOnCheckedChangeListener((g, id) -> {
            if (id >= 1 && id <= modes.length) prefs.put("fire.mode", modes[id - 1].name());
        });
        root.addView(group);
        setContentView(root);
    }
}
