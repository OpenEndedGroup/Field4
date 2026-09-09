package afield.tetgen

import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import kotlin.test.Test

/** The three numbers in the page's opening snippet, which is the one fence that is not a runner. */
class TetgenPageIntroTest {

    @Test fun theOpeningSnippet() {
        if (!TetgenLibrary.isAvailable || !ManifoldLibrary.isAvailable) return
        Solid.scope {
            val part = Solid.cube(80.0, 40.0, 15.0) + Solid.cube(15.0, 40.0, 70.0)
            val mesh = Tetgen.tetrahedralize(part.toTriMesh(), targetEdgeLength = 5.0)
            println("Manifold volume ${part.volume}")
            println("nodeCount ${mesh.nodeCount}")
            println("cellCount ${mesh.cellCount}")
            println("volume    ${mesh.volume()}")
            println("triangles ${part.triangleCount}")
            Unit
        }
    }
}
