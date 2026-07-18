package com.nuvio.tv.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.nuvio.tv.BuildConfig
import java.io.File

object ApkInstaller {

    private const val TAG = "ApkInstaller"

    /** Action used by the status BroadcastReceiver for PackageInstaller session callbacks. */
    const val ACTION_INSTALL_STATUS = "com.nuvio.tv.updater.INSTALL_STATUS"

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Build the best-resolving intent to reach the "install unknown apps" settings screen.
     *
     * BRAVIA (and some other TV firmwares) don't always resolve the package-scoped
     * ACTION_MANAGE_UNKNOWN_APP_SOURCES variant, so we try, in order:
     *   1. ACTION_MANAGE_UNKNOWN_APP_SOURCES with package: uri
     *   2. ACTION_MANAGE_UNKNOWN_APP_SOURCES without uri
     *   3. ACTION_SECURITY_SETTINGS
     *   4. ACTION_SETTINGS
     * ...and return the first that actually resolves.
     */
    fun buildUnknownSourcesSettingsIntent(context: Context): Intent? {
        val candidates = mutableListOf<Intent>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            candidates += Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
            candidates += Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        }
        candidates += Intent(Settings.ACTION_SECURITY_SETTINGS)
        candidates += Intent(Settings.ACTION_SETTINGS)

        val pm = context.packageManager
        val resolved = candidates.firstOrNull { it.resolveActivity(pm) != null }
        if (resolved == null) {
            Log.w(TAG, "No unknown-sources settings intent resolved on this device")
            return null
        }
        return resolved.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Install the given APK using a PackageInstaller session. This surfaces the real
     * OS install UI (and STATUS_PENDING_USER_ACTION accurately), eliminating the class
     * of stale-precondition bugs that the deprecated ACTION_VIEW path suffered from on TV.
     *
     * Falls back to the legacy FileProvider + ACTION_VIEW path if session creation fails.
     */
    fun launchInstall(context: Context, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            Log.e(TAG, "APK missing or empty: ${apkFile.absolutePath}")
            return
        }
        try {
            installViaSession(context, apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller session failed, falling back to ACTION_VIEW", e)
            try {
                launchInstallLegacy(context, apkFile)
            } catch (e2: Exception) {
                Log.e(TAG, "Legacy ACTION_VIEW install also failed", e2)
            }
        }
    }

    private fun installViaSession(context: Context, apkFile: File) {
        val appContext = context.applicationContext
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(appContext.packageName)
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apkFile.inputStream().use { input ->
                session.openWrite("cinex_update", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val statusIntent = Intent(ACTION_INSTALL_STATUS).apply {
                `package` = appContext.packageName
                setClass(appContext, InstallResultReceiver::class.java)
            }

            // targetSdk 36: a mutable PendingIntent is required because the system fills in
            // extras (status, STATUS_PENDING_USER_ACTION confirmation intent) on callback.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE

            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                sessionId,
                statusIntent,
                flags
            )

            session.commit(pendingIntent.intentSender)
        }
    }

    /** Legacy deprecated install path, kept only as a fallback. */
    @Suppress("DEPRECATION")
    private fun launchInstallLegacy(context: Context, apkFile: File) {
        val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apkFile)

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(intent)
    }
}
