# OSCQuery: controls other tools can find

[OSC](osc.md) sends values to an address, but the sender has to know the address, the type and
the range in advance. **OSCQuery** lets a program publish that list itself. It serves its
parameters as JSON over HTTP, announces itself on the local network, and pushes changes to
anyone listening. TouchDesigner (the *OSCQuery* COMP), Max (`oscquery` packages), Chataigne, VDMX,
ossia score and the python and JavaScript libraries all speak it.

`afield.oscquery` does it in both directions. A box can publish parameters that those tools
list, show as controls and set. A box can also open another program's OSCQuery service and read,
set and follow its parameters.

Open `public/docs/assets/oscquery_demo/` as a document to try everything on this page with
nothing else running: box 1 publishes, and boxes 3 and 4 connect back to it as a remote.

## Publishing parameters

```diffstatic {data-lang=kotlin}
import afield.oscquery.*

var cutoff = 1000.0
var on = true

val q = oscQuery("My piece")
q.float("/synth/cutoff", 20.0..20000.0, cutoff, "filter cutoff in Hz") { cutoff = it }
q.bool("/synth/on", on) { on = it }
q.menu("/scene", listOf("dawn", "dusk", "night")) { scene -> markdown("scene: $scene") }
```

`oscQuery(name)` starts the service, or reuses it if it is already running. It serves HTTP on port
9020, takes OSC over UDP on 9021, and announces itself on the network as `name`. In TouchDesigner,
add an *OSCQuery* COMP; "My piece" appears in its list of servers, with a slider for the cutoff, a
toggle for `on` and a menu for the scene.

Each callback runs on Field's main loop, like the rest of the box, when another program sets the
parameter. Values arrive as the declared type and clipped to the range. An int sent to `cutoff`
arrives as a Double, and 30000 arrives as 20000.

| call | OSC type | shows up as |
| --- | --- | --- |
| `float(path, range, initial)` | `f` | a slider |
| `int(path, range, initial)` | `i` | an integer slider |
| `bool(path, initial)` | `T`/`F` | a toggle |
| `string(path, initial)` | `s` | a text field |
| `menu(path, choices, initial)` | `s` with `VALS` | a menu |
| `vector(path, listOf(x, y))` | `ff`, `fff`, ... | a 2D pad, a colour, ... |
| `param(path, type, initial, ...)` | anything else | |

Every call also takes a `description`, which other programs show as a tooltip.
`q.describe("/synth", "...")` describes a folder. Re-running the box replaces the callbacks and
keeps the current values, so a value another program set is not reset when you edit the box.
Deleting the box removes its parameters, and connected programs see them disappear.

## Sending values out

`set` changes a parameter from Field. Every program that is listening receives the new value
right away; the parameter's own callback does not run, because the change came from Field:

```diffstatic {data-lang=kotlin}
val level = q.float("/meter/level", 0.0..1.0, access = OscAccess.READ)
loop {
    level.set(0.5 + 0.5 * sin(AnimationClock.nowSeconds()))
    delay(30)
}
```

`access = OscAccess.READ` marks a value other programs may watch but not set. Use it for meters and
analysis results.

## Field controls, published

`controls` builds a [control group](visualizers.md#build-one-surface-with-mixed-control-types) whose sliders and checkboxes are also OSCQuery
parameters, under one prefix:

```diffstatic {data-lang=kotlin}
var gain = 0.5
var mute = false
oscQuery("My piece").controls("/surface") {
    slider("gain", 0.0, 1.0, initialValue = gain) { gain = it }
    checkbox("mute", mute) { mute = it }
}
```

Dragging the slider in Field runs the callback and sends the new value to every listening
program. When TouchDesigner sets `/surface/gain`, the Field slider moves and the callback runs.

## Using another program's parameters

`oscQueryRemote(host, port)` reads a service's parameters. The port is the HTTP port, the one
TouchDesigner shows next to its OSCQuery server:

```diffstatic {data-lang=kotlin}
val td = oscQueryRemote("127.0.0.1", 9000)
td["/project1/level"].set(0.5)                 // sent as OSC, as the type the server declares
td["/project1/level"].listen { v -> markdown("level: $v") }

markdown(td.params.joinToString("\n") { "- `${it.path}` ${it.type} ${it.range}" })
```

`set` sends the value as OSC to the port the service lists in its `HOST_INFO`, converted to the
parameter's declared type, so `0.5` goes out as a float. `listen` asks the service to push every
change over its WebSocket. Its callback runs on the main loop. `number`, `value`, `range`,
`choices` and `description` read what the service last reported; `refresh()` reads the list
again.

`oscQueryMirror(remote)` builds Field controls for all of them: a slider for each number and a
checkbox for each boolean. Each control sets the remote parameter when you move it, and moves
when the remote value changes. The optional third argument keeps only the parameters you want:

```diffstatic {data-lang=kotlin}
oscQueryMirror(td, "td") { it.path.startsWith("/project1/") }
```

## Finding services

`oscQueryDiscover()` lists the OSCQuery services that answer on the local network within a second
and a half:

```diffstatic {data-lang=kotlin}
for (s in oscQueryDiscover())
    markdown("- **${s.instance}** at `${s.address}:${s.port}`")

val chataigne = OscQueryRemote.find("Chataigne")   // the first one whose name matches, or null
```

Field announces its own service the same way, as `_oscjson._tcp` with an `_osc._udp` entry
for the OSC port. Pass `advertise = false` to `oscQuery` to stay quiet. You can still connect to
`host:9020` by hand.

## Ports and the network

`oscQuery(name, httpPort = 9020)` takes another port for a second service. The OSC port is always
the next one up unless you pass `oscPort`. All `Box.oscQuery` calls with the same port share one
service, so several boxes can each publish their own parameters to it.

Like Field's other [OSC](osc.md) inputs, the service listens on every network interface, so
another machine can browse and set it. That is what OSCQuery is for, but anyone on the network
can change those parameters. `OscQueryServer(name, bindAddress = "127.0.0.1")` keeps a service on
this machine only. `OscQueryServices.closeAll()` stops every service and remote connection.

## What this does not do

- No `TAGS`, `UNIT`, `CLIPMODE`, `EXTENDED_TYPE` or `CRITICAL`; `HOST_INFO` lists them as
  unsupported, and remotes fall back to their defaults.
- OSC over TCP is not offered; values arrive over UDP or as binary frames on the WebSocket.
- `PATH_RENAMED` is never sent; a rename is a `PATH_REMOVED` and a `PATH_ADDED`.
- Discovery does not resolve a name clash: two services with the same name on one network both
  appear, under the same name.

??? aside "How this page was checked"
    The fences here are static: nothing on the page is captured by the docs runner. The behaviour
    is pinned by `src/jvmTest/kotlin/afield/oscquery/OscQueryRoundTripTest.kt`, which drives a
    server over real loopback HTTP, UDP and WebSocket with the JDK clients and JavaOSC, and by
    the demo document, whose boxes were each run through the backdoor. The DNS-SD responder is
    written from RFC 6762 and 6763 rather than taken from JmDNS, to avoid a new dependency.
    `dev_docs/OSCQUERY_BRIDGE.md` records what was tested against other implementations.
