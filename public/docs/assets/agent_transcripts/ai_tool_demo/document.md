# Field Document: ai_tool_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Give the AI a tool — the whole mechanism

<!-- field-box id="ai-tool-demo-minimal" index="0" -->

### Narrative

From `code`

Give Field's AI a tool of its own — the whole mechanism, in one box, and with no API key.

A tool is an ordinary Kotlin class (which becomes the schema the model reads) plus an
ordinary Kotlin lambda (which becomes what happens when the model calls it). Register the
pair through afield.ai.tools.AgentToolExtensions and the model can call it by name.

Nothing in Field has to be edited to add one: the registration goes the other way.

public/docs/aiFeatures.md      — "Give the AI a tool of your own"
dev_docs/ai/AGENT_TOOL_EXTENSIONS.md — the design, and what is still not covered

```edn [field-box id="ai-tool-demo-minimal" role="properties"]
{"id" "ai-tool-demo-minimal" "index" 0 "name" "Give the AI a tool — the whole mechanism" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Give the AI a tool — the whole mechanism" "_tombstone" false "frame" #afield/rect [60.0 60.0 620.0 170.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ai-tool-demo-minimal_code_.kt" "lines" 101 "characters" 4636 "sha256" "f7a1b8115d8db02304da21e170e3546b10aff3460ccfc514e8cd1b2b75d7c745"}]}
```

```kotlin [field-property box="ai-tool-demo-minimal" name="code" path="ai-tool-demo-minimal_code_.kt"]
// Give Field's AI a tool of its own — the whole mechanism, in one box, and with no API key.
//
// A tool is an ordinary Kotlin class (which becomes the schema the model reads) plus an
// ordinary Kotlin lambda (which becomes what happens when the model calls it). Register the
// pair through afield.ai.tools.AgentToolExtensions and the model can call it by name.
//
// Nothing in Field has to be edited to add one: the registration goes the other way.
//
//   public/docs/aiFeatures.md      — "Give the AI a tool of your own"
//   dev_docs/ai/AGENT_TOOL_EXTENSIONS.md — the design, and what is still not covered

import afield.ai.tools.AgentToolExtensions
import afield.ai.tools.SummarisesForToolLog
import afield.ai.tools.ToolExecutionContext
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

// ---------------------------------------------------------------- 1. the request class

// This class IS the schema. Its simple name is the tool's name, and these annotations are
// the only prompt the tool gets — write them for a reader who cannot see the code.
//
//   a parameter with no default  -> "required"
//   a parameter with a default   -> optional, and Kotlin's default is what happens
//   an enum                      -> {"type": "string", "enum": [...]}

@JsonClassDescription("Measure a rectangular room and say whether it meets a minimum floor area.")
data class MeasureRoom(
    @JsonPropertyDescription("The room's width in metres.")
    val width: Double,
    @JsonPropertyDescription("The room's depth in metres.")
    val depth: Double,
    @JsonPropertyDescription("The smallest floor area, in square metres, that counts as acceptable.")
    val minimumArea: Double = 11.0,
)

// ---------------------------------------------------------------- 2. the result

// SummarisesForToolLog is worth implementing on every result. Without it, Field's tool I/O
// log records only the class name, which cannot tell a turn where the model got what it
// needed from one where it got an error and gave up.

data class MeasureRoomResult(
    val area: Double,
    val perimeter: Double,
    val acceptable: Boolean,
    val note: String,
) : SummarisesForToolLog {
    override fun toolLogSummary() = "area=%.2f acceptable=%s".format(area, acceptable)
}

// ---------------------------------------------------------------- 3. register it

// Idempotent by tool NAME, so re-running this box replaces the tool rather than adding a
// second copy of it — and reaches an AI Chat that is already open, on its next turn.
// There is no order you have to follow.

AgentToolExtensions.register(MeasureRoom::class.java) { _, request ->
    val area = request.width * request.depth
    MeasureRoomResult(
        area = area,
        perimeter = 2 * (request.width + request.depth),
        acceptable = area >= request.minimumArea,
        note = if (area >= request.minimumArea)
            "%.2f m2 clears the %.1f m2 minimum by %.2f.".format(area, request.minimumArea, area - request.minimumArea)
        else
            "%.2f m2 is %.2f short of the %.1f m2 minimum.".format(area, request.minimumArea - area, request.minimumArea),
    )
}

// ---------------------------------------------------------------- 4. see it the way a model does

// execute() is the same entry point a model's tool call goes through: a name and a JSON
// string. So you can drive your own tool from a box, with no API key and no model, and see
// exactly what would come back.

val ctx = ToolExecutionContext()

println("--- the schema the model is shown ---")
println(AgentToolExtensions.describe(ctx).single { it.contains("\"MeasureRoom\"") })
println()

println("--- a call the model might make ---")
val big = AgentToolExtensions.execute(ctx, "MeasureRoom", """{"width": 4.2, "depth": 3.1}""") as MeasureRoomResult
println(big.note)
println("tool log line: " + big.toolLogSummary())
println()

// minimumArea was omitted above, so Kotlin's default (11.0) applied. Supply it and the
// same room becomes unacceptable — which is the whole point of testing the JSON path
// rather than calling the handler directly.
val strict = AgentToolExtensions.execute(
    ctx, "MeasureRoom", """{"width": 4.2, "depth": 3.1, "minimumArea": 15.0}"""
) as MeasureRoomResult
println(strict.note)
println()

println("registered right now: " + AgentToolExtensions.availableToolNames(ctx))
println()
println("Now select any box, press cmd-4, and ask: \"is a 4.2 by 3.1 metre room big enough?\"")
println("Edit the handler above, re-run this box, and ask again — the change lands immediately.")
```

## 2. A tool that hands the model a picture

<!-- field-box id="ai-tool-demo-geometry" index="1" -->

### Narrative

From `code`

A tool that hands the model a PICTURE.

GeometryAgentTools (afield.graphics.render) is a real, public tool set built on the same
AgentToolExtensions seam as the box to the left, over the loaders in
public/docs/geometry_from_disk.md. It does the thing that makes a geometry tool worth
having: the model can LOOK at what it just asked about, because the result implements
CarriesToolImages and the render is CPU-only — no GPU, no window, no AWT.

This box builds a mesh, writes it somewhere the tools are allowed to read, and then calls
them exactly as a model would. No API key, no model.

```edn [field-box id="ai-tool-demo-geometry" role="properties"]
{"id" "ai-tool-demo-geometry" "index" 1 "name" "A tool that hands the model a picture" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "A tool that hands the model a picture" "_tombstone" false "frame" #afield/rect [720.0 60.0 620.0 170.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ai-tool-demo-geometry_code_.kt" "lines" 80 "characters" 4064 "sha256" "dea62917c012313c75840feedcf3654d785b49d22d3536b583adb0c1c4bc634d"}]}
```

```kotlin [field-property box="ai-tool-demo-geometry" name="code" path="ai-tool-demo-geometry_code_.kt"]
// A tool that hands the model a PICTURE.
//
// GeometryAgentTools (afield.graphics.render) is a real, public tool set built on the same
// AgentToolExtensions seam as the box to the left, over the loaders in
// public/docs/geometry_from_disk.md. It does the thing that makes a geometry tool worth
// having: the model can LOOK at what it just asked about, because the result implements
// CarriesToolImages and the render is CPU-only — no GPU, no window, no AWT.
//
// This box builds a mesh, writes it somewhere the tools are allowed to read, and then calls
// them exactly as a model would. No API key, no model.

import afield.ai.tools.AgentToolExtensions
import afield.ai.tools.CarriesToolImages
import afield.ai.tools.SummarisesForToolLog
import afield.ai.tools.ToolExecutionContext
import afield.graphics.render.InspectGeometryFileResult
import java.io.File

// ---------------------------------------------------------------- somewhere to look

// The search roots are the ENTIRE permission model for these tools: they take a path from a
// language model, and will not read outside a root you added. Point this at your own scans
// or exports instead — .obj .stl .ply .glb .gltf .fbx .dae all load.
val root = File(System.getProperty("java.io.tmpdir"), "field-ai-tool-demo").also { it.mkdirs() }
if (!File(root, "ball.obj").isFile) {
    icosphereMesh(3, 1.0).withColor(0.3, 0.7, 1.0).saveObj(File(root, "ball.obj"))
}
if (!File(root, "knot.obj").isFile) {
    torusMesh(majorSegments = 64, minorSegments = 32).saveObj(File(root, "knot.obj"))
}

GeometryAgentTools.clearSearchRoots()
GeometryAgentTools.addSearchRoot(root)
GeometryAgentTools.register()

val ctx = ToolExecutionContext()

// ---------------------------------------------------------------- the gate

// Both tools are gated on there being somewhere to look. A gate is asked on every turn, and
// a closed one removes the tool from the list the model is shown rather than leaving it
// visible and failing when called — seventeen schemas on every request is a real cost for a
// conversation that is not about the subject.
println("with a root: " + AgentToolExtensions.availableToolNames(ctx).filter { it.endsWith("GeometryFile") || it.endsWith("GeometryFiles") })

// ---------------------------------------------------------------- the calls

println()
println("--- ListGeometryFiles {} ---")
println(AgentToolExtensions.execute(ctx, "ListGeometryFiles", "{}"))

println()
println("--- InspectGeometryFile ---")
val report = AgentToolExtensions.execute(
    ctx, "InspectGeometryFile", """{"path": "knot.obj", "yawDegrees": 34.0}"""
) as InspectGeometryFileResult

println((report as SummarisesForToolLog).toolLogSummary())
println()
println("  vertices    ${report.vertexCount}")
println("  triangles   ${report.triangleCount}")
println("  extent      %.3f x %.3f x %.3f".format(report.extent[0], report.extent[1], report.extent[2]))
println("  watertight  ${report.watertight}   (a torus is closed, so this is true)")
println("  chi         ${report.eulerCharacteristic}   (0 for a torus — one handle)")
println("  volume      ${report.volume}")
report.notes.forEach { println("  note        " + it) }

// ---------------------------------------------------------------- what the model sees

// The picture does NOT travel in the JSON above. CarriesToolImages annotates toolImages
// @get:JsonIgnore, because a base64 PNG in a function result is spent as text tokens and
// buys nothing — a model cannot see an image encoded inside a JSON string. The bridge lifts
// it onto the conversation as a real image message instead. Here it is:
val image = (report as CarriesToolImages).toolImages.single()
display("<div style='color:#9fb0c4;font:12px sans-serif;padding-bottom:6px'>" + image.caption + "</div>" +
        "<img src='" + image.dataUrl + "' style='width:100%;border-radius:4px'/>")

println()
println("Change yawDegrees above and re-run to see the other side — which is exactly what a")
println("model does when it is not sure, and the reason the camera is an argument at all.")
```

