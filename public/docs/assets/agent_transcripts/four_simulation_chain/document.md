# Field Document: four_simulation_chain

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="sim_renderer" index="0" -->

```edn [field-box id="sim_renderer" role="properties"]
{"id" "sim_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["sim_bundle"] "properties" {"children" #{#afield/box "sim_bundle"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sim_renderer_code_.kt" "lines" 11 "characters" 257 "sha256" "da1a66d5910c848f9d836b6900a88d22cfa59bf5a909ff8dccf733f5aa9dfbbf"}]}
```

```kotlin [field-property box="sim_renderer" name="code" path="sim_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Simulation chains")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. bundle

<!-- field-box id="sim_bundle" index="1" -->

### Narrative

From `code`

`afield.four.fabricated` is not among a box's automatic imports, unlike `afield.four`
itself. And the `prop<>` type argument is written out in full on purpose: `prop<>`
rewrites the box's code and the rewrite does not carry the snippet's own imports, so
`prop<StandardRenderer.Bundle>(bundle)` under an
`import afield.four.fabricated.StandardRenderer` fails with
`Unresolved reference 'StandardRenderer'` reported against the *import* line, while
every other reference in the box resolves through it normally.

```edn [field-box id="sim_bundle" role="properties"]
{"id" "sim_bundle" "index" 1 "name" "bundle" "parents" ["root" "sim_renderer"] "children" ["sim_fluid" "sim_distance"] "properties" {"children" #{#afield/box "sim_fluid" #afield/box "sim_distance"} "parents" #{#afield/box "root" #afield/box "sim_renderer"} "name" "bundle" "_tombstone" false "frame" #afield/rect [380.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sim_bundle_code_.kt" "lines" 34 "characters" 1497 "sha256" "86d574016923649d34b723fe35a70f3ef614f82ab2cce25a829d8a719a357e89"}]}
```

```kotlin [field-property box="sim_bundle" name="code" path="sim_bundle_code_.kt"]
// `afield.four.fabricated` is not among a box's automatic imports, unlike `afield.four`
// itself. And the `prop<>` type argument is written out in full on purpose: `prop<>`
// rewrites the box's code and the rewrite does not carry the snippet's own imports, so
// `prop<StandardRenderer.Bundle>(bundle)` under an
// `import afield.four.fabricated.StandardRenderer` fails with
// `Unresolved reference 'StandardRenderer'` reported against the *import* line, while
// every other reference in the box resolves through it normally.
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.applyScenePlan
import afield.stdlib.linalg.Vector3f

prop<afield.four.fabricated.StandardRenderer.Bundle>(bundle)

// `renderer` is the parent box's `prop<Renderer>`, visible here through the preamble —
// there is nothing to fetch with `fromContext()`.
//
// This one *is* a `once`, unlike the chain boxes below, and for a reason rather than out
// of habit: `StandardRenderer.build` has no replace-in-place path, so re-running it would
// strand the bundle every chain in the document is appended to.
once.bundle {
    val b = StandardRenderer.build(
        renderer,
        StandardRenderer.Config(
            renderWidth = 1024,
            renderHeight = 1024,
            backgroundColor = Vector3f(0.02f, 0.02f, 0.03f)
        )
    )
    b.applyScenePlan()
    b.present(renderer = renderer)
    b.attachTo(renderer)
    b
}
println("bundle ready: ${bundle.postStack.names()}")
```

## 3. distance field

<!-- field-box id="sim_distance" index="2" -->

```edn [field-box id="sim_distance" role="properties"]
{"id" "sim_distance" "index" 2 "name" "distance field" "parents" ["root" "sim_bundle"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "sim_bundle"} "name" "distance field" "_tombstone" false "frame" #afield/rect [1060.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sim_distance_code_.kt" "lines" 100 "characters" 4049 "sha256" "9e572237e6e28ad0b5d3e63d52a5acc36d450fa61823ea05bdad0ea288f33c49"}]}
```

```kotlin [field-property box="sim_distance" name="code" path="sim_distance_code_.kt"]
import afield.four.fabricated.FabricatePassChain
import afield.four.fabricated.FabricatePassInput
import afield.four.fabricated.FabricatePassLibrary
import afield.four.fabricated.FabricatePassSize
import afield.four.fabricated.FabricatePassSpec
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.addPassChain
import afield.stdlib.linalg.Vector2f

prop<afield.four.fabricated.FabricateBuiltChain>(sdf)

// The mask: a disc and a bar, in every channel. A chain head that synthesises its own
// input is self-contained however the chains before it ended up looking.
val maskFragment = """
    #version 450
    layout(location = 0) in vec2 vUv;
    layout(push_constant) uniform MaskPush {
        vec2 centre;
        float radius;
        float bar;
    } push;
    layout(location = 0) out vec4 outColor;

    void main() {
        float disc = length(vUv - push.centre) <= push.radius ? 1.0 : 0.0;
        float bar = (abs(vUv.y - 0.78) < push.bar && vUv.x > 0.2 && vUv.x < 0.8) ? 1.0 : 0.0;
        float m = max(disc, bar);
        outColor = vec4(m, m, m, m);
    }
"""

// The inset. Two chains in one post stack run in series, so whichever is appended last
// owns the screen — append this one after the fluid and the fluid simply disappears. This
// pass runs at full resolution, passes the incoming frame (the fluid) through, and draws
// the distance field into the top-right *square* quadrant, so both simulations are visible
// and the field keeps its aspect ratio. It is also a worked full-resolution rejoin with a
// named extra input, which is the shape every composite in the library has.
val insetFragment = """
    #version 450
    layout(location = 0) in vec2 vUv;
    layout(set = 0, binding = 0) uniform sampler2D sourceTex;
    layout(set = 0, binding = 1) uniform sampler2D bandsTex;
    layout(location = 0) out vec4 outColor;

    void main() {
        vec4 under = texture(sourceTex, vUv);
        if (vUv.x > 0.5 && vUv.y < 0.5) {
            vec2 q = vec2((vUv.x - 0.5) * 2.0, vUv.y * 2.0);
            outColor = vec4(texture(bandsTex, q).rgb, under.a);
        } else {
            outColor = under;
        }
    }
"""

// Declared every run, like the fluid: `addPassChain` replaces by name. Nothing here is
// persistent, so the whole field simply re-floods from scratch on the next frame.
sdf = bundle.addPassChain(
    listOf(
        FabricatePassSpec(
            name = "mask",
            fragment = maskFragment,
            size = FabricatePassSize.Absolute(256, 256),
            source = FabricatePassChain.CHAIN_INPUT
        )
    ) + FabricatePassLibrary.jumpFloodDistance(
        // Seed, nine flood rounds at halving stride, JFA+1, then the distance — and
        // `signed`, which seeds the *boundary* rather than the interior, so one flood
        // gives a field that is negative inside and positive out.
        prefix = "sdf",
        resolution = 256,
        source = "mask",
        signed = true,
        scale = 1f / 512f
    ) + FabricatePassLibrary.distanceBands(
        name = "sdf-bands",
        source = "sdf-distance"
    ) + FabricatePassSpec(
        name = "sdf-inset",
        fragment = insetFragment,
        // Sized from its primary input, which is the full-resolution frame, while
        // sampling the 256x256 field. That combination is the whole point of the layer.
        size = FabricatePassSize.MatchSource,
        source = FabricatePassChain.CHAIN_INPUT,
        extraInputs = listOf(FabricatePassInput("bandsTex", "sdf-bands"))
    ),
    printReport = true
)
bundle.attachTo(renderer)
bundle.present(renderer = renderer)

// ── live knobs. Move the shape and the whole field re-floods, from scratch, every frame.
//    `sdf[name]` reaches every pass that declares `name`; a lambda would bind a supplier
//    instead, the way the fluid's splat does.
sdf["centre"] = vec(0.38, 0.38)
sdf["radius"] = 0.16f
sdf["bar"] = 0.02f
sdf["spacing"] = 0.03f

println("distance field: ${sdf.names().size} passes, output ${sdf.output.width} across")
```

## 4. fluid

<!-- field-box id="sim_fluid" index="3" -->

```edn [field-box id="sim_fluid" role="properties"]
{"id" "sim_fluid" "index" 3 "name" "fluid" "parents" ["root" "sim_bundle"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "sim_bundle"} "name" "fluid" "_tombstone" false "frame" #afield/rect [720.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sim_fluid_code_.kt" "lines" 84 "characters" 3764 "sha256" "48b175b88a1fcbfc55424491e04a846add587978dea5c3e93041d4999102c260"}]}
```

```kotlin [field-property box="sim_fluid" name="code" path="sim_fluid_code_.kt"]
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
```

