package io.github.xjc.jiagu;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/**
 * 壳 Application，只包含 native 声明和初始化调用。
 * 此类会被注入到宿主 APK 的主 DEX 中。
 */
public class ProxyApplication extends Application {

    private static final String TAG = "Jiagu_Proxy";

    static {
        System.loadLibrary("jiagu-core");
    }

    public native void nativeInit(Context context, String aesKey);

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Log.d(TAG, "ProxyApplication attachBaseContext -> 开始加固自解密");
        
        String aesKey = "DEFAULT_KEY";
        try {
            ApplicationInfo ai = base.getPackageManager().getApplicationInfo(
                    base.getPackageName(), PackageManager.GET_META_DATA);
            Bundle bundle = ai.metaData;
            if (bundle != null && bundle.containsKey("AES_KEY")) {
                aesKey = bundle.getString("AES_KEY");
                Log.d(TAG, "从 Manifest 读取 AES_KEY 成功");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load meta-data, NameNotFound: " + e.getMessage());
        }

        nativeInit(base, aesKey);
    }
}
