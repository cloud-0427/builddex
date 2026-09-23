# Keep only the stable JNI and reflection entry points. All other runtime classes are
# eligible for R8 shrinking, optimization, and obfuscation in the shell pipeline.
-keep,allowoptimization class io.github.xjc.jiagu.ProxyApplication {
    public <init>();
    public native int nativeAttach(android.content.Context);
    public native void nativeOnCreate();
}
-keep,allowoptimization class io.github.xjc.jiagu.JiaguStartupReporter {
    public static void nativeStageFinished(int, int, java.lang.String, long);
    public static void observeFirstActivity(android.app.Application);
}
-keep,allowoptimization interface io.github.xjc.jiagu.JiaguStartupLogUploader {
    public void upload(android.content.Context, io.github.xjc.jiagu.JiaguStartupEvent);
}
-keep class androidx.annotation.Keep

# Keep all R classes in the main DEX to avoid NoClassDefFoundError when business code
# is loaded via custom ClassLoader and references resources.
-keep class **.R$* {
    public static <fields>;
}

# Google Play Integrity and GMS Tasks missing classes warnings
-dontwarn com.google.android.gms.tasks.Task
-dontwarn com.google.android.gms.tasks.Tasks
-dontwarn com.google.android.play.core.integrity.IntegrityManagerFactory
-dontwarn com.google.android.play.core.integrity.StandardIntegrityManager$PrepareIntegrityTokenRequest$Builder
-dontwarn com.google.android.play.core.integrity.StandardIntegrityManager$PrepareIntegrityTokenRequest
-dontwarn com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityToken
-dontwarn com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityTokenProvider
-dontwarn com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityTokenRequest$Builder
-dontwarn com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityTokenRequest
-dontwarn com.google.android.play.core.integrity.StandardIntegrityManager
