import afield.io.mqtt.mqtt
import java.util.UUID

prop<String>(routingTopic)
routingTopic = "field/routing/${UUID.randomUUID()}"

val direct = "direct" to ("console" to "screen")
val send = "send" to ("console" to "relay")
val forward = "forward" to ("relay" to "screen")
val links = [direct, send, forward]
val positions = mapOf("console" to vec(30, 55, 0), "relay" to vec(210, 165, 0), "screen" to vec(390, 55, 0))
val teal = vec(0.23, 0.83, 0.74, 1.0)
val grey = vec(0.35, 0.4, 0.47, 1.0)

val model = Datalog()
model.add(
    """
    reach(A, B) :- link(A, B).
    reach(A, C) :- reach(A, B), link(B, C).
    """,
)

fun showSnapshot(active: Set<String>) {
    val routes = model.rowsOf("reach")
    val proof = model.why("reach(console, screen)")
    val reachable = routes.filter { it[0] == "console" }.map { it[1] }.toSet() + "console"
    val diagram = buildList {
        links.forEach { (name, endpoints) ->
            val (from, to) = endpoints
            add(
                fline {
                    moveTo(positions.getValue(from))
                    lineTo(positions.getValue(to))
                    color = if (name in active) teal else grey
                    thickness = if (name in active) 4.0 else 1.0
                },
            )
        }
        positions.forEach { (name, at) ->
            add(
                fline {
                    circle(at.x.toDouble(), at.y.toDouble(), 9.0)
                    filled = true
                    color = if (name in reachable) teal else grey
                },
            )
        }
    }
    lines(diagram, w = 480.0, h = 170.0, fitToSize = true)
    val status = if (proof == null) "Screen unreachable" else "Screen reachable"
    val detail = proof?.render() ?: "No live route from the console."
    markdown("route", "### $status\n\nConsole (left), relay (bottom), screen (right).\n\n~~~\n$detail\n~~~")
    println(status)
}

markdown("route", "Waiting for a routing snapshot.")
var hasSnapshot = false
mqtt.subscribe(routingTopic, key = "routing") { _, message ->
    val flags = links.mapNotNull { (name, _) ->
        message.number(name)?.takeIf { it == 0.0 || it == 1.0 }?.let { name to it }
    }
    if (flags.size == links.size) {
        val active = flags.filter { it.second == 1.0 }.map { it.first }.toSet()
        val changed = model.replaceFacts(
            "link",
            links.filter { it.first in active }.map { (_, endpoints) -> [endpoints.first, endpoints.second] },
        )
        if (!hasSnapshot || changed) showSnapshot(active)
        hasSnapshot = true
    }
}
