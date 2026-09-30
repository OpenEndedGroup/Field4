import afield.graphics.FLine
import afield.graphics.slug.SlugPath
import afield.four.buffers.bindPushConstant
import java.io.File

// Put vertex.glsl / fragment.glsl in this box's vertex / fragment properties.
// Run as a child of an existing renderer box, following the canonical lessons.
val executable = File("native/field-slug/build/field-slug")
val path = FLine().moveTo(-1, -1).lineTo(1, -1)
    .cubicTo(vec(1.4, 0, 0), vec(0.8, 1.3, 0), vec(0, 1, 0))
    .lineTo(-1, 1).close()
// Reverse winding for the inner contour: a hole under the nonzero winding rule.
path.moveTo(-0.3, -0.3).lineTo(-0.3, 0.3).lineTo(0.3, 0.3).lineTo(0.3, -0.3).close()
val slug = SlugPath.compile(path, executable, tolerance = 1e-4f)

val module = moduleHere { primitiveType = triangles }
val mesh = Mesh()
module += mesh named "slug"
renderer += module
mesh.resize(6)

// Immutable data: no per-frame uploads for camera motion or a changing fill.
val data = defaultBuffer { size = slug.byteSize }.buffer
data.write { slug.words.forEach { putInt(it) } }
mesh.push.bindPushConstant("slugData", { data }) { error("Missing slugData push constant") }
val camera = Camera()
mesh.p = camera.p
mesh.m = camera.m
mesh.push.bindPushConstant("viewport", {
    val extent = renderer.swapChain.swapChainExtent
    vec(extent.width(), extent.height())
}) { error("Missing viewport push constant") }
mesh.push.bindPushConstant("tint", { vec(0.9, 0.6, 0.3, 1.0) }) { error("Missing tint push constant") }

var time = 0.0
loop {
    time += 0.01
    camera.at = vec(3 * Math.sin(time), 1, -3 - 2 * Math.cos(time))
    true
}

// Geometry edits: compile a NEW SlugPath, then publish it on the render thread.
// For this first prototype, re-run this box (which replaces its named module).
// A later SlugDrawable should manage page swaps and GPU-safe retirement explicitly.
