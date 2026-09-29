# Field Document: cloth_simdverlet_start

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. 20 x 10 cloth in a SimdVerlet

<!-- field-box id="cloth_start" index="0" -->

```edn [field-box id="cloth_start" role="properties"]
{"id" "cloth_start" "index" 0 "name" "20 x 10 cloth in a SimdVerlet" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "20 x 10 cloth in a SimdVerlet" "_tombstone" false "frame" #afield/rect [80.0 80.0 360.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "cloth_start_code_.kt" "lines" 50 "characters" 2343 "sha256" "1ca4f931131718247c302863e61059d5b3d983d158242919cf653580dcf05463"}]}
```

```kotlin [field-property box="cloth_start" name="code" path="cloth_start_code_.kt"]
import afield.four.physics.*

// The physics system. State3 is the SoA particle store; the simulation owns it and grows
// it as things are added, so it starts empty.
val sim = SimdVerlet(SimdVerlet.State3.empty())

// 20 wide, 10 tall. addCloth appends the particles to whatever is already in `sim`, wires
// the four-slot distance constraints, and registers the curvature force.
//
// The plane view below has y growing DOWN the screen, so the sheet hangs from row 0 and
// gravity is +y. Every fourth column of that row is a nail; the rest of it swings.
//
// stiffness x iterations is the spring constant that matters (the solve accumulates all
// `iterations` corrections into one force). 60 x 16 holds this sheet at ~11% peak strain;
// 150 x 16 diverges. curvatureStrength is a shape force on the same scale, so the
// library's 0.05 default is invisible next to springs this stiff — at 100 it takes about
// 40% of the kink out of the festoons, which is what makes it worth a knob here.
val cloth = sim.addCloth(
    width = 20,
    height = 10,
    spacing = 0.5f,
    stiffness = 60f,
    curvatureStrength = 100f,
    pinned = { col, row -> row == 0 && col % 4 == 0 },
)

// One window for every frame. Without it showPlane re-fits the picture to whatever the
// cloth happens to occupy, and the sheet looks still while the world shrinks around it.
val view = Rect(-1.0, -1.0, 12.0, 9.0)

println("${cloth.count} cloth particles, ${sim.size} in the simulation")

var frames = 0
loop {
    // One frame of physics for the whole simulation: gravity, the per-particle force
    // generators, the registered bulk forces (the cloth's curvature pass), then
    // `iterations` of the constraint solve, then Verlet integration.
    cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = 2f, iterations = 16)
    frames++

    // What the solver holds, not what the scene means: a segment per distance constraint,
    // a dot per particle, pinned particles in red, a whisker per particle's velocity.
    // Try cloth.grabNearest(x, y) and cloth.moveGrabbed(x, y) to drag one through it.
    showPlane(
        sim.debugLines(velocityScale = 30f),
        window = view,
        title = "20 x 10 cloth — SimdVerlet, 4-slot distance constraints",
        caption = "frame $frames · cyan: constraints · green: velocity · red: pinned",
    )
}
```

