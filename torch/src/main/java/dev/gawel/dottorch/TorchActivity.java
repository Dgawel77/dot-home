package dev.gawel.dottorch;

import android.app.Activity;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * "Dot Torch": an invisible launcher entry that flips the flashlight and closes at once.
 * It exists so RegiStar's Back-Tap "Open app" action can toggle the torch, which it has no built-in action for.
 * The torch's current state comes from the first TorchCallback event, which fires right after registering.
 * setTorchMode needs no camera permission.
 */
public class TorchActivity extends Activity {
    private static final String TAG = "DotTorch";
    /** Give up if the camera service never reports the torch state (it normally answers in a few ms). */
    private static final long TIMEOUT_MS = 600;

    private final Handler main = new Handler(Looper.getMainLooper());
    private CameraManager cm;
    private String torchId;
    private boolean done;

    private final CameraManager.TorchCallback callback = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String id, boolean enabled) {
            if (done || !id.equals(torchId)) return;
            try {
                cm.setTorchMode(torchId, !enabled);
            } catch (CameraAccessException | IllegalArgumentException e) {
                Log.w(TAG, "torch toggle failed", e);
            }
            finishNow();
        }

        @Override public void onTorchModeUnavailable(String id) {
            if (!done && id.equals(torchId)) finishNow(); // camera in use: leave it alone
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);
        cm = (CameraManager) getSystemService(CAMERA_SERVICE);
        torchId = findTorch(cm);
        if (torchId == null) { finishNow(); return; }
        cm.registerTorchCallback(callback, main);
        main.postDelayed(this::finishNow, TIMEOUT_MS);
    }

    /** The back camera with a flash unit, or any camera with one if no back one has it. */
    static String findTorch(CameraManager cm) {
        String any = null;
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                if (!Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) continue;
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
                if (any == null) any = id;
            }
        } catch (CameraAccessException e) {
            Log.w(TAG, "camera list failed", e);
        }
        return any;
    }

    private void finishNow() {
        if (done) return;
        done = true;
        main.removeCallbacksAndMessages(null);
        if (cm != null) cm.unregisterTorchCallback(callback);
        finish();
        noAnimation();
    }

    @SuppressWarnings("deprecation")
    private void noAnimation() {
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        else overridePendingTransition(0, 0);
    }
}
