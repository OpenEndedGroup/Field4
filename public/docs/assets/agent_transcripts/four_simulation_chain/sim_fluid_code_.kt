import afield.four.fabricated.FabricatePassChain
import afield.four.fabricated.FabricatePassLibrary
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.addPassChain
import afield.four.util.DownResult
import afield.four.util.MouseEvent
import afield.four.util.MouseEvents
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f

prop<afield.four.fabricated.FabricateBuiltChain>(fluid)

val r: Renderer = fromContext()
val b: StandardRenderer.Bundle = fromContext()

// Twenty-seven passes, declared once. `fluid-velocity` and `fluid-dye` are persistent, so
// they get ping-pong targets that survive the frame; everything between them is scratch
// the planner packs onto five shared slots. `printReport` prints the whole thing — read it
// before believing the picture, because a chain planned at the wrong resolution renders
// something perfectly plausible.
once.fluid {
    // The composite is part of **this** chain, not a second `addPassChain` call, and that
    // is not a style preference. `addPassChain` plans against `postStack.output()` at the
    // moment it is called, so in a later call `CHAIN_INPUT` means *this chain's output* —
    // and a composite in that later chain naming `fluid-dye` as its blurred input fails
    // with "fluid-dye is not a pass in this chain". Declaring both together is what makes
    // `sharp = CHAIN_INPUT` mean the full-resolution frame the fluid is being laid over.
    val c = b.addPassChain(
        FabricatePassLibrary.stableFluid(
            prefix = "fluid",
            resolution = 256,
            iterations = 20,
            dyeDissipation = 0.99f,
            vorticity = 24f
        ) + FabricatePassLibrary.composite(
            // The chain otherwise ends on the 256² simulation grid; this rejoins at full
            // resolution, and it is the same `composite` the aperture chain uses.
            name = "fluid-show",
            blurred = "fluid-dye",
            sharp = FabricatePassChain.CHAIN_INPUT,
            blend = 0f,
            gain = 1f,
            add = 1f
        ),
        renderer = r,
        printReport = true
    )

    // ── painting. Nothing in the library reads a mouse; the splat is push constants,
    //    which is the seam that keeps the chain a declaration. The `force` and `dye`
    //    passes deliberately share these names, so one handler drives both.
    //
    //    The `onDown` is not optional even though it does nothing: MouseEvents only polls
    //    the buttons some handler has *asked* about (`buttonsRequested = down.keys +
    //    up.keys`), and `onDrag` only fires while a button is down — so with no
    //    down-handler registered for "left", no drag ever arrives and the fluid sits there
    //    looking broken.
    //    And the lambdas name their parameter type on purpose. `onDown` and `onUp` are
    //    each overloaded on `() -> Boolean` and a `(MouseEvent) -> …` form, and a bare
    //    `{ true }` is an overload resolution ambiguity *in a box* — the box compiler does
    //    not pick the no-argument candidate the way the surrounding Kotlin does. Typing
    //    the parameter is what resolves it.
    val mouse = MouseEvents(r)
    mouse.onDown("left", "fluid-paint") { _: MouseEvent -> DownResult.Keep }
    mouse.onDrag("fluid-paint") { ev ->
        val u = (ev.pos.x / r.window.width.toFloat()).coerceIn(0f, 1f)
        val v = (ev.pos.y / r.window.height.toFloat()).coerceIn(0f, 1f)
        listOf("fluid-force", "fluid-dye").forEach { pass ->
            c.setPush(pass, "splatCenter", Vector2f(u, v))
            c.setPush(pass, "splatVelocity", Vector2f(ev.delta.x * 30f, ev.delta.y * 30f))
            c.setPush(pass, "splatColor", Vector3f(0.35f, 0.8f, 1.0f))
            c.setPush(pass, "splatRadius", 0.04f)
            c.setPush(pass, "splatMass", 0.05f)
        }
        true                       // returning false *removes* the handler
    }
    // Stop injecting the moment the button comes up, or the last splat keeps pumping
    // forever at wherever the drag happened to end.
    mouse.onUp("left", "fluid-paint") { _: MouseEvent ->
        listOf("fluid-force", "fluid-dye").forEach { pass ->
            c.setPush(pass, "splatVelocity", Vector2f(0f, 0f))
            c.setPush(pass, "splatMass", 0f)
        }
        true
    }

    b.attachTo(r)
    b.present(renderer = r)
    c
}

// ── live knobs. Edit a number, hit run. No rebuild, no reallocation. ──
fluid.setPush("fluid-dye", "dissipation", 0.99f)
fluid.setPush("fluid-force", "vorticity", 24f)

println("drag in the window to paint. state fields: ${fluid.stateNames()}")
