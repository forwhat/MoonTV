# Keep the native bridge callable after release minification.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
