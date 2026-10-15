import afield.graphics.scene.fabricated.FabricatePassChain
import afield.graphics.scene.fabricated.FabricatePassLibrary
import afield.graphics.scene.fabricated.addPassChain
import afield.graphics.vulkan.util.MouseEvent
import afield.graphics.vulkan.util.MouseEvents

prop<afield.graphics.scene.fabricated.FabricateBuiltChain>(fluid)

// The knobs. A new `resolution` starts an empty fluid; the rest keep the one you painted.
val resolution = 256
val iterations = 20 // Jacobi pressure sweeps: fewer leaves divergence, and dye piles up
val dyeDissipation = 0.99f
val vorticity = 24f // 0f smears; higher puts back the curl advection loses
val dragGain = 30 // how hard a drag pushes, per pixel moved

/*
Twenty-seven passes, declared every run. `addPassChain` replaces passes of the same
name, and reuses a target whose shape has not changed — so editing a shader or a number
and hitting run remakes the chain without dropping the fluid, while changing
`resolution` gives you a new, empty one. That is why there is no `once` here: wrapping
the declaration in one would mean a resolution you edited never took effect.

`renderer` and `bundle` come from the parent boxes' `prop<>`s through the preamble, so
there is nothing to fetch.
*/
fluid = bundle.addPassChain(
    FabricatePassLibrary.stableFluid(
        prefix = "fluid",
        resolution = resolution,
        iterations = iterations,
        dyeDissipation = dyeDissipation,
        vorticity = vorticity,
    ) +
        FabricatePassLibrary.composite(
            /*
            The chain otherwise ends on the 256x256 simulation grid; this rejoins at full
            resolution, and it is the same `composite` the aperture chain uses. It belongs
            to *this* chain rather than a second `addPassChain` call: chain input is
            `postStack.output()` at call time, so in a later call `CHAIN_INPUT` would mean
            this chain's own output and naming `fluid-dye` would fail.
            */
            name = "fluid-show",
            blurred = "fluid-dye",
            sharp = FabricatePassChain.CHAIN_INPUT,
            blend = 0f,
            gain = 1f,
            add = 1f,
        ),
    printReport = true,
)
// The frame graph has to re-sync before the new passes are scheduled.
bundle.attachTo(renderer)
bundle.present(renderer = renderer)

/*
── painting ──

A lambda *binds*: the supplier is re-read every time the push buffer is written, so
these three lambdas are the whole animation and the drag handler below only moves a `var`.
`fluid[name]` sets every pass that declares `name`, which is why one assignment reaches
both the force pass and the dye pass — `stableFluid` gives them the same names on
purpose.
*/
var paintAt = vec(0.5, 0.5)
var paintPush = vec(0, 0)
var paintMass = 0f

fluid["splatCenter"] = { paintAt }
fluid["splatVelocity"] = { paintPush }
fluid["splatMass"] = { paintMass }
fluid["splatColor"] = vec(0.35, 0.8, 1.0)
fluid["splatRadius"] = 0.04f

/*
Handlers are held by name, so re-running replaces them rather than stacking a second
copy — no `once` needed here either. `onUp` names its parameter type because it is
overloaded on `() -> Boolean` and a `(MouseEvent) -> Boolean` form, and a bare `{ true }`
is ambiguous.
*/
val mouse = MouseEvents(renderer)
mouse.onDrag("left", "fluid-paint") { ev ->
    paintAt = ev.uv // 0..1 across the window
    paintPush = vec(ev.delta.x * dragGain, ev.delta.y * dragGain)
    paintMass = 0.05f
    true // returning false *removes* the handler
}
mouse.onUp("left", "fluid-paint") { _: MouseEvent ->
    paintPush = vec(0, 0) // or the last splat pumps forever where the drag ended
    paintMass = 0f
    true
}

println("drag in the window to paint. state fields: ${fluid.stateNames()}")
