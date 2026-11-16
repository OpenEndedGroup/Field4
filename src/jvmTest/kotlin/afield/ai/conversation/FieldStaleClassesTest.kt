package afield.ai.conversation

import java.io.File
import kotlin.test.*

/** The one detector for Field classes rewritten under a running JVM ([FieldStaleClasses]). */
class FieldStaleClassesTest {
    @Test fun aLinkageErrorAnywhereInTheCauseChainIsStale() {
        val wrapped = RuntimeException("tool failed", NoClassDefFoundError("afield/app/diagnostics/CodeUseSourceTarget"))
        val message = FieldStaleClasses.messageFor(wrapped)
        assertNotNull(message)
        assertTrue("CodeUseSourceTarget" in message && "restart" in message, message)
        assertNull(FieldStaleClasses.messageFor(IllegalStateException("ordinary")))
        assertNull(FieldStaleClasses.messageFor(ExceptionInInitializerError("a box's static init")))
    }

    @Test fun aBareFieldClassNameAsABoxErrorIsStale() {
        assertTrue(FieldStaleClasses.errorTextIsStale("afield/runtime/kotlin/SnippetClassReceiverCrash"))
        assertTrue(FieldStaleClasses.errorTextIsStale("java.lang.NoClassDefFoundError: afield/graphics/FLine\n\tat ..."))
        assertFalse(FieldStaleClasses.errorTextIsStale("Unresolved reference 'foo'."))
        assertFalse(FieldStaleClasses.errorTextIsStale("NoSuchMethodError: com.example.Thing.go()"), "not Field's classes")
    }

    @Test fun massUnresolvedFieldPackagesCollapseToOneSummary() {
        val packages = setOf("graphics", "math")
        val diags = List(611) { if (it % 2 == 0) "Unresolved reference 'graphics'." else "Unresolved reference 'math'." }
        val summary = FieldStaleClasses.summarizeMassUnresolved(diags) { it in packages }
        assertNotNull(summary)
        assertTrue("611 of 611" in summary && "'graphics'" in summary, summary)
        // A real mistake names a few symbols, not Field's packages en masse.
        assertNull(FieldStaleClasses.summarizeMassUnresolved(List(3) { "Unresolved reference 'graphics'." }) { it in packages })
        assertNull(FieldStaleClasses.summarizeMassUnresolved(List(30) { "Unresolved reference 'myThing'." }) { it in packages })
    }

    @Test fun fieldPackagesAreThoseOnTheClassLoader() {
        assertTrue(FieldStaleClasses.isFieldPackage("ai"))
        assertFalse(FieldStaleClasses.isFieldPackage("noSuchPackageHere"))
    }

    @Test fun classFilesNewerThanJvmStartAreFound() {
        val dir = kotlin.io.path.createTempDirectory("stale").toFile()
        try {
            val cls = File(dir, "afield/x/Y.class").apply { parentFile.mkdirs(); writeText("") }
            val started = cls.lastModified() - 10_000
            assertEquals(listOf(cls.canonicalFile), FieldStaleClasses.classesRewrittenSince(started, dir.path).map { it.canonicalFile })
            assertEquals(emptyList(), FieldStaleClasses.classesRewrittenSince(cls.lastModified() + 10_000, dir.path))
            assertTrue(FieldStaleClasses.runsFrom(dir, "other${File.pathSeparator}${dir.path}"))
        } finally { dir.deleteRecursively() }
    }
}
