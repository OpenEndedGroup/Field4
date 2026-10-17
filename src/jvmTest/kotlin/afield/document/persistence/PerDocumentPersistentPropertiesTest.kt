package afield.document.persistence

import afield.document.graph.Box
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A document's `:persistentProperties` header belongs to that document. Reading one used to add its
 * names to the process-global [Box.ioPersistentProperties], so every other open document persisted
 * them too, until the process exited.
 */
class PerDocumentPersistentPropertiesTest {
    private fun document(header: String): String {
        val dir = Files.createTempDirectory("afield-persistent-props").toFile()
        dir.resolve("metadata.edn").writeText(
            """{:version 2, :persistentProperties [$header], :boxes [{:id "b1", :properties {"name" "b1"}}]}"""
        )
        return dir.path
    }

    @Test
    fun `one document's header does not change what another saves`() {
        val name = "perDocumentProbe_${System.nanoTime()}"
        val withHeader = document("\"frame\" \"$name\"")
        val without = document("\"frame\"")
        val ctx = SavingContext()
        ctx.readEdnTransfers(withHeader)
        ctx.readEdnTransfers(without)

        assertFalse(name in Box.ioPersistentProperties, "a loaded header leaked into the global set")
        assertTrue(name in Box.persistentPropertiesFor(withHeader))
        assertFalse(name in Box.persistentPropertiesFor(without))

        val box = Box(id = "probe-box")
        box.properties[name] = "kept"
        assertTrue(name in ctx.toTransfer(setOf(box), withHeader).single().properties,
            "the document that declared the name must keep saving it")
        assertFalse(name in ctx.toTransfer(setOf(box), without).single().properties,
            "a document that never declared the name must not save it")
    }
}
