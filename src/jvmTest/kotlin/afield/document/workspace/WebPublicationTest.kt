package afield.document.workspace

import afield.app.server.SessionInformation
import afield.document.graph.Box
import afield.document.persistence.SavingContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebPublicationTest {
    @Test
    fun publicationReadsLatestOwnedOutputsAndRootWithoutExecutingOrWriting() {
        val directory = Files.createTempDirectory("field-web-publication-").toFile()
        try {
            val root = Box("root")
            val info = SessionInformation(directory.path, "publication", root = root, ephemeral = true)
            root.inside = info
            root.setPropertyRaw("drawing", "{\"elements\":[]}", doResync = false)
            val box = Box("shared-id").also { it.inside = info; info.boxes[it.id] = it }
            box.setPropertyRaw("name", "Publication", doResync = false)
            box.setPropertyRaw("code", "error(\"publication must not execute this\")", doResync = false)
            box.output.retainedAreas.put("value", "old")
            box.output.retainedAreas.put("value", "latest")
            box.output.retainedAreas.put("removed", "obsolete")
            box.output.retainedAreas.control("clear", "removed")
            root.output.retainedAreas.put("root-output", "root content")
            val unrelated = Box("shared-id")
            unrelated.output.retainedAreas.put("value", "another document")

            val result = exportWebDocument(info)
            assertEquals(listOf("latest", "root content"), result.outputs.map { it.value })
            assertEquals(listOf("shared-id", "root"), result.outputs.map { it.uid })
            val restored = SavingContext().readEdnTransfersFromString(result.document.edn)
            assertEquals(setOf("root", "shared-id"), restored.map { it.id }.toSet())
            assertEquals("{\"elements\":[]}", restored.single { it.id == "root" }.properties["drawing"])
            assertTrue(result.document.edn.contains("publication must not execute this"))
            assertTrue(directory.listFiles().isNullOrEmpty(), "Publication wrote into the author document")
            assertTrue(result.assets.contains("offline-outputs.js"))
            assertTrue(result.assets.contains("min/vs/base/worker/workerMain.js"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
