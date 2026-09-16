# Field Document: four_simulation_chain

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
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
{"id" "sim_bundle" "index" 1 "name" "bundle" "parents" ["root" "sim_renderer"] "children" ["sim_fluid" "sim_distance"] "properties" {"children" #{#afield/box "sim_fluid" #afield/box "sim_distance"} "parents" #{#afield/box "root" #afield/box "sim_renderer"} "name" "bundle" "_tombstone" false "frame" #afield/rect [380.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sim_bundle_code_.kt" "lines" 30 "characters" 1121 "sha256" "37ef8499fe4840c9000af5090fe93ecbf2cb0989e858f126ae1020db1aa22c39"}]}
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

val r: Renderer = fromContext()

once.bundle {
    val b = StandardRenderer.build(
        r,
        StandardRenderer.Config(
            renderWidth = 1024,
            renderHeight = 1024,
            backgroundColor = Vector3f(0.02f, 0.02f, 0.03f)
        )
    )
    b.applyScenePlan()
    b.present(renderer = r)
    b.attachTo(r)
    b
}
println("bundle ready: ${bundle.postStack.names()}")
```

## 3. distance field

<!-- field-box id="sim_distance" index="2" -->

```edn [field-box id="sim_distance" role="properties"]
{"id" "sim_distance" "index" 2 "name" "distance field" "parents" ["root" "sim_bundle"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "sim_bundle"} "name" "distance field" "_tombstone" false "frame" #afield/rect [1060.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sim_distance_code_.kt" "lines" 103 "characters" 4054 "sha256" "06d07988731ae19a43ee36b1dbb7eba1fd4886c9c1827d41af4ee306dc8f3101"}]}
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

val r: Renderer = fromContext()
val b: StandardRenderer.Bundle = fromContext()

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

// The inset. Two chains in one post stack run in series, so whichever is appended last owns
// the screen — append this one after the fluid and the fluid simply disappears. This pass
// runs at full resolution, passes the incoming frame (the fluid) through, and draws the
// distance field into the top-right *square* quadrant, so both simulations are visible and
// the field keeps its aspect ratio. It is also a worked full-resolution rejoin with a named
// extra input, which is the shape every composite in the library has.
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

once.sdf {
    val mask = FabricatePassSpec(
        name = "mask",
        fragment = maskFragment,
        size = FabricatePassSize.Absolute(256, 256),
        source = FabricatePassChain.CHAIN_INPUT,
        push = mapOf("centre" to Vector2f(0.38f, 0.38f), "radius" to 0.16f, "bar" to 0.02f)
    )
    // Seed, nine flood rounds at halving stride, JFA+1, then the distance — and `signed`,
    // which seeds the *boundary* rather than the interior so one flood gives a field that
    // is negative inside and positive out.
    val c = b.addPassChain(
        listOf(mask) + FabricatePassLibrary.jumpFloodDistance(
            prefix = "sdf",
            resolution = 256,
            source = "mask",
            signed = true,
            scale = 1f / 512f
        ) + FabricatePassLibrary.distanceBands(
            name = "sdf-bands",
            source = "sdf-distance",
            spacing = 0.03f
        ) + FabricatePassSpec(
            name = "sdf-inset",
            fragment = insetFragment,
            // Sized from its primary input, which is the full-resolution frame, while
            // sampling the 256² field. That combination is the whole point of the layer.
            size = FabricatePassSize.MatchSource,
            source = FabricatePassChain.CHAIN_INPUT,
            extraInputs = listOf(FabricatePassInput("bandsTex", "sdf-bands"))
        ),
        renderer = r,
        printReport = true
    )
    b.attachTo(r)
    b.present(renderer = r)
    c
}

// ── live knobs. Move the shape and the whole field re-floods, from scratch, every frame. ──
sdf.setPush("mask", "centre", Vector2f(0.38f, 0.38f))
sdf.setPush("mask", "radius", 0.16f)
sdf.setPush("sdf-bands", "spacing", 0.03f)

println("distance field: ${sdf.names().size} passes, output ${sdf.output.width}²")
```

## 4. fluid

<!-- field-box id="sim_fluid" index="3" -->

```edn [field-box id="sim_fluid" role="properties"]
{"id" "sim_fluid" "index" 3 "name" "fluid" "parents" ["root" "sim_bundle"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "sim_bundle"} "name" "fluid" "_tombstone" false "frame" #afield/rect [720.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sim_fluid_code_.kt" "lines" 96 "characters" 4650 "sha256" "8a9f2c8f5418d3156ba372ecceb35ae5a33d1943d8e5a93c0e492433b0086a8d"}]}
```

```kotlin [field-property box="sim_fluid" name="code" path="sim_fluid_code_.kt"]
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
    //    `{ true }` is an overload resolution ambiguity — everywhere, not just in a box;
    //    the only other caller in the repo, OrbitControls, types its parameter too.
    //    Typing the parameter is what resolves it.
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
```

