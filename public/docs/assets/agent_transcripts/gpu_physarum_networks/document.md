# Field Document: gpu_physarum_networks

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. renderer

<!-- field-box id="net_renderer" index="0" -->

```edn [field-box id="net_renderer" role="properties"]
{"id" "net_renderer" "index" 0 "name" "renderer" "parents" ["root"] "children" ["net_slime"] "properties" {"children" #{#afield/box "net_slime"} "parents" #{#afield/box "root"} "name" "renderer" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "net_renderer_code_.kt" "lines" 14 "characters" 470 "sha256" "bfadf7ebed01508d10340cf3a2cd3eeab9519a6437a86f8081d89f40fd1b9cc1"}]}
```

```kotlin [field-property box="net_renderer" name="code" path="net_renderer_code_.kt"]
import afield.graphics.vulkan.util.Options

prop<Window>(window)
prop<Renderer>(renderer)
Options.enableHDR = false
Options.profiling = false
// once: re-running this box keeps the existing window and renderer
once.renderer {
    window = Window("Physarum networks", initialDimensions = Rect(80.0, 80.0, 640.0, 640.0))
    Renderer(window)
}
println("renderer ready")
// the frame loop runs as this box's coroutine; stopping the box stops rendering
start(renderer::run)
```

## 2. slime

<!-- field-box id="net_slime" index="1" -->

### Narrative

From `code`

Jones' Physarum on a 512 x 512 trail, fed at the dots in the frame "network". `exclusive`
is Jones' one-agent-per-pixel rule; `recycle` gives the field an edge, and an agent that
reaches it re-enters at a random food source, so the 4000 agents are a budget of road:
fewer give a tree between the dots, more give loops.

```edn [field-box id="net_slime" role="properties"]
{"id" "net_slime" "index" 1 "name" "slime" "parents" ["root" "net_renderer"] "children" ["net_measure" "net_reset"] "properties" {"children" #{#afield/box "net_measure" #afield/box "net_reset"} "parents" #{#afield/box "root" #afield/box "net_renderer"} "name" "slime" "_tombstone" false "frame" #afield/rect [400.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "net_slime_code_.kt" "lines" 27 "characters" 1070 "sha256" "a84d25833d71cbeb0afa0fe74c9b5eca390b8ff971fd8952af05f5489194911f"}]}
```

```kotlin [field-property box="net_slime" name="code" path="net_slime_code_.kt"]
/*
Jones' Physarum on a 512 x 512 trail, fed at the dots in the frame "network". `exclusive`
is Jones' one-agent-per-pixel rule; `recycle` gives the field an edge, and an agent that
reaches it re-enters at a random food source, so the 4000 agents are a budget of road:
fewer give a tree between the dots, more give loops.
*/
val side = 512
// the frame, read as trail pixels: x across, and y up, as the window draws the field
val map = notation.space("network", x = 0.0..side.toDouble(), y = side.toDouble()..0.0)

fun dots() = map.marks.filter { it.kind == MarkType.ELLIPSE }.map { map.toSpace(it.center) }

prop<ShaderPhysarumSystem>(physarum) = ShaderPhysarumSystem() {
    width = side
    height = side
    agentCount = 4000
    exclusive = true
    recycle = true
    food = dots()
    exposure = 0.25f
}
// steps once a frame and draws the trail
physarum.installInto()

// every edit to the drawing moves the food
notation.onChange { physarum.settings.food = dots() }
println("${physarum.settings.food.size} food sources, ${physarum.settings.agentCount} agents")
```

## 3. measure

<!-- field-box id="net_measure" index="2" -->

### Narrative

From `code`

Every 120 frames: read the trail back, extract the network between the food sources and
score it as Tero did, beside the minimum spanning tree and the Delaunay triangulation of
the same dots. A road is a pixel at a quarter of the level an agent standing on it every
frame would reach. The slider sets the trail's half-life: shorter thins the network
towards a tree, longer lets it hold loops.

```edn [field-box id="net_measure" role="properties"]
{"id" "net_measure" "index" 2 "name" "measure" "parents" ["root" "net_slime"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "net_slime"} "name" "measure" "_tombstone" false "frame" #afield/rect [760.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "net_measure_code_.kt" "lines" 35 "characters" 1517 "sha256" "caec7763e47f8d971a38d10d736013950879648877dab27b97353afbc9550241"}]}
```

```kotlin [field-property box="net_measure" name="code" path="net_measure_code_.kt"]
/*
Every 120 frames: read the trail back, extract the network between the food sources and
score it as Tero did, beside the minimum spanning tree and the Delaunay triangulation of
the same dots. A road is a pixel at a quarter of the level an agent standing on it every
frame would reach. The slider sets the trail's half-life: shorter thins the network
towards a tree, longer lets it hold loops.
*/
slider("half-life (frames)", min = 2.0, max = 40.0, step = 0.5, initialValue = 8.3) { h ->
    physarum.trail.settings.halfLifeFrames = vec(h, h, h, h)
}

frames {
    while (frame()) {
        val pending = physarum.trail.read()
        while (!pending.isDone && frame()) {}
        if (!pending.isDone) break
        val trail = pending.get().channel(0)
        val food = physarum.settings.food
        val decay = physarum.trail.decayFactors().x
        val network = TransportNetwork.extract(trail, food, threshold = 0.25 / (1.0 - decay))
        val scores = listOf(
            network.score("physarum"),
            TransportNetwork.mst(food).score("MST"),
            TransportNetwork.delaunay(food).score("Delaunay"),
        )
        display("scores", "<pre>" + NetworkScore.table(scores) + "</pre>")
        // flipped, so it reads the same way up as the drawing and the window
        showImg(
            network.drawOver(trail.colormap("magma", 0f, (1.0 / (1.0 - decay)).toFloat())).flipY(),
            channel = "network",
            maxSide = 512,
        )
        repeat(120) { frame() }
    }
}
```

## 4. map

<!-- field-box id="net_map" index="3" -->

### Narrative

From `code`

The map: a frame named "network" on the drawing layer (Alt+D) and a first set of food
sources as dots inside it. The frame is the whole trail field, so a dot's place in the
frame is its place in the field. Draw more dots (any ellipse) inside the frame, drag
them, delete them: the slime box follows. Re-running this box puts back the dots it
drew and leaves yours alone.

```edn [field-box id="net_map" role="properties"]
{"id" "net_map" "index" 3 "name" "map" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "map" "_tombstone" false "frame" #afield/rect [40.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "net_map_code_.kt" "lines" 30 "characters" 901 "sha256" "fe84eb0562517c1ff84af1e84a1bf5a5fb261ce130fd8b0cfc19f059f7aa3aae"}]}
```

```kotlin [field-property box="net_map" name="code" path="net_map_code_.kt"]
/*
The map: a frame named "network" on the drawing layer (Alt+D) and a first set of food
sources as dots inside it. The frame is the whole trail field, so a dot's place in the
frame is its place in the field. Draw more dots (any ellipse) inside the frame, drag
them, delete them: the slime box follows. Re-running this box puts back the dots it
drew and leaves yours alone.
*/
val left = 400.0
val top = 200.0
val side = 400.0
val dots = listOf(
    0.22 to 0.30,
    0.38 to 0.18,
    0.62 to 0.22,
    0.80 to 0.35,
    0.30 to 0.52,
    0.52 to 0.45,
    0.72 to 0.58,
    0.20 to 0.75,
    0.45 to 0.70,
    0.62 to 0.82,
    0.84 to 0.78,
    0.50 to 0.30,
)
notation {
    frame("network", left, top, side, side) {
        for ((u, v) in dots) ellipse(left + u * side - 6, top + v * side - 6, 12, 12, fill = "#ffd8a8")
    }
}
println("${dots.size} food sources drawn in the frame \"network\"")
```

## 5. reset

<!-- field-box id="net_reset" index="4" -->

### Narrative

From `code`

empty the trail and scatter the agents again; the food stays where it is drawn

```edn [field-box id="net_reset" role="properties"]
{"id" "net_reset" "index" 4 "name" "reset" "parents" ["root" "net_slime"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "net_slime"} "name" "reset" "_tombstone" false "frame" #afield/rect [400.0 200.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "net_reset_code_.kt" "lines" 4 "characters" 162 "sha256" "4fa159d7b37a5f90d11d62c12afd63190469f1b3bfb1293d1df0aa0bd6262fab"}]}
```

```kotlin [field-property box="net_reset" name="code" path="net_reset_code_.kt"]
// empty the trail and scatter the agents again; the food stays where it is drawn
physarum.trail.clear()
physarum.resetAgents(System.nanoTime())
println("reset")
```

