package io.github.xjc.jiagu;

import android.app.Application;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

/**
 * 极简壳程序，所有逻辑均在 JNI 层实现。
 */
public class ProxyApplication extends Application {

    private static final String TAG = "Jiagu_Proxy";

    private void loadCore() {
        long startedAt = SystemClock.elapsedRealtime();
        JiaguStartupReporter.startupAttemptStarted();
        try {
            System.loadLibrary("jiagu-core");
            JiaguStartupReporter.shellCoreLoadSucceeded(
                    SystemClock.elapsedRealtime() - startedAt);
        } catch (Throwable error) {
            JiaguStartupReporter.shellCoreLoadFailed(error,
                    SystemClock.elapsedRealtime() - startedAt);
            throw error;
        }
        Log.i(TAG, "[StartupTiming] stage=load-jiagu-core-library durationMs=" +
                (SystemClock.elapsedRealtime() - startedAt));
    }

    public native int nativeAttach(Context context);
    public native void nativeOnCreate();

    @Override
    protected void attachBaseContext(Context base) {
        long startedAt = SystemClock.elapsedRealtime();
        super.attachBaseContext(base);
        JiaguStartupReporter.initialize(base);
        loadCore();
        JiaguStartupReporter.beginDecryption(base);
        int nativeResult = nativeAttach(base);
        if (nativeResult != 0) {
            JiaguStartupReporter.stageFailed(JiaguStartupEvent.Stage.SHELL_ATTACH,
                    "NATIVE_ATTACH_FAILED", SystemClock.elapsedRealtime() - startedAt);
            throw new IllegalStateException("Jiagu native startup failed: " + nativeResult);
        }
        JiaguStartupReporter.stageSucceeded(JiaguStartupEvent.Stage.SHELL_ATTACH,
                "SHELL_ATTACH_COMPLETED", SystemClock.elapsedRealtime() - startedAt);
        Log.i(TAG, "[StartupTiming] complete proxy attachBaseContext totalMs=" +
                (SystemClock.elapsedRealtime() - startedAt));
    }

    @Override
    public void onCreate() {
        long startedAt = SystemClock.elapsedRealtime();
        super.onCreate();
        nativeOnCreate();
        Log.i(TAG, "[StartupTiming] complete proxy onCreate totalMs=" +
                (SystemClock.elapsedRealtime() - startedAt));
    }
}
