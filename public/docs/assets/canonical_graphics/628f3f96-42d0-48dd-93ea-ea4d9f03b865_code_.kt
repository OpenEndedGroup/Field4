import afield.graphics.text.slug.*
import org.lwjgl.vulkan.VK10.*

/*
5e. Slug filled paths: an FLine filled on the GPU with the Slug coverage algorithm.
Parent: window. SlugPath.compile turns the path into curve and band data; the vertex and fragment
shaders (open them with the editor's property switcher) draw one quad covering the path and compute
per-pixel coverage from that data, so edges stay sharp at any zoom.
Compiling needs the native helper; build it once with: bash native/field-slug/build.sh
*/
name = "5e. Slug filled paths"
val path =
    FLine().moveTo(-1, -1).lineTo(1, -1).cubicTo(vec(1.4, 0, 0), vec(0.8, 1.3, 0), vec(0, 1, 0)).lineTo(-1, 1).close()
// Clockwise inner contour cuts a hole in the counterclockwise outer contour.
path.moveTo(-0.3, -0.3).lineTo(-0.3, 0.3).lineTo(0.3, 0.3).lineTo(0.3, -0.3).close()
val prepared = SlugPath.compile(path, "field-slug".file)

// `//%-` is Field's execution break: the path is prepared above it, before the GPU setup below.
//%-
val module = moduleHere {
    primitiveType = triangles
    srcAlpha = VK_BLEND_FACTOR_ONE
    depthWrite = false
}
val mesh = Mesh()
module += "slug" to mesh
renderer += module
// Six vertices: the two triangles of the covering quad.
mesh.resize(6)
val data = SlugBuffer(renderer, prepared)

// When the box is re-run or stopped, remove the mesh first, then free the atlas it reads.
RunScope.current()!!.own(policy = ReleasePolicy.RUNNING) {
    if (module.current.meshes["slug"] === mesh) module.current.meshes.remove("slug")
    data.close()
}

// Push constants, matched by name to the shaders' SlugPC block.
mesh.slugData = { data }
val camera = Camera()
camera.at = vec(0, 0, -4)

mesh.mvp = { Matrix4f(camera.p.evaluateUniform()).mul(camera.modelView()) }
mesh.viewport = {
    val extent = renderer.swapChain.swapChainExtent
    vec(extent.width(), extent.height())
}
var tint = vec(0.9, 0.9, 0.9, 0.8)
mesh.tint = { tint }

// Focus the renderer window: WASD/arrows move, E/C rise/fall, R resets.
var kc by keyCamera(camera = camera)

// Tint and camera can change without preparing the geometry again. To change the geometry, prepare a
// new SlugPath and call data.replace(newPath) on the render thread.
