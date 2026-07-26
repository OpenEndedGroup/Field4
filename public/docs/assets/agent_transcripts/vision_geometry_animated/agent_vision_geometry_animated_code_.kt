// {{ visionmetal uniforms=light,pulse }}
import kotlin.math.cos
import kotlin.math.sin

// Two ways to animate a mesh, and this box is the second one:
//
//   1. Keep the geometry still and move it in the shader from a uniform. Cheapest; use it
//      whenever the motion is a transform (see visionos_start).
//   2. Rebuild the mesh. Necessary when the *topology* or the spine changes — which is the
//      case here, because the curve itself is being redrawn.
//
// At this size (~600 vertices) rebuilding every frame is comfortable. For a mesh in the
// tens of thousands, rebuild on demand instead and let the shader do per-frame motion.
onStart {
    uniform("light", 0.4, 0.85, 0.5)

    onFrame { frame ->
        val t = frame.time

        geometry {
            color(0.2, 0.9, 0.8)
            tube(
                (0..72).map { i ->
                    val u = i / 72.0
                    // A helix whose winding count drifts, so the spine really does change
                    // shape rather than just rotating.
                    val a = u * (9.0 + 3.0 * sin(t * 0.3)) + t
                    val radius = 0.13 + 0.05 * sin(u * 3.1416 + t * 0.7)
                    p(radius * sin(a), -0.22 + u * 0.44, -1.2 + radius * cos(a))
                },
                radius = 0.011 + 0.007 * sin(t * 2.0),
                sides = 8,
            )
        }

        uniform("pulse", 0.5 + 0.5 * sin(t * 2.0))
    }

    onStop { println("animated geometry stopped") }
    println("animated geometry running — rebuilding the mesh each frame")
}
