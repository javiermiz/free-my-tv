package io.freemytv;

import android.util.Log;

/** The app's log (adb logcat -s FreeMyTV). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("FreeMyTV", message);
    }
}
