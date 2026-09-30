import afield.RunScope
import afield.ReleasePolicy
import afield.four.Renderer
import afield.four.buffers.bindPushConstant
import afield.four.modules.*
import afield.graphics.FLine
import afield.graphics.slug.*
import afield.stdlib.linalg.*
import org.lwjgl.vulkan.VK10.*
import java.io.File

name = "5e. Slug filled paths"
// Build the optional helper once: bash native/field-slug/build.sh
val renderer: Renderer = fromContext()
val path = FLine().moveTo(-1, -1).lineTo(1, -1)
    .cubicTo(vec(1.4, 0, 0), vec(0.8, 1.3, 0), vec(0, 1, 0))
    .lineTo(-1, 1).close()
// Clockwise inner contour cuts a hole in the counterclockwise outer contour.
path.moveTo(-0.3, -0.3).lineTo(-0.3, 0.3).lineTo(0.3, 0.3).lineTo(0.3, -0.3).close()
val prepared = SlugPath.compile(path, File("native/field-slug/build/field-slug"))

//%-
val module = moduleHere {
    primitiveType = triangles
    srcAlpha = VK_BLEND_FACTOR_ONE
    depthWrite = false
}
val mesh = Mesh()
module += "slug" to mesh
renderer += module
mesh.resize(6)
val data = SlugBuffer(renderer, prepared)
RunScope.current()!!.own(policy = ReleasePolicy.RUNNING) {
    if (module.current.meshes["slug"] === mesh) module.current.meshes.remove("slug")
    data.close()
}
mesh.push.bindPushConstant("slugData", { data }) { throw IllegalStateException("Missing slugData") }
val camera = Camera()
camera.at = vec(0, 0, -4)
mesh.push.bindPushConstant("mvp", {
    Matrix4f(camera.p.evaluateUniform()).mul(camera.modelView())
}) { throw IllegalStateException("Missing mvp") }
mesh.push.bindPushConstant("viewport", {
    val extent = renderer.swapChain.swapChainExtent
    vec(extent.width(), extent.height())
}) { throw IllegalStateException("Missing viewport") }
var tint = vec(0.9, 0.9, 0.9, 0.8)
mesh.push.bindPushConstant("tint", { tint }) { throw IllegalStateException("Missing tint") }

// Focus the renderer window: WASD/arrows move, E/C rise/fall, R resets.
var kc by keyCamera(camera = camera)
// Change tint or camera without preparing geometry again.
// Geometry edits: prepare a new SlugPath, then data.replace(newPath) on the render thread.
// Re-running/stopping this box removes its mesh before retiring the atlas.
