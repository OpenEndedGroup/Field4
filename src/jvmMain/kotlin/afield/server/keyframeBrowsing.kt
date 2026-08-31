package afield.server

import afield.Handled
import afield.RunsAt
import afield.h
import afield.inMain
import afield.installHandlers
import afield.logger.lprintln
import afield.rawHandlers
import afield.stdlib.KValue
import afield.stdlib.KeyframeCollection
import afield.stdlib.KeyframeScope
import afield.stdlib.SlotSummary
import afield.stdlib.SourceBackedBlock
import afield.stdlib.StackSLString
import afield.stdlib.formatSlotNumber
import afield.thisSession
import io.ktor.websocket.Frame
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.lang.ref.WeakReference
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.name

/**
 * The server half of the keyframe collection browser: one `/ws` raw channel, no new HTTP route.
 *
 * The page itself is `keyframe_browser.html`, served by the static mount over
 * `src/commonMain/resources`, so nothing here widens Field's HTTP surface — which matters, because
 * every verbless `route(…) { handle { … } }` is an unauthenticated all-methods endpoint and there is
 * a census test counting them (`FieldApiCorsExposureTest`). A raw channel rides the socket the
 * client already has, the way the h2 explorer and the log stream do.
 *
 * ## What crosses the wire
 *
 * Structure as JSON, presentation as HTML. The collection summary, the slot bands and the version
 * rows are typed payloads the page lays out itself; individual *values* come over as rendered HTML
 * from `.h`, because that is the whole point of reusing the value visualizers — a `Vector3f` arrives
 * with its axis widget already drawn, and a type that registers its own printer gets it for free.
 *
 * ## Read-only, deliberately
 *
 * There is an [apply][KeyframeBrowserProtocol] op, because putting a saved look back is the reason
 * you were browsing. There is no edit op. Everything on disk is append-only by construction —
 * `saveKey` never overwrites a numbered version and an unreadable manifest is quarantined rather
 * than replaced — and an in-browser edit button would quietly break that. Getting a modified look
 * back out goes through `updateCode`, which emits pasteable Kotlin.
 */

private const val KEYFRAME_BROWSER_CHANNEL = "__keyframe_browser__"

// ---------------------------------------------------------------------------------------------
// Token registry
// ---------------------------------------------------------------------------------------------

/**
 * What one browser iframe is looking at. The scope is held weakly: a browser left open in a box
 * output must not keep a whole sketch's uniform structures alive, and a scope that has been
 * collected simply degrades the browser to read-only, which is the correct behaviour rather than a
 * failure.
 */
private class KeyframeBrowserHandle(
    val root: Path,
    val pattern: String,
    val scope: WeakReference<KeyframeScope>?
) {
    @Volatile
    var cached: KeyframeCollection? = null

    /** Re-read the directory. Cheap enough to do on every open; the cache is for the click-arounds. */
    fun reload(): KeyframeCollection =
        KeyframeCollection.load(root, pattern, scope?.get()).also { cached = it }

    fun collection(): KeyframeCollection = cached ?: reload()
}

private val keyframeBrowserHandles = ConcurrentHashMap<String, KeyframeBrowserHandle>()
private val keyframeBrowserIds = AtomicLong(1)

/**
 * Register a directory + glob for browsing and return the token the iframe passes back on every
 * request.
 *
 * Tokens are process-local and never expire — they hold a path and a weak scope reference, not a
 * collection, so an abandoned one costs a map entry rather than a retained sketch.
 */
fun registerKeyframeBrowser(root: Path, pattern: String, scope: KeyframeScope? = null): String {
    val token = "kb${keyframeBrowserIds.getAndIncrement()}"
    keyframeBrowserHandles[token] = KeyframeBrowserHandle(root, pattern, scope?.let { WeakReference(it) })
    return token
}

// ---------------------------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------------------------

@Serializable
private data class KBRequest(
    val channel: String? = null,
    val reqId: String,
    val op: String,
    val root: String? = null,
    val slot: String? = null,
    val fileId: Int? = null,
    val otherId: Int? = null
)

@Serializable
private data class KBFile(
    val id: Int,
    val name: String,
    val relative: String,
    val schemaVersion: Int,
    val slotCount: Int,
    val versions: Int,
    val timestamp: String? = null,
    val note: String? = null
)

@Serializable
private data class KBOpen(
    val reqId: String,
    val ok: Boolean = true,
    val type: String = "open",
    val root: String,
    val pattern: String,
    val fileCount: Int,
    val versionCount: Int,
    val slotCount: Int,
    val schemaVersions: List<Int>,
    val latestTimestamp: String? = null,
    val latestNote: String? = null,
    val hasScope: Boolean,
    val files: List<KBFile>,
    val slots: List<SlotSummary>,
    val problems: List<String>
)

@Serializable
private data class KBFragment(
    val reqId: String,
    val ok: Boolean = true,
    val type: String = "fragment",
    val title: String,
    val html: String
)

@Serializable
private data class KBText(
    val reqId: String,
    val ok: Boolean = true,
    val type: String = "text",
    val title: String,
    val text: String
)

@Serializable
private data class KBError(
    val reqId: String,
    val ok: Boolean = false,
    val type: String = "error",
    val error: String
)

// ---------------------------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------------------------

private fun esc(s: String): String = buildString(s.length) {
    s.forEach {
        when (it) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(it)
        }
    }
}

/**
 * One saved value as HTML, through the same route a live one takes.
 *
 * `KValue.toAny()` rebuilds the real Kotlin value — a `Vector3f`, a `CameraPose`, a `Quaternionf` —
 * and `.h` then renders it exactly as it would in a box output, custom printers and all. The two
 * exceptions match `keyframer.kt`'s own `keyframeValueHtml`: a `StackSLString` renders its source
 * rather than the wrapper, and a `SourceBackedBlock` shows the box it came from over its source,
 * since `.h` on a block that has not compiled yet says nothing useful.
 */
private fun valueHtml(v: KValue): String {
    val any = runCatching { v.toAny() }.getOrElse {
        return "<span style='color:#f88'>unreadable: ${esc(it.message ?: it::class.java.simpleName)}</span>"
    }
    return when (any) {
        is StackSLString -> "<pre class='kb-src'>${esc(any.value)}</pre>"
        is SourceBackedBlock<*> ->
            "<div class='kb-block'><span class='kb-dim'>sourceBlock in ${esc(any.boxId)}</span>" +
                "<pre class='kb-src'>${esc(any.source)}</pre></div>"
        else -> any.h
    }
}

/** The expand panel for one slot: every file's value, then that slot's trajectory per file. */
private fun slotFragment(collection: KeyframeCollection, summary: SlotSummary): String = buildString {
    append("<div class='kb-detail'>")

    append("<div class='kb-detail-head'>")
    append("<code>${esc(summary.name)}</code> <span class='kb-dim'>${esc(summary.typeLabel)}</span>")
    append(" <span class='kb-dim'>· in ${summary.presentIn} of ${summary.total}</span>")
    append("</div>")

    append("<table class='kb-values'>")
    collection.files.forEach { file ->
        val v = file.values[summary.name]
        append("<tr>")
        append("<th>${esc(file.relative)}</th>")
        if (v == null) {
            append("<td class='kb-missing'>absent — schema ${file.schemaVersion}</td>")
        } else {
            append("<td>${valueHtml(v)}</td>")
        }
        append("</tr>")
    }
    append("</table>")

    // The trajectory. Only files that have actually been saved more than once contribute anything,
    // and this is the one place that opens the numbered snapshots — see KeyframeCollection.slotHistory.
    val withHistory = collection.files.filter { it.versions.size > 1 }
    if (withHistory.isNotEmpty()) {
        append("<div class='kb-detail-head kb-detail-sub'>across saves</div>")
        withHistory.forEach { file ->
            val history = collection.slotHistory(file.id, summary.name)
            append("<div class='kb-history'>")
            append("<div class='kb-history-name'>${esc(file.relative)}</div>")
            append("<div class='kb-history-rail'>")
            history.forEach { (info, value) ->
                val label = buildString {
                    append("v${info.index}")
                    info.note?.let { append(" — ").append(it) }
                    append("\n").append(info.timestamp)
                    append("\n").append(value?.let { shortValue(it) } ?: "absent")
                }
                val cls = if (value == null) "kb-tick kb-tick-absent" else "kb-tick"
                append("<span class='$cls' title='${esc(label)}'></span>")
            }
            append("</div>")
            val numeric = history.mapNotNull { (_, v) -> (v as? KValue.Num)?.value }
            if (numeric.size > 1) {
                append("<div class='kb-dim kb-history-range'>")
                append("${formatSlotNumber(numeric.min())} – ${formatSlotNumber(numeric.max())}")
                append(" over ${history.size} saves</div>")
            } else {
                append("<div class='kb-dim kb-history-range'>${history.size} saves</div>")
            }
            append("</div>")
        }
    }

    append("</div>")
}

/** A one-line rendering of a saved value, for tooltips and the code preview. */
private fun shortValue(v: KValue): String = when (v) {
    is KValue.Num -> formatSlotNumber(v.value)
    is KValue.Vec2 -> "vec(${formatSlotNumber(v.x.toDouble())}, ${formatSlotNumber(v.y.toDouble())})"
    is KValue.Vec3 -> "vec(${formatSlotNumber(v.x.toDouble())}, ${formatSlotNumber(v.y.toDouble())}, ${formatSlotNumber(v.z.toDouble())})"
    is KValue.Vec4 -> "vec(${formatSlotNumber(v.x.toDouble())}, ${formatSlotNumber(v.y.toDouble())}, ${formatSlotNumber(v.z.toDouble())}, ${formatSlotNumber(v.w.toDouble())})"
    is KValue.Quat -> "quat(…)"
    is KValue.DualQuat -> "dualquat(…)"
    is KValue.CameraPoseValue -> "cameraPose(focus=${formatSlotNumber(v.focusDistance.toDouble())})"
    is KValue.Str -> v.value.lineSequence().firstOrNull()?.take(48).orEmpty()
    is KValue.StackSL -> v.value.lineSequence().firstOrNull()?.take(48).orEmpty()
    is KValue.SourceBlock -> "sourceBlock@${v.boxId}"
}

/** Rebuild a file's entries as live values, for diffing and applying. */
private fun liveValues(values: Map<String, KValue>): MutableMap<String, Any?> =
    values.mapValues { (_, v) -> v.toAny() as Any? }.toMutableMap()

// ---------------------------------------------------------------------------------------------
// Protocol
// ---------------------------------------------------------------------------------------------

private object KeyframeBrowserProtocol {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun handle(text: String): String? {
        val req = runCatching { json.decodeFromString(KBRequest.serializer(), text) }.getOrNull() ?: return null
        if (req.channel != KEYFRAME_BROWSER_CHANNEL) return null

        fun fail(message: String) = json.encodeToString(KBError.serializer(), KBError(req.reqId, error = message))

        val token = req.root ?: return fail("missing root token")
        val handle = keyframeBrowserHandles[token] ?: return fail("unknown keyframe browser token")

        return runCatching {
            when (req.op) {
                "open" -> {
                    val c = handle.reload()
                    json.encodeToString(
                        KBOpen.serializer(),
                        KBOpen(
                            reqId = req.reqId,
                            root = c.root.toString(),
                            pattern = c.pattern,
                            fileCount = c.files.size,
                            versionCount = c.versionCount,
                            slotCount = c.slotNames.size,
                            schemaVersions = c.schemaVersions,
                            latestTimestamp = c.latest?.timestamp,
                            latestNote = c.latest?.note,
                            hasScope = handle.scope?.get() != null,
                            files = c.files.map {
                                KBFile(
                                    id = it.id,
                                    name = it.path.name,
                                    relative = it.relative,
                                    schemaVersion = it.schemaVersion,
                                    slotCount = it.values.size,
                                    versions = it.versions.size,
                                    timestamp = it.latest?.timestamp,
                                    note = it.latest?.note
                                )
                            },
                            slots = c.slots(),
                            problems = c.problems
                        )
                    )
                }

                "slot" -> {
                    val name = req.slot ?: return fail("missing slot name")
                    val c = handle.collection()
                    val summary = c.slots().firstOrNull { it.name == name } ?: return fail("unknown slot $name")
                    json.encodeToString(
                        KBFragment.serializer(),
                        KBFragment(req.reqId, title = name, html = slotFragment(c, summary))
                    )
                }

                "diff" -> {
                    val c = handle.collection()
                    val a = req.fileId?.let { c.file(it) } ?: return fail("missing or unknown left file")
                    val b = req.otherId?.let { c.file(it) } ?: return fail("missing or unknown right file")
                    // KeyDiff already renders itself for box output, so the diff view is not a new
                    // view at all — it is the one keyframer.kt has always had, hoisted into a panel.
                    val scope = KeyframeScope()
                    val diff = scope.Key(liveValues(a.values))
                        .diff(scope.Key(liveValues(b.values)), a.relative, b.relative)
                    json.encodeToString(
                        KBFragment.serializer(),
                        KBFragment(
                            req.reqId,
                            title = "${a.relative} → ${b.relative}",
                            html = diff.toHTML()
                        )
                    )
                }

                "code" -> {
                    val c = handle.collection()
                    val file = req.fileId?.let { c.file(it) } ?: return fail("missing or unknown file")
                    val scope = KeyframeScope()
                    val code = scope.Key(liveValues(file.values)).updateCode(apply = true)
                    json.encodeToString(
                        KBText.serializer(),
                        KBText(req.reqId, title = file.relative, text = code)
                    )
                }

                "apply" -> {
                    val scope = handle.scope?.get()
                        ?: return fail("no live scope — open the browser with scope = yourScope to apply")
                    val c = handle.collection()
                    val file = req.fileId?.let { c.file(it) } ?: return fail("missing or unknown file")
                    val key = scope.Key(liveValues(file.values))
                    // Applying writes to uniform structures and rebinds shader sources, so it belongs
                    // on the main thread with every other write to a live sketch.
                    inMain { key() }
                    val landed = key.values.keys.count { scope.capture().values.containsKey(it) }
                    json.encodeToString(
                        KBText.serializer(),
                        KBText(
                            req.reqId,
                            title = file.relative,
                            text = "applied ${file.values.size} values; $landed have a target in this scope"
                        )
                    )
                }

                else -> fail("unknown op ${req.op}")
            }
        }.getOrElse { e ->
            lprintln("error,keyframer", "keyframe browser op ${req.op} failed: ${e.message}")
            fail("${req.op} failed: ${e.message ?: e::class.java.simpleName}")
        }
    }
}

/**
 * Test seam: run one protocol request against a freshly registered collection and hand back the raw
 * JSON reply, without a server, a socket or a browser.
 *
 * Everything above is private, and the parts most worth pinning are the parts that are *not* this
 * file's own logic — that `.h` really renders a saved `Vector3f` as a live one, and that the diff
 * panel really is `KeyDiff.toHTML`. A test that reimplemented either would prove nothing; this runs
 * the same code the socket does.
 */
internal fun keyframeBrowserRequestForTesting(
    root: Path,
    pattern: String,
    op: String,
    scope: KeyframeScope? = null,
    slot: String? = null,
    fileId: Int? = null,
    otherId: Int? = null
): String {
    val token = registerKeyframeBrowser(root, pattern, scope)
    val request = Json.encodeToString(
        KBRequest.serializer(),
        KBRequest(
            channel = KEYFRAME_BROWSER_CHANNEL,
            reqId = "test",
            op = op,
            root = token,
            slot = slot,
            fileId = fileId,
            otherId = otherId
        )
    )
    return KeyframeBrowserProtocol.handle(request)
        ?: error("the protocol declined a request on its own channel: $request")
}

/**
 * Test seam for the two answers that cannot be reached through
 * [keyframeBrowserRequestForTesting], because that one always registers a valid token: an unknown
 * token, and a message addressed to some other channel (which must come back null, so the rest of
 * the raw-handler chain still gets a look).
 */
internal fun keyframeBrowserRawForTesting(text: String): String? = KeyframeBrowserProtocol.handle(text)

@RunsAt(installHandlers)
fun installKeyframeBrowserHandler() {
    rawHandlers.getOrPut(KEYFRAME_BROWSER_CHANNEL) { mutableSetOf() }.add { text ->
        val session = thisSession.get()
        val out = KeyframeBrowserProtocol.handle(text) ?: return@add Handled.no
        inMain {
            session.send(Frame.Text(out))
        }
        Handled.yes
    }
}
