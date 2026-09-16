import afield.four.fabricated.FabricatePassChain
import afield.four.fabricated.FabricatePassLibrary
import afield.four.fabricated.addPassChain
import afield.four.util.DownResult
import afield.four.util.MouseEvent
import afield.four.util.MouseEvents

prop<afield.four.fabricated.FabricateBuiltChain>(fluid)

// Twenty-seven passes, declared every run. `addPassChain` replaces passes of the same
// name, and reuses a target whose shape has not changed — so editing a shader or a number
// and hitting run remakes the chain without dropping the fluid, while changing
// `resolution` gives you a new, empty one. That is why there is no `once` here: wrapping
// the declaration in one would mean a resolution you edited never took effect.
//
// `renderer` and `bundle` come from the parent boxes' `prop<>`s through the preamble, so
// there is nothing to fetch.
fluid = bundle.addPassChain(
    FabricatePassLibrary.stableFluid(
        prefix = "fluid",
        resolution = 256,
        iterations = 20,
        dyeDissipation = 0.99f,
        vorticity = 24f
    ) + FabricatePassLibrary.composite(
        // The chain otherwise ends on the 256x256 simulation grid; this rejoins at full
        // resolution, and it is the same `composite` the aperture chain uses. It belongs
        // to *this* chain rather than a second `addPassChain` call: chain input is
        // `postStack.output()` at call time, so in a later call `CHAIN_INPUT` would mean
        // this chain's own output and naming `fluid-dye` would fail.
        name = "fluid-show",
        blurred = "fluid-dye",
        sharp = FabricatePassChain.CHAIN_INPUT,
        blend = 0f,
        gain = 1f,
        add = 1f
    ),
    printReport = true
)
// The frame graph has to re-sync before the new passes are scheduled.
bundle.attachTo(renderer)
bundle.present(renderer = renderer)

// ── painting ──
//
// A lambda *binds*: the supplier is re-read every time the push buffer is written, so
// these four lines are the whole animation and the drag handler below only moves a `var`.
// `fluid[name]` sets every pass that declares `name`, which is why one assignment reaches
// both the force pass and the dye pass — `stableFluid` gives them the same names on
// purpose.
var paintAt = vec(0.5, 0.5)
var paintPush = vec(0, 0)
var paintMass = 0f

fluid["splatCenter"] = { paintAt }
fluid["splatVelocity"] = { paintPush }
fluid["splatMass"] = { paintMass }
fluid["splatColor"] = vec(0.35, 0.8, 1.0)
fluid["splatRadius"] = 0.04f

// Handlers are held by name, so re-running replaces them rather than stacking a second
// copy — no `once` needed here either.
//
// The `onDown` is not optional even though it does nothing: MouseEvents only polls the
// buttons some handler has *asked* about (`buttonsRequested = down.keys + up.keys`), and
// `onDrag` only fires while a button is down, so without it no drag ever arrives and the
// fluid sits there looking broken. And both lambdas name their parameter type because
// `onDown`/`onUp` are overloaded on `() -> Boolean` and a `(MouseEvent) -> ...` form, and a
// bare `{ true }` is ambiguous — everywhere, not just in a box.
val mouse = MouseEvents(renderer)
mouse.onDown("left", "fluid-paint") { _: MouseEvent -> DownResult.Keep }
mouse.onDrag("fluid-paint") { ev ->
    paintAt = vec(ev.pos.x / renderer.window.width, ev.pos.y / renderer.window.height)
    paintPush = vec(ev.delta.x * 30, ev.delta.y * 30)
    paintMass = 0.05f
    true                       // returning false *removes* the handler
}
mouse.onUp("left", "fluid-paint") { _: MouseEvent ->
    paintPush = vec(0, 0)      // or the last splat pumps forever where the drag ended
    paintMass = 0f
    true
}

println("drag in the window to paint. state fields: ${fluid.stateNames()}")
