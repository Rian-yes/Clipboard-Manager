# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# ── AIDL interfaces mocking system APIs & Shizuku IPC ───────────────────────
-keep class android.content.IClipboard { *; }
-keep class android.content.IClipboard$** { *; }
-keep class android.content.IOnPrimaryClipChangedListener { *; }
-keep class android.content.IOnPrimaryClipChangedListener$** { *; }
-keep class android.content.IOnPrimaryClipChangedListener$Stub { *; }
-keep class android.content.IOnPrimaryClipChangedListener$Stub$Proxy { *; }
-keep class android.content.pm.IPackageManager { *; }
-keep class android.content.pm.IPackageManager$** { *; }
-keep class android.app.IActivityManager { *; }
-keep class android.app.IActivityManager$** { *; }
-keep class android.app.IProcessObserver { *; }
-keep class android.app.IProcessObserver$** { *; }

# ── Shizuku internals used directly or via reflection ────────────────────────
-keep class rikka.shizuku.** { *; }
-keep class rikka.shizuku.ShizukuBinderWrapper { *; }

# ── Gson configuration ───────────────────────────────────────────────────────
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-keep class com.google.gson.annotations.SerializedName { *; }
-keep class com.google.gson.annotations.Expose { *; }
-keep class com.google.gson.annotations.Since { *; }
-keep class com.google.gson.annotations.Until { *; }
-keep class com.google.gson.annotations.JsonAdapter { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep public class * implements java.lang.reflect.Type

# Keep backup and serialization model classes intact
-keep class nocom.rian.clipboardmanager.db.ClipboardItem { *; }
-keep class nocom.rian.clipboardmanager.db.BackupManifest { *; }
-keep class nocom.rian.clipboardmanager.db.AutomationRule { *; }
-keep class nocom.rian.clipboardmanager.db.BackupResult { *; }
-keep class nocom.rian.clipboardmanager.db.AutomationRuleList { *; }
-keep class nocom.rian.clipboardmanager.db.BackupManager { *; }
-keep class nocom.rian.clipboardmanager.db.ClipboardDao { *; }
-keep class nocom.rian.clipboardmanager.AdvancedActivity$Companion { *; }
-keep class nocom.rian.clipboardmanager.AdvancedActivity {
    public static final nocom.rian.clipboardmanager.AdvancedActivity$Companion Companion;
}

# ── ADD: Room entities — prevent column name mangling ────────────────────────
-keep @androidx.room.Entity class * { *; }
-keepclassmembers @androidx.room.Entity class * { *; }

# ── ADD: Preserve reflection targets used by invokeClipboard/invokeDynamic ───
-keepclassmembers class * extends android.os.Binder {
    public *;
}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile