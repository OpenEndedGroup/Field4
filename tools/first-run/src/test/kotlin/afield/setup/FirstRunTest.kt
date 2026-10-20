package afield.setup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FirstRunTest {
    @Test fun browserOnlyWithoutExtrasDoesNotRequireCompilersOrDesktopPackages() {
        assertTrue(packagePlan("apt-get", false, setOf("g++", "cmake"), emptySet(), true).commands.isEmpty())
    }
    @Test fun ubuntuAndDebianT64TransitionsUseTheCorrectDesktopNames() {
        assertTrue(usesT64Packages("ID=ubuntu\nVERSION_ID=\"24.04\"\n"))
        assertTrue(usesT64Packages("ID=debian\nVERSION_ID=\"13\"\n"))
        assertFalse(usesT64Packages("ID=ubuntu\nVERSION_ID=\"22.04\"\n"))
        assertFalse(usesT64Packages("ID=debian\nVERSION_ID=\"12\"\n"))
        assertFalse(usesT64Packages("ID=arch\n"))
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
    @Test fun archDesktopAndVulkanAlreadyInstalledProposeNoTransaction() {
        val proposed = packagePlan("pacman", true, emptySet(), setOf("vulkan"), false)
        val installed = setOf("gtk3", "nss", "alsa-lib", "mesa", "libxkbcommon", "vulkan-icd-loader", "vulkan-tools")
        val filtered = omitInstalledPacmanPackages(proposed, installed)
        assertTrue(filtered.commands.isEmpty())
        assertFalse(filtered.notes.any { it.startsWith("Use a fully updated Arch system.") })
    }
    @Test fun archMixedPlanKeepsOnlyMissingPackagesAndPreservesConfirmation() {
        val proposed = packagePlan("pacman", true, emptySet(), setOf("vulkan"), false)
        val filtered = omitInstalledPacmanPackages(proposed, setOf("gtk3", "nss", "alsa-lib", "mesa", "libxkbcommon", "vulkan-icd-loader"))
        assertEquals(listOf("sudo", "pacman", "-S", "--needed", "--", "vulkan-tools"), filtered.commands.single())
        val apt = packagePlan("apt-get", true, emptySet(), emptySet(), false)
        assertEquals(apt, omitInstalledPacmanPackages(apt, emptySet()))
    }
    @Test fun unsupportedPackageManagerProducesGuidanceAndNoShellCommand() {
        val plan = packagePlan(null, true, setOf("git-lfs"), setOf("python", "vulkan"), false)
        assertTrue(plan.commands.isEmpty())
        assertTrue(plan.notes.any { "git-lfs" in it })
    }
    @Test fun nativeExtrasNeedCompilersEvenInBrowserMode() {
        val plan = packagePlan("apt-get", false, setOf("cmake", "g++"), setOf("ceres", "csound"), true)
        val argv = plan.commands.single()
        assertTrue("cmake" in argv && "build-essential" in argv)
        assertTrue("libsndfile1-dev" in argv && "flex" in argv && "bison" in argv)
        assertFalse("libgtk-3-0t64" in argv)
    }
    @Test fun cargoAndMacLibraryChoicesHaveTheirOwnPrerequisites() {
        val argv = packagePlan("brew", false, setOf("cargo", "cmake"), setOf("paint", "ceres", "manifold", "lua"), false).commands.single()
        assertTrue(setOf("rust", "cmake", "manifold", "luajit").all { it in argv })
    }
    @Test fun paintRejectsPre2024RustToolchainsBeforeRunningItsBuild() {
        assertFalse(supportsRust2024("cargo 1.75.0 (abc 2023-12-28)"))
        assertFalse(supportsRust2024("rustc 1.84.1 (abc 2025-01-30)"))
        assertFalse(supportsRust2024("not installed"))
        assertTrue(supportsRust2024("cargo 1.85.0 (abc 2025-02-20)"))
        assertTrue(supportsRust2024("rustc 1.97.0 (abc 2026-07-09)"))
        assertFalse(supportsRustAtLeast("cargo 1.94.0", 95))
        assertTrue(supportsRustAtLeast("cargo 1.95.0", 95))
    }
    @Test fun selectionKeepsLegacyNumbersAndRejectsArbitraryCommands() {
        assertEquals(setOf("vulkan", "python", "ceres"), selectedExtras("1, 2 ceres"))
        assertEquals(emptySet(), selectedExtras(""))
        assertFailsWith<IllegalArgumentException> { selectedExtras("0") }
        assertFailsWith<IllegalArgumentException> { selectedExtras("ceres;touch /tmp/wrong") }
    }
    @Test fun platformSpecificBuildsNeverLeakIntoOtherPlatforms() {
        assertTrue(preparationCommands(setOf("syphon", "csound"), "windows").isEmpty())
        assertTrue(preparationCommands(setOf("syphon"), "linux").isEmpty())
        val command = preparationCommands(setOf("ceres", "tetgen", "libpd"), "windows").single()
        assertEquals(listOf("cmd.exe", "/d", "/c", "gradlew.bat"), command.take(4))
        assertTrue("buildCeresRedistributable" in command && "buildTetgenNative" in command && "buildLibpdNative" in command)
        assertEquals(setOf("python", "cmake", "cl"), extraTools(setOf("ceres"), "windows"))
    }
    @Test fun patchyBuildRunsOnlyWhenSelectedOnEachPlatform() {
        for (platform in listOf("mac", "linux", "windows")) {
            assertTrue(preparationCommands(emptySet(), platform).isEmpty())
            val command = preparationCommands(setOf("patchy-web"), platform).single()
            assertEquals("buildPatchyWeb", command.last())
            assertTrue("cmake" in extraTools(setOf("patchy-web"), platform))
        }
    }
    @Test fun largeModelAndInterpreterSetupsRemainExplicitSeparateRecipes() {
        assertTrue(preparationCommands(setOf("cv", "voice", "diffusion", "audio-ai", "kratos", "tracker", "transport", "opendlssnr", "ardy", "closd"), "linux").isEmpty())
        assertFalse(extraTools(setOf("syphon"), "windows").contains("xcrun"))
    }
    @Test fun nativeArtifactChecksHonorOverridesAndWindowsOutputLayouts() {
        val root = File(System.getProperty("java.io.tmpdir"), "field-setup-checkout").absoluteFile
        val override = File(System.getProperty("java.io.tmpdir"), "external/custom-ceres.so").absoluteFile
        assertEquals(listOf(override), artifactCandidates(root, "ceres", "linux", mapOf("AFIELD_CERES_LIBRARY" to override.path)))
        assertTrue(artifactCandidates(root, "tetgen", "windows", emptyMap()).any { it.path.replace('\\', '/').endsWith("Release/field_tetgen_jvm.dll") })
        assertTrue(artifactCandidates(root, "libpd", "windows", emptyMap()).all { it.name == "libpd-multi.dll" })
        assertTrue(artifactCandidates(root, "python", "linux", emptyMap()).isEmpty())
        assertTrue(artifactCandidates(root, "solidcraft", "mac", emptyMap()).single().path.replace('\\', '/').endsWith("build/native/solidcraft/libfield_solidcraft_jvm.dylib"))
    }
    @Test fun everyCatalogGuideAndPreparationTaskExistsInThisCheckout() {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "field.sh").isFile }
        val build = File(root, "build.gradle.kts").readText()
        assertEquals(extrasCatalog.size, extrasCatalog.map { it.id }.toSet().size)
        for (extra in extrasCatalog) {
            assertTrue(File(root, extra.guide).isFile, "Missing guide for ${extra.id}: ${extra.guide}")
            extra.task?.let { assertTrue("\"$it\"" in build, "Missing preparation task for ${extra.id}: $it") }
        }
    }
}
