package afield.study.source

import afield.animation.keyframes.h
import afield.data.collections.times
import afield.document.context.why
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.path
import afield.graphics.render.rangeTo
import afield.lang.stacksl.h
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.remAssign
import afield.math.linalg.times
import afield.math.linalg.v2
import afield.math.linalg.x
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.runtime.lifecycle.once
import afield.ui.output.h

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.r
import afield.data.collections.s
import afield.document.workspace.file
import afield.document.workspace.files
import afield.math.linalg.d
import afield.math.linalg.invoke
import afield.math.linalg.set
import afield.media.image.channel
import afield.media.image.close
import afield.media.image.open
import afield.media.image.split
import afield.study.build.StudyBuildStatus
import afield.study.build.StudyBuilds
import afield.document.graph.Box
import afield.ai.tools.currentToolArgs
import afield.ui.output.getButtonCallback
import afield.study.build.StudyBuildTestSupport.build
import afield.study.build.StudyBuildTestSupport.temp
import afield.study.build.StudyBuildTestSupport.writing
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

/**
 * A loot rule in a tiny language: `<tier> <doors>` lines pick the first tier whose door count a room reaches,
 * and lines after `# checks` are checks: `epic needs <n>` (no epic nearer than n doors) and `some epic`.
 * A level is `room doors, room doors`. The replay reads the checks from the rule itself, or from the frozen
 * copy when the test keeps them there.
 */
class QualifiedSourceTest {
    private val levels = mapOf(
        "keep" to "gate 1, yard 2, keep 4",
        "crypt" to "stairs 1, ossuary 3, tomb 5")
    private val rule = "epic 4\nrare 2\ncommon 0\n# checks\nepic needs 3\nsome epic\n"
    private val shown = mutableMapOf<String, String>()
    private val runners = mutableMapOf<String, StudyBuilds>()
    private val opened = mutableListOf<AutoCloseable>()

    @AfterTest fun close() { opened.asReversed().forEach { runCatching { it.close() } } }

    private fun parse(level: String) = level.split(", ").associate { it.substringBefore(' ') to it.substringAfter(' ').toInt() }

    private fun place(rule: String, checks: List<String>, level: String): Map<String, String> {
        val tiers = rule.lines().takeWhile { it != "# checks" }.filter { it.isNotBlank() && !it.startsWith("//") }
            .map { it.substringBefore(' ') to it.substringAfter(' ').toInt() }
        val rooms = parse(level)
        val placed = rooms.mapValues { (_, doors) -> tiers.first { doors >= it.second }.first }
        for (check in checks) when {
            check.startsWith("epic needs ") -> placed.filter { it.value == "epic" && rooms.getValue(it.key) < check.removePrefix("epic needs ").toInt() }
                .keys.firstOrNull()?.let { error("$it has epic loot ${rooms.getValue(it)} doors from the spawn") }
            check == "some epic" -> check("epic" in placed.values) { "no room has epic loot" }
        }
        return placed.toSortedMap()
    }

    private fun recorded(level: String) = QualifiedFixture(level, place(rule, listOf("epic needs 3", "some epic"), level))

    private class Holder(var text: String) {
        fun read() = text
        fun replace(expected: String, replacement: String) = synchronized(this) { if (text != expected) false else { text = replacement; true } }
    }

    private inner class Setup(
        val directory: File = temp(),
        val frozen: List<FrozenRegion> = listOf(FrozenRegion.after("# checks")),
        initial: String = rule,
        val control: QualifiedControl = QualifiedControl("no-deep-room", "hall 1, store 2", "no room has epic loot"),
        val dependency: AtomicReference<String> = AtomicReference("placer-v1"),
        val retainFrom: ((QualifiedRetainSource) -> QualifiedFixture)? = null,
        val checksFromCopy: Boolean = false,
    ) {
        val ruleText = Holder(initial)
        val fixtureText = Holder("")
        fun open() = QualifiedSource("loot", "Loot rule", directory, ruleText::read, ruleText::replace, fixtureText::read, fixtureText::replace,
            control, frozen, "loot-replay-v1", levels.mapValues { recorded(it.value) }, { mapOf("placer" to dependency.get()) },
            retainFrom, runners::get, "txt", builds = { stages, inputs -> StudyBuilds(File(directory, "builds"), stages, inputs, workers = 1) },
            show = { channel, html -> shown[channel] = html }) { r ->
                val files = frozen.any { it is FrozenRegion.Files }
                val checks = when {
                    checksFromCopy -> r.checks.getValue("audit")
                    files -> bundle(r.source).getValue("audit")
                    else -> r.source.substringAfter("# checks\n")
                }.lines().filter(String::isNotBlank)
                val tiers = if (files) bundle(r.source).getValue("tiers") else r.source
                ({ input: String -> place(tiers, checks, input) })
            }.also { opened += it }
    }

    private fun bundle(text: String): Map<String, String> = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readValue(text,
        object : com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {})

    private fun QualifiedSource.edit(description: String, before: String, after: String, file: String? = null) =
        candidate(rule.currentId(), SourceStudy.hash(rule.liveSource()), description, listOf(QualifiedEdit(before, after, file)))

    private fun QualifiedSource.replayed(candidate: QualifiedCandidate) = replays.await(test(candidate.id).id, 30.0)

    private fun QualifiedSource.applyCandidate(c: QualifiedCandidate) = apply(c.id, c.baseRevision, c.baseSourceHash)

    @Test fun aCandidateIsAdmittedOnlyAfterACompleteCurrentReplay() {
        val q = Setup().open()
        val c = q.edit("Note where the tiers come from", "epic 4", "// tiers by doors from the spawn\nepic 4")
        assertContains(assertFailsWith<StudyRejection> { q.applyCandidate(c) }.message!!, "not been replayed")
        assertEquals(q.rule.revision().source, rule, "nothing is published by preparing a candidate")

        val job = q.replayed(c)
        assertEquals(StudyBuildStatus.SUCCEEDED, job.status, job.message)
        val report = assertNotNull(q.reportOf(job))
        assertEquals(setOf("crypt", "keep"), report.cases.map { it.fixture }.toSet())
        assertTrue(report.cases.all { it.verdict == QualifiedVerdict.SAME })
        assertTrue(report.control.passed && report.control.failed)
        assertNull(q.admissionProblem(c.id))

        val revision = q.applyCandidate(c)
        assertEquals(c.source, q.source())
        assertEquals(c.id, revision.facts["candidate"])
        assertEquals(job.id, revision.facts["replay"])
        assertContains(assertFailsWith<StudyRejection> { q.applyCandidate(c) }.message!!, "The rule changed since this candidate was made")
    }

    @Test fun theCandidateIdCoversEverythingItWasMadeWith() {
        val q = Setup().open()
        val c = q.edit("Rename", "common 0", "common 0\n// plain")
        assertEquals(64, c.id.length)
        assertEquals(c.id, q.loadCandidate(c.id).id)
        assertEquals(q.rule.currentId(), c.baseRevision)
        assertEquals(setOf("crypt", "keep"), c.fixtures.keys)
        assertEquals("placer-v1", c.dependencies["placer"])
        assertEquals(setOf("after # checks"), c.checks.keys)
        val file = File(q.directory, "candidates/${c.id}.json")
        file.writeText(file.readText().replace("common 0", "common 1"))
        assertContains(assertFailsWith<IllegalStateException> { q.loadCandidate(c.id) }.message!!, "changed on disk")
    }

    @Test fun aHeldReplayIsNotAdmittedAndCannotBeRebaselined() {
        val q = Setup().open()
        val c = q.edit("Epic from two doors", "epic 4", "epic 2")
        val job = q.replayed(c)
        assertEquals(StudyBuildStatus.FAILED, job.status)
        assertEquals("replay-held", job.problem?.code)
        assertContains(job.message, "yard has epic loot 2 doors from the spawn")
        assertContains(assertFailsWith<StudyRejection> { q.applyCandidate(c) }.message!!, "is not admitted")
        assertContains(assertFailsWith<StudyRejection> { q.proposeRebaseline(job.id) }.message!!, "held")
        assertEquals(rule, q.source())
    }

    @Test fun theMustFailControlMustFailAndForTheStatedReason() {
        // These checks never look for an epic room, so the impossible level passes: the checks are not running.
        val weak = "epic 4\nrare 2\ncommon 0\n# checks\nepic needs 3\n"
        val q = Setup(initial = weak).open()
        val c = q.edit("Comment", "common 0", "common 0\n// x")
        val job = q.replayed(c)
        assertEquals("replay-held", job.problem?.code)
        assertContains(job.message, "The control no-deep-room passed")
        assertFalse(q.reportOf(job)!!.control.failed)
        assertContains(q.admissionProblem(c.id)!!, "Its latest replay is failed")

        // A control that fails, but not with the message it names, does not count either.
        val other = Setup(control = QualifiedControl("no-deep-room", "hall 1, store 2", "unreachable")).open()
        val d = other.edit("Comment", "common 0", "common 0\n// x")
        val result = other.reportOf(other.replayed(d))!!.control
        assertTrue(result.failed); assertFalse(result.passed)
        assertNotNull(other.admissionProblem(d.id))
    }

    @Test fun changedDependenciesFixturesOrChecksRevokeAdmission() {
        val setup = Setup()
        val q = setup.open()
        val c = q.edit("Comment", "common 0", "common 0\n// x")
        assertEquals(StudyBuildStatus.SUCCEEDED, q.replayed(c).status)
        setup.dependency.set("placer-v2")
        assertContains(q.admissionProblem(c.id)!!, "dependencies changed: placer")
        assertContains(assertFailsWith<StudyRejection> { q.test(c.id) }.message!!, "Make a new candidate")
        assertContains(assertFailsWith<StudyRejection> { q.applyCandidate(c) }.message!!, "placer")
        setup.dependency.set("placer-v1")
        assertNull(q.admissionProblem(c.id), "the same dependencies make the same replay current again")
    }

    @Test fun markerPolicyKeepsTheChecksOutOfReach() {
        val setup = Setup()
        val q = setup.open()
        assertContains(assertFailsWith<StudyRejection> { q.edit("Relax", "epic needs 3", "epic needs 1") }.message!!, "after '# checks'")
        assertContains(assertFailsWith<StudyRejection> { q.edit("Drop marker", "# checks\n", "") }.message!!, "exactly once")
        assertContains(assertFailsWith<StudyRejection> { q.edit("Two markers", "common 0", "common 0\n# checks") }.message!!, "exactly once")

        // An edit to the checks made outside apply blocks candidates until a person accepts it.
        setup.ruleText.text = rule.replace("epic needs 3", "epic needs 2")
        assertContains(q.checksNotice()!!, "edited outside loot.apply")
        assertContains(assertFailsWith<StudyRejection> { q.edit("Comment", "common 0", "common 0\n// x") }.message!!, "edited outside")
        currentToolArgs.set(emptyMap())
        try { assertContains(assertFailsWith<StudyRejection> { q.upgradeChecks("agent", "relax") }.message!!, "Only a person") }
        finally { currentToolArgs.remove() }
        val done = q.upgradeChecks("marc", "two doors is far enough")
        assertEquals(q.rule.currentId(), done["rule"])
        assertNull(q.checksNotice())
        assertEquals("marc", q.rule.revision().facts["checksUpgradedBy"])
    }

    @Test fun filesPolicyLetsOnlyTheNamedFilesChange() {
        val bundle = """{"audit": "epic needs 3\nsome epic", "tiers": "epic 4\nrare 2\ncommon 0\n"}"""
        val q = Setup(frozen = listOf(FrozenRegion.files("tiers", "notes")), initial = bundle).open()
        assertContains(assertFailsWith<StudyRejection> { q.edit("Relax", "epic needs 3\nsome", "epic needs 1\nsome", file = "audit") }.message!!,
            "audit, which hold the checks")
        assertContains(assertFailsWith<StudyRejection> {
            q.candidate(q.rule.currentId(), SourceStudy.hash(q.rule.liveSource()), "Add", addFiles = mapOf("audit2" to "x"))
        }.message!!, "not one of the files")
        val notes = q.candidate(q.rule.currentId(), SourceStudy.hash(q.rule.liveSource()), "Add notes", addFiles = mapOf("notes" to "why"))
        assertEquals(mapOf("audit" to "epic needs 3\nsome epic"), notes.checks)
        val c = q.edit("Comment", "common 0", "common 0\n// x", file = "tiers")
        assertEquals(StudyBuildStatus.SUCCEEDED, q.replayed(c).status)
        q.applyCandidate(c)
        assertContains(bundle(q.source()).getValue("tiers"), "// x")
    }

    @Test fun baselinePolicyJudgesWithAFrozenCopyUpgradedOnlyExplicitly() {
        val audit = AtomicReference("epic needs 3\nsome epic")
        val setup = Setup(frozen = listOf(FrozenRegion.baseline { mapOf("audit" to audit.get()) }), initial = "epic 4\nrare 2\ncommon 0\n", checksFromCopy = true)
        val q = setup.open()
        assertEquals(mapOf("audit" to "epic needs 3\nsome epic"), q.currentChecks())
        val c = q.edit("Epic from two doors", "epic 4", "epic 2")
        assertEquals("replay-held", q.replayed(c).problem?.code, "the frozen copy holds a rule with no checks of its own")

        // The checks' source moves on; the copy does not follow until it is upgraded, and says it is stale.
        audit.set("epic needs 2\nsome epic")
        assertEquals("epic needs 3\nsome epic", q.currentChecks()["audit"])
        assertContains(q.checksNotice()!!, "frozen copy of the checks differs from its source (audit)")
        assertContains(q.inspect()["checksNotice"].toString(), "audit")
        val before = q.checksCopy!!.currentId()
        q.upgradeChecks("marc", "two doors is far enough")
        assertNotEquals(before, q.checksCopy.currentId())
        assertEquals("epic needs 2\nsome epic", q.currentChecks()["audit"])
        assertNull(q.checksNotice())
        assertContains(q.admissionProblem(c.id)!!, "checks changed")
        val d = q.edit("Epic from two doors", "epic 4", "epic 2")
        assertEquals(mapOf("audit" to "epic needs 2\nsome epic"), d.checks)
        assertContains(assertFailsWith<StudyRejection> { q.upgradeChecks("marc", "again") }.message!!, "already current")

        // The copy survives a reopen, and is never refrozen from its source.
        q.close()
        audit.set("epic needs 9")
        val reopened = setup.open()
        assertEquals("epic needs 2\nsome epic", reopened.currentChecks()["audit"])
    }

    @Test fun onlyAPersonChangesARecordedOutcome() {
        val q = Setup().open()
        val c = q.edit("Three doors is deep enough for epic", "epic 4", "epic 3")
        val job = q.replayed(c)
        assertEquals("replay-changed", job.problem?.code, job.message)
        assertEquals(listOf("crypt"), q.reportOf(job)!!.cases.filter { it.verdict == QualifiedVerdict.CHANGED }.map { it.fixture })

        val proposal = q.proposeRebaseline(job.id)
        assertEquals(listOf("crypt"), proposal.entries.map { it.fixture })
        assertEquals(listOf(QualifiedChange("/ossuary", "rare", "epic")), proposal.entries.single().changes)
        assertEquals("rare", (q.corpus().getValue("crypt").outcome as Map<*, *>)["ossuary"], "proposing changes nothing")
        assertEquals(proposal.id, q.proposeRebaseline(job.id).id, "the same replay proposes the same thing")

        // No tool path can approve: inside a tool call the approval is refused, and the corpus refuses edits made around it.
        currentToolArgs.set(emptyMap())
        try { assertContains(assertFailsWith<StudyRejection> { q.approve(proposal.id, "crypt", "agent", "tool") }.message!!, "Only a person") }
        finally { currentToolArgs.remove() }
        val live = q.fixtures.liveSource()
        assertContains(assertFailsWith<StudyRejection> {
            q.fixtures.edit(q.fixtures.currentId(), SourceStudy.hash(live), live.replace("\"rare\"", "\"epic\""), "by hand")
        }.message!!, "only by a person's approval")

        // The person presses the Approve button the proposal drew.
        q.refresh()
        assertContains(shown.getValue("loot-approval"), "Approve the new outcome of crypt")
        val button = q.approveButtons().getValue(proposal.id to "crypt")
        assertNotNull(getButtonCallback(button)).invoke(true)
        val approved = q.corpus().getValue("crypt")
        assertEquals("epic", (approved.outcome as Map<*, *>)["ossuary"])
        val approval = approved.approvals.single()
        assertEquals(QualifiedSource.personName(), approval.approvedBy)
        assertEquals(proposal.id, approval.proposal)
        assertTrue(approval.approvedAt.isNotBlank())
        assertContains(q.fixtures.revision().description, "approved a new outcome for crypt")
        q.refresh()
        assertContains(shown.getValue("loot-approval"), "approved by ${approval.approvedBy}")
        assertTrue(q.pendingProposals().isEmpty())
        assertContains(assertFailsWith<StudyRejection> { q.approve(proposal.id, "crypt", "marc", "again") }.message!!, "already approved")

        // The old candidate was made with the old outcomes; a copy made now replays clean and applies.
        assertContains(q.admissionProblem(c.id)!!, "fixtures changed")
        val again = q.candidate(q.rule.currentId(), SourceStudy.hash(q.rule.liveSource()), c.description, fromCandidate = c.id)
        assertEquals(c.source, again.source)
        assertEquals(StudyBuildStatus.SUCCEEDED, q.replayed(again).status)
        q.applyCandidate(again)
        assertContains(q.source(), "epic 3")
    }

    @Test fun aProposalWaitsAcrossAReopen() {
        val setup = Setup()
        val q = setup.open()
        val job = q.replayed(q.edit("Three doors", "epic 4", "epic 3"))
        val proposal = q.proposeRebaseline(job.id)
        q.close()
        val reopened = setup.open()
        assertEquals(listOf(proposal.id), reopened.pendingProposals().map { it.id })
        assertEquals(q.rule.currentId(), reopened.rule.currentId())
        assertEquals(setOf("crypt", "keep"), reopened.corpus().keys)
    }

    @Test fun retainedFixturesComeFromFinishedBuildsAndNeverOverwrite() {
        val q = Setup(retainFrom = { s -> QualifiedFixture(s.file("level.txt").readText(), mapOf("placed" to s.record.facts["placed"])) }).open()
        StudyBuilds(temp(), listOf(writing("place", "level", "level.txt") { mapOf("placed" to it.length) }), { mapOf("level" to "hall 1, vault 5") }).use { level ->
            runners["level"] = level
            val job = level.build("place")
            val revision = q.fixtures.currentId()
            val hash = SourceStudy.hash(q.fixtures.liveSource())
            assertContains(assertFailsWith<StudyRejection> { q.retainFixture("level", job.id, "vault", revision, "stale") }.message!!, "Conflict")
            assertContains(assertFailsWith<StudyRejection> { q.retainFixture("level", job.id, "no-deep-room", revision, hash) }.message!!, "control")
            val kept = q.retainFixture("level", job.id, "vault", revision, hash)
            assertEquals(false, kept["alreadyPresent"])
            val case = q.corpus().getValue("vault")
            assertEquals("hall 1, vault 5", case.input)
            assertEquals(mapOf("placed" to 15), case.outcome)
            assertEquals(job.id, case.retained["jobId"])
            assertEquals(SourceStudy.hash("hall 1, vault 5"), case.retained["inputSha256"])
            val now = q.fixtures.currentId()
            val nowHash = SourceStudy.hash(q.fixtures.liveSource())
            assertEquals(true, q.retainFixture("level", job.id, "vault", now, nowHash)["alreadyPresent"])
            assertContains(assertFailsWith<StudyRejection> { q.retainFixture("level", job.id, "keep", now, nowHash) }.message!!, "already holds another case")
        }
        val bare = Setup().open()
        assertContains(assertFailsWith<StudyRejection> { bare.retainFixture("level", "x", "y", "r", "h") }.message!!, "no way to retain")
    }

    @Test fun theFixturesRefuseOperationsAfterAnEditOutsideTheTools() {
        val setup = Setup()
        val q = setup.open()
        setup.fixtureText.text = setup.fixtureText.text.replace("\"rare\"", "\"epic\"")
        assertContains(assertFailsWith<StudyRejection> { q.edit("Comment", "common 0", "common 0\n// x") }.message!!, "edited outside")
        assertEquals(true, (q.inspect()["fixtures"] as Map<*, *>)["editedOutsideTools"])
        assertEquals("rare", (q.corpus().getValue("crypt").outcome as Map<*, *>)["ossuary"], "the checked corpus is what replays read")
    }

    @Test fun theToolsAreTheSixAndNoneApproves() {
        val q = Setup().open()
        val box = Box("qualified-tools")
        try {
            val tools = box.registerQualifiedTools(q)
            assertEquals(listOf("loot.inspect", "loot.candidate", "loot.test", "loot.apply", "loot.retainFixture", "loot.proposeRebaseline"), tools.map { it.name })
            assertTrue(tools.none { "approve" in it.name.lowercase() || "upgrade" in it.name.lowercase() })
            assertContains(tools.last().description, "You cannot approve")
        } finally { afield.ai.tools.DocumentToolRegistry.clearBox(box) }
    }

    @Test fun theReplayCardShowsEachFixtureAndTheControl() {
        val q = Setup().open()
        q.replayed(q.edit("Three doors", "epic 4", "epic 3"))
        q.refresh()
        val changed = shown.getValue("loot")
        assertContains(changed, "Outcomes changed · needs a person")
        assertContains(changed, "<td class=\"q-warn\">changed</td>")
        assertContains(changed, "ossuary: rare → epic")
        assertContains(changed, "failed, as it must")
        assertContains(changed, "no room has epic loot")

        // Epic from two doors puts epic loot in the control's store, so it fails, but not as it must.
        q.replayed(q.edit("Epic from two doors", "epic 4", "epic 2"))
        q.refresh()
        val held = shown.getValue("loot")
        assertContains(held, ">Held<")
        assertContains(held, "yard has epic loot 2 doors from the spawn")
        assertContains(held, "failed otherwise")
    }
}
