package afield.runtime.kotlin

import afield.app.server.SessionInformation
import afield.app.server.documents
import afield.document.graph.Box
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedTypesReadOnlyTest {
    @Test
    fun publicationRespectsExplicitAndRegisteredReadOnlySessions() {
        for (readOnly in listOf(false, true)) for (registered in listOf(false, true)) {
            val document = Files.createTempDirectory("shared-types-policy-").toFile()
            val root = Box("root")
            val session = SessionInformation(document.absolutePath, document.name, root = root, readOnly = readOnly)
            root.inside = session
            if (registered) documents[session.filename] = session
            var output: File? = null
            try {
                output = SharedTypes.publicationRoot(document, if (registered) null else session)
                if (readOnly) {
                    assertTrue(output.isDirectory)
                    assertFalse(output.toPath().startsWith(document.toPath()))
                } else {
                    assertEquals(File(document, SharedTypes.ROOT_NAME), output)
                }
                assertTrue(document.list()!!.isEmpty(), "choosing a publication destination must not write into the document")
            } finally {
                if (registered) documents.remove(session.filename, session)
                if (readOnly) output?.deleteRecursively()
                document.deleteRecursively()
            }
        }
    }
}
