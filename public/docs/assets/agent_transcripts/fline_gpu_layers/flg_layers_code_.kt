/*
FLine layers: the parent of the "strokes", "fills" and "points" boxes.

It owns an FLineLayers — `lines`, a mutable list of FLines, and three per-frame GPU
buffers they are packed into — and the Camera the three draw boxes share. Both are
published to every box beneath this one. Any box beneath it draws by changing
`flineLayers.lines`; the loop at the bottom uploads that list once a frame.

The upload goes by content, not by object: a line packs to vertices once, and a line
with the same points and style next frame (even a new FLine, as spatial rules make)
reuses them. Each swapchain image's buffer also remembers what it holds where, and
skips copying a span that is already there. The "upload" output says what each upload
did. Needs the renderer box as its parent.
*/
import afield.graphics.vulkan.util.Camera

prop<FLineLayers>(flineLayers)
prop<Camera>(flineCamera)

// `once`: the draw boxes hold these buffers' addresses, so re-running this box keeps them.
once.flineLayers { FLineLayers(renderer, samplesPerCubic = 16) }

// Looking down +z at a 600 × 360 drawing, centred on it. Lines in pixel-like units, y down.
once.flineCamera {
    Camera(renderer).apply {
        at = vec(300, 180, -260)
        target = vec(300, 180, 0)
        up = vec(0, -1, 0)
    }
}

// WASD/arrows move, E/C rise and fall, R resets, while the renderer window has focus.
var kc by keyCamera(camera = flineCamera)

var frame = 0
loop {
    flineLayers.upload()

    frame++
    if (frame % 30 == 0) markdown("upload", "`${flineLayers.stats}`")
}
