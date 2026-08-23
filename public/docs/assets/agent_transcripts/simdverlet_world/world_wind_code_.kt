import afield.four.physics.*
import afield.graphics.FLine
import afield.stdlib.vec

// A flag. It lies in the x/y plane and flaps in z, which is why the view at the bottom is
// from ABOVE: seen from the front this is a rectangle standing perfectly still.
val sim = SimdVerlet(SimdVerlet.State3.empty())
val cloth = sim.addCloth(
    width = 26,
    height = 16,
    spacing = 0.2f,
    stiffness = 60f,
    curvatureStrength = 0f,
    position = { c, r -> vec(c * 0.2f, r * 0.2f, 0f) },
    pinned = { c, _ -> c == 0 },
)

// Two models of air, and they are not alternatives.
//
// `sim.addWind` is per-particle drag, F = drag * (w - v). It works on anything and it cannot
// tell which way the fabric is facing -- two identical sheets, one face-on and one edge-on,
// get exactly the same force out of it. So it leans a sheet; it does not fly one.
val wind = sim.addWind(
    velocityX = 9f,
    drag = 0.05f,
    turbulence = 0.7f,       // gusts, as a fraction of the mean speed
    turbulenceScale = 0.5f,  // inverse gust size, in world units
    seed = 12,               // same seed, same weather, forever
)

// `cloth.addWind` is the per-quad model, which uses the sheet's own grid: normal from the
// diagonals, area from half its length, F = 0.5 rho Cd A (n . v_rel) |n . v_rel| n quartered
// onto the corners. That single (n . v_rel) is the flutter: a quad turning edge-on stops being
// pushed, overshoots, catches the wind on its other face, and the wave runs down the sheet.
// Comment this line out and watch the flag stop flying and start leaning.
val aero = cloth.addWind(wind)

// The view from above: keep x, and put z where y usually goes.
fun topView(lines: List<FLine>) = lines.map { it.transform { p -> vec(p.x, p.z, 0.0) } }

val view = Rect(-0.5, -1.3, 6.0, 2.6)
var frames = 0

loop {
    cloth.step(dt = 1f / 60f, damping = 0.98f, gravity = 2f, iterations = 16)
    frames++

    showPlane(
        topView(sim.debugLines(points = false, thickness = 0.9)) +
            topView(wind.windLines(-0.4f, 0f, -1.1f, 5.6f, 0f, 1.1f, columns = 10, rows = 7, scale = 0.05f)),
        window = view,
        title = "a flag in gusty wind, seen from above",
        caption = "frame $frames · aero impulse ${"%.1f".format(aero.lastImpulse)} · green: the wind field",
    )
}
