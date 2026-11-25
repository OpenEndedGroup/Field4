package afield.runtime

import afield.geometry.network.NativeGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeLibrariesTest {
    @Test
    fun statusReportsEveryLibraryGroupedByArea() {
        val status = NativeLibraries.status()
        assertEquals(NativeLibraries.names, status.map { it.name }.toSet())
        // The registry key is the capability's own name, so the welcome page and describe() agree.
        for (name in NativeLibraries.names) assertEquals(name, NativeLibraries.capability(name)!!.name)
        val areaIndexes = status.map { NativeArea.order.indexOf(it.area) }
        assertTrue(areaIndexes.all { it >= 0 }, "every area is one of NativeArea.order: ${status.map { it.area }}")
        assertEquals(areaIndexes.sorted(), areaIndexes, "the welcome page groups by area, so status() keeps areas together")
    }

    @Test
    fun everyLibrarySaysWhatToDoOnEveryOs() {
        for (s in NativeLibraries.status()) {
            assertEquals(setOf("macos", "linux", "windows"), s.install.keys, "${s.name} install keys")
            assertTrue(s.docsPage.endsWith(".md"), "${s.name} docs page ${s.docsPage}")
            assertTrue(s.description.isNotBlank(), "${s.name} description")
            if (s.available) assertTrue(s.description.startsWith("found at "), "${s.name}: ${s.description}")
        }
    }

    @Test
    fun geometryIsASliceOfTheRegistry() {
        assertTrue(NativeGeometry.names.isNotEmpty())
        assertTrue(NativeLibraries.names.containsAll(NativeGeometry.names))
        for (name in NativeGeometry.names) assertEquals(NativeArea.GEOMETRY, NativeLibraries.capability(name)!!.area)
        assertTrue("manifold" !in NativeGeometry.names)
    }

    @Test
    fun unknownLibraryIsReportedNotThrown() {
        assertEquals(false, NativeLibraries.available("noSuchLibrary"))
        assertTrue(NativeLibraries.describe("noSuchLibrary").contains("not an optional native library"))
    }
}
