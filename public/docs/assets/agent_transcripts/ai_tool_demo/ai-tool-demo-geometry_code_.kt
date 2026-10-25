/*
A tool that hands the model a PICTURE.

GeometryAgentTools (afield.graphics.render) is a real, public tool set built on the same
AgentToolExtensions seam as the box to the left, over the loaders in
public/docs/geometry_from_disk.md. It does the thing that makes a geometry tool worth
having: the model can LOOK at what it just asked about, because the result implements
CarriesToolImages and the render is CPU-only — no GPU, no window, no AWT.

This box builds a mesh, writes it somewhere the tools are allowed to read, and then calls
them exactly as a model would. No API key, no model.
*/

import java.io.File

// ---------------------------------------------------------------- somewhere to look

/*
The search roots are the ENTIRE permission model for these tools: they take a path from a
language model, and will not read outside a root you added. Point this at your own scans
or exports instead — .obj .stl .ply .glb .gltf .fbx .dae all load.
*/
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

/*
Both tools are gated on there being somewhere to look. A gate is asked on every turn, and
a closed one removes the tool from the list the model is shown rather than leaving it
visible and failing when called — seventeen schemas on every request is a real cost for a
conversation that is not about the subject.
*/
println(
    "with a root: " +
        AgentToolExtensions.availableToolNames(ctx).filter {
            it.endsWith("GeometryFile") || it.endsWith("GeometryFiles")
        },
)

// ---------------------------------------------------------------- the calls

println()
println("--- ListGeometryFiles {} ---")
println(AgentToolExtensions.execute(ctx, "ListGeometryFiles", "{}"))

println()
println("--- InspectGeometryFile ---")
val report =
    AgentToolExtensions.execute(
        ctx,
        "InspectGeometryFile",
        """{"path": "knot.obj", "yawDegrees": 34.0}""",
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

/*
The picture does NOT travel in the JSON above. CarriesToolImages annotates toolImages
@get:JsonIgnore, because a base64 PNG in a function result is spent as text tokens and
buys nothing — a model cannot see an image encoded inside a JSON string. The bridge lifts
it onto the conversation as a real image message instead. Here it is:
*/
val image = (report as CarriesToolImages).toolImages.single()
display(
    "<div style='color:#9fb0c4;font:12px sans-serif;padding-bottom:6px'>" +
        image.caption +
        "</div>" +
        "<img src='" +
        image.dataUrl +
        "' style='width:100%;border-radius:4px'/>",
)

println()
println("Change yawDegrees above and re-run to see the other side — which is exactly what a")
println("model does when it is not sure, and the reason the camera is an argument at all.")
