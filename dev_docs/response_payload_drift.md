# A route census counts doors, not what goes through them

Written 2026-09-13 from backlog Q9. The case is `GET /api/instance-info`, but the shape is
general and the general shape is the point.

## What happened

`FieldInstanceInfo` (`Server.kt:106#private data class FieldInstanceInfo(`) gained a fourth
property, `workspace = displayWorkspacePath(home)`
(`Server.kt:3608#workspace = displayWorkspacePath(home),`) — the absolute path of the directory
the server resolves document names against. On an ordinary install that is a folder inside the
user's documents; `./field.sh --print-workspace` reports `~/Documents/AFieldWorkspace`.

The field was added for a good reason and it is not the defect. The defect is that **three
separate artifacts went on describing the old payload**, and nothing in the build could tell:

| artifact | what it said | true of the payload? |
| --- | --- | --- |
| `public/docs/server_surface.md:66` | "returns the absolute path of your checkout" | no — 1 of 4 keys |
| `Server.kt:5533` KDoc | "`/api/instance-info` leaks the path of your work" | no — understates by one key |
| `ServerSurfaceLedgerTest.kt:467` | `"GET /api/instance-info",` is a landmark **route** | yes, and irrelevant |

The third row is the mechanism. `ServerSurfaceLedgerTest` is a genuinely good census — it
enumerates read routes, all-method routes, static mounts, `/ws` commands and raw channels, with
floors and landmarks against a census that silently shrank. It counts **doors**. Adding a key to
a response body does not add a door. The door it was counting did not change; only what gets
handed through it changed. So the census stayed green, correctly, over a population that never
included the thing that moved.

## Why it matters more for a read than it looks

`installFieldApiCors` (`Server.kt:5405#internal fun Application.installFieldApiCors() {`) is
`anyHost()` plus `allowMethod(HttpMethod.Get)`. Measured on 2026-09-13 against a Field launched
out of this worktree on port 9613: `GET /api/instance-info` carrying
`Origin: https://evil.example` and `Sec-Fetch-Mode: cors` returned **`200`** with
**`Access-Control-Allow-Origin: *`** and `Content-Length: 246`. That header is what makes the
*body* readable by a calling page rather than merely reachable. So a new key in this payload is
not "more data in a local response"; it is more data a page you visit can read.

Two qualifications, because the difference between them is where prose about this normally goes
wrong:

- **`anyHost()` is not the whole posture.** `installFieldApiCors` also calls
  `installFieldApiSameSiteGuard()`, which since 2026-08-20 covers **reads** as well as writes
  (`Server.kt:5512#private fun fieldApiReadNeedsSameSite(secFetchMode: String?, path: String): Boolean {`).
  `/api/instance-info` is in neither escape list — not `fieldApiSelfGuardedReadPrefixes`
  (`Server.kt:5486`, two `/api/docs-*` entries) nor `fieldApiSameSiteExemptPaths`
  (`Server.kt:5467`, one entry) — so with the switch **on**, a cross-site `fetch` of it is
  refused. The switch is **off by default**, so the measurement above is the default posture and
  not the only posture.
- **"The field is exposed" and "any website can read the field" are different claims.** The
  first is a property of the data class. The second is a property of the CORS configuration plus
  the default value of one environment variable. Verify them separately; the published page now
  states which of the two each of its sentences is making.

## The transferable rule

> A guard that enumerates **endpoints** is blind to changes in **payloads**. If the reason an
> endpoint is interesting is *what it answers*, the guard has to pin the answer's shape.

This is the same family as the `dev_docs/guard_population_hygiene.md` failure — a guard going
green over a population that does not contain the thing that moved — with the population being
"route names" rather than "test cases".

## Proposed guard, not implemented

Not built tonight: backlog Q9 asks for a documented human decision rather than a new gate, and
`src/jvmTest/kotlin/afield/docs/**` was owned by another branch on 2026-09-13.

One-line sketch: for each `@Serializable` response data class reachable from `routing { }`,
assert its property-name set against a ledger table in `dev_docs/server_surface_inventory.md`,
so adding a key to a cross-origin-readable payload costs a doc edit. Start with the routes whose
bodies are readable cross-origin, which is the population where a new key has a security
meaning, rather than with all of them.

Also unfiled, and smaller: `public/docs/server_surface.md` is not in the `CitationContractTest`
page corpus, so the three `path:line#anchor` citations added to it on 2026-09-13 are unguarded
and will rot without saying so. `"public/docs/server_surface.md" to 3` is the entry; all three
anchors were verified unique-in-file before publishing.

## Known-stale, deliberately not edited

`public/docs/server_surface.md:118-121` still says the same-site switch inspects "mutating
methods only: `POST`, `PUT`, `DELETE`. A `GET` goes through untouched." That is false at
`8c6b64cab` — `Server.kt:5603` branches on `Get || Head` — and the probe-table row at
`server_surface.md:299` is measured data from an older build for the same reason. Correcting
either honestly means re-probing a Field started with `AFIELD_API_REQUIRE_SAME_SITE=1`, and
rewriting a table headed "probed against a running Field" without re-probing it is the defect
this note is about, one level up. Left for a branch that can afford the second launch.
