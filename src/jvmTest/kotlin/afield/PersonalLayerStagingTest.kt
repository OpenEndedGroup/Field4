package afield

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * Two Field processes started from one checkout must never stage the personal jar at the same path:
 * the second one's copy overwrote the jar the first held open, and the first lost the bytes of its own
 * personal classes until it restarted.
 */
class PersonalLayerStagingTest {
    @Test fun stagingPathsDifferByProcessAndGeneration() {
        val root = File("/checkout/build/personal/afield-personal.jar")
        val a = PersonalLayer.stagedPath(root, 0, pid = 100)
        assertEquals(File("/checkout/build/personal/generations/pid-100/gen-0/afield-personal.jar"), a)
        assertNotEquals(a, PersonalLayer.stagedPath(root, 0, pid = 200))
        assertNotEquals(a, PersonalLayer.stagedPath(root, 1, pid = 100))
    }

    @Test fun onlyExitedProcessesLoseTheirStaging() {
        val generations = createTempDirectory("personal-generations").toFile()
        try {
            for (name in listOf("pid-1", "pid-2", "pid-3", "pid-x", "gen-0")) File(generations, "$name/gen-0").mkdirs()
            PersonalLayer.removeExitedStaging(generations, self = 1, alive = { it == 2L })
            assertEquals(setOf("pid-1", "pid-2", "pid-x", "gen-0"), generations.list()!!.toSet())
        } finally { generations.deleteRecursively() }
    }
}
