@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.Box
import afield.ReadOnlyDocuments
import afield.Runloop
import afield.ai.core.ToolEffect
import afield.ai.core.aiTool
import afield.ai.core.currentToolArgs
import afield.registerButtonCallback
import afield.registerStopHook
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * # Qualified sources: changing a rule without breaking what already works
 *
 * A rule (a placement recipe, a fallback, a solver strategy) is a source that other builds consume.
 * A [QualifiedSource] keeps three things beside it and lets the rule change only when all three agree:
 *
 * - the **rule**, a [SourceStudy] over a box property;
 * - the **fixtures**, a [SourceStudy] of retained real cases, each an exact input and the outcome the rule
 *   gave it ([QualifiedFixture]);
 * - a **replay** stage, a [StudyBuilds] runner that runs a candidate rule on every fixture and on a
 *   [QualifiedControl], an input no correct rule can pass, which must fail.
 *
 * A change starts as a *candidate*: a frozen record of the base it was made against, its source, the
 * fixtures, the dependencies and the checks ([QualifiedCandidate]), named by the hash of all of them. The
 * replay compares each fixture's new outcome with the recorded one: the same, changed, or held (the rule
 * or its checks refused it). A candidate is admitted, and `apply` publishes it through
 * [SourceStudy.edit], only when its replay is current, covers every fixture with the same outcome, the
 * control failed as it must, and nothing it depends on has changed.
 *
 * The checks that judge a candidate are out of its reach ([FrozenRegion]): files it may not edit, text
 * after a marker that must stay byte for byte, or a separate frozen copy. Only a person changes them, and
 * only a person changes a fixture's recorded outcome: `proposeRebaseline` draws each changed outcome with
 * an **Approve** button, and the press records who approved it and when. No tool approves.
 *
 * [Box.qualifiedSource] makes one in a box and registers the agent tools `<name>.inspect`, `.candidate`,
 * `.test`, `.apply`, `.retainFixture` and `.proposeRebaseline`.
 */

/** A retained case: the exact [input] text and the [outcome] the rule gave it, which must survive a JSON round trip. */
data class QualifiedFixture(val input: String, val outcome: Any?)

/**
 * An input no correct rule can pass, replayed with every candidate. Its replay must fail (the rule or its
 * checks throw), with [messageContains] in the message when given. A control that passes means the checks
 * are not running, so no candidate is admitted.
 */
data class QualifiedControl(val name: String, val input: String, val messageContains: String? = null) {
    init { require(name.matches(FIXTURE_NAME)) { "A control needs a short lower-case name" } }
}

/**
 * Where a qualified source keeps its checks out of a candidate's reach. A candidate breaking any of its
 * regions is refused before it is saved. What the regions freeze is recorded in the candidate as
 * [QualifiedCandidate.checks] and handed to the replay as [QualifiedReplay.checks].
 */
sealed class FrozenRegion {
    /** The rule is a JSON object of file name to text, and a candidate may change or add only the [editable] files. */
    class Files(val editable: Set<String>) : FrozenRegion() {
        init { require(editable.isNotEmpty()) { "Name at least one file a candidate may edit" } }
    }

    /** Everything after [marker], which must occur exactly once in the rule, stays byte for byte as it is. */
    class After(val marker: String) : FrozenRegion() {
        init { require(marker.isNotBlank()) { "The marker cannot be blank" } }
    }

    /**
     * A separate copy of the checks, named file to text, frozen when the source is first made. [current] says
     * what the checks should be now (a kit's files, say); when it differs from the copy, the card and
     * `inspect` say so, and the copy changes only by [QualifiedSource.upgradeChecks].
     */
    class Baseline(val current: () -> Map<String, String>) : FrozenRegion()

    companion object {
        /** [Files]: only [editable] may change. */
        fun files(vararg editable: String): FrozenRegion = Files(editable.toSet())
        /** [After]: the text after [marker] stays as it is. */
        fun after(marker: String): FrozenRegion = After(marker)
        /** [Baseline]: a frozen copy of the checks, upgraded explicitly from [current]. */
        fun baseline(current: () -> Map<String, String>): FrozenRegion = Baseline(current)
    }
}

/**
 * What a replay works with: the candidate's [source], the frozen [checks] it is judged by, and the build
 * [context] (files, progress, `runProcess`). The replay function returns a runner for one input.
 */
class QualifiedReplay internal constructor(val source: String, val checks: Map<String, String>, val context: StudyBuildContext)

/** A finished build attempt a fixture is retained from: its [record], and its files by path through [file]. */
class QualifiedRetainSource internal constructor(val runner: String, val record: StudyBuildRecord, private val read: (String) -> File) {
    /** A file of the attempt; a recorded result file is checked against its hash. */
    fun file(path: String): File = read(path)
}

/** How a fixture came out in a replay. */
enum class QualifiedVerdict { SAME, CHANGED, HELD }

/** One fixture in a replay: its [verdict], the new [outcome], the [recorded] one, and the refusal when [verdict] is HELD. */
data class QualifiedCaseResult(val fixture: String, val verdict: QualifiedVerdict, val outcome: Any?, val recorded: Any?, val problem: String?)

/** The control in a replay: whether it [failed], the message it failed with, and whether that counts as [passed]. */
data class QualifiedControlResult(val name: String, val failed: Boolean, val problem: String?, val passed: Boolean)

/** A replay's verdict, kept as `replay.json` in its attempt. */
data class QualifiedReplayReport(val candidate: String, val sourceHash: String, val fixturesRevision: String,
    val cases: List<QualifiedCaseResult>, val control: QualifiedControlResult, val passed: Boolean)

/** A fixture as the corpus holds it, with where it was retained from and every approved change to its outcome. */
data class QualifiedCase(val input: String, val outcome: Any?, val retained: Map<String, Any?> = emptyMap(),
    val approvals: List<QualifiedApproval> = emptyList())

/** A person's approval of one fixture's new outcome. */
data class QualifiedApproval(val proposal: String, val candidate: String, val replay: String, val beforeHash: String,
    val afterHash: String, val approvedBy: String, val approvedVia: String, val approvedAt: String)

/**
 * A frozen candidate. [id] is the hash of every other field, so a candidate also verifies itself: the base
 * it was made against, its source, the fixture corpus, the dependencies and the checks it is judged by.
 */
data class QualifiedCandidate(val namespace: String, val baseRevision: String, val baseSourceHash: String,
    val source: String, val sourceHash: String, val description: String, val parent: String?,
    val fixturesRevision: String, val fixtures: Map<String, QualifiedCase>,
    val dependencies: Map<String, String>, val checks: Map<String, String>, val id: String = "")

/** One changed value in a fixture's outcome, at a JSON [path]. */
data class QualifiedChange(val path: String, val before: Any?, val after: Any?)

/** One fixture in a rebaseline proposal: the recorded and proposed outcomes, their hashes and the changes between them. */
data class QualifiedProposalEntry(val fixture: String, val beforeHash: String, val afterHash: String,
    val before: Any?, val after: Any?, val changes: List<QualifiedChange>, val truncated: Boolean)

/** Proposed new outcomes from a failed replay, waiting for a person. [id] is the hash of the rest. */
data class QualifiedProposal(val namespace: String, val candidate: String, val replay: String, val fixturesRevision: String,
    val entries: List<QualifiedProposalEntry>, val id: String = "")

/**
 * A rule with its fixtures, checks and replay. Made by [Box.qualifiedSource]; construct directly only to
 * supply the storage yourself (tests do). All changes go through the methods here; [rule] and [fixtures]
 * refuse an edit made any other way.
 *
 * @param name the tool namespace and replay runner name
 * @param readRule, replaceRule the live rule text and its compare-and-swap (see [SourceStudy])
 * @param readFixtures, replaceFixtures the same for the fixture corpus, a JSON object of [QualifiedCase]s
 * @param seedFixtures the corpus when it has none yet
 * @param replayVersion changes whenever the replay function changes, like a stage's `recipeVersion`
 * @param dependencies the identities of anything else the replay relies on (`fileIdentity`, `classIdentity`)
 * @param retain turns a finished attempt of a build in the document into a fixture; without it `retainFixture` refuses
 * @param runners finds a build runner of the document by name, for [retainFixture]
 * @param builds makes the replay runner from its stage and input reader
 * @param show puts a card's HTML in an output channel; null shows nothing
 * @param onMain runs a person's button press where the rule's other changes run
 * @param replay prepares a candidate and returns a runner for one input: return the outcome, or throw to hold it
 */
class QualifiedSource(
    val name: String,
    val label: String,
    val directory: File,
    readRule: () -> String,
    replaceRule: (String, String) -> Boolean,
    readFixtures: () -> String,
    replaceFixtures: (String, String) -> Boolean,
    val control: QualifiedControl,
    val frozen: List<FrozenRegion>,
    val replayVersion: String,
    private val seedFixtures: Map<String, QualifiedFixture> = emptyMap(),
    private val dependencies: () -> Map<String, String> = { emptyMap() },
    private val retain: ((QualifiedRetainSource) -> QualifiedFixture)? = null,
    private val runners: (String) -> StudyBuilds? = { null },
    ruleExtension: String = "kt",
    maxSourceLength: Int = 200000,
    builds: (List<StudyBuildStage>, () -> Map<String, String>) -> StudyBuilds,
    private val show: ((channel: String, html: String) -> Unit)? = null,
    private val onMain: (() -> Unit) -> Unit = { it() },
    private val replay: (QualifiedReplay) -> (String) -> Any?,
) : AutoCloseable {
    private val mapper = jacksonObjectMapper()
    private val canonical = jacksonObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
    private val pretty = canonical.copy().enable(SerializationFeature.INDENT_OUTPUT)
    private val ruleAdmission = ThreadLocal<Map<String, Any?>?>()
    private val fixtureAdmission = ThreadLocal<String?>()
    private val checksAdmission = ThreadLocal<String?>()
    private val files = frozen.filterIsInstance<FrozenRegion.Files>().singleOrNull()
    private val markers = frozen.filterIsInstance<FrozenRegion.After>().map { it.marker }
    private val baseline = frozen.filterIsInstance<FrozenRegion.Baseline>().singleOrNull()
    private val candidates = File(directory, "candidates")
    private val proposals = File(directory, "proposals")
    private val prefix = "qualified-${UUID.randomUUID()}"
    private val buttons = mutableMapOf<String, String>()
    private var lastCard = ""
    private var lastApproval = ""
    @Volatile private var cardError: String? = null
    @Volatile private var closed = false
    private var refresher: java.util.concurrent.ScheduledExecutorService? = null

    /** The rule's checked history. Edits not made by [apply] or [upgradeChecks] are refused. */
    val rule: SourceStudy<String>

    /** The fixture corpus's checked history. Edits not made by [retainFixture], an approval or the seed are refused. */
    val fixtures: SourceStudy<String>

    /** The frozen copy of the checks, when [frozen] has a [FrozenRegion.Baseline]. */
    val checksCopy: SourceStudy<String>?

    /** The replay runner, with its one stage `replay`. */
    val replays: StudyBuilds

    init {
        require(name.matches(STUDY_BUILD_NAME)) { "A qualified source's name must be a simple word" }
        require(frozen.isNotEmpty()) { "Say how the checks are kept out of a candidate's reach: give at least one FrozenRegion" }
        require(frozen.count { it is FrozenRegion.Files } <= 1 && frozen.count { it is FrozenRegion.Baseline } <= 1) {
            "Give at most one Files region and one Baseline region"
        }
        require(control.name !in seedFixtures) { "The control '${control.name}' cannot also be a fixture" }
        seedFixtures.keys.forEach { require(it.matches(FIXTURE_NAME)) { "Fixture names are short lower-case words: '$it'" } }
        directory.mkdirs()
        rule = SourceStudy(File(directory, "rule"), ruleExtension, readRule, replaceRule, maxSourceLength = maxSourceLength,
            admit = { source, _ ->
                val facts = ruleAdmission.get() ?: throw StudyRejection("Change this rule through $name.candidate, $name.test and $name.apply.")
                check(facts["sourceHash"] == SourceStudy.hash(source)) { "The admitted source is not the one being published" }
                StudyAdmission(source, facts)
            }, load = { StudyAdmission(it) })
        fixtures = SourceStudy(File(directory, "fixtures"), "json", readFixtures, replaceFixtures, maxSourceLength = 4_000_000,
            admit = { source, _ ->
                val reason = fixtureAdmission.get() ?: throw StudyRejection(
                    "Fixtures change only by $name.retainFixture, and a recorded outcome only by a person's approval.")
                val count = corpusOf(source).size
                StudyAdmission(source, mapOf("reason" to reason, "fixtures" to count))
            }, load = { StudyAdmission(it) })
        checksCopy = baseline?.let { _ ->
            val file = File(directory, "checks.json")
            SourceStudy(File(directory, "checks"), "json", { if (file.isFile) file.readText() else "" }, { expected, replacement ->
                synchronized(file) { if ((if (file.isFile) file.readText() else "") != expected) false else { file.writeText(replacement); true } }
            }, maxSourceLength = 4_000_000, admit = { source, _ ->
                val reason = checksAdmission.get() ?: throw StudyRejection("The frozen checks change only by upgradeChecks.")
                StudyAdmission(source, mapOf("reason" to reason, "files" to checksOf(source).keys.sorted()))
            }, load = { StudyAdmission(it) })
        }
        startHistories()
        replays = builds(listOf(StudyBuildStage("replay", "Replay $label", replayVersion, setOf("fixtures", "dependencies", "checks"),
            inputHelp = StudyBuildInputHelp("Idle", "No change to $label is being tested."), build = ::runReplay)), ::replayInputs)
        candidates.mkdirs(); proposals.mkdirs()
    }

    private fun startHistories() {
        if (rule.resume() == SourceStudy.EMPTY) {
            val live = rule.liveSource()
            withRule(live, mapOf("first" to true)) { rule.edit(SourceStudy.EMPTY, SourceStudy.hash(live), live, "$label as first written") }
        }
        if (fixtures.resume() == SourceStudy.EMPTY) {
            val live = fixtures.liveSource()
            val seed = live.ifBlank { corpusText(seedFixtures.mapValues { (_, f) -> QualifiedCase(f.input, normal(f.outcome), mapOf("seeded" to true)) }) }
            corpusOf(seed)
            withFixtures("seed") { fixtures.edit(SourceStudy.EMPTY, SourceStudy.hash(live), seed, "Fixtures as first written") }
        }
        checksCopy?.let { copy ->
            if (copy.resume() == SourceStudy.EMPTY) {
                val live = copy.liveSource()
                val seed = canonical.writeValueAsString(baseline!!.current().toSortedMap())
                withChecks("first launch") { copy.edit(SourceStudy.EMPTY, SourceStudy.hash(live), seed, "Checks as first frozen") }
            }
        }
    }

    // ---- reading ---------------------------------------------------------------------------------

    /** The rule as last applied: what builds that use the rule should read. */
    fun source(): String = rule.revision().source

    /** The fixture corpus as last checked. */
    fun corpus(): Map<String, QualifiedCase> = corpusOf(fixtures.revision().source)

    /** Everything the replay depends on beyond the candidate: [dependencies], the replay version and the control. */
    fun currentDependencies(): Map<String, String> = (dependencies() + mapOf(
        "qualified.replay" to replayVersion,
        "qualified.control" to SourceStudy.hash(canonical.writeValueAsString(control)))).toSortedMap()

    /** The checks a candidate is judged by now: the frozen parts of the applied rule, and the frozen copy. */
    fun currentChecks(): Map<String, String> = frozenParts(source()) + (checksCopy?.let { checksOf(it.revision().source) }.orEmpty())

    /**
     * Why the checks need a person, or null: the live rule's frozen parts differ from the applied ones (an edit
     * outside `apply`), or a [FrozenRegion.Baseline]'s copy differs from what its `current` says.
     */
    fun checksNotice(): String? {
        val live = rule.liveSource()
        val edited = runCatching { frozenParts(live) != frozenParts(source()) }.getOrDefault(true)
        val stale = checksCopy?.let { copy -> runCatching { checksOf(copy.revision().source) != baseline!!.current() }.getOrDefault(true) } ?: false
        return when {
            edited && stale -> "The rule's checks were edited outside $name.apply, and the frozen copy of the checks differs from its source."
            edited -> "The rule's checks were edited outside $name.apply, so no candidate can be made until a person accepts or restores them."
            stale -> "The frozen copy of the checks differs from its source (${staleFiles().joinToString()}); a person can upgrade it."
            else -> null
        }
    }

    private fun staleFiles(): List<String> {
        val copy = checksOf(checksCopy!!.revision().source)
        val now = baseline!!.current()
        return (copy.keys + now.keys).filter { copy[it] != now[it] }.distinct().sorted()
    }

    /** What an agent reads first: the rule, the fixtures, the checks, the latest replay and anything waiting for a person. */
    fun inspect(includeSource: Boolean = false): Map<String, Any?> {
        val live = rule.liveSource()
        val latest = replays.history(0, 1).firstOrNull()
        val stage = replays.stages().single()
        return linkedMapOf(
            "rule" to linkedMapOf("revision" to rule.currentId(), "liveSourceHash" to SourceStudy.hash(live),
                "editedOutsideApply" to (live != source()), "source" to live.takeIf { includeSource }),
            "frozen" to frozen.map(::describe),
            "checksNotice" to checksNotice(),
            "fixtures" to linkedMapOf("revision" to fixtures.currentId(), "sourceHash" to SourceStudy.hash(fixtures.liveSource()),
                "names" to corpus().keys.sorted(), "editedOutsideTools" to (fixtures.liveSource() != fixtures.revision().source)),
            "control" to control.name,
            "dependencies" to currentDependencies(),
            "replayRunner" to name,
            "latestReplay" to latest?.let { replaySummary(it, stage) },
            "waitingForPerson" to pendingProposals().map { p -> mapOf("proposal" to p.id, "fixtures" to pendingEntries(p).map { it.fixture }) },
            "next" to "Make a candidate with $name.candidate against rule.revision and rule.liveSourceHash, replay it with $name.test, and apply it with $name.apply once the replay passes.")
    }

    private fun describe(region: FrozenRegion): String = when (region) {
        is FrozenRegion.Files -> "Only these files may change: ${region.editable.sorted().joinToString()}."
        is FrozenRegion.After -> "Everything after '${region.marker}' stays as it is."
        is FrozenRegion.Baseline -> "The checks are a frozen copy (${checksOf(checksCopy!!.revision().source).keys.sorted().joinToString()}), outside the rule."
    }

    private fun replaySummary(job: StudyBuildRecord, stage: StudyStageState): Map<String, Any?> {
        val report = reportOf(job)
        return linkedMapOf("jobId" to job.id, "candidate" to job.request["candidate"], "status" to job.status.name.lowercase(),
            "current" to job.ranAgainst(stage.fingerprint), "message" to job.message,
            "held" to report?.cases?.filter { it.verdict == QualifiedVerdict.HELD }?.associate { it.fixture to it.problem },
            "changed" to report?.cases?.filter { it.verdict == QualifiedVerdict.CHANGED }?.map { it.fixture },
            "control" to report?.control)
    }

    // ---- candidates ------------------------------------------------------------------------------

    /**
     * Make a candidate against the live rule, read at [expectedRevision] with [expectedSourceHash]. Give either
     * [edits] (each `before` must occur exactly once; with [FrozenRegion.Files], `file` names the file), a whole
     * [replacement] source, or neither with [fromCandidate] to copy that candidate's source. [addFiles] adds new
     * editable files. Nothing runs and nothing is published; the candidate is saved and its id returned.
     */
    @Synchronized
    fun candidate(expectedRevision: String, expectedSourceHash: String, description: String, edits: List<QualifiedEdit> = emptyList(),
        replacement: String? = null, addFiles: Map<String, String> = emptyMap(), fromCandidate: String? = null): QualifiedCandidate {
        if (description.isBlank()) throw StudyRejection("Say what the candidate changes in its description")
        requireCleanFixtures()
        val live = rule.liveSource()
        if (rule.currentId() != expectedRevision || SourceStudy.hash(live) != expectedSourceHash)
            throw StudyRejection("Conflict: the rule changed since you read it. Inspect again; nothing was saved.")
        if (frozenParts(live) != frozenParts(source()))
            throw StudyRejection("The rule's checks were edited outside $name.apply, so no candidate can be made until a person accepts or restores them.")
        val parent = fromCandidate?.takeIf { it.isNotBlank() }?.let { loadCandidate(it) }?.also {
            if (it.baseRevision != expectedRevision || it.baseSourceHash != expectedSourceHash)
                throw StudyRejection("Candidate ${it.id.take(12)} was made against another version of the rule; start from the live rule.")
        }
        val start = parent?.source ?: live
        val source = when {
            replacement != null -> { if (edits.isNotEmpty() || addFiles.isNotEmpty()) throw StudyRejection("Give a replacement or edits, not both"); replacement }
            files != null -> editBundle(start, edits, addFiles)
            addFiles.isNotEmpty() -> throw StudyRejection("Only a rule made of files can add files")
            edits.isEmpty() -> start
            else -> SourceStudy.applyEdits(start, edits.map { if (it.file != null) throw StudyRejection("This rule is one text; leave 'file' empty"); StudySourceEdit(it.before, it.after) })
        }
        if (source == live) throw StudyRejection("The candidate is the same as the live rule; nothing to test.")
        checkFrozen(live, source)
        val record = QualifiedCandidate(name, expectedRevision, expectedSourceHash, source, SourceStudy.hash(source), description.trim(), parent?.id,
            fixtures.currentId(), corpus(), currentDependencies(), currentChecks())
        val saved = record.copy(id = candidateHash(record))
        val file = File(candidates, "${saved.id}.json")
        val text = pretty.writeValueAsString(saved)
        if (!file.isFile) file.writeText(text)
        return saved
    }

    private fun candidateHash(c: QualifiedCandidate) = SourceStudy.hash(canonical.writeValueAsString(c.copy(id = "")))

    /** A saved candidate, checked against its id. */
    fun loadCandidate(id: String): QualifiedCandidate {
        if (!id.matches(HASH)) throw StudyRejection("'$id' is not a candidate id")
        val file = File(candidates, "$id.json")
        if (!file.isFile) throw StudyRejection("No candidate $id")
        val c = mapper.readValue<QualifiedCandidate>(file)
        check(c.id == id && candidateHash(c) == id && SourceStudy.hash(c.source) == c.sourceHash) { "Candidate $id changed on disk; make a new one" }
        return c
    }

    private fun editBundle(start: String, edits: List<QualifiedEdit>, add: Map<String, String>): String {
        val bundle = bundleOf(start).toMutableMap()
        for ((file, text) in add) {
            if (file !in files!!.editable) throw StudyRejection("'$file' is not one of the files a candidate may add or edit (${files.editable.sorted().joinToString()})")
            if (file in bundle) throw StudyRejection("'$file' already exists; edit it instead")
            bundle[file] = text
        }
        for ((file, group) in edits.groupBy { it.file ?: throw StudyRejection("Each edit names its 'file' in this rule") }) {
            val text = bundle[file] ?: throw StudyRejection("No file '$file' in the rule")
            bundle[file] = SourceStudy.applyEdits(text, group.map { StudySourceEdit(it.before, it.after) })
        }
        return pretty.writeValueAsString(bundle.toSortedMap())
    }

    // The policy check: a candidate's frozen parts equal its base's.
    private fun checkFrozen(base: String, candidate: String) {
        files?.let { region ->
            val before = bundleOf(base); val after = bundleOf(candidate)
            val touched = (before.keys + after.keys).filter { it !in region.editable && before[it] != after[it] }.sorted()
            if (touched.isNotEmpty()) throw StudyRejection("The candidate changes ${touched.joinToString()}, which hold the checks; only ${region.editable.sorted().joinToString()} may change.")
        }
        for (marker in markers) {
            if (tail(candidate, marker) != tail(base, marker))
                throw StudyRejection("The candidate changes the text after '$marker', which holds the checks; keep everything after it as it is.")
        }
    }

    private fun tail(source: String, marker: String): String {
        val at = source.indexOf(marker)
        if (at < 0 || source.indexOf(marker, at + 1) >= 0) throw StudyRejection("The rule must contain '$marker' exactly once")
        return source.substring(at + marker.length)
    }

    private fun frozenParts(source: String): Map<String, String> = buildMap {
        files?.let { region -> bundleOf(source).filterKeys { it !in region.editable }.forEach { (k, v) -> put(k, v) } }
        markers.forEach { put("after $it", tail(source, it)) }
    }

    private fun bundleOf(source: String): Map<String, String> = try { mapper.readValue<Map<String, String>>(source) }
        catch (e: Exception) { throw StudyRejection("This rule is a JSON object of file name to text: ${e.message?.lineSequence()?.first()}") }

    // ---- replay ----------------------------------------------------------------------------------

    /** Queue the replay of a candidate on every fixture and the control, and return the attempt at once. */
    @Synchronized
    fun test(candidateId: String): StudyBuildRecord {
        val c = loadCandidate(candidateId)
        staleness(c)?.let { throw StudyRejection("$it Make a new candidate.") }
        val stage = replays.stages().single()
        val fingerprint = stage.fingerprint ?: throw StudyRejection(stage.reason ?: "The replay cannot start")
        return replays.start("replay", fingerprint, mapOf("candidate" to c.id))
    }

    // Why a candidate no longer describes the present, or null.
    private fun staleness(c: QualifiedCandidate): String? = when {
        c.baseRevision != rule.currentId() || c.baseSourceHash != SourceStudy.hash(rule.liveSource()) ->
            "The rule changed since this candidate was made (it is now ${rule.currentId()})."
        c.fixturesRevision != fixtures.currentId() || c.fixtures != corpus() -> "The fixtures changed since this candidate was made."
        c.dependencies != currentDependencies() -> "Its dependencies changed: ${(c.dependencies.keys + currentDependencies().keys).filter { c.dependencies[it] != currentDependencies()[it] }.distinct().joinToString()}."
        c.checks != currentChecks() -> "The checks changed since this candidate was made."
        else -> null
    }

    private fun replayInputs(): Map<String, String> = mapOf(
        "fixtures" to "${fixtures.currentId()} ${SourceStudy.hash(fixtures.revision().source)}",
        "dependencies" to canonical.writeValueAsString(currentDependencies()),
        "checks" to SourceStudy.hash(canonical.writeValueAsString(currentChecks())))

    private fun runReplay(context: StudyBuildContext): StudyBuildProduct {
        val id = context.request["candidate"] as? String ?: throw StudyBuildFailure(StudyBuildProblem("no-candidate",
            "Replay a candidate with $name.test; the replay has nothing to run on its own."))
        val c = loadCandidate(id)
        fun stale(message: String): Nothing = throw StudyBuildFailure(StudyBuildProblem("candidate-stale", message, listOf("Make a new candidate.")))
        if (c.fixturesRevision != context.inputs.getValue("fixtures").substringBefore(' ')) stale("The fixtures changed since this candidate was made.")
        if (canonical.writeValueAsString(c.dependencies) != context.inputs["dependencies"]) stale("Its dependencies changed since it was made.")
        if (SourceStudy.hash(canonical.writeValueAsString(c.checks)) != context.inputs["checks"]) stale("The checks changed since it was made.")
        context.file("candidate.json").writeText(pretty.writeValueAsString(c))
        context.progress(0.02, "Preparing the candidate")
        val run = try { replay(QualifiedReplay(c.source, c.checks, context)) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            throw StudyBuildFailure(StudyBuildProblem("candidate-unusable", "The candidate could not be prepared: ${messageOf(e)}",
                listOf("Fix the candidate and make a new one.")))
        }
        val names = c.fixtures.keys.sorted()
        val results = names.mapIndexed { i, fixture ->
            context.checkCancelled()
            context.progress(0.05 + 0.9 * i / (names.size + 1), "Replaying $fixture")
            val recorded = c.fixtures.getValue(fixture)
            try {
                val outcome = normal(run(recorded.input))
                val same = canonicalText(outcome) == canonicalText(recorded.outcome)
                QualifiedCaseResult(fixture, if (same) QualifiedVerdict.SAME else QualifiedVerdict.CHANGED, outcome, recorded.outcome, null)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                QualifiedCaseResult(fixture, QualifiedVerdict.HELD, null, recorded.outcome, messageOf(e))
            }
        }
        context.checkCancelled()
        context.progress(0.95, "Replaying the control ${control.name}")
        val controlResult = try {
            run(control.input)
            QualifiedControlResult(control.name, false, null, false)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val message = messageOf(e)
            QualifiedControlResult(control.name, true, message, control.messageContains == null || message.contains(control.messageContains))
        }
        val passed = controlResult.passed && results.all { it.verdict == QualifiedVerdict.SAME }
        val report = QualifiedReplayReport(c.id, c.sourceHash, c.fixturesRevision, results, controlResult, passed)
        val file = context.file("replay.json")
        file.writeText(pretty.writeValueAsString(report))
        if (passed) return StudyBuildProduct(listOf("replay.json", "candidate.json"), mapOf("candidate" to c.id, "fixtures" to names.size))
        val held = results.filter { it.verdict == QualifiedVerdict.HELD }
        val changed = results.filter { it.verdict == QualifiedVerdict.CHANGED }
        val message = buildList {
            if (held.isNotEmpty()) add("Held: " + held.joinToString("; ") { "${it.fixture} (${it.problem})" } + ".")
            if (changed.isNotEmpty()) add("Changed: ${changed.joinToString { it.fixture }}.")
            if (!controlResult.failed) add("The control ${control.name} passed, so the checks are not running.")
            else if (!controlResult.passed) add("The control ${control.name} failed for another reason: ${controlResult.problem}")
        }.joinToString(" ")
        val suggestions = when {
            held.isNotEmpty() || !controlResult.passed -> listOf("Revise the candidate and make a new one; the live rule is unchanged.")
            else -> listOf("If the new outcomes are right, call $name.proposeRebaseline with this job id; a person approves each one.")
        }
        throw StudyBuildFailure(StudyBuildProblem(if (held.isEmpty() && controlResult.passed) "replay-changed" else "replay-held", message, suggestions,
            mapOf("candidate" to c.id, "held" to held.map { it.fixture }, "changed" to changed.map { it.fixture },
                "control" to controlResult, "replay" to "replay.json", "replaySha256" to SourceStudy.hash(file.readText()))))
    }

    private fun messageOf(e: Throwable): String = (e.message ?: e.javaClass.simpleName).lineSequence().first().take(400)

    /** The report of a replay attempt, checked against its recorded hash; null when it has none. */
    fun reportOf(job: StudyBuildRecord): QualifiedReplayReport? {
        val file = runCatching { replays.artifactFile(job.id, "replay.json") }.getOrNull()?.takeIf { it.isFile } ?: return null
        // A passed replay recorded the file as a result; a failed one recorded its hash in the problem.
        val recorded = job.artifacts.firstOrNull { it.path == "replay.json" }
        val intact = if (recorded != null) runCatching { replays.verifiedFile(job.id, recorded) }.isSuccess
            else (job.problem?.details?.get("replaySha256") as? String)?.let { it == SourceStudy.hash(file.readText()) } ?: false
        return if (intact) mapper.readValue<QualifiedReplayReport>(file) else null
    }

    /** The latest replay attempt of [candidateId], or null. */
    fun replayOf(candidateId: String): StudyBuildRecord? {
        var offset = 0
        while (true) {
            val page = replays.history(offset, 100)
            page.firstOrNull { it.request["candidate"] == candidateId }?.let { return it }
            if (page.size < 100) return null
            offset += 100
        }
    }

    /** Why [candidateId] cannot be applied now, or null when it can. */
    fun admissionProblem(candidateId: String): String? {
        val c = runCatching { loadCandidate(candidateId) }.getOrElse { return it.message }
        staleness(c)?.let { return it }
        val job = replayOf(c.id) ?: return "It has not been replayed; call $name.test."
        if (job.status != StudyBuildStatus.SUCCEEDED) return "Its latest replay is ${job.status.name.lowercase()}: ${job.message}"
        if (!job.ranAgainst(replays.stages().single().fingerprint)) return "Its replay describes other fixtures, dependencies or checks; replay it again."
        val report = reportOf(job) ?: return "Its replay report is missing or changed; replay it again."
        if (report.candidate != c.id || report.sourceHash != c.sourceHash) return "Its replay ran another candidate."
        if (report.cases.map { it.fixture }.toSet() != c.fixtures.keys) return "Its replay does not cover every fixture."
        if (!report.passed || report.cases.any { it.verdict != QualifiedVerdict.SAME }) return "Its replay did not keep every outcome."
        if (!report.control.passed) return "Its control did not fail as it must."
        return null
    }

    /**
     * Publish an admitted candidate as the next revision of the rule, through [SourceStudy.edit] with the
     * revision and live-source hash the caller read. Refused, with nothing changed, unless [admissionProblem] is null.
     */
    @Synchronized
    fun apply(candidateId: String, expectedRevision: String, expectedSourceHash: String): SourceRevision<String> {
        val c = loadCandidate(candidateId)
        if (c.baseRevision != expectedRevision || c.baseSourceHash != expectedSourceHash)
            throw StudyRejection("Candidate ${c.id.take(12)} was made against another version of the rule.")
        admissionProblem(c.id)?.let { throw StudyRejection("Candidate ${c.id.take(12)} is not admitted: $it") }
        val job = checkNotNull(replayOf(c.id))
        return withRule(c.source, mapOf("candidate" to c.id, "replay" to job.id, "fixturesRevision" to c.fixturesRevision,
            "fixtures" to c.fixtures.keys.sorted(), "dependencies" to c.dependencies)) {
            rule.edit(expectedRevision, expectedSourceHash, c.source, c.description)
        }.also { refresh() }
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /**
     * Retain a finished attempt [jobId] of the build runner [runner] in this document as fixture [fixture], with
     * the input and outcome the `retain` adapter reads from it. Guarded by the corpus revision and hash the
     * caller read. Retaining the same case again changes nothing; a different case under an existing name is refused.
     */
    @Synchronized
    fun retainFixture(runner: String, jobId: String, fixture: String, expectedRevision: String, expectedSourceHash: String): Map<String, Any?> {
        val adapter = retain ?: throw StudyRejection("$label has no way to retain cases from builds; its box passes `retain` to qualifiedSource.")
        if (!fixture.matches(FIXTURE_NAME) || fixture == control.name) throw StudyRejection("Name the fixture with a short lower-case word other than the control's")
        requireCleanFixtures()
        if (fixtures.currentId() != expectedRevision || SourceStudy.hash(fixtures.liveSource()) != expectedSourceHash)
            throw StudyRejection("Conflict: the fixtures changed since you read them. Inspect again.")
        val builds = runners(runner) ?: throw StudyRejection("No build runner '$runner' in this document")
        val record = runCatching { builds.job(jobId) }.getOrElse { throw StudyRejection("No attempt $jobId in '$runner'") }
        if (record.status !in setOf(StudyBuildStatus.SUCCEEDED, StudyBuildStatus.FAILED, StudyBuildStatus.OBSOLETE))
            throw StudyRejection("Retain a finished attempt; $jobId is ${record.status.name.lowercase()}.")
        val read = { path: String -> record.artifacts.firstOrNull { it.path == path }?.let { builds.verifiedFile(record.id, it) } ?: builds.artifactFile(record.id, path) }
        val retained = adapter(QualifiedRetainSource(runner, record, read))
        val case = QualifiedCase(retained.input, normal(retained.outcome), linkedMapOf("runner" to runner, "jobId" to record.id,
            "stage" to record.stage, "status" to record.status.name.lowercase(), "fingerprint" to record.fingerprint,
            "inputSha256" to SourceStudy.hash(retained.input), "retainedAt" to Instant.now().toString()))
        val corpus = corpus()
        corpus[fixture]?.let { existing ->
            if (existing.input == case.input && canonicalText(existing.outcome) == canonicalText(case.outcome))
                return mapOf("fixture" to fixture, "alreadyPresent" to true, "revision" to fixtures.currentId())
            throw StudyRejection("Fixture '$fixture' already holds another case; retain this one under a new name.")
        }
        val revision = withFixtures("retained $fixture") {
            fixtures.edit(expectedRevision, expectedSourceHash, corpusText(corpus + (fixture to case)), "Retained $fixture from $runner/${record.id}")
        }
        refresh()
        return mapOf("fixture" to fixture, "alreadyPresent" to false, "revision" to revision.id,
            "sourceHash" to SourceStudy.hash(revision.source), "fixtures" to corpus.size + 1)
    }

    private fun requireCleanFixtures() {
        if (fixtures.liveSource() != fixtures.revision().source)
            throw StudyRejection("The fixtures were edited outside $name.retainFixture and approval. Restore them to revision ${fixtures.currentId()} first.")
    }

    private fun corpusOf(source: String): MutableMap<String, QualifiedCase> {
        val corpus = try { mapper.readValue<Map<String, QualifiedCase>>(source) }
            catch (e: Exception) { throw StudyRejection("Fixtures are a JSON object of named cases: ${e.message?.lineSequence()?.first()}") }
        corpus.keys.forEach { if (!it.matches(FIXTURE_NAME)) throw StudyRejection("Fixture names are short lower-case words: '$it'") }
        if (control.name in corpus) throw StudyRejection("The control '${control.name}' cannot also be a fixture")
        return corpus.toSortedMap()
    }

    private fun corpusText(corpus: Map<String, QualifiedCase>): String = pretty.writeValueAsString(corpus.toSortedMap()) + "\n"

    private fun checksOf(source: String): Map<String, String> = mapper.readValue(source)

    // ---- rebaselines -----------------------------------------------------------------------------

    /**
     * Propose the new outcomes of a failed replay [jobId] whose only failures are changed outcomes. Shows each
     * fixture's recorded and proposed outcome with an **Approve** button. Nothing changes until a person presses
     * one; this never approves.
     */
    @Synchronized
    fun proposeRebaseline(jobId: String): QualifiedProposal {
        val job = runCatching { replays.job(jobId) }.getOrElse { throw StudyRejection("No replay $jobId") }
        if (job.status != StudyBuildStatus.FAILED || job.problem?.code != "replay-changed")
            throw StudyRejection(if (job.problem?.code == "replay-held") "This replay held or its control did not fail; fix the candidate first: ${job.message}"
                else "Only a finished replay whose sole failures are changed outcomes can be proposed.")
        val report = reportOf(job) ?: throw StudyRejection("The replay's report is missing or changed")
        val c = loadCandidate(report.candidate)
        val corpus = corpus()
        val entries = report.cases.filter { it.verdict == QualifiedVerdict.CHANGED }.map { r ->
            val live = corpus[r.fixture] ?: throw StudyRejection("Fixture ${r.fixture} is no longer in the corpus")
            if (canonicalText(live.outcome) != canonicalText(c.fixtures.getValue(r.fixture).outcome))
                throw StudyRejection("Fixture ${r.fixture} changed since this candidate; make and replay a new one.")
            val changes = mutableListOf<QualifiedChange>()
            diff(mapper.valueToTree(live.outcome), mapper.valueToTree(r.outcome), "", changes)
            QualifiedProposalEntry(r.fixture, SourceStudy.hash(canonicalText(live.outcome)), SourceStudy.hash(canonicalText(r.outcome)),
                live.outcome, r.outcome, changes.take(MAX_CHANGES), changes.size > MAX_CHANGES)
        }
        val draft = QualifiedProposal(name, c.id, job.id, fixtures.currentId(), entries)
        val proposal = draft.copy(id = SourceStudy.hash(canonical.writeValueAsString(draft)))
        val file = File(proposals, "${proposal.id}.json")
        if (!file.isFile) file.writeText(pretty.writeValueAsString(proposal))
        refresh()
        return proposal
    }

    private fun diff(before: com.fasterxml.jackson.databind.JsonNode?, after: com.fasterxml.jackson.databind.JsonNode?, path: String, out: MutableList<QualifiedChange>) {
        if (before == after || out.size > MAX_CHANGES) return
        if (before != null && after != null && before.isObject && after.isObject)
            (before.fieldNames().asSequence() + after.fieldNames().asSequence()).distinct().sorted().forEach { diff(before.get(it), after.get(it), "$path/$it", out) }
        else if (before != null && after != null && before.isArray && after.isArray && before.size() == after.size())
            for (i in 0 until before.size()) diff(before[i], after[i], "$path/$i", out)
        else out += QualifiedChange(path.ifEmpty { "/" }, before?.let { mapper.convertValue(it, Any::class.java) }, after?.let { mapper.convertValue(it, Any::class.java) })
    }

    /** A saved proposal, checked against its id. */
    fun proposal(id: String): QualifiedProposal {
        if (!id.matches(HASH)) throw StudyRejection("'$id' is not a proposal id")
        val p = mapper.readValue<QualifiedProposal>(File(proposals, "$id.json"))
        check(p.id == id && SourceStudy.hash(canonical.writeValueAsString(p.copy(id = ""))) == id) { "Proposal $id changed on disk; propose it again" }
        return p
    }

    private fun approvalOf(p: QualifiedProposal, fixture: String): QualifiedApproval? =
        corpus()[fixture]?.approvals?.lastOrNull { it.proposal == p.id }

    private fun pendingEntries(p: QualifiedProposal): List<QualifiedProposalEntry> {
        val corpus = corpus()
        return p.entries.filter { e -> approvalOf(p, e.fixture) == null && corpus[e.fixture]?.let { SourceStudy.hash(canonicalText(it.outcome)) } == e.beforeHash }
    }

    private fun allProposals(): List<QualifiedProposal> = proposals.listFiles { f -> f.name.matches(Regex("[a-f0-9]{64}\\.json")) }.orEmpty()
        .sortedBy { it.lastModified() }.mapNotNull { runCatching { proposal(it.nameWithoutExtension) }.getOrNull() }

    /** Proposals with a fixture still waiting for a person. */
    fun pendingProposals(): List<QualifiedProposal> = allProposals().filter { pendingEntries(it).isNotEmpty() }

    /**
     * Record a person's approval of [fixture]'s proposed outcome in proposal [proposalId]: the fixture's outcome
     * becomes the proposed one, as a new fixtures revision that records [by], [via] and the time. Reached only
     * from the proposal's Approve button; refused inside any agent tool call.
     */
    @Synchronized
    internal fun approve(proposalId: String, fixture: String, by: String, via: String): SourceRevision<String> {
        requirePerson("approve a fixture's new outcome")
        requireCleanFixtures()
        val p = proposal(proposalId)
        val entry = p.entries.firstOrNull { it.fixture == fixture } ?: throw StudyRejection("Proposal ${p.id.take(12)} has no fixture '$fixture'")
        if (approvalOf(p, fixture) != null) throw StudyRejection("$fixture is already approved")
        val corpus = corpus()
        val live = corpus[fixture] ?: throw StudyRejection("Fixture $fixture is no longer in the corpus")
        if (SourceStudy.hash(canonicalText(live.outcome)) != entry.beforeHash) throw StudyRejection("Fixture $fixture changed since this proposal; ask for a new one.")
        val approval = QualifiedApproval(p.id, p.candidate, p.replay, entry.beforeHash, entry.afterHash, by, via, Instant.now().toString())
        val updated = live.copy(outcome = entry.after, approvals = live.approvals + approval)
        val text = fixtures.liveSource()
        return withFixtures("approved $fixture") {
            fixtures.edit(fixtures.currentId(), SourceStudy.hash(text), corpusText(corpus + (fixture to updated)), "$by approved a new outcome for $fixture (proposal ${p.id.take(12)})")
        }
    }

    /**
     * Make the checks current: accept the live rule's edited frozen parts as a new revision, and upgrade a
     * [FrozenRegion.Baseline]'s copy to what its `current` says. For a person (the card's button) or a migration
     * in box code; refused inside an agent tool call. Every candidate made before is then stale.
     */
    @Synchronized
    fun upgradeChecks(by: String, reason: String): Map<String, Any?> {
        requirePerson("change the checks")
        if (reason.isBlank()) throw StudyRejection("Say why the checks change")
        val done = linkedMapOf<String, Any?>()
        val live = rule.liveSource()
        if (frozenParts(live) != frozenParts(source())) {
            val revision = withRule(live, mapOf("checksUpgradedBy" to by, "reason" to reason)) {
                rule.edit(rule.currentId(), SourceStudy.hash(live), live, "$by accepted edited checks: $reason")
            }
            done["rule"] = revision.id
        }
        checksCopy?.let { copy ->
            val now = canonical.writeValueAsString(baseline!!.current().toSortedMap())
            if (checksOf(now) != checksOf(copy.revision().source)) {
                val files = staleFiles()
                val revision = withChecks("$by: $reason") { copy.edit(copy.currentId(), SourceStudy.hash(copy.liveSource()), now, "$by upgraded ${files.joinToString()}: $reason") }
                done["checks"] = revision.id; done["files"] = files
            }
        }
        if (done.isEmpty()) throw StudyRejection("The checks are already current")
        refresh()
        return done
    }

    private fun requirePerson(action: String) {
        if (currentToolArgs.get() != null) throw StudyRejection("Only a person can $action, with the button on the card; no tool can.")
    }

    private fun <T> withRule(source: String, facts: Map<String, Any?>, block: () -> T): T {
        ruleAdmission.set(facts + ("sourceHash" to SourceStudy.hash(source)))
        try { return block() } finally { ruleAdmission.remove() }
    }

    private fun <T> withFixtures(reason: String, block: () -> T): T {
        fixtureAdmission.set(reason)
        try { return block() } finally { fixtureAdmission.remove() }
    }

    private fun <T> withChecks(reason: String, block: () -> T): T {
        checksAdmission.set(reason)
        try { return block() } finally { checksAdmission.remove() }
    }

    private fun normal(value: Any?): Any? = mapper.readValue<Any?>(canonical.writeValueAsString(value))
    private fun canonicalText(value: Any?): String = canonical.writeValueAsString(normal(value))

    // ---- the cards -------------------------------------------------------------------------------

    /** Start redrawing the cards twice a second; [Box.qualifiedSource] does this. */
    internal fun refreshContinuously() {
        refresher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "qualified-source").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ refresh() }, 0, 500, TimeUnit.MILLISECONDS) }
    }

    /** Draw the replay card in channel [name] and, while a proposal waits, the approval view in `<name>-approval`. */
    fun refresh() {
        val out = show ?: return
        if (closed) return
        val card = try { cardHtml() } catch (e: Exception) { "<div style=\"color:#ff6969;user-select:text\">${escape(e.message ?: "Unavailable")}</div>" }
        val approval = try { approvalHtml() } catch (e: Exception) { "<div style=\"color:#ff6969;user-select:text\">${escape(e.message ?: "Unavailable")}</div>" }
        synchronized(buttons) {
            if (card != lastCard) { lastCard = card; out(name, card) }
            if (approval != lastApproval) { lastApproval = approval; if (approval.isNotEmpty()) out("$name-approval", approval) }
        }
    }

    // Each button is registered once, the first time it is drawn; its key says what it does.
    private fun button(key: String, label: String, important: Boolean = false, action: () -> Unit): String {
        val id = synchronized(buttons) {
            buttons[key] ?: "$prefix-${buttons.size}".also { id ->
                buttons[key] = id
                registerButtonCallback(id) { down ->
                    if (down && !closed) onMain {
                        cardError = try { action(); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
                        refresh()
                    }
                }
            }
        }
        return """<span class="field-button" data-control-id="$id"><button id="$id" type="button" class="field-button-hit${if (important) " important" else ""}" aria-pressed="false">${escape(label)}</button></span>"""
    }

    /** The control ids of the Approve buttons drawn now, by proposal and fixture. */
    internal fun approveButtons(): Map<Pair<String, String>, String> = synchronized(buttons) {
        buttons.filterKeys { it.startsWith("approve ") }.mapKeys { (k, _) -> k.split(' ').let { it[1] to it[2] } }
    }

    private fun cardHtml(): String {
        val stage = replays.stages().single()
        val job = replays.history(0, 1).firstOrNull()
        val report = job?.let(::reportOf)
        val corpus = corpus()
        val body = StringBuilder()
        body.append("<div class=\"q-title\"><b>${escape(label)}</b><span class=\"q-muted\">rule ${escape(rule.currentId())} · ${corpus.size} fixtures · must fail: ${escape(control.name)}</span></div>")
        checksNotice()?.let { notice ->
            body.append("<p class=\"q-warn\">${escape(notice)}</p>")
            body.append("<p>${button("upgrade", "Accept the current checks", true) { upgradeChecks(personName(), "accepted on the card") }}</p>")
        }
        if (rule.liveSource() != source() && checksNotice() == null) body.append("<p class=\"q-muted\">The live rule has edits that no candidate has qualified yet.</p>")
        if (job == null) body.append("<p class=\"q-muted\">No candidate has been replayed yet.</p>")
        else {
            val c = (job.request["candidate"] as? String)?.let { runCatching { loadCandidate(it) }.getOrNull() }
            val current = job.ranAgainst(stage.fingerprint)
            val status = when {
                job.status in setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING) -> "Replaying"
                report?.passed == true && !current -> "Passed"
                report != null && job.problem?.code == "replay-changed" && !current -> "Outcomes changed"
                report?.passed == true && c?.sourceHash == SourceStudy.hash(source()) -> "Passed · applied as ${rule.currentId()}"
                report?.passed == true -> "Passed · ready to apply"
                report != null && job.problem?.code == "replay-changed" -> "Outcomes changed · needs a person"
                report != null -> "Held"
                else -> job.status.name.lowercase().replaceFirstChar { it.uppercase() }
            }
            body.append("<p><span class=\"q-muted\">Candidate ${escape(c?.id?.take(12) ?: "?")}</span> ${escape(c?.description ?: "")}</p>")
            val tone = when {
                report?.passed == true -> "ok"
                report != null && job.problem?.code == "replay-changed" -> "warn"
                report != null -> "bad"
                else -> "run"
            }
            val earlier = if (!current && job.status !in setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING)) " · made before the fixtures or checks last changed" else ""
            body.append("<p class=\"q-status q-$tone\">${escape(status + earlier)}</p>")
            if (job.status in setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING))
                body.append("<progress value=\"${job.progress}\" max=\"1\"></progress><p class=\"q-muted\">${escape(job.message)}</p>")
            if (report != null) {
                body.append("<table><colgroup><col style=\"width:24%\"><col style=\"width:24%\"><col style=\"width:52%\"></colgroup>" +
                    "<tr class=\"q-muted\"><td>fixture</td><td>outcome</td><td></td></tr>")
                for (r in report.cases) {
                    val (word, cls, detail) = when (r.verdict) {
                        QualifiedVerdict.SAME -> Triple("same", "ok", "")
                        QualifiedVerdict.CHANGED -> Triple("changed", "warn", changeSummary(r.recorded, r.outcome))
                        QualifiedVerdict.HELD -> Triple("held", "bad", r.problem.orEmpty())
                    }
                    body.append("<tr><td>${escape(r.fixture)}</td><td class=\"q-$cls\">$word</td><td>${escape(detail.take(300))}</td></tr>")
                }
                val ctl = report.control
                body.append("<tr><td>${escape(ctl.name)} <span class=\"q-muted\">(must fail)</span></td><td class=\"q-${if (ctl.passed) "ok" else "bad"}\">${if (ctl.passed) "failed, as it must" else if (ctl.failed) "failed otherwise" else "passed"}</td>" +
                    "<td>${escape(ctl.problem ?: "the checks are not running")}</td></tr></table>")
            } else if (job.status !in setOf(StudyBuildStatus.QUEUED, StudyBuildStatus.RUNNING)) body.append("<p class=\"q-bad\">${escape(job.message)}</p>")
        }
        if (pendingProposals().isNotEmpty()) body.append("<p class=\"q-warn\">New outcomes are waiting for your approval below.</p>")
        cardError?.let { body.append("<p class=\"q-bad\">${escape(it)}</p>") }
        return wrap(body.toString())
    }

    private fun approvalHtml(): String {
        val p = allProposals().lastOrNull() ?: return ""
        val pending = pendingEntries(p).map { it.fixture }.toSet()
        val body = StringBuilder()
        body.append("<div class=\"q-title\"><b>New outcomes for ${escape(label)}</b><span class=\"q-muted\">candidate ${escape(p.candidate.take(12))} · replay ${escape(p.replay.take(8))}</span></div>")
        body.append("<p>Every other fixture kept its outcome and the control failed as it must. Approve a fixture only if its new outcome is right; nothing changes until you do.</p>")
        for (e in p.entries) {
            val approval = approvalOf(p, e.fixture)
            body.append("<h4>${escape(e.fixture)}${approval?.let { " <span class=\"q-ok\">· approved by ${escape(it.approvedBy)} at ${escape(it.approvedAt.take(19).replace('T', ' '))} UTC</span>" } ?: ""}</h4>")
            body.append("<table><colgroup><col style=\"width:22%\"><col style=\"width:39%\"><col style=\"width:39%\"></colgroup><tr class=\"q-muted\"><td>where</td><td>recorded</td><td>proposed</td></tr>")
            for (c in e.changes) body.append("<tr><td class=\"q-muted\">${escape(c.path.removePrefix("/").ifEmpty { "outcome" })}</td><td class=\"q-bad\">${escape(brief(c.before).take(600))}</td><td class=\"q-ok\">${escape(brief(c.after).take(600))}</td></tr>")
            body.append("</table>")
            if (e.truncated) body.append("<p class=\"q-muted\">Only the first $MAX_CHANGES changes are listed; both outcomes are in the proposal.</p>")
            if (e.fixture in pending) body.append("<p>${button("approve ${p.id} ${e.fixture}", "Approve the new outcome of ${e.fixture}", true) {
                approve(p.id, e.fixture, personName(), "Approve button on the $label card")
            }}</p>")
            else if (approval == null) body.append("<p class=\"q-muted\">This fixture changed after the proposal; ask for a new one.</p>")
        }
        if (pending.isEmpty()) body.append("<p class=\"q-ok\">All approved. A candidate made from here replays against the new outcomes.</p>")
        return wrap(body.toString())
    }

    private fun wrap(body: String) = """
<style>
.$prefix{background:#000;color:#ddd;padding:16px;border-radius:12px;font:14px/1.45 Iosevka,monospace;width:680px;box-sizing:border-box;user-select:text}
.$prefix .q-title{display:flex;gap:12px;align-items:baseline}.$prefix .q-title span{margin-left:auto}.$prefix p{margin:8px 0 0;overflow-wrap:anywhere}
.$prefix h4{margin:16px 0 4px;color:#ddd;font:inherit;font-weight:bold}.$prefix table{border-collapse:collapse;width:100%;table-layout:fixed;margin-top:10px}
.$prefix td{vertical-align:top;padding:2px 10px 2px 0;overflow-wrap:anywhere}.$prefix .q-muted{color:#999}.$prefix .q-ok{color:#55d4f2}
.$prefix .q-warn{color:#f5b748}.$prefix .q-bad{color:#ff6969}.$prefix .q-run{color:#ddd}.$prefix .q-status{font-weight:bold}
.$prefix progress{display:block;width:100%;height:5px;margin-top:10px;accent-color:#55d4f2;border:0}
.$prefix .field-button{display:inline-flex;margin:0;padding:0;background:none;border:0}
.$prefix button.field-button-hit{font:inherit;text-transform:none;letter-spacing:normal;padding:8px 14px;border:0;border-radius:9px;background:#262626;color:#ddd;cursor:pointer;user-select:none}
.$prefix button.important{background:#55d4f2;color:#000}.$prefix button:hover{filter:brightness(1.12)}
</style><div class="$prefix" data-qualified-source="${escape(name)}" data-afield-intrinsic-width="680">$body</div>""".trimIndent()

    private fun changeSummary(before: Any?, after: Any?): String {
        val changes = mutableListOf<QualifiedChange>()
        diff(mapper.valueToTree(before), mapper.valueToTree(after), "", changes)
        return changes.take(4).joinToString("; ") { "${it.path.removePrefix("/").ifEmpty { "outcome" }}: ${brief(it.before)} → ${brief(it.after)}" } +
            if (changes.size > 4) "; and ${changes.size - 4} more" else ""
    }

    private fun brief(value: Any?): String = when (value) {
        null -> "absent"
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { "${it.key}: ${brief(it.value)}" }
        is List<*> -> if (value.size > 12 && value.all { it is Number }) "[${value.size} numbers]" else value.joinToString(", ", "[", "]") { brief(it) }
        else -> value.toString()
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    override fun close() {
        closed = true
        refresher?.shutdownNow()
        replays.close()
    }

    companion object {
        private const val MAX_CHANGES = 200
        private val HASH = Regex("[a-f0-9]{64}")

        /** Who presses a button in this Field: the account it runs under. */
        internal fun personName(): String = System.getProperty("user.name")?.takeIf { it.isNotBlank() } ?: "the person at this Field"

        /**
         * Run [source] as a Kotlin script in a fresh engine and return the value of its last expression. For a
         * rule written in Kotlin: its replay evaluates the candidate once and calls what it returns on each input.
         */
        fun evaluateKotlin(source: String): Any? {
            val engine = afield.createKotlinScriptEngine(filePrefix = "qualified_${System.nanoTime()}")
            val problems = mutableListOf<String>()
            try {
                val value = engine.execAndWait(source,
                    onCompileError = { ds -> problems += ds.map { it.message } },
                    onEvalError = { ds -> problems += ds.map { it.message } },
                    onEvalException = { throw it })
                if (value is kotlin.script.experimental.api.ResultValue.Error) throw value.error
                if (value == null || problems.isNotEmpty()) throw IllegalArgumentException("It does not compile: " + problems.joinToString("; ").ifBlank { "no value" })
                return (value as? kotlin.script.experimental.api.ResultValue.Value)?.value
            } finally { engine.dispose() }
        }
    }
}

/** One exact replacement in a candidate; [file] names the file when the rule is made of files. */
data class QualifiedEdit(val before: String, val after: String, val file: String? = null)

internal val FIXTURE_NAME = Regex("[a-z][a-z0-9-]{0,79}")

/**
 * Keep the rule in this box's property `<name>Rule` (seeded from [seed] the first time) under a
 * [QualifiedSource], with its fixtures in `<name>Fixtures`, and register the agent tools `<name>.inspect`,
 * `.candidate`, `.test`, `.apply`, `.retainFixture` and `.proposeRebaseline`. The replay card shows in the
 * output channel [name] and an approval view, while one waits, in `<name>-approval`.
 *
 * [replay] prepares a candidate (compile it, say) and returns a function that runs it on one fixture's input:
 * return the outcome, or throw to hold it. Re-running the box replaces the source and its tools; the
 * history, fixtures and replays stay in the document's `studies` directory.
 */
fun Box.qualifiedSource(name: String, seed: String, control: QualifiedControl, frozen: List<FrozenRegion>, replayVersion: String,
    fixtures: Map<String, QualifiedFixture> = emptyMap(), label: String = name, extension: String = "kt",
    dependencies: () -> Map<String, String> = { emptyMap() }, retain: ((QualifiedRetainSource) -> QualifiedFixture)? = null,
    replay: (QualifiedReplay) -> (String) -> Any?): QualifiedSource {
    require(name.matches(STUDY_BUILD_NAME)) { "A qualified source's name must be a simple word" }
    check(!ReadOnlyDocuments.blocksWritesFrom(this)) { "This document is read-only" }
    val key = "_qualifiedSource:$name"
    (properties[key] as? QualifiedSource)?.close()
    val ruleProperty = "${name}Rule"
    val fixtureProperty = "${name}Fixtures"
    Box.ioPersistentProperties.addAll(listOf(ruleProperty, fixtureProperty))
    Box.ioSeparateProperties[ruleProperty] = extension
    Box.ioSeparateProperties[fixtureProperty] = "json"
    if (getPropertyRaw(ruleProperty, null) == null) setPropertyRaw(ruleProperty, seed, doResync = true)
    val document = BoxStudies.documentOf(this)
    val owner = this
    // A press arrives on the page's control queue; the change it makes runs on the main thread, like every tool's.
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    val onMain: (() -> Unit) -> Unit = { block -> if (Runloop.isMainThread) block() else kotlinx.coroutines.GlobalScope.launch(Runloop.runLoop.main) { block() } }
    val source = QualifiedSource(name, label, File(BoxStudies.directory(this), "qualified/$name"),
        { getPropertyRaw(ruleProperty, "")?.toString().orEmpty() }, BoxStudies.replacer(this, ruleProperty),
        { getPropertyRaw(fixtureProperty, "")?.toString().orEmpty() }, BoxStudies.replacer(this, fixtureProperty),
        control, frozen, replayVersion, fixtures, dependencies, retain, { studyBuildRunner(document, it) }, extension,
        builds = { stages, inputs -> owner.studyBuilds(name, stages, workers = 1, readInputs = inputs) },
        show = { channel, html -> BoxStudies.show(owner, channel, html) }, onMain = onMain, replay = replay)
    properties[key] = source
    registerStopHook(Runnable { source.close(); if (properties[key] === source) properties.remove(key) })
    registerQualifiedTools(source)
    source.refreshContinuously()
    return source
}

/** The six agent tools of [q]; none of them approves a fixture's outcome or changes the checks. */
internal fun Box.registerQualifiedTools(q: QualifiedSource): List<afield.ai.core.BoxTool> {
    val ns = q.name
    return listOf(aiTool("$ns.inspect") {
        describe("Read the qualified rule '${q.label}': its revision and live-source hash (quote both to $ns.candidate and $ns.apply), " +
            "the frozen checks a candidate may not change, the fixture names and corpus revision, the latest replay and any outcomes waiting for a person. Changes nothing.")
        namespace(ns); effects(ToolEffect.READ_DOCUMENT)
        val includeSource by param<Boolean>("Include the complete live rule text", default = false)
        action { q.inspect(includeSource()) }
    }, aiTool("$ns.candidate") {
        describe("Prepare a candidate change to '${q.label}' against the live rule you inspected. Give exact edits (each 'before' must occur once; " +
            "name 'file' when the rule is made of files), a whole 'source', or neither with fromCandidateId to copy a saved candidate after a person approved new outcomes. " +
            "The frozen checks cannot change. Saves the candidate with the fixtures, dependencies and checks it will be judged by; runs nothing and publishes nothing. Next: $ns.test.")
        namespace(ns); effects(ToolEffect.MUTATE_DOCUMENT)
        val expectedRevision by param<String>("rule.revision from $ns.inspect")
        val expectedSourceHash by param<String>("rule.liveSourceHash from $ns.inspect")
        val description by param<String>("What the change does and why, in a sentence")
        val edits by param<List<Map<String, String>>>("Exact replacements: {before, after, file?}", default = emptyList<Map<String, String>>())
        val source by param<String>("A whole replacement source instead of edits", default = "")
        val addFiles by param<Map<String, String>>("New editable files, by name (rules made of files only)", default = emptyMap<String, String>())
        val fromCandidateId by param<String>("Start from this saved candidate's source, made against the same rule", default = "")
        action {
            val c = q.candidate(expectedRevision(), expectedSourceHash(), description(),
                edits().map { QualifiedEdit(it["before"].orEmpty(), it["after"].orEmpty(), it["file"]?.takeIf(String::isNotBlank)) },
                source().takeIf { it.isNotEmpty() }, addFiles(), fromCandidateId())
            mapOf("candidateId" to c.id, "sourceHash" to c.sourceHash, "fixtures" to c.fixtures.keys.sorted(), "control" to q.control.name,
                "next" to "Call $ns.test with this candidateId.")
        }
    }, aiTool("$ns.test") {
        describe("Queue the replay of a saved candidate on every fixture of '${q.label}' and on its must-fail control, and return the job at once. " +
            "Wait for it with AwaitStudyBuildsRequest (boxId, name, jobId as returned). Changes neither the rule nor the fixtures.")
        namespace(ns); effects(ToolEffect.MUTATE_DOCUMENT, ToolEffect.EXECUTE_CODE)
        val candidateId by param<String>("The candidateId from $ns.candidate")
        action {
            val job = q.test(candidateId())
            mapOf("boxId" to thisBox.id, "name" to ns, "jobId" to job.id, "status" to job.status.name.lowercase())
        }
    }, aiTool("$ns.apply") {
        describe("Publish an admitted candidate as the next revision of '${q.label}'. Admitted means its latest replay is current, every fixture kept its " +
            "recorded outcome, the control failed as it must, and the fixtures, dependencies and checks are those it was made with. Refused otherwise, with the reason; nothing changes.")
        namespace(ns); effects(ToolEffect.MUTATE_DOCUMENT)
        val candidateId by param<String>("The replayed candidateId")
        val expectedRevision by param<String>("rule.revision the candidate was made against")
        val expectedSourceHash by param<String>("rule.liveSourceHash the candidate was made against")
        action {
            val revision = q.apply(candidateId(), expectedRevision(), expectedSourceHash())
            mapOf("revision" to revision.id, "sourceHash" to SourceStudy.hash(revision.source), "facts" to revision.facts,
                "note" to "Builds that read this rule are now stale; rebuild them.")
        }
    }, aiTool("$ns.retainFixture") {
        describe("Keep a finished build attempt in this document as a new fixture of '${q.label}': its exact input and the outcome it had, with where it came from. " +
            "Use the runner name and jobId from InspectStudyBuildsRequest, and the fixtures revision and sourceHash from $ns.inspect. Never changes an existing fixture; " +
            "make a new candidate afterwards, since older ones were made without it.")
        namespace(ns); effects(ToolEffect.MUTATE_DOCUMENT)
        val runner by param<String>("The build runner's name")
        val jobId by param<String>("The finished attempt to keep")
        val fixture by param<String>("A short lower-case name for the new fixture")
        val expectedRevision by param<String>("fixtures.revision from $ns.inspect")
        val expectedSourceHash by param<String>("fixtures.sourceHash from $ns.inspect")
        action { q.retainFixture(runner(), jobId(), fixture(), expectedRevision(), expectedSourceHash()) }
    }, aiTool("$ns.proposeRebaseline") {
        describe("When a replay of '${q.label}' failed only because some fixtures' outcomes changed (nothing held, the control failed as it must), show the person " +
            "each recorded and proposed outcome with an Approve button. You cannot approve: only the person's press changes a recorded outcome. Ask them to review it; " +
            "after approval, make a new candidate with fromCandidateId, replay it and apply it.")
        namespace(ns); effects(ToolEffect.MUTATE_DOCUMENT)
        val jobId by param<String>("The failed replay's jobId")
        action {
            val p = q.proposeRebaseline(jobId())
            mapOf("proposalId" to p.id, "fixtures" to p.entries.map { mapOf("fixture" to it.fixture, "changes" to it.changes.size) },
                "awaitingPerson" to "Shown in the '$ns-approval' output of this box with one Approve button per fixture. Only the person can approve.")
        }
    })
}
