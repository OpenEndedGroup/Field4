package afield.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureSelectorTest {
    private fun options(platform: String = "linux", tools: (String) -> Boolean = { true }, nvidia: Boolean = true,
                        rustErrors: List<String> = emptyList(), rustup: Boolean = true) =
        featureOptions(platform, tools, nvidia, rustErrors, rustup, solidcraftErrors = emptyList())

    @Test fun missingToolsDisableFeaturesAndCannotBeSelectedEvenFromSavedPreferences() {
        val choices = options(tools = { it != "cargo" && it != "uv" }, rustup = false)
        val paint = choices.first { it.extra.id == "paint" }
        assertEquals(listOf("cargo", "rustup"), paint.missing)
        val state = FeatureSelection(choices, setOf("paint", "cv", "ai"))
        assertEquals(setOf("ai"), state.selected)
        state.toggle(choices.indexOf(paint))
        assertFalse("paint" in state.selected)
        val python = choices.indexOfFirst { it.extra.id == "python" }
        state.toggle(python)
        assertTrue("python" in state.selected)
        state.toggle(python)
        assertFalse("python" in state.selected)
    }

    @Test fun failedNvidiaProbeHidesOnlyNvidiaFeaturesAndKeepsCatalogNumbers() {
        val choices = options(nvidia = false)
        assertFalse(choices.any { it.extra.id in setOf("dlss", "opendlssnr", "diffusion", "syphon") })
        assertEquals(13, choices.first { it.extra.id == "shaders" }.number)
        assertTrue(choices.any { it.extra.id == "tracker" })
        assertTrue(options().any { it.extra.id == "diffusion" })
    }

    @Test fun macAndWindowsRespectPlatformFilters() {
        assertFalse(options("mac").any { it.extra.id in setOf("syphon", "dlss", "diffusion", "opendlssnr") })
        val windows = options("windows")
        assertFalse(windows.any { it.extra.id in setOf("syphon", "csound", "tracker") })
        assertTrue("cl" in windows.first { it.extra.id == "tetgen" }.prerequisites)
        assertTrue("python" in windows.first { it.extra.id == "python" }.prerequisites)
        for (platform in listOf("mac", "linux", "windows")) {
            val patchy = options(platform).first { it.extra.id == "patchy-web" }
            assertEquals("buildPatchyWeb", patchy.extra.task)
            assertTrue(patchy.enabled)
            assertTrue(patchy.prerequisites.containsAll(setOf("git", "cmake")))
        }
    }

    @Test fun oldRustDisablesPaintAndMovementStopsAtListEdges() {
        val choices = options(rustErrors = listOf("rustc must support Rust 2024"))
        assertFalse(choices.first { it.extra.id == "paint" }.enabled)
        val state = FeatureSelection(choices)
        state.move(-100); assertEquals(0, state.cursor)
        state.move(100); assertEquals(choices.lastIndex, state.cursor)
    }

    @Test fun solidcraftNeedsModernRustAndHasAnOptInBuild() {
        val old = featureOptions("mac", { true }, rustErrors = emptyList(), solidcraftErrors = listOf("rustc must be at least 1.95"))
        assertFalse(old.first { it.extra.id == "solidcraft" }.enabled)
        for (platform in listOf("mac", "linux", "windows")) {
            val choice = options(platform).first { it.extra.id == "solidcraft" }
            assertEquals("buildSolidCraftNative", choice.extra.task)
            assertTrue(choice.prerequisites.containsAll(setOf("git", "cargo", "rustc")))
            assertTrue("buildSolidCraftNative" in preparationCommands(setOf("solidcraft"), platform).single())
        }
    }

    @Test fun rowsFitNarrowTerminalsAndRightAlignPrerequisitesWithoutZeroPrefixes() {
        val choices = options(tools = { it != "cargo" })
        val escape = Regex("\u001b\\[[0-9;]*m")
        for (width in listOf(24, 40, 80, 140)) {
            val ui = TerminalUi(TerminalColor.ANSI, width)
            choices.forEach { option ->
                val line = escape.replace(featureRow(ui, option, false), "")
                assertEquals(width, line.length)
            }
        }
        val ui = TerminalUi(TerminalColor.NONE, 80)
        assertTrue(featureRow(ui, choices.first(), false).startsWith("   1 [ ]"))
        assertTrue(featureRow(ui, choices.first(), false).endsWith("vulkaninfo"))
        assertTrue(featureRow(TerminalUi(TerminalColor.ANSI, 80), choices.first { it.extra.id == "paint" }, false).contains("\u001b[2;9m"))
    }
}
