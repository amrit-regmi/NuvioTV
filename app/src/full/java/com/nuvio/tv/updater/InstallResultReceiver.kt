package com.nuvio.tv.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Receives status callbacks from the PackageInstaller session used by [ApkInstaller].
 *
 * The important case is [PackageInstaller.STATUS_PENDING_USER_ACTION]: the system hands back
 * a confirmation Intent that we must launch (FLAG_ACTIVITY_NEW_TASK, since we're a receiver
 * with no activity context) to show the real OS install-confirm UI. Once the user confirms
 * (or the grant is already ON), the OS drives the rest of the install itself.
 *
 * Registered in the `full` flavor manifest. Not exported.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirmIntent) }
                        .onFailure { Log.e(TAG, "Failed to launch install-confirm UI", it) }
                } else {
                    Log.e(TAG, "STATUS_PENDING_USER_ACTION with no confirm intent")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Update install succeeded")
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.e(TAG, "Update install failed (status=$status): $msg")
            }
        }
    }

    private companion object {
        const val TAG = "InstallResultReceiver"
    }
}
