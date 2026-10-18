@file:afield.runtime.completion.CompletionCategory("Graphics")
package afield.graphics.paint

/**
 * An editable Klecks painting app inside a box's output panel, saved into the document when you
 * press Submit: [klecks] returns a [KlecksPainting].
 */

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.layers
import afield.runtime.completion.CompletionCategory
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.tools.docs.format
import afield.ui.output.button

import afield.app.protocol.put
import afield.audio.synth.*
import afield.document.graph.Box
import afield.document.graph.box
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.ui.output.displayOpen
import afield.ui.output.outputRequests
import kotlinx.serialization.json.*
import java.util.Base64

/**
 * An editable Klecks painting in this box's output. Press Klecks' Submit button to save it into the
 * document. Strokes not yet submitted are kept as a draft in the browser, so re-running the box or
 * reloading the page brings them back until the next Submit.
 */
fun Box.klecks(channel: String = "klecks", width: Int = 800, height: Int = 600): KlecksPainting {
    require(channel.matches(Regex("[A-Za-z0-9_-]{1,40}"))) { "Klecks channel must be a simple name" }
    require(width in 1..2048 && height in 1..2048) { "Klecks dimensions must be 1..2048" }
    KlecksPainting.registerPersistence()
    val painting = KlecksPainting(this, channel)
    val binding = outputRequests("klecks-$channel") { painting.receive(it) }
    display(channel, """<iframe title="Klecks painting" src="/klecks-field.html?binding=${binding.id}&amp;draft=${java.net.URLEncoder.encode(id + "/" + channel, "UTF-8")}&amp;width=$width&amp;height=$height" style="border:0;display:block;background:#151515;width:${maxOf(width + 280, 700)}px;height:${maxOf(height + 90, 480)}px"></iframe>""")
    displayOpen(channel)
    return painting
}

/** The last submitted image. PSD retains layers; PNG is convenient for Kotlin image consumers. */
class KlecksPainting internal constructor(private val box: Box, val channel: String) {
    companion object {
        private const val PSD = "klecksPsd"
        private const val PNG = "klecksPng"
        private const val CHUNK = 400_000
        private const val MAX_BASE64 = 32_000_000

        internal fun registerPersistence() {
            Box.ioPersistentProperties.add(PSD)
            Box.ioPersistentProperties.add(PNG)
            Box.ioSeparateProperties[PSD] = "json"
            Box.ioSeparateProperties[PNG] = "json"
        }
    }

    private data class Upload(val psdLength: Int, val pngLength: Int,
                              val psd: StringBuilder = StringBuilder(), val png: StringBuilder = StringBuilder())
    private val uploads = mutableMapOf<String, Upload>()

    private fun stored(key: String): JsonObject =
        (box.getPropertyRaw(key) as? String)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())

    private fun bytes(key: String): ByteArray? = stored(key)[channel]?.jsonPrimitive?.contentOrNull
        ?.let { Base64.getDecoder().decode(it) }

    /** Last saved layered Photoshop document, or null until the user submits the painting. */
    fun psdBytes(): ByteArray? = bytes(PSD)

    /** Last saved flattened image, or null until the user submits the painting. */
    fun pngBytes(): ByteArray? = bytes(PNG)

    private fun save(key: String, value: String) {
        val all = stored(key).toMutableMap()
        all[channel] = JsonPrimitive(value)
        box.setPropertyRaw(key, JsonObject(all).toString())
    }

    internal fun receive(request: JsonElement): JsonElement {
        val r = request.jsonObject
        val op = r["op"]?.jsonPrimitive?.content ?: error("Missing Klecks operation")
        return when (op) {
            "info" -> buildJsonObject {
                put("psdLength", stored(PSD)[channel]?.jsonPrimitive?.content?.length ?: 0)
                put("chunkSize", CHUNK)
            }
            "read" -> {
                val source = stored(PSD)[channel]?.jsonPrimitive?.content.orEmpty()
                val offset = r["offset"]?.jsonPrimitive?.int ?: error("Missing offset")
                require(offset in 0..source.length && offset % CHUNK == 0) { "Invalid Klecks offset" }
                JsonPrimitive(source.substring(offset, minOf(source.length, offset + CHUNK)))
            }
            "begin" -> {
                val id = r["id"]?.jsonPrimitive?.content ?: error("Missing upload id")
                require(id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Invalid upload id" }
                val psdLength = r["psdLength"]?.jsonPrimitive?.int ?: error("Missing PSD length")
                val pngLength = r["pngLength"]?.jsonPrimitive?.int ?: error("Missing PNG length")
                require(psdLength in 1..MAX_BASE64 && pngLength in 1..MAX_BASE64) { "Painting exceeds the 24 MB per-format limit" }
                require(uploads.isEmpty()) { "Another Klecks save is in progress" }
                uploads[id] = Upload(psdLength, pngLength)
                JsonPrimitive(true)
            }
            "part" -> {
                val upload = uploads[r["id"]?.jsonPrimitive?.content] ?: error("Unknown Klecks upload")
                val kind = r["kind"]?.jsonPrimitive?.content
                val value = r["value"]?.jsonPrimitive?.content ?: error("Missing upload part")
                require(value.length <= CHUNK && value.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) { "Invalid upload part" }
                val target = when (kind) { "psd" -> upload.psd; "png" -> upload.png; else -> error("Invalid format") }
                val expected = if (kind == "psd") upload.psdLength else upload.pngLength
                require(target.length + value.length <= expected) { "Upload exceeds declared length" }
                target.append(value)
                JsonPrimitive(target.length)
            }
            "commit" -> {
                val id = r["id"]?.jsonPrimitive?.content ?: error("Missing upload id")
                val upload = uploads.remove(id) ?: error("Unknown Klecks upload")
                require(upload.psd.length == upload.psdLength && upload.png.length == upload.pngLength) { "Incomplete Klecks upload" }
                Base64.getDecoder().decode(upload.psd.toString())
                Base64.getDecoder().decode(upload.png.toString())
                save(PSD, upload.psd.toString())
                save(PNG, upload.png.toString())
                JsonPrimitive(true)
            }
            else -> error("Unknown Klecks operation: $op")
        }
    }
}
