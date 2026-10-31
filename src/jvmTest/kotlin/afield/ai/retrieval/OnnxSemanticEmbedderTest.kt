package afield.ai.retrieval

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The embedder really embeds. From 2026-07-06 to 2026-10-22 it read the ONNX result's Map.Entry value (the
 * OnnxTensor) instead of the tensor's array, pooled nothing, and returned zero vectors for every query and
 * every indexed capsule; semantic search's dense half matched nothing and no test noticed.
 */
class OnnxSemanticEmbedderTest {
    private fun resource(name: String): Path =
        Paths.get(assertNotNull(javaClass.getResource("/ai/retrieval/$name"), name).toURI())

    private fun norm(v: FloatArray) = sqrt(v.sumOf { (it * it).toDouble() })
    private fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }

    /**
     * A 425-byte model (`make_tiny_embedder.py`) whose hidden state for a token is a fixed row of a table,
     * so a text's mean-pooled, normalised vector is known exactly. Runs everywhere, through the real
     * session, tokenizer and pooling path.
     */
    @Test
    fun theTinyModelsVectorsAreTheMeanOfItsTokenRows() {
        val embedder = assertNotNull(OnnxSemanticEmbedder.Config(
            resource("tiny_embedder.onnx"), resource("tiny_tokenizer.json"),
            dimension = 4, maxTokens = 16, queryPrefix = "", documentPrefix = "", provider = "cpu",
            fixedTokenLength = 0, fixedBatchSize = 0
        ).createOrNull())
        embedder.use {
            // "red green": rows (1,0,0,0) and (0,1,0,0), whose mean normalises to (0.707, 0.707, 0, 0).
            // "blue" is one token, padded to the batch's length; the padding must not count.
            val (redGreen, blue) = it.encodeQueries(listOf("red green", "blue"))
            val h = (1 / sqrt(2.0)).toFloat()
            listOf(h, h, 0f, 0f).forEachIndexed { i, want -> assertEquals(want, redGreen[i], 1e-5f, "red green [$i]") }
            listOf(0f, 0f, 1f, 0f).forEachIndexed { i, want -> assertEquals(want, blue[i], 1e-5f, "blue [$i]") }
            assertEquals(1.0, norm(it.encodeDocuments(listOf("cyan yellow alpha")).single()), 1e-5)
        }
    }

    /** The installed granite model, when this machine has it: vectors that are unit length and mean something. */
    @Test
    fun theInstalledModelPlacesRelatedTextNearer() {
        val dir = Paths.get(System.getProperty("user.home"), ".afield/semantic-search/models/granite-embedding-97m-multilingual-r2")
        val model = dir.resolve("model_quantized.onnx")
        val tokenizer = dir.resolve("tokenizer.json")
        if (!Files.isRegularFile(model) || !Files.isRegularFile(tokenizer)) return // not installed here (CI, fresh machines)
        val embedder = assertNotNull(OnnxSemanticEmbedder.Config(model, tokenizer, 384, 512, "", "", "cpu", 0, 0).createOrNull())
        embedder.use {
            val (query, fline, shader) = it.encodeQueries(listOf(
                "draw FLine in a box",
                "fline { moveTo(x, y); lineTo(x2, y2) } and show it with lines(flines)",
                "write a GLSL fragment shader for the renderer"))
            assertEquals(1.0, norm(query), 1e-4)
            assertTrue(dot(query, fline) > dot(query, shader), "FLine text should be nearer an FLine query than shader text")
        }
    }
}
