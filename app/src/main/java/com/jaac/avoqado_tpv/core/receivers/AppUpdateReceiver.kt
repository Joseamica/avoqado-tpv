package com.jaac.avoqado_tpv.core.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * App Update Receiver
 *
 * Listens for MY_PACKAGE_REPLACED broadcast to auto-relaunch the app after
 * a self-update via PAX SDK silent install.
 *
 * **Flow (PAX SDK self-update):**
 * 1. User triggers update via ForceUpdateDialog / SelfUpdateScreen
 * 2. PAX SDK installs APK (kills current process)
 * 3. System sends MY_PACKAGE_REPLACED broadcast
 * 4. This receiver launches MainActivity (isActivityResumed = false, process is fresh)
 * 5. App restarts automatically
 *
 * **Flow (ADB debug install):**
 * 1. Developer runs `./gradlew installSandboxDebug`
 * 2. Launcher starts the app immediately (MainActivity.onResume sets isActivityResumed = true)
 * 3. ~100ms later, MY_PACKAGE_REPLACED broadcast fires
 * 4. This receiver checks isActivityResumed → true → skips relaunch
 * 5. No duplicate activity, no coroutine cancellation
 *
 * **Why not use ActivityManager.runningAppProcesses?**
 * Android elevates process priority to FOREGROUND during onReceive(), so
 * runningAppProcesses would report IMPORTANCE_FOREGROUND even when no activity
 * exists (after PAX SDK kill), incorrectly blocking the relaunch.
 *
 * **Registration:** Declared in AndroidManifest.xml with intent-filter
 */
class AppUpdateReceiver : BroadcastReceiver() {

    companion object {
        /**
         * Static flag set by MainActivity.onResume() / onPause().
         * True = an activity is visible on screen (ADB install scenario).
         * False = no activity exists (PAX SDK self-update scenario).
         *
         * This is a JVM static field — it survives within the same process but
         * resets to false when the process is killed (which is exactly what
         * PAX SDK does during install).
         */
        @Volatile
        var isActivityResumed: Boolean = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // Skip relaunch if an activity is already visible on screen.
            // During ADB installs, the launcher starts the app before this
            // broadcast fires, so isActivityResumed is true.
            // During PAX SDK self-updates, the process was killed, so
            // isActivityResumed is false (JVM static reset on process death).
            if (isActivityResumed) {
                Timber.i("🔄 [AppUpdate] Package replaced but activity already visible - skipping relaunch (likely ADB install)")
                return
            }

            Timber.i("🔄 [AppUpdate] Package replaced - relaunching app")

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            launchIntent?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

            if (launchIntent != null) {
                context.startActivity(launchIntent)
                Timber.i("✅ [AppUpdate] App relaunched successfully")
            } else {
                Timber.e("❌ [AppUpdate] Could not get launch intent")
            }
        }
    }
}
