# Conservative: keep this app's own code intact (WorkManager workers, services, JSON parsing, reflection-free but safe), shrink libraries.
-keep class dev.optimizer.android.** { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Shizuku API/provider use reflection + AIDL stubs
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.**
-dontwarn moe.shizuku.**
