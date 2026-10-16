package lows.dgeon.ightr.jiagu;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/** Public API fixture: verify the system's actual Application and JNI activity. */
public final class LocalMvpInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments=args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ClassLoader loader = getTargetContext().getClassLoader();
            if (!(loader instanceof dalvik.system.InMemoryDexClassLoader)) throw new AssertionError("Application loader is not InMemoryDexClassLoader");
            android.app.Application app = (android.app.Application) getTargetContext().getApplicationContext();
            if (!app.getClass().getName().equals("lows.dgeon.ightr.jiagu.Application") || app.getClass().getClassLoader() != loader)
                throw new AssertionError("System did not attach the real Payload Application");
            Intent intent = new Intent().setClassName(getTargetContext(), "lows.dgeon.ightr.jiagu.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Activity activity = startActivitySync(intent);
            if (activity.getClass().getClassLoader() != loader) throw new AssertionError("Activity class identity mismatch");
            Object nativeResult = activity.getClass().getMethod("stringFromJNI").invoke(activity);
            if (!"Hello from C++".equals(nativeResult)) throw new AssertionError("JNI result: " + nativeResult);
            CoroutineServicesTest.platformRunner = this;
            new CoroutineServicesTest().discoversAndInstantiatesBothServices();
            new CoroutineServicesTest().lifecycleScopeDispatchersMainRunsOnMainLooper();
            StartupEventProtocolTest.verify(getTargetContext());
            String expected = arguments == null ? null : arguments.getString("expectedStartupStoreSha256");
            if (expected != null) {
                java.security.MessageDigest hash = java.security.MessageDigest.getInstance("SHA-256");
                java.io.File dir = new java.io.File(getTargetContext().getNoBackupFilesDir(), "jiagu-startup-v1");
                java.io.File[] files = dir.listFiles();
                if (files != null) {
                    java.util.Arrays.sort(files, java.util.Comparator.comparing(java.io.File::getName));
                    byte[] buffer = new byte[8192];
                    for (java.io.File file : files) if (file.isFile()) {
                        hash.update(file.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8)); hash.update((byte)0);
                        try(java.io.InputStream in=new java.io.FileInputStream(file)) { int n; while((n=in.read(buffer))!=-1) hash.update(buffer,0,n); }
                    }
                }
                StringBuilder hex=new StringBuilder(); for(byte b:hash.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                if (!expected.contentEquals(hex)) throw new AssertionError("Disabled telemetry modified persistent startup store: "+hex);
            }
            result.putString("stream", "PASS: Payload Application + Activity identity, native JNI, SPI, coroutines, legacy event JSON\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("stream", Log.getStackTraceString(failure)); finish(Activity.RESULT_CANCELED, result);
        }
    }
}
