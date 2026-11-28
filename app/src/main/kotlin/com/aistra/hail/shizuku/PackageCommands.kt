package com.aistra.hail.shizuku

internal object PackageCommands {
    private val packageNamePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

    private fun command(action: String, packageName: String, userId: Int): List<String> {
        require(userId >= 0) { "An explicit Android user is required" }
        require(packageName.length in 1..255 && packageNamePattern.matches(packageName)) {
            "Invalid package name"
        }
        // Arguments go directly to ProcessBuilder: package identifiers are never parsed by a shell.
        return listOf("/system/bin/pm", action, "--user", userId.toString(), packageName)
    }

    fun uninstall(packageName: String, userId: Int) = command("uninstall", packageName, userId)
    fun reinstall(packageName: String, userId: Int) = command("install-existing", packageName, userId)
}
