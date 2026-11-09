// The glyph chosen for each cell, and every glyph's pixels, bound by the shader's names for them.
// `sceneTex`, for the corner, is the match box's.
prop<RenderToTexture>(cellsTex) = cells
prop<LoadedTexture>(atlasTex) = ascii.glyphAtlasTexture()

var module = moduleHere()
var m = Mesh()
module += "characters" to m
renderer += "characters" to module

m.cell = { vec(ascii.cellWidth, ascii.cellHeight) }
// Field's window is sRGB, which brightens these to #E6E6E6 on #161619.
m.ink = { vec(0.79, 0.79, 0.79, 1) }
m.background = { vec(0.008, 0.008, 0.01, 1) }

// One triangle covering the window.
m.set(3, 1) {
    pos.add(-1, -1, 0, 3, -1, 0, -1, 3, 0)
    tri.add(0, 1, 2)
}
