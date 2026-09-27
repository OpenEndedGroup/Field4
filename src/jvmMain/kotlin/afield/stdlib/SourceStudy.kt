@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.payload
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch

/**
 * The language-neutral half of a live-code study: the part of "talking a function into existence"
 * that does not care what the language is.
 *
 * A study holds one editable source — the text in a box that a person and a model are both working
 * on — and a durable, append-only history of the versions of it that passed a check. Every change
 * arrives as a *transaction*: the caller names the revision it read and a hash of the live source it
 * read, the candidate is checked by the language adapter, and only then is it written into the box,
 * with a compare-and-swap against the text that is there *now*. So a model preparing an edit while a
 * person types cannot overwrite the person, a candidate that fails its check changes nothing, and
 * every accepted version can be read back, compared and restored.
 *
 * What the language adds is one function, [SourceStudy]'s `admit`: compile and check a candidate,
 * returning a value (whatever the language's tools need — a composition, a compiled shader report)
 * or throwing [StudyRejection] with a message a person or a model can act on. `afield.stacksl.study`
 * is the first adapter; `FunctionStudy` predates this class and implements the same protocol with the
 * same file layout, so a revision directory written by either reads the same way.
 *
 * ### On disk
 *
 * `directory/<id>.<extension>` is a revision's source and `directory/<id>.json` its metadata (parent,
 * description, time, and whatever facts the adapter chose to keep). `directory/HEAD` names the checked
 * revision the box last held. An id is `r<n>-<first 12 hex of SHA-256(source)>`, so an id also
 * verifies its own file: a revision whose source was edited on disk refuses to load.
 *
 * This is trusted local code, not a sandbox: `admit` runs whatever the adapter runs. Cancellation
 * ([cancel]) stops a pending publication, not a computation that never checks for it.
 */

/** A candidate the language adapter refused. [message] is written for whoever proposed the candidate; [facts] carry the evidence. */
class StudyRejection(message: String, val facts: Map<String, Any?> = emptyMap()) : IllegalArgumentException(message)

/** An accepted candidate: the compiled [value] the tools use, and [facts] worth saving with the revision. */
data class StudyAdmission<T>(val value: T, val facts: Map<String, Any?> = emptyMap())

/** One checked, immutable version of the source. */
data class SourceRevision<T>(
    val id: String,
    val parent: String?,
    val description: String,
    val source: String,
    val value: T,
    val facts: Map<String, Any?> = emptyMap()
)

/** A revision without its source or value, for history listings. */
data class SourceRevisionSummary(val id: String, val parent: String?, val description: String, val created: String?)

/**
 * A durable, transactional history of one editable source, checked by a language adapter.
 *
 * @param directory where revisions live; created if missing
 * @param extension the file extension revision sources are written with (`stacksl`, `kt`, …)
 * @param readSource the live text, read fresh every time — usually a box property
 * @param replaceSource writes a replacement only if the live text still equals `expected`, returning
 *   whether it did; this is the compare-and-swap that protects a person's concurrent edit
 * @param admit compile and check a candidate. Throw [StudyRejection] to refuse it. The second argument
 *   says whether the pending operation has been cancelled, for adapters with long checks.
 */
class SourceStudy<T>(
    val directory: File,
    val extension: String,
    private val readSource: () -> String,
    private val replaceSource: (expected: String, replacement: String) -> Boolean,
    private val admit: (source: String, cancelled: () -> Boolean) -> StudyAdmission<T>,
    val maxSourceLength: Int = 40000
) {
    private val revisions = linkedMapOf<String, SourceRevision<T>>()
    private val cancelled = AtomicLong()
    private val mapper = jacksonObjectMapper()
    private var active = EMPTY

    init {
        require(extension.matches(Regex("[a-z0-9]{1,12}"))) { "Revision extension must be a short lower-case word" }
        directory.mkdirs()
    }

    companion object {
        /** The id of a study that has not accepted any source yet. */
        const val EMPTY = "empty"

        /** The id every revision has: a sequence number and the first 12 hex digits of its source's hash. */
        val REVISION_ID = Regex("r[0-9]+-[a-f0-9]{12}")

        /** SHA-256 of [source], lower-case hex — the value every edit must quote to prove what it read. */
        fun hash(source: String): String = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }

        /**
         * [source] with each of [edits] applied, or a thrown [StudyRejection] saying why not.
         *
         * Each `before` must occur exactly once in [source], and no two may overlap; all of them are
         * matched against the same original text, so the order they are given in does not matter.
         * A missing or ambiguous match changes nothing.
         */
        fun applyEdits(source: String, edits: List<StudySourceEdit>): String {
            if (edits.size !in 1..32) throw StudyRejection("Supply 1..32 exact replacements")
            val ranges = edits.map { change ->
                if (change.before.isEmpty()) throw StudyRejection("An empty match is ambiguous; include the surrounding source")
                val at = source.indexOf(change.before)
                if (at < 0) throw StudyRejection("Not found in the live source: '${change.before.take(80)}'. Inspect again and copy the text exactly.")
                if (source.indexOf(change.before, at + 1) >= 0) {
                    throw StudyRejection("'${change.before.take(80)}' occurs more than once; include more context so it matches exactly once")
                }
                Triple(at, at + change.before.length, change.after)
            }.sortedBy { it.first }
            if (!ranges.zipWithNext().all { (a, b) -> a.second <= b.first }) throw StudyRejection("Two replacements overlap")
            val candidate = StringBuilder(source)
            ranges.asReversed().forEach { (start, end, text) -> candidate.replace(start, end, text) }
            return candidate.toString()
        }
    }

    /** The live text, as the box holds it now. */
    fun liveSource(): String = readSource()

    /** The id of the checked revision the box holds, or [EMPTY]. */
    @Synchronized
    fun currentId(): String = active

    /** Invalidate every pending publication. The working revision is kept. */
    fun cancel(): String {
        cancelled.incrementAndGet()
        return "Pending publication cancelled; working revision retained."
    }

    private fun checkCancelled(epoch: Long) {
        if (cancelled.get() != epoch || Thread.currentThread().isInterrupted) throw CancellationException("Study operation cancelled")
    }

    /** Whether an operation started at [epoch] has since been cancelled — for long reads outside a transaction. */
    fun isCancelled(epoch: Long): Boolean = cancelled.get() != epoch || Thread.currentThread().isInterrupted

    /** The cancellation epoch to pass to [isCancelled]. */
    fun epoch(): Long = cancelled.get()

    /** Pick up where a previous session left off, if `HEAD` names a revision whose source still verifies. */
    @Synchronized
    fun resume(): String {
        val head = File(directory, "HEAD")
        if (head.isFile) {
            val id = head.readText().trim()
            revision(id)
            active = id
        }
        return active
    }

    /** A revision by id, or the active one for `"current"` (or blank). Loaded from disk and re-admitted on first use. */
    @Synchronized
    fun revision(id: String = "current"): SourceRevision<T> {
        val key = if (id.isBlank() || id == "current") active else id
        revisions[key]?.let { return it }
        if (key == EMPTY) throw StudyRejection("No checked revision yet; make the first edit against 'empty'.")
        if (!key.matches(REVISION_ID)) throw StudyRejection("Unknown revision '$key'")
        val sourceFile = File(directory, "$key.$extension")
        if (!sourceFile.isFile) throw StudyRejection("Unknown revision '$key'")
        val source = sourceFile.readText()
        if (key.substringAfter('-') != hash(source).take(12)) throw StudyRejection("Revision source changed on disk: $key")
        val meta = readMeta(key)
        val admitted = admit(source) { false }
        val result = SourceRevision(key, meta.parent, meta.description, source, admitted.value, admitted.facts)
        revisions[key] = result
        return result
    }

    private data class Meta(val parent: String?, val description: String, val created: String?)

    private fun readMeta(id: String): Meta {
        val node = mapper.readTree(File(directory, "$id.json"))
        return Meta(
            node["parent"]?.takeUnless { it.isNull }?.asText(),
            node["description"]?.asText().orEmpty(),
            node["created"]?.takeUnless { it.isNull }?.asText()
        )
    }

    /** The active revision's ancestry, oldest first. */
    @Synchronized
    fun history(): List<SourceRevisionSummary> {
        val ids = mutableListOf<String>()
        var cursor: String? = active.takeUnless { it == EMPTY }
        while (cursor != null && cursor !in ids) {
            ids += cursor
            cursor = runCatching { readMeta(cursor).parent }.getOrNull()
        }
        return ids.reversed().map { summary(it) }
    }

    /** Every revision on disk, in the order they were made — including ones a restore left off the active line. */
    @Synchronized
    fun allRevisions(): List<SourceRevisionSummary> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" && it.nameWithoutExtension.matches(REVISION_ID) }
        .sortedBy { it.nameWithoutExtension.substringBefore('-').removePrefix("r").toInt() }
        .map { summary(it.nameWithoutExtension) }

    private fun summary(id: String): SourceRevisionSummary {
        val meta = readMeta(id)
        return SourceRevisionSummary(id, meta.parent, meta.description, meta.created)
    }

    /** The live source against the checked revision: its hash, whether they differ, and the live text when they do. */
    @Synchronized
    fun liveState(): Map<String, Any?> {
        val live = readSource()
        val checked = if (active == EMPTY) null else revision().source
        return mapOf(
            "revision" to active,
            "liveSourceHash" to hash(live),
            "dirty" to (live != checked),
            "liveSource" to live.takeIf { it != checked }
        )
    }

    /**
     * Check [source] with the adapter without publishing anything. This is what speculation uses: the
     * same admission a real edit gets, with no transaction around it.
     */
    fun check(source: String): StudyAdmission<T> {
        if (source.length > maxSourceLength) throw StudyRejection("Candidate source must be at most $maxSourceLength characters")
        val epoch = cancelled.get()
        return admit(source) { isCancelled(epoch) }
    }

    /**
     * Replace the live source with [source] as a new checked revision.
     *
     * [base] must be the active revision id and [sourceHash] the hash of the live source *now*, both
     * as the caller last read them; either being stale refuses the edit and overwrites nothing. The
     * candidate is admitted before anything is written, and the live text is compared once more at
     * publication, so a person typing during a slow check keeps what they typed.
     */
    @Synchronized
    fun edit(base: String, sourceHash: String, source: String, description: String): SourceRevision<T> {
        val before = readSource()
        if (base != active || sourceHash != hash(before)) {
            throw StudyRejection("Conflict: the revision or the live source changed since you read them. Inspect again and reconcile; nothing was overwritten.")
        }
        if (source.length > maxSourceLength) throw StudyRejection("Candidate source must be at most $maxSourceLength characters")
        val epoch = cancelled.get()
        val admitted = admit(source) { isCancelled(epoch) }
        checkCancelled(epoch)
        if (readSource() != before) throw StudyRejection("Conflict: the source changed while the candidate was being checked; it was not activated.")
        val number = directory.listFiles().orEmpty().filter { it.extension == "json" }
            .mapNotNull { it.name.substringBefore('-').removePrefix("r").toIntOrNull() }.maxOrNull()?.plus(1) ?: 1
        val id = "r$number-${hash(source).take(12)}"
        val parent = active.takeUnless { it == EMPTY }
        // The durable candidate is written before publication; an unreferenced file after a conflict is harmless.
        File(directory, "$id.$extension").writeText(source)
        File(directory, "$id.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(linkedMapOf(
            "id" to id, "parent" to parent, "description" to description,
            "created" to Instant.now().toString(), "sourceHash" to hash(source), "facts" to admitted.facts
        )))
        checkCancelled(epoch)
        if (!replaceSource(before, source)) throw StudyRejection("Conflict: the source changed at publication; the working revision was kept.")
        val revision = SourceRevision(id, parent, description, source, admitted.value, admitted.facts)
        revisions[id] = revision
        active = id
        File(directory, "HEAD").writeText(id)
        return revision
    }

    /** [edit] with exact replacements applied to the live source — see [applyEdits]. */
    @Synchronized
    fun patch(base: String, sourceHash: String, edits: List<StudySourceEdit>, description: String): SourceRevision<T> {
        val source = readSource()
        if (base != active || sourceHash != hash(source)) {
            throw StudyRejection("Conflict: inspect the current revision and live source before patching.")
        }
        return edit(base, sourceHash, applyEdits(source, edits), description)
    }

    /**
     * Make an earlier revision's source current again, as a **new** revision whose parent is the
     * active one — history is only ever appended to, so the versions in between stay reachable.
     */
    @Synchronized
    fun restore(base: String, sourceHash: String, id: String, description: String = ""): SourceRevision<T> {
        val old = revision(id)
        return edit(base, sourceHash, old.source, description.ifBlank { "restore ${old.id}: ${old.description}" })
    }
}

/**
 * Where a study of one box keeps its revisions: `studies/<box id>` in the box's document.
 *
 * A study belongs to the box it studies, so two studies in one document never share a history, and
 * a study survives the box being renamed.
 */
object BoxStudies {
    /** The open document [box] belongs to, or `null` for a box in no open document. */
    fun documentOf(box: afield.Box): afield.SessionInformation? = afield.documents.values.firstOrNull { it.boxes[box.id] === box }

    /**
     * Put [html] in [box]'s output [channel] and open it, from anywhere. `box.display` goes through
     * the output proxies an execution sets up, so from library code acting on a box that has not run
     * in this session it sends nothing; this sends the output message itself.
     */
    fun show(box: afield.Box, channel: String, html: String) {
        @Suppress("UNCHECKED_CAST")
        val shown = (box.getPropertyRaw(SHOWN, null) as? MutableMap<String, String>)
            ?: java.util.concurrent.ConcurrentHashMap<String, String>().also { box.properties[SHOWN] = it }
        shown[channel] = html
        afield.send(afield.Message("set-output").payload(afield.Set_output(box.id, "outputarea", channel, html)))
        afield.send(afield.Message("output-control").payload(afield.OutputControl(uid = box.id, action = "open", channel = channel)))
    }

    /** Where [show] remembers the last card per channel, so a page that arrives later can be given it. */
    private const val SHOWN = "_boxStudiesShown"

    /** How long a page that has just attached is given to receive its boxes before their cards follow. */
    private const val REPLAY_DELAY_MS = 2500L

    /**
     * Send [session] — a page that has just attached to [document], already open — the last card [show]
     * put in each channel of each of its boxes. A card is drawn by library code rather than by a run the
     * page could see, so without this a page that arrives after it (a reload, a second browser, the
     * automation browser) shows the box with no card at all. The cards follow once the page has had
     * time to receive the boxes they belong to.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun replayTo(session: io.ktor.websocket.WebSocketSession, document: afield.SessionInformation) {
        kotlinx.coroutines.GlobalScope.launch {
            kotlinx.coroutines.delay(REPLAY_DELAY_MS)
            document.boxes.values.toList().forEach { box ->
                @Suppress("UNCHECKED_CAST")
                (box.getPropertyRaw(SHOWN, null) as? Map<String, String>)?.forEach { (channel, html) ->
                    afield._send(session, afield.Message("set-output").payload(afield.Set_output(box.id, "outputarea", channel, html)))
                    afield._send(session, afield.Message("output-control").payload(afield.OutputControl(uid = box.id, action = "open", channel = channel)))
                }
            }
        }
    }

    /**
     * The running renderer a live program draws in: the nearest box, starting at [box] and walking up
     * its parents, that holds an open `renderer` — the same population `prop<Renderer>(renderer)` searches.
     */
    fun rendererAbove(box: afield.Box): afield.four.Renderer? {
        val seen = HashSet<afield.Box>()
        val queue = ArrayDeque(listOf(box))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            (next.getPropertyRaw("renderer", null) as? afield.four.Renderer)?.takeIf { !it.isClosed }?.let { return it }
            queue.addAll(next.parents)
        }
        return null
    }

    /**
     * The editor's cursor line in [box]'s [property], while a page on its document has the box selected
     * with that property in the editor; else null. The page reports its selection
     * ([afield.server.documentSelection]) and the editor's view state (`<property>_editorState`), each
     * within a tenth of a second.
     */
    fun editorLine(box: afield.Box, property: String): Int? {
        val document = documentOf(box) ?: return null
        val selection = afield.server.documentSelection(document) ?: return null
        if (selection.selectedUids.lastOrNull() != box.id || selection.property != property) return null
        val state = box.getPropertyRaw("${property}_editorState", null) as? String ?: return null
        return lineOfViewState(state)
    }

    /**
     * What an Inspect says about where the person is looking: the line their editor cursor is on in
     * [source], and its text — or null when the box is not open in their editor. "This one" in a request
     * usually means it; in a RectSL scene, clicking a rectangle in the window puts the cursor on its line.
     */
    fun personAt(line: Int?, source: String): Map<String, Any?>? = line?.let {
        linkedMapOf("line" to it, "text" to source.lines().getOrNull(it - 1)?.trim()?.take(160),
            "meaning" to "the line the person's editor cursor is on; 'this', 'here' or 'that one' in their request usually means it")
    }

    /** The cursor's line in a Monaco view state (`editor.saveViewState()` as JSON), or null. */
    fun lineOfViewState(viewState: String?): Int? = runCatching {
        mapper.readTree(viewState ?: return null)["cursorState"]?.get(0)?.get("position")?.get("lineNumber")?.asInt()
    }.getOrNull()?.takeIf { it > 0 }

    private val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()

    /** `studies/<box id>` in [box]'s document. */
    fun directory(box: afield.Box): File {
        val session = documentOf(box) ?: throw IllegalStateException("'${box.name}' is not in an open document")
        return File(session.filename, "studies/${box.id}")
    }

    /**
     * A replace-source function for a study of [box]'s [property]: on the main thread, only if the
     * property still holds what the edit was based on, and marking the sidecar for saving.
     */
    fun replacer(box: afield.Box, property: String): (String, String) -> Boolean = { expected, replacement ->
        val session = documentOf(box)
        val publish = {
            if (session == null || session.readOnly || (box.getPropertyRaw(property, "") ?: "") != expected) false
            else {
                box.setPropertyRaw(property, replacement, doResync = true)
                session.markSidecarPending(box.id, property)
                true
            }
        }
        if (afield.Runloop.isMainThread) publish()
        else kotlinx.coroutines.runBlocking { kotlinx.coroutines.withContext(afield.Runloop.runLoop.main) { publish() } }
    }
}
