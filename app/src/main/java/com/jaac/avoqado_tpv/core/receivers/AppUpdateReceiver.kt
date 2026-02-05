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
 * **Flow:**
 * 1. User triggers update via ForceUpdateDialog
 * 2. PAX SDK installs APK (kills current process)
 * 3. System sends MY_PACKAGE_REPLACED broadcast
 * 4. This receiver launches MainActivity
 * 5. App restarts automatically
 *
 * **Registration:** Declared in AndroidManifest.xml with intent-filter
 */
class AppUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Timber.i("🔄 [AppUpdate] Package replaced - relaunching app")

            // Launch the main activity
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            launchIntent?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            if (launchIntent != null) {
                context.startActivity(launchIntent)
                Timber.i("✅ [AppUpdate] App relaunched successfully")
            } else {
                Timber.e("❌ [AppUpdate] Could not get launch intent")
            }
        }
    }
}
