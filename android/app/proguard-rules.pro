# Ifallhub Android — ProGuard/R8 rules.
#
# Keep Capacitor plugins and FFmpegKit so the JS bridge and the native
# transcoder survive minification (release builds run with minifyEnabled true).

# --- Capacitor -------------------------------------------------------------
-keep class com.getcapacitor.** { *; }
-keep @com.getcapacitor.annotation.CapacitorPlugin class * { *; }
-keepclassmembers class * {
    @com.getcapacitor.PluginMethod public *;
}
-keep class com.ifallhub.app.** { *; }
-keepattributes JavascriptInterface
-keepattributes *Annotation*
-dontwarn com.getcapacitor.**

# --- FFmpegKitNext ---------------------------------------------------------
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# --- WebView JS bridge -----------------------------------------------------
-keepclassmembers class fqcn.of.javascript.interface.for.webview {
   public *;
}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Shaka player native helpers (loaded from the bundled JS) --------------
-dontwarn org.w3c.dom.**
-dontwarn org.ebml.**
