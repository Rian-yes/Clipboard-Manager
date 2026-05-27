package android.content.pm;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;

interface IPackageManager {
    ApplicationInfo getApplicationInfo(String packageName, long flags, int userId);
    PackageInfo getPackageInfo(String packageName, long flags, int userId);
    ActivityInfo getActivityInfo(in ComponentName component, long flags, int userId);
}