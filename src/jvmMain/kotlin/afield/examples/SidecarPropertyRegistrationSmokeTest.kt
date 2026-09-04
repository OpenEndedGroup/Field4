package afield.examples

import afield.Box
import afield.SavingContext
import afield.SessionInformation
import afield.thisSessionInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/*
FIELD_TEST
id: examples.saving.sidecar-property-registration
runner: jvm-main
entrypoint: afield.examples.SidecarPropertyRegistrationSmokeTest
tags: [smoke, saving, persistence, sidecar]
timeoutSec: 40
expect:
  exitCode: 0
  stdoutContains:
    - "SidecarPropertyRegistrationSmokeTest ok"
*/

/**
 * Characterization guard for the two-list contract behind sidecar files, and for the specific
 * decision that `remote_js` is **not** a box property.
 *
 * A text property needs entries in *two* registries to survive a save:
 *  - [Box.ioPersistentProperties] decides **whether** it is written. `SavingContext.toTransfer`
 *    and `snapshotBoxes` filter on it *first* and drop anything that does not match.
 *  - [Box.ioSeparateProperties] only decides **where** — the `<boxId>_<property>_.<ext>` sidecar
 *    filename instead of an inline value in `metadata.edn`.
 *
 * Registering only the second silently discards the property on save: the sidecar branch is never
 * reached, so no file is written and nothing lands in `metadata.edn` either. That happened once for
 * `msl` (see the note in KotlinJsHeadProfilesSmokeTest) and it was true of `remote_js` from
 * 52a1d563e (2025-11-03), the first commit carrying Boxes.kt, until this test was written.
 *
 * `remote_js` was never a property at all. It is the *name* the RemoteJS/WebXR language-root box
 * carries — `"name" "remote_js"` beside `mainLanguage = remotejs` on box c04566d0 in
 * templates/remote/metadata.json. That root keeps its text in `code`; its children keep their
 * Javascript in `code` and `customization`. Measured across ~/Documents/AFieldWorkspace on
 * 2026-09-04, over the 401 directories holding a metadata.edn: 5 of them name a box `remote_js`,
 * and across the tree there are 3847 `*_code_.kt` sidecars, 354 `*_customization_.js`, and zero
 * `*_remote_js_.js`. It was absent from [Box.ioPersistentProperties] and
 * [Box.uiResyncProperties] too, so it could not have round-tripped even if something had set it.
 * The registration was removed rather than completed; this test pins that direction so it is not
 * reintroduced by symmetry-seeking.
 */
object SidecarPropertyRegistrationSmokeTest {

    private const val DEAD_PROPERTY = "remote_js"

    @JvmStatic
    fun main(args: Array<String>) {
        verifyEverySidecarPropertyCanBeWritten()
        verifyRemoteJsIsNotRegisteredAnywhere()
        verifySerializationKeepsCustomizationAndDropsAnUnregisteredProperty()
        println("SidecarPropertyRegistrationSmokeTest ok")
    }

    /**
     * The invariant, stated over the whole registry rather than over one property: a sidecar
     * filename is useless if the property is filtered out before the sidecar branch runs.
     */
    private fun verifyEverySidecarPropertyCanBeWritten() {
        val persistent = Box.ioPersistentProperties.map { it.toRegex() }
        val orphans = Box.ioSeparateProperties.keys.filterNot { property ->
            persistent.any { property.matches(it) }
        }
        require(orphans.isEmpty()) {
            "these properties name a sidecar file but are not in Box.ioPersistentProperties, so a " +
                "save drops them before the sidecar is ever written: $orphans — either add a " +
                "matching ioPersistentProperties pattern, or remove the ioSeparateProperties entry"
        }
        require(Box.ioSeparateProperties.isNotEmpty() && persistent.isNotEmpty()) {
            "the registries are empty, so this test is asserting nothing"
        }
        println(
            "every sidecar property is persistent ✓ " +
                "(${Box.ioSeparateProperties.size} sidecar keys vs ${persistent.size} persistent patterns)"
        )
    }

    private fun verifyRemoteJsIsNotRegisteredAnywhere() {
        require(!Box.ioSeparateProperties.containsKey(DEAD_PROPERTY)) {
            "$DEAD_PROPERTY is a box *name*, not a property, and must not claim a sidecar extension"
        }
        require(Box.ioPersistentProperties.none { it.toRegex().matches(DEAD_PROPERTY) }) {
            "$DEAD_PROPERTY must not be persistent; nothing reads or writes such a property"
        }
        require(Box.uiResyncProperties.none { it.matches(DEAD_PROPERTY) }) {
            "$DEAD_PROPERTY must not resync; there is no editor pane behind it"
        }
        // `customization` is the live Javascript property `remote_js` was mistaken for, and it is
        // registered in all three. Asserting it here keeps the test honest: the three checks above
        // pass trivially for any misspelling, so pin that the real property is fully wired.
        require(Box.ioSeparateProperties["customization"] == "js") {
            "customization must still save as a .js sidecar, got ${Box.ioSeparateProperties["customization"]}"
        }
        require(Box.ioPersistentProperties.any { it.toRegex().matches("customization") }) {
            "customization must be persistent"
        }
        require(Box.uiResyncProperties.any { it.matches("customization") }) {
            "customization must resync"
        }
        println("remote_js is registered nowhere; customization is registered everywhere ✓")
    }

    /**
     * The consequence, through the real serialization path. `toTransfer` (saving.kt) and
     * `snapshotBoxes` share the same shape: filter on [Box.ioPersistentProperties] first, *then*
     * consult [Box.ioSeparateProperties] for the sidecar branch. So an unregistered property is
     * dropped before the sidecar branch is reached — no file, and nothing in the EDN either.
     *
     * Both calls here are synchronous. `SavingContext.save` finishes its I/O on a background thread
     * and reports through the runloop, which a `jvm-main` entrypoint does not pump, so driving the
     * assertion through `save`'s `onComplete` hangs until the harness timeout.
     */
    private fun verifySerializationKeepsCustomizationAndDropsAnUnregisteredProperty() {
        val workspace = Files.createTempDirectory("afield-sidecar-registration").toFile()
        val document = File(workspace, "doc").also { it.mkdirs() }
        File(document, SavingContext.METADATA_EDN).writeText("{:version 2 :boxes []}\n")

        val root = Box("root")
        val session = SessionInformation(
            filename = document.absolutePath,
            documentName = document.name,
            boxes = ConcurrentHashMap(),
            root = root,
            ephemeral = true
        )
        root.inside = session
        session.boxes[root.id] = root
        thisSessionInfo.set(session)

        try {
            val box = Box("registrationbox")
            box.inside = session
            session.boxes[box.id] = box
            box.setPropertyRaw("name", "registration box", doResync = false)
            box.setPropertyRaw("customization", "// a live javascript property", doResync = false)
            box.setPropertyRaw(DEAD_PROPERTY, "// never persisted", doResync = false)
            require(box.getPropertyRaw(DEAD_PROPERTY) != null) {
                "the test needs $DEAD_PROPERTY actually set on the box for the drop to mean anything"
            }

            // The sidecar-writing path: `toTransfer` writes each sidecar as it goes.
            val transfer = SavingContext().toTransfer(setOf(box), document.absolutePath)
                .single { it.id == box.id }
            require("customization" in transfer.properties) {
                "customization should have been serialized, got ${transfer.properties.keys}"
            }
            require(DEAD_PROPERTY !in transfer.properties) {
                "$DEAD_PROPERTY survived the ioPersistentProperties filter: ${transfer.properties.keys}"
            }

            val written = document.listFiles()?.map { it.name }?.sorted() ?: emptyList()
            require("registrationbox_customization_.js" in written) {
                "expected a customization sidecar on disk, got $written"
            }
            require(written.none { it.endsWith("_${DEAD_PROPERTY}_.js") }) {
                "a $DEAD_PROPERTY sidecar was written; it is not a property: $written"
            }

            // The inline path, which is what `metadata.edn` and `document.md` are built from.
            val edn = SavingContext().exportEdnPackage(setOf(box), document.absolutePath)
            require("\"customization\"" in edn) { "customization missing from the exported EDN" }
            require("\"$DEAD_PROPERTY\"" !in edn) {
                "$DEAD_PROPERTY leaked into the exported EDN as an inline property"
            }

            println("serialization writes customization, drops $DEAD_PROPERTY \u2713")
        } finally {
            thisSessionInfo.remove()
            workspace.deleteRecursively()
        }
    }
}
