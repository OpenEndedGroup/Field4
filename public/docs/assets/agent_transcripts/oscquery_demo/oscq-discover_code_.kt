import afield.oscquery.*

// OSCQuery services announcing themselves on this network (DNS-SD _oscjson._tcp), this Field's
// included once box 1 has run. Each one can be opened with oscQueryRemote(address, port).
val found = oscQueryDiscover(2000)
markdown(if (found.isEmpty()) "No OSCQuery services answered." else
    found.joinToString("\n") { "- **${it.instance}** at `${it.address}:${it.port}`" })
