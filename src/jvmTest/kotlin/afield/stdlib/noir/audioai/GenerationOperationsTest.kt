package afield.stdlib.noir.audioai

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GenerationOperationsTest {
    private val source = AssetRegion("sha256:" + "a".repeat(64), 12.5, 4.5)

    @Test
    fun writesAllMusicGenStyleControlsIntoTheOperation() {
        val request = styleCandidates(
            source = source,
            prompt = "suspended brass",
            durationSeconds = 9.0,
            seeds = 4L..7L,
            controls = MusicGenStyleControls(
                useSampling = true,
                topK = 120,
                topP = 0.82,
                temperature = 1.15,
                cfg = 3.5,
                cfgBeta = 4.0,
                twoStepCfg = true,
                styleQuantization = 2,
                styleExcerptSeconds = 4.0,
                styleDownsampleFactor = 2,
                styleEncodecCodebooks = 3,
            ),
        )

        assertEquals("generate.style", request.operation)
        assertEquals(4, request.candidateCount)
        assertEquals(120, request.parameters.getValue("top_k").jsonPrimitive.int)
        assertEquals(0.82, request.parameters.getValue("top_p").jsonPrimitive.double)
        assertEquals(1.15, request.parameters.getValue("temperature").jsonPrimitive.double)
        assertEquals(4.0, request.parameters.getValue("cfg_beta").jsonPrimitive.double)
        assertTrue(request.parameters.getValue("two_step_cfg").jsonPrimitive.boolean)
        assertEquals(2, request.parameters.getValue("style_downsample_factor").jsonPrimitive.int)
        assertEquals(3, request.parameters.getValue("style_encodec_codebooks").jsonPrimitive.int)
    }

    @Test
    fun rejectsControlsTheWorkerCannotHonor() {
        assertFailsWith<IllegalArgumentException> {
            styleCandidates(source.copy(durationSeconds = 1.0))
        }
        assertFailsWith<IllegalArgumentException> {
            styleCandidates(source, controls = MusicGenStyleControls(styleExcerptSeconds = 4.6))
        }
        assertFailsWith<IllegalArgumentException> {
            MusicGenStyleControls(topP = 1.1)
        }
    }
}
