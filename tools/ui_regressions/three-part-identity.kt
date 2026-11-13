// Part identity: ids, layer paths and metadata reach the viewer; what the reader hides and selects is kept by
// part id across a redraw; a contact sheet styles the same parts differently in each panel.
import afield.geometry.mesh.boxMesh

var identityFrame = 0
fun identityScene(shift: Double) {
    three(channel = "parts", name = "parts", width = 640, height = 420) {
        mesh(boxMesh(1, 1, 1).translated(shift, 0.0, 0.0), id = "a", layer = listOf("Walls"), wireframe = false)
        mesh(boxMesh(1, 1, 1).translated(2, 0, 0), id = "b", layer = listOf("Walls"), wireframe = false)
        mesh(boxMesh(0.4, 1, 0.1).translated(4, 0, 0), id = "door", layer = listOf("Walls", "Doors"), wireframe = false,
            metadata = mapOf("kind" to "swing", "pivot" to listOf(3.8, 0.0, 0.0)))
        mesh(boxMesh(6, 0.1, 2).translated(2, 1, 0), id = "roof", layer = listOf("Roof"), hidden = true, wireframe = false)
        inspect(units = "m")
        controls(parts = true)
        metadata("shift", shift)
        note("frame $identityFrame")
    }
    three(channel = "sheet", name = "sheet", width = 640, height = 300) {
        mesh(boxMesh(1, 1, 1), id = "a", panel = -1, wireframe = false)
        mesh(boxMesh(1, 1, 1).translated(1.5, 0, 0), id = "b", panel = -1, wireframe = false)
        columns(2); helpers(false); background("#ffffff")
        panel(title = "a", styles = mapOf("a" to ThreePartStyle.highlight(), "b" to ThreePartStyle.muted()))
        panel(title = "b", styles = mapOf("b" to ThreePartStyle.highlight(), "a" to ThreePartStyle.muted()))
        note("frame $identityFrame")
    }
}
identityScene(0.0)
slider("shift", 0.0, 1.0, 0.01, 0.0) { identityFrame++; identityScene(-it) }
