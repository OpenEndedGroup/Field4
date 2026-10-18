package afield.ai.agent

import afield.app.protocol.AiAssist
import afield.document.graph.Box
import afield.app.server.SessionInformation
import afield.app.protocol.payload
import afield.ai.conversation.AiBoxConversation
import afield.ai.conversation.AwaitedBuild
import afield.ai.conversation.TurnSuspension
import afield.app.diagnostics.logging.lprintln
import afield.study.build.StudyBuildNoticeKind
import afield.study.build.StudyBuildStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Waiting for study build jobs without the model: a box chat that ended its turn on [AwaitStudyBuildsRequest] is resumed by [StudyBuildAwaits] when its jobs are done or its deadline passes.
 *
 * [AiChatSuspension] and [AiChatAwaitedJob] are the wait as `ai-chat-history` reports it to the panel.
 */

/** A job a chat waits for, as `ai-chat-history` reports it. [status] is the last one seen, or "" before the first poll. */
@Serializable
data class AiChatAwaitedJob(val boxId: String, val name: String, val jobId: String, val stage: String = "",
    val label: String = "", val status: String = "")

/** What a box chat is waiting for (`AiBoxChatReply.suspendedOn`): since when, until when at the latest, and the jobs. */
@Serializable
data class AiChatSuspension(val since: String, val resumesBy: String, val jobs: List<AiChatAwaitedJob>)

/**
 * Box chats whose turn ended with `AwaitStudyBuildsRequest`, waiting for their build jobs without the model.
 *
 * Each wait is saved with the chat, as the box's [PROPERTY] (JSON, persisted in `metadata.edn`), and polled
 * here every [pollMs] through the build runners: no model request is made while it waits. When every job
 * is terminal, or the deadline passes, this writes a continuation (each job's status, currency, facts and
 * result notes) and hands it to the box chat as a request of Field's own (`AiAssist.continuationId`). The
 * chat starts a turn with it, or, when a turn is already running in that box, gives it to that turn the way
 * a message typed mid-turn is given ([afield.ai.conversation.AiTurnInbox]). A request the chat refuses (AI setup
 * failed, say) is offered again later, backing off to ten minutes.
 *
 * A restarted Field arms every saved wait again when the document is next opened ([rearm]). A runner that
 * has not come back after [runnerGraceMs] is read from its directory instead: a job it recorded as queued
 * or running there cannot finish, since the runner that ran it has stopped.
 *
 * The suspended turn's transcript ends with a waiting row ([SavedSuspension.row]), saved in its closed form;
 * while the wait lasts, what is shown to a panel has it open ([shown]), so it shimmers like live activity.
 * Nothing in the saved transcript is rewritten when the wait ends.
 */
class StudyBuildAwaits(
    private val graphFor: (SessionInformation) -> FieldGraphTools = { FieldGraphTools.forTurn(it) },
    private val resume: (SessionInformation, Box, AiAssist) -> Boolean = ::resumeThroughChat,
    val pollMs: Long = 5_000,
    val runnerGraceMs: Long = 120_000,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Whether a document is still open in this Field; a closed one's waits are armed again when it reopens. */
    private val isOpen: (SessionInformation) -> Boolean = { info -> afield.app.server.documents.values.any { it === info } },
) : AutoCloseable {

    /** What [PROPERTY] holds. [row] is the transcript block the suspended turn ended with. */
    @Serializable
    data class SavedSuspension(val id: String, val property: String = "code", val maxToolRounds: Int = 24,
        val since: Long, val deadline: Long, val jobs: List<SavedJob>, val row: String)

    /** One awaited job as saved: [directory] is the runner's, read when the runner is not running. */
    @Serializable
    data class SavedJob(val boxId: String, val name: String, val jobId: String, val stage: String = "",
        val label: String = "", val directory: String = "")

    private class Armed(val info: SessionInformation, val box: Box, @Volatile var saved: SavedSuspension) {
        val status = ConcurrentHashMap<String, String>()
        val runnerMissingSince = ConcurrentHashMap<String, Long>()
        /** The continuation once written, until the chat takes it; and when to offer it again. */
        var pending: AiAssist? = null
        var nextOffer = 0L
        var offers = 0
    }

    private val armed = ConcurrentHashMap<String, Armed>()
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ai4-await-builds").apply { isDaemon = true } }
    @Volatile private var ticking: ScheduledFuture<*>? = null

    private fun key(info: SessionInformation, box: Box) = "${info.filename}\u0000${box.id}"

    /**
     * Save [suspension] as [box]'s wait and start watching it. A wait already armed for the box keeps its jobs:
     * the new one waits for both sets, until the later deadline.
     */
    fun suspend(info: SessionInformation, box: Box, suspension: TurnSuspension, property: String, maxToolRounds: Int): SavedSuspension {
        val now = clock()
        val previous = armed[key(info, box)]?.saved ?: read(box)
        val jobs = ((previous?.jobs.orEmpty()) + suspension.jobs.map {
            SavedJob(it.boxId, it.name, it.jobId, it.stage, it.label, it.directory)
        }).distinctBy { Triple(it.boxId, it.name, it.jobId) }
        val deadline = maxOf(previous?.deadline ?: 0L, suspension.deadlineMs)
        val saved = SavedSuspension(UUID.randomUUID().toString(), property, maxToolRounds, previous?.since ?: now, deadline, jobs, waitingRow(jobs, deadline))
        write(box, saved)
        arm(info, box, saved)
        return saved
    }

    /** Arm every wait saved in [info]'s boxes that is not armed yet; called when a document has loaded. */
    fun rearm(info: SessionInformation) {
        for (box in (info.boxes.values + info.root).distinctBy { it.id }) {
            val saved = read(box) ?: continue
            if (armed[key(info, box)]?.saved?.id == saved.id) continue
            lprintln("info,ai4", "re-armed the wait of ${box.name.ifBlank { box.id }} for ${saved.jobs.size} build job(s)")
            arm(info, box, saved)
        }
    }

    /** Stop waiting (`/stop-waiting`, a cleared or rewound chat). The builds keep running. Returns what was waited for. */
    fun cancel(info: SessionInformation, box: Box): SavedSuspension? {
        val saved = armed.remove(key(info, box))?.saved ?: read(box)
        if (saved != null) write(box, null)
        return saved
    }

    /** What [box]'s chat waits for, for `ai-chat-history`; null when it is not waiting. */
    fun suspensionOf(info: SessionInformation, box: Box): AiChatSuspension? {
        val a = armed[key(info, box)]
        val saved = a?.saved ?: read(box) ?: return null
        return AiChatSuspension(Instant.ofEpochMilli(saved.since).toString(), Instant.ofEpochMilli(saved.deadline).toString(),
            saved.jobs.map { AiChatAwaitedJob(it.boxId, it.name, it.jobId, it.stage, it.label, a?.status?.get(it.jobId).orEmpty()) })
    }

    /** [markdown] as a panel should show it: while [box] waits, its waiting row open, like live activity. */
    fun shown(info: SessionInformation, box: Box, markdown: String): String {
        val row = (armed[key(info, box)]?.saved ?: return markdown).row
        val at = markdown.lastIndexOf(row)
        if (at < 0) return markdown
        return markdown.substring(0, at) + row.replaceFirst("<<progress>>", "<<progress-open>>") + markdown.substring(at + row.length)
    }

    private fun arm(info: SessionInformation, box: Box, saved: SavedSuspension) {
        armed[key(info, box)] = Armed(info, box, saved)
        synchronized(this) {
            if (ticking == null) ticking = executor.scheduleWithFixedDelay({
                runCatching { tick() }.onFailure { lprintln("warning,ai4", "build wait poll failed: ${it.message ?: it::class.java.simpleName}") }
            }, pollMs, pollMs, TimeUnit.MILLISECONDS)
        }
    }

    /** One poll of every armed wait. The executor calls it every [pollMs]; tests call it directly. */
    internal fun tick() {
        for ((k, a) in armed) {
            // A deleted box, or a wait cleared or replaced through the saved property, is no longer this one; a
            // closed document's wait stays saved with it, and is armed again when the document opens.
            if (!isOpen(a.info)) { armed.remove(k, a); continue }
            if (a.info.boxes[a.box.id] !== a.box && a.box !== a.info.root) { armed.remove(k, a); continue }
            if (read(a.box)?.id != a.saved.id) { armed.remove(k, a); continue }
            val now = clock()
            val pending = a.pending
            if (pending != null) { if (now >= a.nextOffer) offer(k, a, pending, now); continue }
            val graph = graphFor(a.info)
            var terminal = true
            for (job in a.saved.jobs) {
                val state = graph.studyBuilds.awaitedState(job.awaited())
                if (state.runnerLive) a.runnerMissingSince.remove(job.jobId) else a.runnerMissingSince.putIfAbsent(job.jobId, now)
                val status = state.record?.status
                a.status[job.jobId] = status?.name ?: "UNKNOWN"
                val settled = when {
                    status != null && status !in ACTIVE -> true
                    state.runnerLive -> false
                    // No runner: wait out the grace (its box re-running, a document reopening); then report what it left.
                    else -> now - (a.runnerMissingSince[job.jobId] ?: now) >= runnerGraceMs
                }
                if (!settled) terminal = false
            }
            if (!terminal && now < a.saved.deadline) continue
            val outcomes = a.saved.jobs.map { graph.studyBuilds.awaitedOutcome(it.awaited()) }
            val text = continuationText(outcomes, now - a.saved.since, timedOut = !terminal)
            val continuation = AiAssist(uid = a.box.id, prompt = (a.box.getPropertyRaw(a.saved.property) as? String).orEmpty(),
                extraPrompt = text, property = a.saved.property, kind = "general", maxToolRounds = a.saved.maxToolRounds,
                queueIfBusy = true, continuationId = UUID.randomUUID().toString())
            a.pending = continuation
            offer(k, a, continuation, now)
        }
    }

    private fun offer(k: String, a: Armed, continuation: AiAssist, now: Long) {
        val taken = runCatching { resume(a.info, a.box, continuation) }
            .onFailure { lprintln("warning,ai4", "could not resume ${a.box.name.ifBlank { a.box.id }}'s chat: ${it.message}") }
            .getOrDefault(false)
        if (taken) {
            armed.remove(k, a)
            if (read(a.box)?.id == a.saved.id) write(a.box, null)
            return
        }
        a.offers++
        a.nextOffer = now + minOf(30_000L shl minOf(a.offers - 1, 5), 600_000L)
        lprintln("info,ai4", "${a.box.name.ifBlank { a.box.id }}'s chat did not take its build outcome; offering it again in ${(a.nextOffer - now) / 1000} s")
    }

    override fun close() {
        ticking?.cancel(false)
        executor.shutdownNow()
        armed.clear()
    }

    companion object {
        /** The box property holding a chat's wait: [SavedSuspension] as JSON, saved in `metadata.edn`. */
        const val PROPERTY = "ai4_chat_suspension"

        /** The one Field uses; its polling thread starts with the first wait. */
        val live: StudyBuildAwaits by lazy { StudyBuildAwaits() }

        private val ACTIVE = setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING)
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Continuations the chat has taken (started a turn with, or queued in one), until [resumeThroughChat] reads them. */
        private val taken = ConcurrentHashMap.newKeySet<String>()

        /** Called by the box chat when it starts or queues a request carrying [continuationId]. */
        fun accepted(continuationId: String) { if (continuationId.isNotBlank()) taken += continuationId }

        fun read(box: Box): SavedSuspension? = (box.getPropertyRaw(PROPERTY) as? String)?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(SavedSuspension.serializer(), it) }.getOrNull() }

        private fun write(box: Box, saved: SavedSuspension?) {
            if (saved == null) box.removePropertyRaw(PROPERTY, doResync = false)
            else box.setPropertyRaw(PROPERTY, json.encodeToString(SavedSuspension.serializer(), saved), doResync = false)
            // Saved with the chat: the next autosave writes metadata.edn, wait included.
            AiBoxConversation.markPending(box)
        }

        private fun SavedJob.awaited() = AwaitedBuild(boxId, name, jobId, stage, label, directory)

        private fun jobLine(job: SavedJob) =
            "- `${job.name}/${job.stage}`${job.label.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}, attempt `${job.jobId}`"

        private fun plural(n: Int) = if (n == 1) "1 build" else "$n builds"

        /** The closed transcript block a suspended turn ends with; [shown] opens it while the wait lasts. */
        internal fun waitingRow(jobs: List<SavedJob>, deadline: Long): String = buildString {
            append("<<progress>>\n")
            append(afield.ai.conversation.AiChatBlock.buildWaitTitle(jobs.size)).append('\n')
            jobs.forEach { append(jobLine(it)).append('\n') }
            val at = Instant.ofEpochMilli(deadline).atZone(ZoneId.systemDefault())
            val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate() == at.toLocalDate()
            val time = at.format(DateTimeFormatter.ofPattern(if (today) "HH:mm" else "EEE d MMM HH:mm"))
            append("This conversation resumes when they finish, or at $time at the latest. ")
            append("Type /stop-waiting to stop waiting; the builds keep running.\n")
        }

        private fun duration(ms: Long): String {
            val minutes = ms / 60_000
            return when {
                ms < 60_000 -> "${(ms / 1000).coerceAtLeast(1)} s"
                minutes < 60 -> "$minutes min"
                else -> "${minutes / 60} h ${minutes % 60} min"
            }
        }

        private fun facts(values: Map<String, Any?>): String = values.entries.take(12).joinToString(", ") { (k, v) ->
            val shown = when (v) {
                null -> "null"
                is Number, is Boolean -> v.toString()
                is String -> if (v.length <= 80) v else v.take(77) + "..."
                is Collection<*> -> "[${v.size} items]"
                is Map<*, *> -> "{${v.size} entries}"
                else -> v.toString().take(80)
            }
            "$k=$shown"
        }.let { if (values.size > 12) "$it, and ${values.size - 12} more" else it }

        /**
         * The message a waiting chat resumes with: each job's verdict, notes and facts, in the words the person
         * sees too (it is the resumed turn's visible request). [timedOut]: the deadline passed first.
         */
        internal fun continuationText(outcomes: List<AwaitedJobOutcome>, waitedMs: Long, timedOut: Boolean): String = buildString {
            val running = outcomes.count { it.record?.status in ACTIVE && it.runnerLive }
            append(afield.ai.conversation.AiChatBlock.FIELD_MESSAGE_PREFIX)
            if (timedOut) append("the time you allowed for these builds has passed (${duration(waitedMs)}), and " +
                "${plural(running)} ${if (running == 1) "is" else "are"} still running; they keep running. No model call was made while you waited.\n\n")
            else append("the ${if (outcomes.size == 1) "build" else "builds"} you awaited ${if (outcomes.size == 1) "has" else "have"} ended, " +
                "after ${duration(waitedMs)}. No model call was made while ${if (outcomes.size == 1) "it" else "they"} ran.\n\n")
            for (o in outcomes) {
                val job = o.job
                append("- `${job.name}/${job.stage}`")
                job.label.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
                append(", attempt `${job.jobId}`: ")
                val r = o.record
                when {
                    r == null -> append("**unknown**: the runner has no record of this attempt.")
                    r.status in ACTIVE && !o.runnerLive -> append("**interrupted**: its runner is not running (its box stopped, or Field " +
                        "restarted), so this attempt cannot finish. Run the runner's box again, then start the stage again.")
                    r.status in ACTIVE -> append("**still ${r.status.name.lowercase()}** (${(r.progress * 100).toInt()}%, ${r.message}).")
                    else -> {
                        append("**${r.status.name.lowercase()}**")
                        // Currency says something only of a success: whether its result is the one in force.
                        if (r.status == StudyBuildStatus.SUCCEEDED) when (o.current) {
                            true -> append("; current result")
                            false -> append("; not the current result (the inputs changed, or a newer attempt replaced it)")
                            null -> {}
                        }
                        append(".")
                        r.problem?.let { p ->
                            append(" ${p.message}")
                            if (p.suggestions.isNotEmpty()) append(" Suggestions: ${p.suggestions.joinToString("; ")}.")
                        } ?: r.message.takeIf { it.isNotBlank() && r.status != StudyBuildStatus.SUCCEEDED }?.let { append(" $it") }
                    }
                }
                if (r != null && r.facts.isNotEmpty()) append(" Facts: ${facts(r.facts)}.")
                o.notes.forEach { n ->
                    append(" ${if (n.kind == StudyBuildNoticeKind.INFO) "Note" else n.kind.name.lowercase().replaceFirstChar { it.uppercase() }}: ${n.message}")
                }
                append('\n')
            }
            append("\nCarry on from where you stopped. InspectStudyBuildsRequest with a jobId gives an attempt's full record.")
        }

        /** The default way to hand a continuation to a chat: the `ai-assist` handler, as the panel sends it. */
        fun resumeThroughChat(info: SessionInformation, box: Box, continuation: AiAssist): Boolean {
            val previousInfo = afield.app.server.thisSessionInfo.get()
            val previousSession = afield.app.server.thisSession.get()
            afield.app.server.thisSessionInfo.set(info)
            // The turn's notices go to a page showing the document; with none open, to every page.
            info.sessions.firstOrNull()?.let { afield.app.server.thisSession.set(it) } ?: afield.app.server.thisSession.remove()
            try {
                afield.app.protocol.handle(afield.app.protocol.Message("ai-assist").payload(continuation)) { }
            } finally {
                if (previousInfo == null) afield.app.server.thisSessionInfo.remove() else afield.app.server.thisSessionInfo.set(previousInfo)
                if (previousSession == null) afield.app.server.thisSession.remove() else afield.app.server.thisSession.set(previousSession)
            }
            return taken.remove(continuation.continuationId)
        }
    }
}
