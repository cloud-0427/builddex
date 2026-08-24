# Keep the ProxyApplication class and all related shell code
-keep class io.github.xjc.jiagu.** { *; }
-keep interface io.github.xjc.jiagu.** { *; }

# Keep App Startup and Core components to ensure they are available in the shell
# These are essential because they are whitelisted in JiaguTask to stay in the main DEX
-keep class androidx.startup.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.annotation.Keep

# Keep all R classes in the main DEX to avoid NoClassDefFoundError when business code
# is loaded via custom ClassLoader and references resources.
-keep class **.R$* {
    public static <fields>;
}
