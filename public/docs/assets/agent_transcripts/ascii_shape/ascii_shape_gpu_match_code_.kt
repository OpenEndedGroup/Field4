import org.lwjgl.vulkan.VK10.*

// One texel per character cell; the fragment shader writes the chosen glyph's index into red.
prop<RenderToTexture>(cells) = RenderToTexture {
    width = scene.parameters.width / ascii.cellWidth
    height = scene.parameters.height / ascii.cellHeight
    format = VK_FORMAT_R8G8B8A8_UNORM
    colorFormats = [VK_FORMAT_R8G8B8A8_UNORM]
    useDepth = false
}
// Rendered after the scene it reads, even when the scene box runs again.
renderer.renderList += "cells" to cells.after(scene)

// The shader's two textures, bound by the names it gives them: the scene, and every glyph's six numbers.
prop<RenderToTexture>(sceneTex) = scene
prop<LoadedTexture>(glyphVectors) = ascii.glyphVectorTexture()

var module = moduleHere { +cells }
var m = Mesh()
module += "match" to m
cells += "match" to module

// The shader's push constants: a cell's size in pixels, and the two contrast exponents.
m.cell = { vec(ascii.cellWidth, ascii.cellHeight) }
m.contrast = { 3f }
m.directional = { 3f }

// One triangle covering the whole target, so the fragment shader runs once per texel: once per cell.
m.set(3, 1) {
    pos.add(-1, -1, 0, 3, -1, 0, -1, 3, 0)
    tri.add(0, 1, 2)
}
