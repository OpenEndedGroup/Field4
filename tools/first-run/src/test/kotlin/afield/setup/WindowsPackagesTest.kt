package afield.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsPackagesTest {
    @Test fun defaultWindowsDesktopNeedsNoWingetPackages() {
        assertTrue(packagePlan("winget", true, emptySet(), emptySet(), false).commands.isEmpty())
    }

    @Test fun curatedMissingToolsUseExactIdsAndInteractiveNoUpgradeCommands() {
        val plan = wingetPlan(setOf("python", "uv", "cmake", "cargo", "rustc", "rustup", "git-lfs"))
        assertEquals(listOf("Python.Python.3.12", "astral-sh.uv", "Kitware.CMake", "Rustlang.Rustup", "GitHub.GitLFS"),
            plan.commands.map { it[it.indexOf("--id") + 1] })
        plan.commands.forEach { command ->
            assertEquals(listOf("winget", "install"), command.take(2))
            assertEquals("winget", command[command.indexOf("--source") + 1])
            assertTrue(command.containsAll(listOf("--exact", "--interactive", "--no-upgrade")))
            assertFalse(command.any { it in setOf("--silent", "--force", "--allow-reboot", "--accept-source-agreements", "--accept-package-agreements", "--ignore-security-hash") })
        }
    }

    @Test fun manualSdkAndCompilerRequirementsNeverBecomePackageCommands() {
        val plan = wingetPlan(setOf("cl", "vulkaninfo", "nvidia-smi", "bad;command"))
        assertTrue(plan.commands.isEmpty())
        assertTrue(plan.notes.single().contains("Install these prerequisites separately"))
    }

    @Test fun wingetMakesOnlyFullyMappedWindowsFeaturesSelectable() {
        fun choices(winget: Boolean) = featureOptions("windows", { false }, false, emptyList(), false, winget)
        val blocked = choices(false).first { it.extra.id == "cv" }
        assertFalse(blocked.enabled)
        val installable = choices(true).first { it.extra.id == "cv" }
        assertTrue(installable.enabled)
        assertTrue(featureRow(TerminalUi(TerminalColor.NONE, 80), installable, false).endsWith("install: uv"))
        assertFalse(choices(true).first { it.extra.id == "ceres" }.enabled) // MSVC remains manual.
        assertFalse(choices(true).first { it.extra.id == "vulkan" }.enabled)
        val linux = featureOptions("linux", { false }, false, emptyList(), false, true)
        assertFalse(linux.first { it.extra.id == "cv" }.enabled)
    }

    @Test fun noUpgradeAlreadyInstalledResultsAreAcceptedOnlyForWingetInstall() {
        val command = wingetPlan(setOf("uv")).commands.single()
        assertTrue(commandSucceeded(command, 0))
        assertTrue(commandSucceeded(command, -1978335135))
        assertTrue(commandSucceeded(command, -1978335189))
        assertFalse(commandSucceeded(command, -1978335231))
        assertFalse(commandSucceeded(command, -1978335227)) // cancellation
        assertFalse(commandSucceeded(listOf("winget", "upgrade", "--all"), -1978335135))
        assertFalse(commandSucceeded(listOf("git", "lfs", "pull"), -1978335135))
    }

    @Test fun wingetInstallDefersBuildsUntilANewTerminalButSkippingDoesNot() {
        assertTrue(shouldDeferPreparation("winget", true))
        assertFalse(shouldDeferPreparation("winget", false))
        assertFalse(shouldDeferPreparation("pacman", true))
        val plan = packagePlan("winget", false, setOf("uv"), setOf("cv"), false)
        assertEquals("astral-sh.uv", plan.commands.single()[3])
        assertTrue(plan.notes.any { it.contains("fresh terminal") })
    }
}
