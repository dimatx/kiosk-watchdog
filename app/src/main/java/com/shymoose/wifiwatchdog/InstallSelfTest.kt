package com.shymoose.wifiwatchdog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Reproduces the package installer's update dialog on demand.
 *
 * The auto-confirm service can otherwise only be exercised by waiting for
 * another app to ship an update, which makes it untestable on a wall display.
 * Handing the app its own APK back produces the identical dialog — same
 * signature and same version code means the installer offers to "install an
 * update to this existing application", which is exactly the screen the service
 * is written against.
 *
 * Triggering the dialog from adb is not an option: the implicit VIEW intent is
 * refused because the shell uid is not an authorised installer source, and the
 * explicit component is not exported.
 */
object InstallSelfTest {

    private const val DIR = "selftest"
    private const val FILE = "update.apk"

    /**
     * Copies the running APK somewhere the installer can read and launches the
     * confirm dialog against it.
     *
     * Does file I/O, so call this off the main thread. Returns null on success,
     * otherwise a message worth showing the user.
     */
    fun run(context: Context): String? {
        requestUnknownSources(context)?.let { return it }

        val source = File(context.applicationInfo.sourceDir)
        if (!source.isFile) return "Cannot locate the installed APK"

        val target = File(context.cacheDir, "$DIR/$FILE")
        val copied = runCatching {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
        }
        copied.exceptionOrNull()?.let {
            EventLog.add(context, EventLevel.ERROR, "Self-test could not stage the APK: ${it.message}")
            return "Could not stage the APK"
        }

        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        }.getOrElse {
            EventLog.add(context, EventLevel.ERROR, "Self-test could not share the APK: ${it.message}")
            return "Could not share the APK"
        }

        // The dialog shows this app's own name, which is deliberately not in the
        // allowlist — arm a short window instead of teaching the service to
        // always approve installs of itself.
        InstallAutoClickService.armSelfTest()

        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }
        return runCatching {
            context.startActivity(intent)
            EventLog.add(context, EventLevel.ACTION, "Started auto-confirm self-test")
            null
        }.getOrElse {
            EventLog.add(context, EventLevel.ERROR, "Self-test could not open the installer: ${it.message}")
            "Could not open the installer"
        }
    }

    /**
     * Sends the user to grant "install unknown apps" if it is still missing.
     *
     * Declaring REQUEST_INSTALL_PACKAGES is not enough from Android 8: it only
     * gets the app listed on the special-access screen, where the switch starts
     * off. Without it the installer answers with "not allowed to install unknown
     * apps from this source" and the self-test never reaches its dialog.
     *
     * There is no runtime prompt for this, so the settings screen is the only
     * route — which is also the route a Fire tablet has to take, since none of
     * this can be granted over adb there.
     *
     * Returns null when the grant is already held, otherwise a message for the
     * user. Kiosk Satellite needs its own grant to update itself; this one only
     * covers the self-test.
     */
    private fun requestUnknownSources(context: Context): String? {
        if (context.packageManager.canRequestPackageInstalls()) return null

        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            EventLog.add(
                context,
                EventLevel.WARN,
                "Self-test needs \"install unknown apps\" — opened the settings screen"
            )
            "Allow installing unknown apps, then run the self-test again"
        }.getOrElse {
            EventLog.add(context, EventLevel.ERROR, "Could not open unknown-sources settings: ${it.message}")
            "Allow \"install unknown apps\" for this app, then try again"
        }
    }
}
