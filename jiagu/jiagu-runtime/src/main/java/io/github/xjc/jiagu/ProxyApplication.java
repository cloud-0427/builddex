package io.github.xjc.jiagu;

import android.app.Application;
import android.content.Context;
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
        
        // 实际开发中，此密钥应通过网络动态获取，此处为占位。
        String aesKey = "MY_SECURE_AES_KEY"; 
        nativeInit(base, aesKey);
    }
}
