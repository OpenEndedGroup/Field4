// {{ visionmetal uniforms=spin,light }}
import kotlin.math.cos
import kotlin.math.sin

// Four shapes in one mesh, laid out left to right 1.2m in front of you.
onStart {
    geometry {
        // The current colour applies to everything built after it. `tintFaces` overrides
        // it per face so the cube's six sides stay legible while it turns.
        color(1.0, 0.42, 0.18)
        cube(0.11, cx = -0.42, cz = -1.2, tintFaces = true)

        color(0.25, 0.75, 1.0)
        sphere(0.13, segments = 32, rings = 20, cx = -0.14, cz = -1.2)

        // plane() writes uv across the whole sheet, which the shader picks up as a checker.
        color(0.95, 0.85, 0.25)
        plane(0.26, 0.26, cols = 8, rows = 8, cx = 0.14, cz = -1.2)

        // tube() sweeps a polygon along a polyline — the same real-thickness treatment
        // the native FLine viewer gives a stroke.
        color(0.62, 0.42, 1.0)
        tube(
            (0..48).map { i ->
                val t = i / 48.0
                val a = t * 6.2831853
                p(0.42 + 0.055 * sin(a), -0.13 + t * 0.26, -1.2 + 0.055 * cos(a))
            },
            radius = 0.017,
            sides = 10,
        )

        log("built $vertexCount vertices, ${indexCount / 3} triangles")
    }

    uniform("light", 0.4, 0.85, 0.5)
    onFrame { frame -> uniform("spin", frame.time * 0.4) }

    onStop { println("geometry shapes stopped") }
    println("geometry shapes running")
}
