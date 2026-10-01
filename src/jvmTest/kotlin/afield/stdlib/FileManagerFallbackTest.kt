package afield.stdlib

import afield.Box
import afield.SessionInformation
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

class FileManagerFallbackTest {
    @Test fun precedenceReplacementRemovalAndHistory() {
        val temp = Files.createTempDirectory("file-fallback").toFile().canonicalFile
        val name = "helper-${temp.name}.bin"
        val searchName = "fallback_test_${System.nanoTime()}"
        try {
            val document = File(temp, "doc").apply { mkdirs() }
            val project = File(temp, "_projects/p").apply { mkdirs() }
            File(project, "contents.edn").writeText("{:documents [\"doc\"]}")
            val root = Box("root")
            val session = SessionInformation(document.absolutePath, "doc", ConcurrentHashMap(), root)
            root.inside = session
            session.boxes[root.id] = root
            val manager = FileManager.forSession(session)
            val target = File(temp, "installed/helper")
            val local = File(document, "files/$name")
            FileManager.addFile(name, target)
            assertEquals(local, manager.file(name)) // missing helper is not a cached answer
            target.parentFile.mkdirs()
            target.writeText("helper")
            assertEquals(target, manager.file(name))
            val history = root.getPropertyRaw(FILE_MANAGER_ACCESS_HISTORY_PROPERTY) as List<*>
            assertEquals("file:$name", (history.last() as Map<*, *>)["source"])
            assertEquals(local, manager.file("%document/$name"))
            assertFailsWith<IllegalStateException> { manager.file("**/$name") }

            val replacement = File(temp, "replacement").apply { writeText("replacement") }
            FileManager.addFile(name, replacement.toPath())
            assertEquals(replacement, manager.file(name))
            val search = File(temp, "search").apply { mkdirs() }
            FileManager.add(searchName, search, search = true)
            val searchCopy = File(search, name).apply { writeText("search") }
            assertEquals(searchCopy, manager.file(name))
            val projectCopy = File(project, "files/$name").apply { parentFile.mkdirs(); writeText("project") }
            assertEquals(projectCopy, manager.file(name))
            local.writeText("document")
            assertEquals(local, manager.file(name))
            local.delete(); projectCopy.delete(); searchCopy.delete()
            assertEquals(replacement, manager.file(name))
            replacement.delete()
            assertEquals(local, manager.file(name))
            replacement.mkdir() // a directory cannot supply an exact-file fallback
            assertEquals(local, manager.file(name))
            replacement.delete(); replacement.writeText("restored")
            assertEquals(replacement, manager.file(name))
            assertTrue(FileManager.removeFile(name))
            assertEquals(local, manager.file(name))
            assertTrue(replacement.exists())
            assertFalse(FileManager.removeFile(name))
            for (invalid in listOf("", "../helper", "a/b", "a\\b", "%document", "*.bin", ".", "..")) {
                assertFailsWith<IllegalArgumentException>(invalid) { FileManager.addFile(invalid, target) }
            }
        } finally {
            FileManager.removeFile(name)
            FileManager.remove(searchName)
            temp.deleteRecursively()
        }
    }

    @Test fun helperLocationComesFromLoadedCodeAndCanPrecedeBuild() {
        val temp = Files.createTempDirectory("helper-installation").toFile()
        try {
            val buildScript = File(temp, "native/field-slug/build.sh").apply { parentFile.mkdirs(); writeText("") }
            val expected = File(buildScript.parentFile, "build/field-slug").toPath()
            assertEquals(expected, bundledSlugExecutable(File(temp, "build/classes/kotlin/jvm/main").toPath()))
            assertEquals(expected, bundledSlugExecutable(File(temp, "build/libs/field.jar").toPath()))
            buildScript.delete()
            assertNull(bundledSlugExecutable(temp.toPath()))
        } finally {
            temp.deleteRecursively()
        }
    }
}
