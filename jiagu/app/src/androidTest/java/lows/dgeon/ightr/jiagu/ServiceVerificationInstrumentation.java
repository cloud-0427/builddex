package lows.dgeon.ightr.jiagu;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/** Platform-only runner: AndroidX runner references classes hidden/renamed in the payload. */
public final class ServiceVerificationInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            waitForIdleSync();
            CoroutineServicesTest.platformRunner = this;
            CoroutineServicesTest tests = new CoroutineServicesTest();
            tests.discoversAndInstantiatesBothServices();
            result.putString("services", "PASS: both providers discovered and instantiated");
            tests.lifecycleScopeDispatchersMainRunsOnMainLooper();
            result.putString("dispatchersMain", "PASS: task executed on main Looper");
            result.putString("stream", "OK (2 service verification tests)\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
