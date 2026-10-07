package afield

import java.io.File
import java.nio.file.Files
import org.yaml.snakeyaml.Yaml
import kotlin.test.*

class FirstContactStartingPointTest {
    @Test fun newcomerExamplesCopyTheirCodeAndNeverOverwriteAnEditedCopy() {
        val temp = Files.createTempDirectory("first-contact-").toFile()
        val old = System.getProperty("afield.templates.dir")
        try {
            val sources = File(temp, "templates").apply { mkdirs() }
            for (name in listOf("first-steps", "first-graphics")) {
                File("templates", name).copyRecursively(File(sources, name))
            }
            System.setProperty("afield.templates.dir", sources.absolutePath)
            // Other bundled or personal templates may coexist with the two newcomer examples.
            assertTrue(templateStartingPoints().map { it.filename }.toSet().containsAll(setOf("first-steps", "first-graphics")))
            val workspace = File(temp, "workspace").apply { mkdirs() }
            for (name in listOf("first-steps", "first-graphics")) {
                val first = openStartingPointIntoWorkspace(workspace.absolutePath, name)
                assertEquals(name, first.filename)
                val copy = File(workspace, name)
                for (file in File(sources, name).listFiles().orEmpty().filter { it.isFile }) {
                    assertContentEquals(file.readBytes(), File(copy, file.name).readBytes(), file.name)
                }
                val code = copy.listFiles()!!.first { it.name.endsWith("_code_.kt") }
                code.writeText("my edit")
                assertEquals("$name-2", openStartingPointIntoWorkspace(workspace.absolutePath, name).filename)
                assertEquals("my edit", code.readText())
                assertNotEquals("my edit", File(sources, "$name/${code.name}").readText())
            }
        } finally {
            if (old == null) System.clearProperty("afield.templates.dir") else System.setProperty("afield.templates.dir", old)
            temp.deleteRecursively()
        }
    }

    @Test fun recordingUsesTheCodeNewcomersActuallyReceive() {
        val macro = Yaml().load<Map<String, Any?>>(File("dev_docs/agents/transcripts/docs_capture/first_steps.yaml").readText())
        val operation = (macro["operations"] as List<*>).single() as Map<*, *>
        val create = operation["create"] as Map<*, *>
        val properties = create["properties"] as Map<*, *>
        assertEquals(File("templates/first-steps/first_steps_drawing_code_.kt").readText(), properties["code"])
    }
}
