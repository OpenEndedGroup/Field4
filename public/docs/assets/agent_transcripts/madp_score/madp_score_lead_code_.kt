/*
The canvas is the score. Run this box: the orange playhead sweeps from here to the right once
every eleven seconds, and every box it crosses runs its score { } block each frame, drawing into the
dark panel below and playing dough sounds. Every box in the score is armed when this one starts.

Edit a box and run it again to change what it does; stop a box to silence it; drag a box to move
it in time, or stretch it to make it last longer. Inside score { }: localProgress (0 to 1 across
the box), localT (seconds into it), justEntered, onSubdivision(n), boxPoint(u, v), draw.*,
sound(path), thisRMS (how loud this box's own sound is right now) and box.move/set.
*/
score {
    val center = boxPoint(0.5, 0.5)
    val size = min(bounds.w * scaleX, bounds.h * scaleY)
    val radius = size * (0.12 + thisRMS * 0.42 + tri01(localT * 2) * 0.14)

    draw.ring(center, radius, color = "rgba(255, 238, 205, 0.96)", width = 2.5)
    draw.circle(center, radius * 0.18, fill = "rgba(255, 238, 205, 0.18)")

    if (justEntered) sound("/dough/play/note/48/duration/0.18/s/sine/gain/0.45/lpf/900/release/0.14/verb/0.25")
}

playScore(loop = 11.0, x = 40.0..1880.0, y = 40.0..600.0, width = 520, height = 320)
