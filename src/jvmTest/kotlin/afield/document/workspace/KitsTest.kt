package afield.document.workspace

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.r
import afield.data.collections.s
import afield.data.collections.times
import afield.document.graph.minusAssign
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.graphics.render.rangeTo
import afield.graphics.vulkan.buffers.add
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.linalg.x
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.runtime.lifecycle.once

import afield.animation.keyframes.h
import afield.data.graph.rules
import afield.geometry.mesh.summary
import afield.graphics.render.label
import afield.lang.stacksl.h
import afield.math.linalg.i
import afield.study.source.SourceStudy
import afield.ui.output.h
import afield.document.graph.Box
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * The three-way rule a migration acts on: for each property and asset, the document's record, the
 * document and the kit. `tools/test_field_kit.py` covers what `field_kit.py` does with the result.
 */
class KitsTest {
    private val mapper = jacksonObjectMapper()
    private fun temp(): File = Files.createTempDirectory("kits-").toFile()
    private fun h(text: String) = SourceStudy.hash(text)

    /** A kit at [dir] whose `rules` role installs [code] and a frozen copy [audit], with the asset palette.json = [palette]. */
    private fun kit(dir: File, revision: String, code: String, audit: String = "{}", palette: String = "red"): File {
        dir.mkdirs()
        File(dir, "kit.json").writeText(mapper.writeValueAsString(mapOf("schema" to "field.kit.v1", "name" to "rooms",
            "launchOrder" to listOf("rules"),
            "roles" to mapOf("rules" to mapOf("name" to "Rules", "properties" to mapOf(
                "code" to mapOf("file" to "roles/rules/code.kt", "sha256" to h(code)),
                "rulesAudit" to mapOf("file" to "roles/rules/audit.json", "sha256" to h(audit), "frozen" to true))),
                "plan" to mapOf("name" to "Plan", "properties" to mapOf("code" to mapOf("file" to "roles/plan/code.kt", "sha256" to h("plan"))))),
            "assets" to mapOf("palette.json" to h(palette)), "revision" to revision)))
        return dir
    }

    /** Record [kitDir]'s manifest in [doc] as the document's own, as field_kit.py does. */
    private fun record(doc: File, kitDir: File) {
        val manifest = mapper.readValue(File(kitDir, "kit.json"), Map::class.java) + ("directory" to kitDir.absolutePath)
        File(doc, Kits.RECORD).apply { parentFile.mkdirs() }.writeText(mapper.writeValueAsString(manifest))
    }

    private class Doc(val dir: File, val rules: Box, val plan: Box) { val boxes get() = listOf(rules, plan) }

    /** A document on revision r1 of a kit, holding exactly the kit's copy. */
    private fun onKit(): Pair<Doc, File> {
        val root = temp()
        val kitDir = kit(File(root, "kit"), "r1", "val rules = 1")
        val doc = File(root, "doc").apply { mkdirs() }
        File(doc, "files/palette.json").apply { parentFile.mkdirs() }.writeText("red")
        record(doc, kitDir)
        val rules = Box("rules-box").apply { properties["kitRole"] = "rules"; properties["code"] = "val rules = 1"; properties["rulesAudit"] = "{}" }
        val plan = Box("plan-box").apply { properties["kitRole"] = "plan"; properties["code"] = "plan" }
        return Doc(doc, rules, plan) to kitDir
    }

    private fun state(i: KitInspection, label: String) = i.items.single { it.label == label }.state

    @Test fun aDocumentHoldingItsKitsCopyIsCurrentAndSaysWhichKitItRuns() {
        val (doc, _) = onKit()
        val inspection = Kits.inspect(doc.boxes, doc.dir)
        assertEquals("rooms", inspection.kit)
        assertEquals("r1", inspection.recordedRevision)
        assertTrue(inspection.items.all { it.state == KitItemState.CURRENT }, inspection.items.toString())
        assertEquals(4, inspection.items.size)
        assertEquals(emptyList(), inspection.migrationRefusals)
        assertContains(inspection.summary(), "runs kit 'rooms' at revision r1")
        assertContains(inspection.summary(), "Nothing differs from the kit")
    }

    @Test fun aNewerKitIsOnlyADifferenceUntilAMigrationInstallsIt() {
        val (doc, kitDir) = onKit()
        kit(kitDir, "r2", "val rules = 2", palette = "blue")
        val inspection = Kits.inspect(doc.boxes, doc.dir)
        assertEquals("r2", inspection.kitRevision)
        assertEquals(KitItemState.KIT_CHANGED, state(inspection, "rules · code"))
        assertEquals(KitItemState.KIT_CHANGED, state(inspection, "palette.json"))
        assertEquals(KitItemState.CURRENT, state(inspection, "plan · code"))
        assertEquals(emptyList(), inspection.migrationRefusals)
        // Inspecting changed nothing: the document still holds, and records, its own copy.
        assertEquals("val rules = 1", doc.rules.properties["code"])
        assertEquals("red", File(doc.dir, "files/palette.json").readText())
        assertEquals("r1", Kits.record(doc.dir)!!.revision)
    }

    @Test fun anEditMatchingNeitherTheRecordNorTheKitIsTheDocumentsOwnAndAMigrationRefusesIt() {
        val (doc, kitDir) = onKit()
        kit(kitDir, "r2", "val rules = 2")
        doc.rules.properties["code"] = "val rules = 7 // mine"
        File(doc.dir, "files/palette.json").writeText("green")
        val inspection = Kits.inspect(doc.boxes, doc.dir)
        assertEquals(KitItemState.DOCUMENT_EDITED, state(inspection, "rules · code"))
        assertEquals(KitItemState.DOCUMENT_EDITED, state(inspection, "palette.json"))
        assertEquals(2, inspection.migrationRefusals.size)
        assertContains(inspection.migrationRefusals.first(), "Rules · code was edited in this document")
        // Even without a newer kit, an edit is a difference from the kit.
        kit(kitDir, "r1", "val rules = 1")
        assertEquals(KitItemState.DOCUMENT_EDITED, state(Kits.inspect(doc.boxes, doc.dir), "rules · code"))
    }

    @Test fun anEditThatAlreadyMatchesTheNewKitIsCurrent() {
        val (doc, kitDir) = onKit()
        kit(kitDir, "r2", "val rules = 2")
        doc.rules.properties["code"] = "val rules = 2"
        assertEquals(KitItemState.CURRENT, state(Kits.inspect(doc.boxes, doc.dir), "rules · code"))
    }

    @Test fun aFrozenCopyThatDiffersFromTheKitIsReplacedNotRefused() {
        val (doc, kitDir) = onKit()
        // A provider wrote the audit copy once, from an older kit, and nothing has updated it since.
        doc.rules.properties["rulesAudit"] = "{\"old\": true}"
        val same = Kits.inspect(doc.boxes, doc.dir)
        assertEquals(KitItemState.FROZEN_STALE, state(same, "rules · rulesAudit"))
        assertEquals(emptyList(), same.migrationRefusals, "a frozen copy is the kit's to replace")
        kit(kitDir, "r2", "val rules = 1", audit = "{\"new\": true}")
        assertEquals(KitItemState.FROZEN_STALE, state(Kits.inspect(doc.boxes, doc.dir), "rules · rulesAudit"))
        doc.rules.properties["rulesAudit"] = "{\"new\": true}"
        assertEquals(KitItemState.CURRENT, state(Kits.inspect(doc.boxes, doc.dir), "rules · rulesAudit"))
    }

    @Test fun aMissingRoleBoxIsInstalledWhenTheKitAddsItAndRefusedWhenTheDocumentDeletedIt() {
        val (doc, kitDir) = onKit()
        val withoutPlan = listOf(doc.rules)
        assertEquals(KitItemState.DOCUMENT_EDITED, state(Kits.inspect(withoutPlan, doc.dir), "plan · code"))
        assertContains(Kits.inspect(withoutPlan, doc.dir).migrationRefusals.single(), "Plan · code was removed in this document")
        // Before the kit is installed there is no record, so every absent property is the kit's to add.
        File(doc.dir, Kits.RECORD).delete()
        File(doc.dir, "files/palette.json").delete()
        val fresh = Kits.inspect(emptyList(), doc.dir, kitDir)
        assertNull(fresh.recordedRevision)
        assertTrue(fresh.items.filter { !it.frozen }.all { it.state == KitItemState.KIT_CHANGED }, fresh.items.toString())
    }

    @Test fun twoBoxesClaimingOneRoleStopAMigration() {
        val (doc, _) = onKit()
        val twin = Box("twin").apply { properties["kitRole"] = "rules"; properties["code"] = "val rules = 1" }
        val inspection = Kits.inspect(doc.boxes + twin, doc.dir)
        assertContains(inspection.problems.single(), "2 boxes play role 'rules'")
        assertEquals(inspection.problems, inspection.migrationRefusals.take(1))
    }

    @Test fun aRoleOrAssetTheKitDroppedIsLeftAlone() {
        val (doc, kitDir) = onKit()
        val manifest = mapper.readValue(File(kitDir, "kit.json"), Map::class.java).toMutableMap()
        manifest["assets"] = emptyMap<String, String>()
        File(kitDir, "kit.json").writeText(mapper.writeValueAsString(manifest))
        val inspection = Kits.inspect(doc.boxes, doc.dir)
        assertEquals(KitItemState.REMOVED_FROM_KIT, state(inspection, "palette.json"))
        assertEquals(emptyList(), inspection.migrationRefusals)
    }

    @Test fun aKitThatMovedIsReportedAndTheRecordStillComparesTheDocument() {
        val (doc, kitDir) = onKit()
        kitDir.deleteRecursively()
        doc.rules.properties["code"] = "val rules = 7"
        val inspection = Kits.inspect(doc.boxes, doc.dir)
        assertFalse(inspection.kitFound)
        assertContains(inspection.problems.single(), "The kit is not at")
        assertEquals(KitItemState.DOCUMENT_EDITED, state(inspection, "rules · code"))
    }

    @Test fun aDocumentNotOnAKitSaysSo() {
        val inspection = Kits.inspect(listOf(Box("lonely")), temp())
        assertNull(inspection.kit)
        assertEquals("This document is not on a kit.", inspection.summary())
    }

    @Test fun declaringAKitHasFieldSaveItsSourcesAsSidecars() {
        val (doc, kitDir) = onKit()
        Kits.inspect(doc.boxes, doc.dir)
        assertTrue("rulesAudit" in afield.document.graph.Box.ioPersistentProperties)
        assertEquals("json", afield.document.graph.Box.ioSeparateProperties["rulesAudit"])
        assertTrue("kitRole" in afield.document.graph.Box.ioPersistentProperties)
        assertFalse(Kits.read(kitDir).roles.getValue("rules").properties.getValue("code").frozen)
    }

    @Test fun theCardNamesTheKitAndEachDifference() {
        val (doc, kitDir) = onKit()
        kit(kitDir, "r2", "val rules = 2")
        File(doc.dir, "files/palette.json").writeText("green")
        val html = kitCardHtml(Kits.inspect(doc.boxes, doc.dir))
        assertContains(html, "Kit rooms")
        assertContains(html, "revision r1")
        assertContains(html, "changed in the kit")
        assertContains(html, "edited here")
        assertContains(html, "files/palette.json")
    }

    @Test fun showKitInspectsTheBoxsOwnDocument() {
        val (doc, _) = onKit()
        val info = afield.app.server.SessionInformation(doc.dir.path, "level", root = Box("root"), ephemeral = true)
        val card = Box("card")
        (doc.boxes + card).forEach { info.boxes[it.id] = it; it.inside = info }
        val shown = card.showKit()
        assertEquals("r1", shown.recordedRevision)
        assertTrue(shown.items.all { it.state == KitItemState.CURRENT })
    }

    @Test fun aManifestWithoutTheSchemaOrWithAnUnknownLaunchRoleIsRefused() {
        val dir = temp()
        File(dir, "kit.json").writeText("""{"name": "x", "revision": "r"}""")
        assertFailsWith<IllegalArgumentException> { Kits.read(dir) }
        File(dir, "kit.json").writeText("""{"schema": "field.kit.v1", "name": "x", "revision": "r", "launchOrder": ["walls"], "roles": {}}""")
        assertFailsWith<IllegalArgumentException> { Kits.read(dir) }
    }

    @Test fun aCopiedStartingPointLaunchesItsRolesInLaunchOrderOnceOnFirstOpen() {
        val (doc, kitDir) = onKit()
        File(kitDir, "kit.json").let { f ->
            val m = mapper.readValue(f, Map::class.java).toMutableMap(); m["launchOrder"] = listOf("plan", "rules")
            f.writeText(mapper.writeValueAsString(m))
        }
        record(doc.dir, kitDir)
        assertEquals(emptyList(), KitFirstOpen.rolesToLaunch(doc.dir, doc.boxes), "no marker, nothing to launch")
        File(doc.dir, KitFirstOpen.MARKER).writeText("")
        assertEquals(listOf(doc.plan, doc.rules), KitFirstOpen.rolesToLaunch(doc.dir, doc.boxes))
        assertFalse(File(doc.dir, KitFirstOpen.MARKER).exists(), "the marker is consumed")
        assertEquals(emptyList(), KitFirstOpen.rolesToLaunch(doc.dir, doc.boxes), "a second open launches nothing")
    }
}
