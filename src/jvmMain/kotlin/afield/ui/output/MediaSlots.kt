@file:afield.runtime.completion.CompletionCategory("Output")

package afield.ui.output

import afield.ai.inference.onnx.ClipVector
import afield.ai.inference.onnx.clipEncode
import afield.ai.inference.onnx.clipEncodeImage
import afield.document.graph.Box
import afield.app.diagnostics.logging.lprintln
import afield.study.source.BoxStudies
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/**
 * A row of input slots for pictures and text, in a box's output: what a program that compares a few
 * things (CLIP embeddings, a style transfer, a classifier) reads its inputs from.
 *
 * ```kotlin
 * val inputs = mediaSlots {
 *     image("public/docs/assets/madp_tetra/tabby_cat.jpg")
 *     image("public/docs/assets/madp_tetra/labrador.jpg")
 *     text("a photo of a cat")
 *     tray("public/docs/assets/madp_tetra")     // more pictures, to drag onto a slot
 * }
 * inputs.onChange { slots ->
 *     val vectors = slots.map { it.clip() }
 *     ...
 * }
 * ```
 *
 * Each slot has a number and a colour, and holds one picture or one piece of text:
 *
 * - **click** a picture slot to choose a file, or **drop** one on it; drop several and they fill that
 *   slot and the ones after it;
 * - drop a **folder** (or use **folder…**) to put its pictures in the **tray** under the slots, then
 *   drag any of them onto a slot; [MediaSlotsBuilder.tray] fills the tray from the box's own code;
 * - **type** into a text slot;
 * - **reset** puts every slot back as the code says.
 *
 * Pictures are scaled in the browser to at most 640 pixels on the long side, with the photo's EXIF
 * orientation applied, before they are sent; the box sees a [BufferedImage]. What was dropped or typed
 * outlives a re-run of the box, but not a restart of Field: the code's own files are what a document
 * keeps.
 *
 * [onChange] runs on a thread of its own, once now and again after every change, and only when every
 * slot is filled. Changes that arrive while it is running are folded into one more run.
 */

/**
 * The row of slots a box is holding: read [slots] or index it with [get], ask whether it is
 * [complete], and hand [onChange] a block to run whenever one of them changes.
 *
 * There is one of these per box and output channel, and a re-run finds the slots as they were left.
 * Where the code for a slot has itself changed, the code's new content wins over whatever was
 * dropped on it.
 */
class MediaSlots internal constructor(private val box: Box, private val channel: String, private val defaults: List<MediaSlot>) {

    private val key = "${box.id}/$channel"
    private val state: SlotState = slotStates.getOrPut(key) { SlotState() }
    private val actionId = "media-slots-${UUID.randomUUID()}"
    private val worker get() = state.worker
    private val pending get() = state.pending
    @Volatile private var listener: ((List<MediaSlot>) -> Unit)? = null

    init {
        // A slot whose code changed since the last run takes the code's new content over a drop.
        val signatures = defaults.map { "${it.kind}|${it.file?.path}|${it.text}" }
        signatures.forEachIndexed { i, sig -> if (state.signatures.getOrNull(i) != sig) state.slots.remove(i) }
        state.signatures = signatures
        registerStringCallback(actionId) { json -> runCatching { act(json) }.onFailure { lprintln("error,media-slots", "media slots: ${it.message}") } }
    }

    /** The slots as they are now: the code's, with whatever was dropped or typed over them. */
    val slots: List<MediaSlot>
        get() = defaults.mapIndexed { i, d -> state.slots[i]?.let { d.with(it) } ?: d }

    /** The pictures waiting in the tray. */
    val tray: List<MediaSlot> get() = state.tray.toList()

    /** Slot [i], counting from 0. */
    operator fun get(i: Int): MediaSlot = slots[i]

    /** Whether every slot holds something. */
    val complete: Boolean get() = slots.none { it.isEmpty }

    /**
     * Run [block] with the slots now, and again after every change, on a thread of its own. It is not
     * called while a slot is empty. Calling `onChange` again replaces the block.
     */
    fun onChange(block: (List<MediaSlot>) -> Unit) {
        listener = block
        changed()
    }

    private fun changed() {
        if (listener == null || !complete) return
        if (!pending.compareAndSet(false, true)) return
        worker.execute {
            pending.set(false)
            val now = slots
            if (now.any { it.isEmpty }) return@execute
            try {
                listener?.invoke(now)
            } catch (t: Throwable) {
                state.error = "${t::class.simpleName}: ${t.message}"
                lprintln("error,media-slots", "media slots onChange failed: ${t.message}")
                t.printStackTrace()
                redraw()
            }
        }
    }

    internal fun act(json: String) {
        val message = Json.parseToJsonElement(json).jsonObject
        val slot = message["slot"]?.jsonPrimitive?.int
        state.error = null
        when (message["action"]?.jsonPrimitive?.content) {
            "image" -> {
                val files = message["files"]!!.jsonArray.map { it.jsonObject }
                var target = slot ?: 0
                for (f in files) {
                    while (target < defaults.size && defaults[target].kind != MediaSlot.Kind.IMAGE) target++
                    if (target >= defaults.size) break
                    state.slots[target] = decode(f)
                    target++
                }
                redraw(); changed()
            }
            "tray" -> {
                val item = state.tray.getOrNull(message["item"]!!.jsonPrimitive.int) ?: return
                if (slot != null && defaults.getOrNull(slot)?.kind == MediaSlot.Kind.IMAGE) {
                    state.slots[slot] = SlotContent(image = item.image, name = item.label, file = item.file)
                    redraw(); changed()
                }
            }
            "tray-add" -> {
                for (f in message["files"]!!.jsonArray.map { it.jsonObject }) {
                    if (state.tray.size >= MAX_TRAY) break
                    val c = decode(f)
                    state.tray += MediaSlot(-1, MediaSlot.Kind.IMAGE, "#808080", c.name, c.image, "", c.file)
                }
                redraw()
            }
            "text" -> {
                if (slot != null && defaults.getOrNull(slot)?.kind == MediaSlot.Kind.TEXT) {
                    state.slots[slot] = SlotContent(text = message["text"]!!.jsonPrimitive.content)
                    // No redraw: the person is typing into the slot, and redrawing would take the caret away.
                    changed()
                }
            }
            "reset" -> {
                state.slots.clear(); state.tray.clear(); state.tray += initialTray
                redraw(); changed()
            }
        }
    }

    private fun decode(f: JsonObject): SlotContent {
        val name = f["name"]?.jsonPrimitive?.content ?: "image"
        val data = f["dataUrl"]!!.jsonPrimitive.content.substringAfter(",")
        val image = ImageIO.read(Base64.getDecoder().decode(data).inputStream()) ?: error("could not read $name as an image")
        return SlotContent(image = image, name = name.substringBeforeLast('.'))
    }

    internal var initialTray: List<MediaSlot> = emptyList()
        set(value) {
            field = value
            val signature = value.joinToString("|") { it.file?.path ?: it.label }
            if (state.traySignature != signature) { state.tray.clear(); state.tray += value; state.traySignature = signature }
        }

    internal fun redraw() = BoxStudies.show(box, channel, html())

    internal fun html(): String = renderMediaSlots(actionId, slots, state.tray, state.error)

    companion object {
        private val slotStates = ConcurrentHashMap<String, SlotState>()
        private const val MAX_TRAY = 64
    }
}

/** What a person put over a slot: a picture or a piece of text. */
internal class SlotContent(val image: BufferedImage? = null, val name: String = "", val text: String? = null, val file: File? = null)

/** What outlives a re-run, by box and channel. */
private class SlotState {
    val slots = ConcurrentHashMap<Int, SlotContent>()
    val tray = java.util.concurrent.CopyOnWriteArrayList<MediaSlot>()
    @Volatile var traySignature: String? = null
    @Volatile var signatures: List<String> = emptyList()
    @Volatile var error: String? = null
    val pending = AtomicBoolean(false)
    val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "media-slots").apply { isDaemon = true } }
}

/**
 * One slot of a [MediaSlots]: a picture or a piece of text, with the slot's number and colour.
 */
class MediaSlot internal constructor(
    /** The slot's position, from 0; the widget shows `index + 1`. */
    val index: Int,
    /** Whether the slot takes a picture or a piece of text. */
    val kind: Kind,
    /** The slot's CSS colour, which a drawing of the result should reuse. */
    val color: String,
    /** The picture's file name without its extension, or, for a text slot, the text itself. */
    val label: String,
    /** The picture, for an image slot that is filled, and null for one that is not. */
    val image: BufferedImage?,
    /** The text, for a text slot; empty for an image slot. */
    val text: String,
    /** Where the picture came from, when it came from a file on this machine rather than a drop. */
    val file: File? = null,
) {
    /** A picture slot or a text slot. */
    enum class Kind { IMAGE, TEXT }

    /** Whether there is nothing in the slot yet. */
    val isEmpty: Boolean get() = if (kind == Kind.IMAGE) image == null else text.isBlank()

    /**
     * The slot's CLIP embedding: [clipEncodeImage] for a picture, [clipEncode] for text. Both land in
     * the same 512-dimensional space, so a picture and a sentence can be compared.
     */
    fun clip(): ClipVector = when (kind) {
        Kind.IMAGE -> clipEncodeImage(image ?: error("slot ${index + 1} is empty"))
        Kind.TEXT -> clipEncode(text.ifBlank { error("slot ${index + 1} is empty") })
    }

    internal fun with(c: SlotContent): MediaSlot = when (kind) {
        Kind.IMAGE -> MediaSlot(index, kind, color, c.name, c.image, "", c.file)
        Kind.TEXT -> MediaSlot(index, kind, color, c.text ?: "", null, c.text ?: "")
    }

    /** The slot's number as the widget shows it, its kind and its label, and whether it is empty. */
    override fun toString(): String = "MediaSlot(${index + 1}, $kind, \"$label\"${if (isEmpty) ", empty" else ""})"
}

/** The receiver of [Box.mediaSlots]'s block: one call per slot, in order, and any trays. */
class MediaSlotsBuilder internal constructor(private val colors: List<String>) {
    internal val slots = ArrayList<MediaSlot>()
    internal val tray = ArrayList<MediaSlot>()

    private fun nextColor() = colors[slots.size % colors.size]

    /** A picture slot holding the image at [path] (relative to Field's working directory, or absolute). */
    fun image(path: String) {
        val file = File(path)
        val image = ImageIO.read(file) ?: error("mediaSlots: cannot read an image from $file")
        slots += MediaSlot(slots.size, MediaSlot.Kind.IMAGE, nextColor(), file.nameWithoutExtension, image, "", file)
    }

    /** An empty picture slot, to be filled by a drop. */
    fun image() {
        slots += MediaSlot(slots.size, MediaSlot.Kind.IMAGE, nextColor(), "", null, "")
    }

    /** A text slot, starting with [initial]. */
    fun text(initial: String = "") {
        slots += MediaSlot(slots.size, MediaSlot.Kind.TEXT, nextColor(), initial, null, initial)
    }

    /** Put every picture in [folder] (JPEG, PNG, GIF, BMP; not its subfolders) in the tray, by name. */
    fun tray(folder: String) {
        val files = File(folder).listFiles()?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }?.sortedBy { it.name }
            ?: error("mediaSlots: $folder is not a folder")
        for (f in files) {
            val image = ImageIO.read(f) ?: continue
            tray += MediaSlot(-1, MediaSlot.Kind.IMAGE, "#808080", f.nameWithoutExtension, image, "", f)
        }
    }

    private companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp")
    }
}

/**
 * Show a row of picture and text slots in this box's output and return them; see [MediaSlots].
 *
 * @param channel the output channel the slots open in.
 * @param colors the slots' colours in order: the madp `tetra` page's by default.
 */
fun Box.mediaSlots(
    channel: String = "inputs",
    colors: List<String> = MEDIA_SLOT_COLORS,
    block: MediaSlotsBuilder.() -> Unit,
): MediaSlots {
    val built = MediaSlotsBuilder(colors).apply(block)
    require(built.slots.isNotEmpty()) { "mediaSlots: add at least one image(...) or text(...) slot" }
    val slots = MediaSlots(this, channel, built.slots)
    slots.initialTray = built.tray
    display(channel, slots.html())
    return slots
}

/** The madp `tetra` page's four slot colours, then two more from the same palette. */
val MEDIA_SLOT_COLORS = listOf("#8be9fd", "#50fa7b", "#ffb86c", "#ff79c6", "#bd93f9", "#f1fa8c")

private fun thumbnail(image: BufferedImage, size: Int): String {
    val scale = size.toDouble() / minOf(image.width, image.height)
    val w = maxOf(1, (image.width * scale).toInt())
    val h = maxOf(1, (image.height * scale).toInt())
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    out.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        drawImage(image, 0, 0, w, h, null)
        dispose()
    }
    val bytes = ByteArrayOutputStream().also { ImageIO.write(out, "jpg", it) }.toByteArray()
    return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

private fun renderMediaSlots(actionId: String, slots: List<MediaSlot>, tray: List<MediaSlot>, error: String?): String {
    val root = "ms-" + actionId.takeLast(12)
    val filled = slots.count { !it.isEmpty }
    val images = slots.count { it.kind == MediaSlot.Kind.IMAGE }
    val texts = slots.size - images
    val status = error?.let { "<span style='color:#ff7777'>${esc(it)}</span>" } ?: when {
        filled < slots.size -> "drop ${describe(images, texts)} to compute (${filled}/${slots.size} ready)"
        else -> "${describe(images, texts)} ready"
    }
    return buildString {
        append("<div id='$root' class='media-slots' style='font-family:\"Iosevka Web\",ui-monospace,monospace;font-size:12px;color:#b0b0b0;")
        append("background:#0b0b0b;padding:10px;display:flex;flex-direction:column;gap:8px;max-width:820px'>")
        append("<div style='display:grid;grid-template-columns:repeat(${slots.size.coerceAtMost(6)},minmax(0,1fr));gap:8px'>")
        for (s in slots) {
            append("<div class='ms-slot' data-slot='${s.index}' data-kind='${s.kind.name.lowercase()}' style='border:1px dashed #2d2d2d;background:#101010;")
            append("padding:8px;display:flex;flex-direction:column;gap:6px;min-width:0;cursor:${if (s.kind == MediaSlot.Kind.IMAGE) "pointer" else "default"}'>")
            append("<div style='display:flex;align-items:center;gap:6px;min-width:0'>")
            append("<span style='width:20px;height:20px;flex:none;display:inline-flex;align-items:center;justify-content:center;color:#101010;background:${s.color};font-weight:500'>${s.index + 1}</span>")
            val name = if (s.kind == MediaSlot.Kind.TEXT) "text" else s.label.ifBlank { "empty" }
            append("<span style='color:#8a8a8a;overflow:hidden;white-space:nowrap;text-overflow:ellipsis'>${esc(name)}</span></div>")
            if (s.kind == MediaSlot.Kind.IMAGE) {
                append("<div style='aspect-ratio:4/3;border:1px solid #242424;background:#080808;display:flex;align-items:center;justify-content:center;color:#393939;font-size:22px;overflow:hidden'>")
                if (s.image != null) append("<img alt='' src='${thumbnail(s.image, 160)}' style='width:100%;height:100%;object-fit:cover'>")
                else append("<span>${s.index + 1}</span>")
                append("</div><input type='file' accept='image/*' multiple style='display:none'>")
            } else {
                append("<textarea spellcheck='true' placeholder='type text to embed' style='aspect-ratio:4/3;width:100%;box-sizing:border-box;resize:none;background:#080808;")
                append("color:#d0d0d0;border:1px solid #242424;padding:6px;font:inherit;line-height:1.35;outline:none'>${esc(s.text)}</textarea>")
            }
            append("</div>")
        }
        append("</div>")
        append("<div class='ms-tray' style='display:flex;flex-wrap:wrap;gap:4px;align-items:center;min-height:40px;border-top:1px solid #1a1a1a;padding-top:8px'>")
        tray.forEachIndexed { i, t ->
            append("<img class='ms-tray-item' draggable='true' data-item='$i' title='${esc(t.label)} — drag onto a slot' src='${t.image?.let { thumbnail(it, 64) } ?: ""}' ")
            append("style='width:44px;height:44px;object-fit:cover;border:1px solid #242424;cursor:grab'>")
        }
        append("<span style='color:#444;font-size:11px;margin-left:4px'>${if (tray.isEmpty()) "drop a folder here for a tray of pictures" else "drag onto a slot"}</span>")
        append("<span style='margin-left:auto;display:flex;gap:6px'>")
        append("<button class='ms-folder' style='background:#1a1a1a;color:#808080;border:1px solid #252525;padding:2px 10px;font:inherit;cursor:pointer'>folder…</button>")
        append("<button class='ms-reset' style='background:#1a1a1a;color:#808080;border:1px solid #252525;padding:2px 10px;font:inherit;cursor:pointer'>reset</button>")
        append("<input class='ms-folder-input' type='file' webkitdirectory multiple style='display:none'></span></div>")
        append("<div class='ms-status' style='color:#5a5a5a;font-size:11px'>$status</div>")
        append("</div>")
        append(mediaSlotsScript(root, actionId))
    }
}

private fun describe(images: Int, texts: Int): String {
    val words = listOf("no", "one", "two", "three", "four", "five", "six")
    fun n(k: Int) = words.getOrElse(k) { "$k" }
    val pictures = if (images == 1) "one image" else "${n(images)} images"
    return when (texts) {
        0 -> pictures
        1 -> "$pictures and text"
        else -> "$pictures and ${n(texts)} texts"
    }
}

private fun mediaSlotsScript(root: String, actionId: String): String = """
<script>
(() => {
  const root = document.getElementById('$root');
  if (!root) return;
  const send = (o) => { if (window.handleStringInput) window.handleStringInput({ id: '$actionId', value: JSON.stringify(o) }); };
  const status = root.querySelector('.ms-status');
  const isImage = (f) => f && f.type && f.type.startsWith('image/');
  // Scaled in the page, orientation applied, so a 12-megapixel phone photo crosses the socket as ~60 kB.
  async function shrink(file) {
    const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
    const s = Math.min(1, 640 / Math.max(bitmap.width, bitmap.height));
    const c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(bitmap.width * s)); c.height = Math.max(1, Math.round(bitmap.height * s));
    c.getContext('2d').drawImage(bitmap, 0, 0, c.width, c.height);
    return { name: file.name || 'image', dataUrl: c.toDataURL('image/jpeg', 0.88) };
  }
  async function shrinkAll(files) {
    status.textContent = 'reading ' + files.length + (files.length === 1 ? ' picture…' : ' pictures…');
    const out = [];
    for (const f of files) { try { out.push(await shrink(f)); } catch (e) { console.warn('media slots: skipped', f.name, e); } }
    return out;
  }
  function entryFiles(entry) {
    return new Promise((resolve) => {
      if (entry.isFile) { entry.file((f) => resolve([f]), () => resolve([])); return; }
      if (!entry.isDirectory) { resolve([]); return; }
      const reader = entry.createReader(), all = [];
      const next = () => reader.readEntries(async (batch) => {
        if (!batch.length) { resolve((await Promise.all(all.map(entryFiles))).flat()); return; }
        all.push(...batch); next();
      }, () => resolve([]));
      next();
    });
  }
  async function dropped(event) {
    const items = Array.from(event.dataTransfer.items || []);
    const entries = items.map((i) => i.webkitGetAsEntry && i.webkitGetAsEntry()).filter(Boolean);
    const folder = entries.some((e) => e.isDirectory);
    const files = folder ? (await Promise.all(entries.map(entryFiles))).flat() : Array.from(event.dataTransfer.files || []);
    return { folder, files: files.filter(isImage).sort((a, b) => a.name.localeCompare(b.name)) };
  }
  const over = (el, on, color) => { el.style.borderColor = on ? (color || '#8be9fd') : '#2d2d2d'; el.style.background = on ? '#12191b' : '#101010'; };
  root.querySelectorAll('.ms-slot').forEach((slot) => {
    const index = Number(slot.dataset.slot);
    if (slot.dataset.kind === 'text') {
      const area = slot.querySelector('textarea');
      let timer = null;
      area.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(() => send({ action: 'text', slot: index, text: area.value }), 350); });
      area.addEventListener('keydown', (e) => e.stopPropagation());
      return;
    }
    const input = slot.querySelector('input[type=file]');
    input.addEventListener('click', (e) => e.stopPropagation());
    slot.addEventListener('click', () => input.click());
    input.addEventListener('change', async () => {
      const files = Array.from(input.files || []).filter(isImage); input.value = '';
      if (files.length) send({ action: 'image', slot: index, files: await shrinkAll(files) });
    });
    ['dragenter', 'dragover'].forEach((t) => slot.addEventListener(t, (e) => { e.preventDefault(); e.stopPropagation(); over(slot, true); }));
    slot.addEventListener('dragleave', () => over(slot, false));
    slot.addEventListener('drop', async (e) => {
      e.preventDefault(); e.stopPropagation(); over(slot, false);
      const item = e.dataTransfer.getData('text/x-field-tray');
      if (item !== '') { send({ action: 'tray', slot: index, item: Number(item) }); return; }
      const got = await dropped(e);
      if (!got.files.length) return;
      if (got.folder) send({ action: 'tray-add', files: await shrinkAll(got.files) });
      else send({ action: 'image', slot: index, files: await shrinkAll(got.files) });
    });
  });
  root.querySelectorAll('.ms-tray-item').forEach((img) => {
    img.addEventListener('dragstart', (e) => { e.dataTransfer.setData('text/x-field-tray', img.dataset.item); e.dataTransfer.effectAllowed = 'copy'; });
  });
  const tray = root.querySelector('.ms-tray');
  ['dragenter', 'dragover'].forEach((t) => tray.addEventListener(t, (e) => { e.preventDefault(); e.stopPropagation(); }));
  tray.addEventListener('drop', async (e) => {
    e.preventDefault(); e.stopPropagation();
    if (e.dataTransfer.getData('text/x-field-tray') !== '') return;
    const got = await dropped(e);
    if (got.files.length) send({ action: 'tray-add', files: await shrinkAll(got.files) });
  });
  const folderInput = root.querySelector('.ms-folder-input');
  root.querySelector('.ms-folder').addEventListener('click', () => folderInput.click());
  folderInput.addEventListener('change', async () => {
    const files = Array.from(folderInput.files || []).filter(isImage).sort((a, b) => a.name.localeCompare(b.name)); folderInput.value = '';
    if (files.length) send({ action: 'tray-add', files: await shrinkAll(files) });
  });
  root.querySelector('.ms-reset').addEventListener('click', () => send({ action: 'reset' }));
})();
</script>
""".trimIndent()
