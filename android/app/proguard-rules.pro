# Keep JS interface for WebView
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# Keep ONNX Runtime classes and JNI native bindings
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep models and data classes if any
-keep class com.germaz.germed.** { *; }
