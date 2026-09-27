# Field Document: four_summary_pyramid

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. renderer

<!-- field-box id="sp_renderer" index="0" -->

```edn [field-box id="sp_renderer" role="properties"]
{"id" "sp_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["sp_bundle"] "properties" {"children" #{#afield/box "sp_bundle"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sp_renderer_code_.kt" "lines" 11 "characters" 255 "sha256" "8251a3f6a4fdf54cacf976a2bd1dcd6a751183b0b9587f88304830c00777ea1f"}]}
```

```kotlin [field-property box="sp_renderer" name="code" path="sp_renderer_code_.kt"]
import afield.four.util.Options
prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
once.renderer {
    window = Window("Summary pyramid")
    Renderer(window)
}
println("renderer ready")
start(renderer::run)
```

## 2. bundle

<!-- field-box id="sp_bundle" index="1" -->

### Narrative

From `code`

`afield.four.fabricated` is not among a box's automatic imports, unlike `afield.four`
itself. And the `prop<>` type argument is written out in full on purpose: `prop<>`
rewrites the box's code and the rewrite does not carry the snippet's own imports, so
`prop<StandardRenderer.Bundle>(bundle)` under an
`import afield.four.fabricated.StandardRenderer` fails with
`Unresolved reference 'StandardRenderer'` reported against the *import* line.

```edn [field-box id="sp_bundle" role="properties"]
{"id" "sp_bundle" "index" 1 "name" "bundle" "parents" ["root" "sp_renderer"] "children" ["sp_summary"] "properties" {"children" #{#afield/box "sp_summary"} "parents" #{#afield/box "root" #afield/box "sp_renderer"} "name" "bundle" "_tombstone" false "frame" #afield/rect [380.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sp_bundle_code_.kt" "lines" 33 "characters" 1405 "sha256" "fd91de787c436466d30ce1daf97b4c0488bd130a2f26b67c131cebeb36b118d0"}]}
```

```kotlin [field-property box="sp_bundle" name="code" path="sp_bundle_code_.kt"]
// `afield.four.fabricated` is not among a box's automatic imports, unlike `afield.four`
// itself. And the `prop<>` type argument is written out in full on purpose: `prop<>`
// rewrites the box's code and the rewrite does not carry the snippet's own imports, so
// `prop<StandardRenderer.Bundle>(bundle)` under an
// `import afield.four.fabricated.StandardRenderer` fails with
// `Unresolved reference 'StandardRenderer'` reported against the *import* line.
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.applyScenePlan
import afield.stdlib.linalg.Vector3f

prop<afield.four.fabricated.StandardRenderer.Bundle>(bundle)

// `renderer` is the parent box's `prop<Renderer>`, visible here through the preamble —
// there is nothing to fetch with `fromContext()`.
//
// This one *is* a `once`, unlike the chain box below, and for a reason rather than out of
// habit: `StandardRenderer.build` has no replace-in-place path, so re-running it would
// strand the bundle the chain is appended to.
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

## 3. summary pyramid

<!-- field-box id="sp_summary" index="2" -->

```edn [field-box id="sp_summary" role="properties"]
{"id" "sp_summary" "index" 2 "name" "summary pyramid" "parents" ["root" "sp_bundle"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "sp_bundle"} "name" "summary pyramid" "_tombstone" false "frame" #afield/rect [720.0 40.0 320.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sp_summary_code_.kt" "lines" 139 "characters" 6723 "sha256" "54bf91fb82219207af0e3a5aa7c5bb310e68fcd81cdd0b68d07252679dfb3bb5"}]}
```

```kotlin [field-property box="sp_summary" name="code" path="sp_summary_code_.kt"]
import afield.four.fabricated.FabricatePassChain
import afield.four.fabricated.FabricatePassLibrary
import afield.four.fabricated.FabricatePassSize
import afield.four.fabricated.FabricatePassSpec
import afield.four.fabricated.addPassChain
import org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT

prop<afield.four.fabricated.FabricateBuiltChain>(summary)

// The field being summarised: three anisotropic blobs that drift and rotate, with the
// mass in alpha and a value ramp in red. A chain head that synthesises its own input is
// self-contained whatever the post stack looked like before it.
val fieldFragment = """
    #version 450
    layout(location = 0) in vec2 vUv;
    layout(push_constant) uniform FieldPush {
        float time;
    } push;
    layout(location = 0) out vec4 outColor;

    float blob(vec2 uv, vec2 centre, float lean, vec2 radii) {
        vec2 d = uv - centre;
        float s = sin(lean);
        float c = cos(lean);
        vec2 r = vec2(d.x * c + d.y * s, -d.x * s + d.y * c) / radii;
        return exp(-dot(r, r));
    }

    void main() {
        float t = push.time;
        float mass = blob(vUv, vec2(0.32 + 0.05 * sin(t), 0.34), t * 0.5, vec2(0.10, 0.035))
                   + blob(vUv, vec2(0.70, 0.36 + 0.05 * cos(t * 0.8)), -t * 0.3, vec2(0.05, 0.05))
                   + blob(vUv, vec2(0.50, 0.72), 0.7 + 0.25 * sin(t * 0.6), vec2(0.13, 0.03));
        // A Gaussian is never actually zero, so without a floor every cell in the frame
        // has a weight of about 1e-20 — which is over the overlay's own threshold, and
        // draws an ellipse of the rounding error in an empty cell. The picture is then a
        // grid of ellipses everywhere, with the blobs' own cells lost among them.
        mass = mass < 0.01 ? 0.0 : clamp(mass, 0.0, 1.0);
        // The value is deliberately NOT the mass: a summary carries a value *and* a
        // weight, and keeping them different is what makes "the mean value where the mass
        // is" a different question from "how much mass".
        float value = 0.25 + 0.7 * vUv.y;
        outColor = vec4(value, mass * 0.45, mass, mass);
    }
"""

// The field as a *picture*, which is not the same thing as the field. `field` carries a
// value in red over the whole frame because that is what the pyramid weighs and averages;
// looking at it directly is looking at a red wash, because the mass that says which part
// of the frame matters is in the alpha channel. One pass turns it into something to draw
// on: the value's colour, where the mass is.
val viewFragment = """
    #version 450
    layout(location = 0) in vec2 vUv;
    layout(set = 0, binding = 0) uniform sampler2D sourceTex;
    layout(location = 0) out vec4 outColor;

    void main() {
        vec4 f = texture(sourceTex, vUv);
        vec3 warm = mix(vec3(0.65, 0.16, 0.06), vec3(1.0, 0.82, 0.30), f.r);
        outColor = vec4(warm * f.a, 1.0);
    }
"""

// Declared every run. `addPassChain` replaces passes of the same name and reuses a target
// whose shape has not changed, so editing a number and hitting run remakes the chain
// without reallocating anything — which is why there is no `once` here. Wrapping the
// declaration in one would mean a `resolution` you edited never took effect.
summary = bundle.addPassChain(
    listOf(
        FabricatePassSpec(
            name = "field",
            fragment = fieldFragment,
            size = FabricatePassSize.Absolute(256, 256),
            // fp32, and it matters here rather than being caution: the second moments are
            // differences that need every bit of the first ones, and an 8-bit field would
            // quantise the mass before the pyramid ever saw it.
            format = VK_FORMAT_R32G32B32A32_SFLOAT,
            source = FabricatePassChain.CHAIN_INPUT,
            push = mapOf("time" to 0f)
        ),
        FabricatePassSpec(
            name = "field-view",
            fragment = viewFragment,
            size = FabricatePassSize.Absolute(512, 512),
            source = "field"
        )
    ) + FabricatePassLibrary.summaryPyramid(
        // Nine levels per plane, three planes: the leaf is 256² and the root is one cell
        // holding the statistics of the whole field.
        prefix = "summary",
        resolution = 256,
        source = "field",
        weightChannel = 3,
        valueChannel = 0
    ) + FabricatePassLibrary.adaptiveSummaryCells(
        // The half that makes it a quadtree rather than a pyramid: descend from depth 1,
        // stop wherever the cell already describes its contents well enough, and draw the
        // cells you stopped at. Empty space keeps its big cells and the blobs' edges are
        // resolved six levels down, in one pass chain, from one tree.
        //
        // Two passes per level and two *resolutions* per level: the decision runs at the
        // tree's scale (one texel per cell) and the drawing at the picture's.
        prefix = "summary",
        name = "cut",
        over = "field-view",
        coarsest = 1,
        finest = 6,
        // Refine where the cell is partly full — so the cut tracks the blobs' boundaries
        // and leaves both the inside and the outside coarse. Try SPREAD instead to refine
        // where the mass is narrower than its cell, or VALUE_VARIANCE for the classic
        // image quadtree, which here follows the value ramp rather than the blobs.
        criterion = FabricatePassLibrary.SubdivisionCriterion.OCCUPANCY,
        threshold = 0.02f,
        weightGain = 3f
    ),
    printReport = true
)
// The frame graph has to re-sync before the new passes are scheduled.
bundle.attachTo(renderer)
bundle.present(renderer = renderer)

// ── live knobs ──
//
// A lambda *binds*: the supplier is re-read every time the push buffer is written, so
// this one line is the whole animation and there is nothing to call per frame. The whole
// pyramid is rebuilt from the field every frame — twenty-seven passes over 256² and down,
// which is cheaper than the blur in four_pass_chain.
summary["time"] = { renderer.frameCounter * 0.01f }
summary["sigma"] = 1f           // the ellipse's radius, in standard deviations
summary["weightGain"] = 3f      // a mean coverage is a small number; this lifts the fill
// `threshold` reaches every level of the descent at once, because the cut's select
// passes all declare it — so turning this one number re-cuts the whole tree on the next
// frame with nothing rebuilt and nothing reallocated.
summary["threshold"] = 0.02f

println("summary pyramid: ${summary.names().size} passes, " +
        "root ${summary.targetOf("summary-value-0")?.width}x" +
        "${summary.targetOf("summary-value-0")?.height}")
```

