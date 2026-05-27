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

# ── Existing keeps (already in your file) ────────────────────────────────────
-keep class android.content.pm.IPackageManager { *; }
-keep class android.content.pm.IPackageManager$** { *; }
-keep class android.app.IActivityManager { *; }
-keep class android.app.IActivityManager$** { *; }
-keep class android.content.IClipboard { *; }
-keep class android.content.IClipboard$** { *; }

# ── ADD: Clipboard change listener (was being stripped, breaking monitoring) ──
-keep class android.content.IOnPrimaryClipChangedListener { *; }
-keep class android.content.IOnPrimaryClipChangedListener$** { *; }
-keep class android.content.IOnPrimaryClipChangedListener$Stub { *; }
-keep class android.content.IOnPrimaryClipChangedListener$Stub$Proxy { *; }

# ── ADD: IProcessObserver AIDL stub ──────────────────────────────────────────
-keep class android.app.IProcessObserver { *; }
-keep class android.app.IProcessObserver$** { *; }

# ── ADD: Shizuku internals used via reflection ────────────────────────────────
-keep class rikka.shizuku.** { *; }
-keep class rikka.shizuku.ShizukuBinderWrapper { *; }

# ── ADD: Gson — prevent stripping of data classes used in serialization ───────
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class nocom.rian.clipboardmanager.db.BackupFile { *; }
-keep class nocom.rian.clipboardmanager.db.ClipboardItem { *; }

# ── ADD: AutomationRule — required for Gson serialization ────────────────────
-keep class nocom.rian.clipboardmanager.db.AutomationRule { *; }
-keep class nocom.rian.clipboardmanager.db.AutomationRule$** { *; }

# Prevent stripping or modification of your explicit TypeToken declaration
-keep class nocom.rian.clipboardmanager.db.AutomationRuleListTypeToken { *; }

# ── ADD: Gson TypeToken anonymous classes — prevents ProGuard from stripping
#         generic type info needed for List<AutomationRule> deserialization ───
-keep class * extends com.google.gson.TypeToken
-keep public class * implements java.lang.reflect.Type

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
