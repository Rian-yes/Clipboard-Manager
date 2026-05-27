package android.app;

import android.app.ActivityManager;
import java.util.List;

interface IActivityManager {
    // Reverted to 1 parameter to match your system's framework
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum);

    // Added as a more reliable fallback for Android 13/14
    List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses();
}
