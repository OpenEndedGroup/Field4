// The debug view is not garnish. A reprojection with a sign error in y renders an image a
// person will call fine, and a temporal pass built on it looks BETTER than the correct one
// on a still frame. These four modes are how you see which you have.
//
//   0 beauty          the accumulated image. The baseline to flip against.
//   1 backward motion where each pixel came FROM. Flat grey is zero motion.
//   2 previous UV     the reprojected coordinate itself, as a corner-to-corner ramp. The
//                     only mode that shows a CONSTANT offset - inside a difference an
//                     offset is invisible, inside an absolute coordinate it is a colour shift.
//   3 availability    green where history is usable, red where it is not. Watch the red
//                     band on the trailing edge: those pixels were off screen last frame.
val names = listOf("beauty", "backward motion", "previous UV", "availability")
frames {
    while (true) {
        for (m in 0..3) {
            chain.setPush("display", "mode", m)
            println("mode " + m + " - " + names[m])
            repeat(150) { frame() }
        }
    }
}
