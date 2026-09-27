import afield.dmx.*

// 200 RGB pixels of LED tape stuck along a curve. 170 fit in universe 1 (510 slots);
// the patch rolls the other 30 into universe 2 by itself.
val tape = FLine().moveTo(0, 0).cubicTo(x0 = 150, y0 = -160, x1 = 250, y1 = 160, x = 400, y = 0)
val patch = Patch(startUniverse = 1)
patch.stripAlong(tape, 200)

// Art-Net to this machine; use a node's IP (or a broadcast address) to drive real lights.
val out = artnetOut("127.0.0.1")

// A colour field over (x, y, t), evaluated on the DMX sender's own 44 Hz clock.
patch.animate(out) { t ->
    map(t) { x, y, time ->
        vec(0.5 + 0.5 * sin(x * 0.03 - time * 2.0),
            0.5 + 0.5 * sin(x * 0.03 - time * 2.0 + 2.1),
            0.5 + 0.5 * sin(y * 0.03 + time))
    }
}

// Draw the virtual fixtures in their current colours.
loop {
    if (frameNumber % 4 == 0) showPatch(patch)
}
