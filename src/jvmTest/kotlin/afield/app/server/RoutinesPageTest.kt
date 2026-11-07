package afield.app.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `GET /api/routines/adopt` turns its query into a `tools/routines.py` argv only for a real name and harness. */
class RoutinesPageTest {
    @Test
    fun aRoutineAndHarnessBecomeAnInstallArgv() {
        assertEquals(listOf("install", "self-improve", "--harness", "launchd", "--json"), routineAdoptArguments("self-improve", "launchd"))
        for (harness in routineHarnesses) assertEquals(harness, routineAdoptArguments("reap-worktrees", harness)?.get(3))
    }

    @Test
    fun anythingElseIsRefusedBeforeAProcessStarts() {
        for (name in listOf("", "../etc", "Self-Improve", "a", "self improve", "--apply", "x".repeat(60))) {
            assertNull(routineAdoptArguments(name, "prompt"), name)
        }
        for (harness in listOf("", "launchd --apply", "systemd", "PROMPT")) {
            assertNull(routineAdoptArguments("nightly-ci", harness), harness)
        }
    }
}
