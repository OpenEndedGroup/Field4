package afield.four

import afield.four.modules.RiggedFixture
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The committed bending cylinder is still what [RiggedFixture] generates.
 *
 * `RiggedFixture`'s KDoc has named this test since the fixture landed, but until 2026-09-23 it did
 * not exist: only the stress fixture had a drift check
 * (`GltfSkinningStressTest.theCommittedStressFixtureStillMatchesItsGenerator`). The skinning tests
 * read the committed file through Assimp while their oracles are derived from the generator's
 * constants, so a drifted file would make them test one mesh against another's arithmetic.
 */
class RiggedFixtureDriftTest {

    @Test
    fun theCommittedBendingCylinderStillMatchesItsGenerator() {
        val committed = File(RiggedFixture.COMMITTED_PATH)
        assertTrue(committed.exists(), "${committed.absolutePath} is missing; run ./gradlew runSkinningEvidence")
        assertEquals(
            RiggedFixture.bendingCylinderGltf(), committed.readText(),
            "the committed bending cylinder has drifted; regenerate with ./gradlew runSkinningEvidence",
        )
    }
}
