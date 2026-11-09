import org.lwjgl.vulkan.VK10.*

// The font the characters are matched in, and an offscreen target exactly 128 x 48 of its cells.
// The boxes beneath this one read both.
prop<ShapeAscii>(ascii) = ShapeAscii()
prop<RenderToTexture>(scene) = RenderToTexture {
    width = 128 * ascii.cellWidth
    height = 48 * ascii.cellHeight
    // Plain 8-bit, not sRGB, so a shader reading it and the CPU reading it back see the same numbers.
    format = VK_FORMAT_R8G8B8A8_UNORM
    colorFormats = [VK_FORMAT_R8G8B8A8_UNORM]
    useDepth = true
}
renderer.renderList += "scene" to scene

// The knot is drawn into the scene, not into the window.
var module = moduleHere { +scene }
var knot = Mesh()
module += "knot" to knot
scene += "knot" to module

// The vertex shader turns the knot by `time` and projects it at the scene's shape.
var time = 0f
knot.time = {
    time += 1f / 60
    time
}
knot.aspect = { scene.parameters.width.toFloat() / scene.parameters.height }

// A (2, 3) torus knot: a tube of radius 0.16 around a centre line, 240 steps along it and 16 around.
fun centre(a: Double) =
    vec(0.42 * (2 + cos(3 * a)) * cos(2 * a), 0.42 * (2 + cos(3 * a)) * sin(2 * a), -0.42 * sin(3 * a))
knot.set(240 * 16, 240 * 16 * 2) {
    for (i in 0 until 240) for (j in 0 until 16) {
        val a = 2 * PI * i / 240
        val along = (centre(a + 1e-4) - centre(a - 1e-4)).normalize()
        val side = vec(along.y, -along.x, 0).normalize()
        val up = vec(side.y * along.z, -side.x * along.z, side.x * along.y - side.y * along.x)
        val w = 2 * PI * j / 16
        val out = side * cos(w).toFloat() + up * sin(w).toFloat()
        val p = centre(a) + out * 0.16f
        pos.add(p.x, p.y, p.z)
        normal.add(out.x, out.y, out.z)
        val next = (i + 1) % 240
        tri.add(i * 16 + j, next * 16 + j, next * 16 + (j + 1) % 16)
        tri.add(i * 16 + j, next * 16 + (j + 1) % 16, i * 16 + (j + 1) % 16)
    }
}
