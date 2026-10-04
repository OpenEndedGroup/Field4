package afield.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstRunTest {
    @Test fun browserOnlyWithoutExtrasDoesNotRequireCompilersOrDesktopPackages() {
        assertTrue(packagePlan("apt-get", false, setOf("g++", "cmake"), emptySet(), true).commands.isEmpty())
    }
    @Test fun ubuntuDesktopUsesT64NamesAndPythonIsSeparateFromJep() {
        val plan = packagePlan("apt-get", true, setOf("g++"), setOf("python"), true)
        val argv = plan.commands.single()
        assertTrue("libgtk-3-0t64" in argv && "libasound2t64" in argv)
        assertTrue("build-essential" in argv && "python3-dev" in argv)
        assertFalse("jep" in argv)
        assertTrue(plan.notes.any { "not JEP" in it })
    }
    @Test fun archDoesNotPerformAPartialUpgradeOrForceApproval() {
        val argv = packagePlan("pacman", true, setOf("cmake"), emptySet(), false).commands.single()
        assertEquals(listOf("sudo", "pacman", "-S", "--needed", "--"), argv.take(5))
        assertFalse(argv.any { it in setOf("--noconfirm", "-Sy") })
    }
    @Test fun unsupportedPackageManagerProducesGuidanceAndNoShellCommand() {
        val plan = packagePlan(null, true, setOf("git-lfs"), setOf("python", "vulkan"), false)
        assertTrue(plan.commands.isEmpty())
        assertTrue(plan.notes.any { "git-lfs" in it })
    }
}
