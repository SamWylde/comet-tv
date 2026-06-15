# Keep JS interface methods callable from injected scripts (PopupGuard / cosmetic layer).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# WorkManager instantiates workers reflectively.
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
