package afield.stdlib.noir.audioai

import afield.four.synth.Param
import afield.four.synth.SineOsc
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UnderfitOperationsTest {
    private val source = AssetRegion("sha256:" + "b".repeat(64), 3.0, 18.0)

    @Test
    fun `writes Stable Audio and LoRA controls into one typed operation`() {
        val checkpoint = UnderfitLoraCheckpoint(
            workerPath = "quartet/10000.safetensors",
            sha256 = "a".repeat(64),
            strength = 0.7,
            intervalMax = 0.9,
            layerFilter = "transformer.layers.8-23",
        )
        val request = underfitCandidates(
            source = source,
            prompt = "fractured chamber strings",
            durationSeconds = 24.0,
            checkpoints = listOf(checkpoint),
            model = UnderfitModel.MEDIUM,
            operation = UnderfitOperation.VARIATIONS,
            seeds = 20L..22L,
            controls = UnderfitGenerationControls(
                steps = 12,
                cfg = 1.5,
                initNoiseLevel = 0.55,
                samplerType = "pingpong",
                cfgIntervalMin = 0.1,
                cfgIntervalMax = 0.95,
                cfgRescale = 0.2,
                cfgNormThreshold = 4.0,
                durationPaddingSeconds = 4.0,
                apgScale = 0.8,
                chunkedDecode = true,
            ),
        )

        assertEquals("generate.variations", request.operation)
        assertEquals("underfit.stable_audio_3", request.backendPreference)
        assertEquals(3, request.candidateCount)
        assertEquals("medium", request.parameters.getValue("model_name").jsonPrimitive.content)
        assertEquals(12, request.parameters.getValue("steps").jsonPrimitive.int)
        assertEquals(0.55, request.parameters.getValue("init_noise_level").jsonPrimitive.double)
        val encoded = request.parameters.getValue("lora_checkpoints").jsonArray.single().jsonObject
        assertEquals(checkpoint.workerPath, encoded.getValue("path").jsonPrimitive.content)
        assertEquals(checkpoint.sha256, encoded.getValue("sha256").jsonPrimitive.content)
        assertEquals(0.7, encoded.getValue("strength").jsonPrimitive.double)
        assertEquals(0.9, encoded.getValue("interval_max").jsonPrimitive.double)
        assertEquals(
            "transformer.layers.8-23",
            encoded.getValue("layer_filter").jsonPrimitive.content,
        )
    }

    @Test
    fun `hashes a local safetensors checkpoint relative to the worker root`() {
        val root = Files.createTempDirectory("field-underfit-checkpoints")
        val file = root.resolve("voice").resolve("step-500.safetensors")
        Files.createDirectories(file.parent)
        Files.writeString(file, "underfit")

        val checkpoint = UnderfitLoraCheckpoint.fromFile(file, root, strength = 0.6)

        assertEquals("voice/step-500.safetensors", checkpoint.workerPath)
        assertEquals(64, checkpoint.sha256.length)
        assertTrue(checkpoint.sha256.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(0.6, checkpoint.strength)
    }

    @Test
    fun `rejects paths digests and durations the worker cannot honor`() {
        assertFailsWith<IllegalArgumentException> {
            UnderfitLoraCheckpoint("../escape.safetensors", "a".repeat(64))
        }
        assertFailsWith<IllegalArgumentException> {
            UnderfitLoraCheckpoint("model.safetensors", "not-a-digest")
        }
        assertFailsWith<IllegalArgumentException> {
            underfitCandidates(source, durationSeconds = 121.0, model = UnderfitModel.SMALL_MUSIC)
        }
        assertFailsWith<IllegalArgumentException> {
            UnderfitGenerationControls(initNoiseLevel = 1.1)
        }
        assertFailsWith<IllegalArgumentException> {
            UnderfitLoraCheckpoint("model.safetensors", "a".repeat(64), intervalMin = 0.8, intervalMax = 0.2)
        }
    }

    @Test
    fun `serializes through the real Field client and service socket`() {
        FieldAudioServiceStub().use { stub ->
            val root = Files.createTempDirectory("field-underfit-seam")
            val wav = renderDoughWav(root.resolve("source.wav"), SineOsc(Param(220f)), seconds = 1.0)
            val client = FieldAudioClient(stub.baseUrl)
            val asset = client.importWav(wav.path).get(10, TimeUnit.SECONDS)
            val accepted = client.submit(underfitCandidates(
                source = AssetRegion(asset.id, 0.0, 1.0),
                durationSeconds = 1.0,
                checkpoints = listOf(UnderfitLoraCheckpoint(
                    "voice/step-500.safetensors", "c".repeat(64), 0.75,
                )),
                seeds = 9L..9L,
            )).get(10, TimeUnit.SECONDS)

            assertEquals("underfit.stable_audio_3", accepted.resolvedBackend)
            val received = stub.submittedOperations.single()
            assertEquals("generate.style", received.operation)
            assertEquals("underfit.stable_audio_3", received.backendPreference)
            assertEquals("c".repeat(64), received.parameters
                .getValue("lora_checkpoints").jsonArray.single().jsonObject
                .getValue("sha256").jsonPrimitive.content)
            assertEquals(emptyList(), stub.protocolViolations)
        }
    }
}
