import afield.io.osc.query.*

/*
Publish four parameters as an OSCQuery service on http port 9020 (OSC UDP on 9021),
advertised over DNS-SD so TouchDesigner, Max, Chataigne and VDMX list it as "Field demo".
The callbacks run here, on Field's main loop, whenever one of them sets a value.
*/
val q = oscQuery("Field demo")
q.describe("/demo", "parameters published by the OSCQuery demo document")

fun show() = markdown(
    "values",
    q.server.all.values
        .sortedBy { it.path }
        .joinToString("\n") { "- `${it.path}` (${it.type}) = **${it.value.joinToString(", ")}**" },
)

q.float("/demo/cutoff", 20.0..20000.0, 1000.0, "filter cutoff in Hz") { show() }
q.bool("/demo/on", true, "sound on") { show() }
q.menu("/demo/scene", ["dawn", "dusk", "night"], "dusk") { show() }
q.vector("/demo/xy", [0.5, 0.5], description = "a point on a pad") { show() }

show()
markdown("Browse **http://localhost:${q.httpPort}/** for the namespace, or **?HOST_INFO** for the service.")
