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

    static {
        long startedAt = SystemClock.elapsedRealtime();
        System.loadLibrary("jiagu-core");
        Log.i(TAG, "[StartupTiming] stage=load-jiagu-core-library durationMs=" +
                (SystemClock.elapsedRealtime() - startedAt));
    }

    public native void nativeAttach(Context context);
    public native void nativeOnCreate();

    @Override
    protected void attachBaseContext(Context base) {
        long startedAt = SystemClock.elapsedRealtime();
        super.attachBaseContext(base);
        Log.i(TAG, "[StartupTiming] begin proxy attachBaseContext");
        nativeAttach(base);
        Log.i(TAG, "[StartupTiming] complete proxy attachBaseContext totalMs=" +
                (SystemClock.elapsedRealtime() - startedAt));
    }

    @Override
    public void onCreate() {
        long startedAt = SystemClock.elapsedRealtime();
        super.onCreate();
        Log.i(TAG, "[StartupTiming] begin proxy onCreate");
        nativeOnCreate();
        Log.i(TAG, "[StartupTiming] complete proxy onCreate totalMs=" +
                (SystemClock.elapsedRealtime() - startedAt));
    }
}
