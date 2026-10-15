# OSCQuery bridge (`afield.io.osc.query`)

User page: `public/docs/oscquery.md`. Demo: `public/docs/assets/oscquery_demo/`.

## Pieces

| file | role |
| --- | --- |
| `OscQueryCodec.kt` | OSC 1.0 message encode/decode (`i f d h s T F N`, bundles on decode) for WS binary frames and the UDP input. |
| `OscQueryServer.kt` | Namespace + Ktor/Netty HTTP on its own port (JSON tree, attribute queries, `HOST_INFO`), WebSocket at `/` on the same port (`LISTEN`/`IGNORE`, `PATH_ADDED/REMOVED/CHANGED`, binary OSC in and out), raw `DatagramSocket` for OSC UDP. |
| `Mdns.kt` | Dependency-free mDNS responder (shares 5353 via SO_REUSEADDR/SO_REUSEPORT) + legacy-unicast browse. |
| `OscQueryRemote.kt` | Client on JDK `java.net.http` (HTTP + WebSocket); sets go out as UDP OSC to `HOST_INFO.OSC_PORT`. |
| `OscQueryBox.kt` | `Box.oscQuery`, `oscQueryRemote`, `oscQueryMirror`, `oscQueryDiscover`; callbacks on the main Runloop (same drain pattern as `afield.io.mqtt`), params owned `ReleasePolicy.KEYED`. |

## Decisions

- **No JmDNS.** It was not in the Gradle cache and adding a dependency on a loaded machine
  during a nightly was a risk; the responder is ~250 lines. It does not probe for conflicts; each
  JVM uses its own host name `field-<pid>.local` with its own A records.
- **UDP input is our own socket, not JavaOSC `OSCPortIn`**, so port 0 works and the same decoder
  serves UDP and WebSocket. JavaOSC is still used as the *independent* sender in the tests.
- **Binds 0.0.0.0** like the existing `osc(port)` input; documented on the user page.
- Re-adding a parameter with the same type keeps its value (box re-run). Booleans are always
  declared `TYPE "T"`.
- Field-side `set` notifies listeners but not the parameter's own `onSet`; remote sets run `onSet`.

## Tests

`src/jvmTest/kotlin/afield/io/osc/query/OscQueryRoundTripTest.kt`: JSON shape over real HTTP (JDK
client), UDP set via JavaOSC reaching the callback (coercion, clipping, read-only ignored), a raw
JDK WebSocket LISTEN client receiving Field-side and OSC-side changes plus `PATH_ADDED`, `IGNORE`,
`OscQueryRemote` mirroring a server, codec and mDNS wire round-trips, and live DNS-SD discovery
(which returns early, with a printed note, when no multicast route exists).

## Interop record (2026-10-07, macOS, Field on backdoor port 9561)

Against the demo document's service (`oscQuery("Field demo")`, HTTP 9020 / OSC 9021):

- `curl /?HOST_INFO` and `/demo/cutoff?VALUE` returned the expected JSON.
- python `websockets` sent `LISTEN`; python-osc `SimpleUDPClient` sent `/demo/cutoff 4321.0`;
  the pushed binary frame decoded with python-osc's `OscMessage` as `/demo/cutoff [4321.0]`, and
  `?VALUE` then read `[4321.0]`.
- Apple's `dns-sd -B _oscjson._tcp` (mDNSResponder) listed `Field demo` on interface 14.
- Not checked: TouchDesigner, Chataigne, VDMX (not installed) or Max (installed, but not driven headless by an overnight agent).

Trap: the interop script ran from a Field box via `ProcessBuilder` on the main Runloop, which
blocked the launched `/demo/lfo` loop, so no lfo frames arrived during it. Run interop clients
from outside the box (or in a `launch`ed coroutine) when you want Runloop-driven values.
