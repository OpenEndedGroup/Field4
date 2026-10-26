package afield.graphics.scene.fabricated

import afield.graphics.render.lighting.*
import afield.math.linalg.Matrix4f
import afield.math.linalg.Vector3f
import afield.math.linalg.Vector4f

/**
 * Driving the direct-lighting experiment from a Fabricate scene, in three calls on
 * [StandardRenderer.Bundle].
 *
 * `DirectLighting` asks for point-sampled world positions, world normals, linear albedo, a source
 * colour and motion vectors, which is a deferred renderer's G-buffer under another set of names. A
 * Fabricate bundle already renders one, so this file is a mapping rather than a second renderer:
 * `directLighting(visibility)` hands the lighting pass the same four attachments the bundle's own
 * shading reads as `gAlbedoRoughness`, `gNormalMetallic`, `gPositionDepth` and `gMotionMaterial`,
 * with the post stack's current output as the source image that `Mode.SOURCE` shows unchanged. The
 * call order is fixed: `bundle.attachTo(renderer)`, then `lighting.attach()`, then present
 * `lighting.output`.
 *
 * Shadow rays have to see the geometry the raster pass drew, and two separate uploads are how the
 * two come apart. `registerOpaqueTriangles` takes one indexed [OpaqueTriangles] snapshot and feeds
 * both: the ray BLAS keeps it indexed, and the bundle's pulling shader, which reads expanded vec4
 * triangles, gets them expanded once at registration rather than every frame. `rtxdiSettings`
 * supplies the optional RTXDI pass with the bundle's own view and projection, since resampling
 * across frames reprojects against the matrices rasterization used.
 *
 * Opaque diffuse surfaces only. Transparency, textured emission and latent pigments have no route
 * through here. `DirectLightingDemo` is the worked scene and `direct_lighting.md` the page.
 */

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
