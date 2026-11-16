package dev.gawel.dothome;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Launcher stub; the real setup screen arrives in a later task. */
public class SetupActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("Dot Home");
        tv.setTextSize(24);
        setContentView(tv);
    }
}
