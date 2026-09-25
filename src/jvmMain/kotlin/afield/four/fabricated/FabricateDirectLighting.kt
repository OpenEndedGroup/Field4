package afield.four.fabricated

import afield.four.lighting.*
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f

/** Build the experiment from Fabricate's existing G-buffer. Call bundle.attachTo first, then
 * lighting.attach; present lighting.output. SOURCE reads the unchanged post-stack output.
 * Opaque diffuse only: transparency, textured emission and latent pigments require a future adapter. */
fun StandardRenderer.Bundle.directLighting(visibility: OpaqueRayScene, name: String = "direct-light"): DirectLighting {
    val source=postStack.output()
    return DirectLighting(gBuffer.renderer,DirectLightSurfaces(gBuffer.imageViewArray[2],gBuffer.imageViewArray[1],
        gBuffer.imageViewArray[0],source,gBuffer.imageViewArray[3]),visibility,gBuffer.width,gBuffer.height,name)
}

/** Camera contract for the optional RTXDI pass, matching this bundle's raster projection. */
fun StandardRenderer.Bundle.rtxdiSettings() = RtxdiSettings(
    { scene.camera.modelView() }, { scene.projection.evaluateUniform() })

/** Populate the existing raster G-buffer from the SAME indexed snapshot used for ray visibility.
 * Fabricate's current pulling shader consumes expanded vec4 triangles; the adapter expands once.
 * The ray BLAS keeps indexed XYZ data. No per-frame geometry upload or silent alpha approximation. */
fun StandardRenderer.Bundle.registerOpaqueTriangles(name: String, geometry: OpaqueTriangles,
    color: Vector3f = Vector3f(0.7f), transform: Matrix4f = Matrix4f()): StandardRenderer.Bundle.PullingMeshPair {
    val mesh=registerPullingMesh(name,geometry.indexData.size,gBuffer.renderer)
    for(t in geometry.indexData.indices step 3) {
        fun point(i: Int): Vector3f { val n=geometry.indexData[t+i]*3;return Vector3f(geometry.vertexData[n],geometry.vertexData[n+1],geometry.vertexData[n+2]) }
        val a=point(0);val b=point(1);val c=point(2)
        val n=Vector3f(b).sub(a).cross(Vector3f(c).sub(a)).normalize()
        for(p in listOf(a,b,c)) {
            mesh.gBuffer.position.append { put(p.x,p.y,p.z,1f) }
            mesh.gBuffer.normal.append { put(n.x,n.y,n.z,0f) }
        }
    }
    mesh.setVertexCount(geometry.indexData.size).setModel(transform)
    mesh.gBuffer.mesh.push["baseColor"]=Vector4f(color,1f)
    return mesh
}
