package afield.manifold

import afield.stdlib.planning.Point3D
import kotlin.test.*

class PlanarContactTest {
    @Test fun finiteBoundaryAndRemovedRegionsMustAgree() = Solid.scope {
        val a = Solid.cube(10.0); val b = a.translated(10.0, 0.0, 0.0)
        val plane = ContactPlane(Point3D(10.0, 0.0, 0.0), Point3D(1.0, 0.0, 0.0))
        val cut = Solid.cube(20.0, 2.0, 2.0).translated(0.0, 4.0, 4.0)
        val ca = (a and cut).toPlanarMesh(); val cb = (b and cut).toPlanarMesh()
        assertEquals(100.0, planarContact(a.toPlanarMesh(), b.toPlanarMesh(), plane).area, 1e-6)
        assertTrue(auditApertures(a.toPlanarMesh(), b.toPlanarMesh(), ca, cb, ca, plane, minimumContactMargin=3.0).passed)
        assertFalse(auditApertures(a.toPlanarMesh(), b.toPlanarMesh(), ca, cb, ca, plane, minimumContactMargin=5.0).passed)
        assertFalse(auditApertures(a.toPlanarMesh(), b.toPlanarMesh(), ca, Solid.empty().toPlanarMesh(), ca, plane).passed)
        assertTrue(planarContacts(a.toPlanarMesh(), b.translated(0.0, 30.0, 0.0).toPlanarMesh()).isEmpty())
    }

    @Test fun nativeSectionKeepsFractionalCoordinates() = Solid.scope {
        val width = 123.123456789
        val section = Section.ofDoublePolygons(listOf(listOf(0.0 to 0.0, width to 0.0, width to 7.0, 0.0 to 7.0)))
        assertEquals(width, section.toDoublePolygons().flatten().maxOf { it.first }, 1e-6)
        assertTrue(kotlin.math.abs(width.toFloat().toDouble()-width) > 1e-6)
        assertEquals(width*7, section.area, 1e-5)
    }

    @Test fun obliqueCutMatchesAfterTranslation() = Solid.scope {
        val offset = Point3D(10000.123, -20000.456, 30000.789)
        fun Solid.move() = translated(offset.x, offset.y, offset.z)
        val a = Solid.cube(10.0).move(); val b = Solid.cube(10.0).translated(10.0, 0.0, 0.0).move()
        val plane = ContactPlane(offset + Point3D(10.0, 0.0, 0.0), Point3D(1.0, 0.0, 0.0))
        val tube = polylineTube(listOf(offset+Point3D(0.0, 2.0, 5.0), offset+Point3D(20.0, 8.0, 5.0)), 1.0)
        val ca = (a and tube).toPlanarMesh(); val cb = (b and tube).toPlanarMesh()
        assertTrue(auditApertures(a.toPlanarMesh(), b.toPlanarMesh(), ca, cb, ca, plane, minimumContactMargin=2.0).passed)
    }

    @Test fun failedScopeClosesItsTemporaries() {
        var temporary: Solid? = null
        assertFailsWith<IllegalStateException> { Solid.scope { temporary = Solid.cube(1.0); error("abort") } }
        assertFails { temporary!!.volume }
        Solid.scope { assertEquals(0.0, Solid.empty().toPlanarMesh().toSolid().volume) }
    }
}
