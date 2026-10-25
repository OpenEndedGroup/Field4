import afield.io.osc.query.*

// The other direction: read an OSCQuery service as a remote. Here it is this Field's own,
// so run box 1 first; point it at TouchDesigner or Chataigne by changing the host and port.
val remote = oscQueryRemote("127.0.0.1", 9020).refresh()
remote["/demo/cutoff"].set(2500.0) // sent as OSC over UDP to the service's OSC_PORT
remote["/demo/scene"].set("night")

markdown(
    "**${remote.name}** — OSC on port ${remote.oscPort}, LISTEN: ${remote.supportsListen}\n\n" +
        remote.params.joinToString("\n") { "- `${it.path}` ${it.type} ${it.range} ${it.description ?: ""}" },
)
