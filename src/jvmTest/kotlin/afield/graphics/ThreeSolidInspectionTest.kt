package afield.graphics

import afield.manifold.*
import afield.stdlib.planning.Point3D
import kotlin.test.*

class ThreeSolidInspectionTest {
    @Test fun fusedStockHasNoProvenanceSeamButSeparatePanelsKeepTheirEdges() = Solid.scope {
        val a=Solid.cube(10.0,20.0,30.0);val b=a.translated(10.0,0.0,0.0)
        val fused=(a+b).toPlanarMesh()
        fun point(m:SolidPlanarMesh,i:Int)=Point3D(m.positions[3*i],m.positions[3*i+1],m.positions[3*i+2])
        fun onJoin(m:SolidPlanarMesh,e:Pair<Int,Int>)=kotlin.math.abs(point(m,e.first).x-10)<1e-8 && kotlin.math.abs(point(m,e.second).x-10)<1e-8
        val edges=solidOutlineEdges(fused)
        assertTrue(edges.none{onJoin(fused,it)},"Joined stock must not draw its Boolean history")
        assertEquals(280.0,edges.sumOf{point(fused,it.first).distanceTo(point(fused,it.second))},1e-8)
        for(s in listOf(a,b)) {
            val m=s.toPlanarMesh()
            assertEquals(100.0,solidOutlineEdges(m).filter{onJoin(m,it)}.sumOf{point(m,it.first).distanceTo(point(m,it.second))},1e-8)
        }
        val artifact=solidArtifactDigest(mapOf("fused" to fused))
        val scene=ThreeSceneBuilder(12);scene.solidParts(mapOf("fused" to fused))
        assertEquals(artifact,scene.inspector!!.artifactDigest)
        assertEquals(edges.size*6,scene.drawables.last().geometry.attributes.getValue("position").array.size)
    }

    @Test fun shallowCreasesRemainVisibleEvenWithOneProvenanceFace() {
        // A real 0.0000001-radian bend; classification occurs before Float display conversion.
        val mesh=SolidPlanarMesh(doubleArrayOf(0.0,0.0,0.0,10.0,0.0,0.0,0.0,10.0,0.0,0.0,-10.0,1e-6),
            intArrayOf(0,1,2,1,0,3),intArrayOf(0,0),listOf(SolidPlanarFace(0,null,0,intArrayOf(0,1))),0.0)
        assertTrue((0 to 1) in solidOutlineEdges(mesh))
        val flat=mesh.copy(positions=mesh.positions.copyOf().also{it[11]=0.0},triangleFaces=intArrayOf(0,1),
            faces=listOf(SolidPlanarFace(0,null,0,intArrayOf(0)),SolidPlanarFace(1,null,0,intArrayOf(1))))
        assertFalse((0 to 1) in solidOutlineEdges(flat))
        assertEquals(4,solidOutlineEdges(flat).size,"Open surface perimeter remains visible")
    }

    @Test fun zUpDisplayRotatesEveryBoundaryAndMotionButKeepsCanonicalIdentity() = Solid.scope {
        val mesh = Solid.cube(10.0,20.0,30.0).translated(100.0,200.0,300.0).toPlanarMesh()
        val parts = mapOf("part" to mesh)
        val original = mesh.positions.copyOf()
        val scene = ThreeSceneBuilder(12)
        scene.solidParts(parts, origin=Point3D(100.0,200.0,300.0),
            assembly=listOf(ThreeAssemblyMotion("part",doubleArrayOf(1.0,2.0,3.0))),upAxis=ThreeUpAxis.Z)
        val inspector = scene.inspector!!
        assertEquals(solidArtifactDigest(parts),inspector.artifactDigest)
        assertEquals(ThreeUpAxis.Z,inspector.renderUpAxis)
        assertContentEquals(doubleArrayOf(1.0,3.0,-2.0),inspector.assembly.single().displacement)
        assertContentEquals(original,mesh.positions)
        val positions=scene.drawables.first().geometry.attributes.getValue("position").array
        // Flat shading expands/reorders render vertices; compare geometric point sets, not ordinals.
        fun canonicalZero(v: Float) = if (v == 0.0f) 0.0f else v
        val expected = original.toList().chunked(3).map {
            listOf((it[0]-100.0).toFloat(), (it[2]-300.0).toFloat(), (200.0-it[1]).toFloat()).map(::canonicalZero)
        }.toSet()
        val actual = positions.toList().chunked(3).map { it.map(::canonicalZero) }.toSet()
        assertEquals(expected,actual)
    }
    @Test fun featureTopologyKeepsCanonicalCoordinatesAndTriangleOrder() = Solid.scope {
        val boundary = Solid.cube(13.0, 17.0, 19.0).translated(1e7+.123456789, 2.0, 3.0).toPlanarMesh()
        val builder = ThreeSceneBuilder(12)
        builder.solidParts(mapOf("test" to boundary), origin=Point3D(1e7,0.0,0.0), upAxis=ThreeUpAxis.Z)
        val topology = builder.inspector!!.parts.getValue("test")
        assertContentEquals(boundary.positions, topology.positions)
        assertContentEquals(boundary.indices, topology.indices)
        assertEquals(6, topology.faces.toSet().size)
        assertEquals(12, topology.edges.size)
        val display = builder.drawables.first().geometry
        val positions = display.attributes.getValue("position").array
        for(t in 0 until boundary.triangleCount) for(k in 0..2) {
            val rendered = display.index!!.array[3*t+k]
            val canonical = boundary.indices[3*t+k]
            assertEquals((boundary.positions[3*canonical]-1e7).toFloat(), positions[3*rendered])
            assertEquals(boundary.positions[3*canonical+2].toFloat(), positions[3*rendered+1])
            assertEquals((-boundary.positions[3*canonical+1]).toFloat(), positions[3*rendered+2])
        }
    }

}
