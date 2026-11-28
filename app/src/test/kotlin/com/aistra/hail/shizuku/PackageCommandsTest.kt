package com.aistra.hail.shizuku

import org.junit.Assert.*
import org.junit.Test

class PackageCommandsTest {
    @Test fun bothOperationsAlwaysUseTheExplicitCurrentAndroidUser() {
        assertEquals(listOf("/system/bin/pm", "uninstall", "--user", "10", "com.example.app"),
            PackageCommands.uninstall("com.example.app", 10))
        assertEquals(listOf("/system/bin/pm", "install-existing", "--user", "0", "android"),
            PackageCommands.reinstall("android", 0))
    }

    @Test fun packageArgumentsCannotInjectShellCommandsOrPackageManagerOptions() {
        for (name in listOf("", "-user", "--user 10", "com.example;id", "com.example\nreboot",
            "com.${'$'}(id).app", "../other", "com..app", "com.example'", "a".repeat(256))) {
            assertThrows(IllegalArgumentException::class.java) { PackageCommands.uninstall(name, 0) }
            assertThrows(IllegalArgumentException::class.java) { PackageCommands.reinstall(name, 0) }
        }
        assertThrows(IllegalArgumentException::class.java) { PackageCommands.uninstall("com.example.app", -1) }
    }
}
