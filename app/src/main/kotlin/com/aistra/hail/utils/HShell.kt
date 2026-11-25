package com.aistra.hail.utils

import android.os.Build
import androidx.annotation.RequiresApi

object HShell {
    private val userArg: String
        get() = "--user ${HPackages.myUserId}"

    fun execute(command: String, root: Boolean): Pair<Int, String?> = runCatching {
        ProcessBuilder(if (root) "su" else "sh").redirectErrorStream(true).start().run {
            outputStream.use { it.write(command.toByteArray()) }
            val output = inputStream.bufferedReader().use { it.readText() }
            waitFor() to output
        }
    }.getOrElse { 1 to it.stackTraceToString() }

    private fun execSU(command: String) = execute(command, true)

    val checkSU get() = execSU("whoami").first == 0

    val lockScreen get() = execSU("input keyevent KEYCODE_POWER").first == 0

    private fun packageCommand(command: String, pkg: String, trace: DecisionTrace?): Boolean {
        val (code, message) = execSU(command)
        trace?.event("root_response", pkg, "exitCode" to code,
            "error" to if (code == 0) null else message?.lineSequence()?.firstOrNull())
        return code == 0
    }

    fun forceStopApp(packageName: String, trace: DecisionTrace? = null): Boolean =
        packageCommand("am force-stop $userArg $packageName", packageName, trace)

    /** Query once per batch as the same root identity and Android user that will perform the write. */
    fun installedEnabledStates(trace: DecisionTrace? = null): Map<String, Boolean>? {
        val user = HPackages.myUserId
        val (code, output) = execSU("pm list packages --user $user && echo HAIL_DISABLED && pm list packages -d --user $user")
        trace?.event("root_inventory", null, "user" to user, "exitCode" to code)
        if (code != 0 || output == null) return null
        val sections = output.lineSequence().map(String::trim).toList()
        val separator = sections.indexOf("HAIL_DISABLED")
        if (separator < 0) return null
        return buildMap {
            sections.forEachIndexed { index, line ->
                if (line.startsWith("package:")) put(line.removePrefix("package:"), index < separator)
            }
        }
    }

    fun setAppDisabled(packageName: String, disabled: Boolean, trace: DecisionTrace? = null): Boolean {
        val pkg = "'" + packageName.replace("'", "'\\''") + "'"
        val user = HPackages.myUserId
        val change = if (disabled) "disable" else "enable"
        val filter = if (disabled) "-d" else "-e"
        trace?.event("android_call", packageName, "method" to "root pm $change", "user" to user)
        val (code, output) = execSU("pm $change --user $user $pkg && pm list packages $filter --user $user $pkg")
        if (code != 0) throw IllegalStateException(output?.trim()?.takeIf { it.isNotEmpty() } ?: "pm ($code)")
        val verified = output?.lineSequence()?.any { it.trim() == "package:$packageName" } == true
        trace?.event("readback", packageName, "reader" to "root pm list packages $filter", "exitCode" to code,
            "settingReader" to "local PackageManager", "observedSetting" to HPackages.currentEnabledSetting(packageName), "verified" to verified)
        return verified
    }

    fun setAppHidden(packageName: String, hidden: Boolean, trace: DecisionTrace? = null): Boolean =
        packageCommand("pm ${if (hidden) "hide" else "unhide"} $userArg $packageName", packageName, trace)

    fun setAppSuspended(packageName: String, suspended: Boolean, trace: DecisionTrace? = null): Boolean =
        packageCommand("pm ${if (suspended) "suspend" else "unsuspend"} $userArg $packageName", packageName, trace)

    fun uninstallApp(packageName: String) = execSU(
        "pm ${if (HPackages.canUninstallNormally(packageName)) "uninstall" else "uninstall $userArg"} $packageName"
    ).first == 0

    fun reinstallApp(packageName: String) = execSU("pm install-existing $userArg $packageName").first == 0

    @RequiresApi(Build.VERSION_CODES.P)
    fun setAppRestricted(packageName: String, restricted: Boolean) = execSU(
        "appops set $userArg $packageName RUN_ANY_IN_BACKGROUND ${if (restricted) "ignore" else "allow"}"
    ).first == 0

    fun grantPermission(packageName: String, permission: String) =
        execSU("pm grant $userArg $packageName $permission").first == 0
}
