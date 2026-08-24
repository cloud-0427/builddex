package io.github.xjc.jiagu;

import android.app.Application;
import android.content.Context;
import android.util.Log;

/**
 * 极简壳程序，所有逻辑均在 JNI 层实现。
 */
public class ProxyApplication extends Application {

    private static final String TAG = "Jiagu_Proxy";

    static {
        System.loadLibrary("jiagu-core");
    }

    public native void nativeAttach(Context context);
    public native void nativeOnCreate();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Log.d(TAG, "Java: Calling nativeAttach...");
        nativeAttach(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Java: Calling nativeOnCreate...");
        nativeOnCreate();
    }
}
