# field-d2-layout

Field's **graph-layout co-process**. It takes a graph of sized boxes and edges, runs one of
[d2](https://d2lang.com)'s layout engines over it, and hands back coordinates and edge routes.
It draws nothing and knows nothing about Field — the caller owns rendering.

The engine worth having here is **TALA**, which Terrastruct open-sourced under MPL-2.0 on
2026-09-07 and bundled into d2 v0.9.0. Dagre and ELK lay out abstract graphs as strict layered
drawings; TALA was built for software-architecture diagrams — labelled boxes with connections,
arranged to be read. That is what a Field canvas and a Petri net both are.

The JVM client is `afield.layout.D2Layout`, which spawns this binary per call and answers `null`
whenever it cannot — missing, wrong protocol, timed out, or an error. Callers fall back rather than
fail.

First caller: `PetriLayout.TALA` (`afield.petri.PetriVisual`), which falls through to
`PetriLayout.FORCE` and its own force-directed solver when the helper is absent. Intended second
caller: an "organize document" command over a document's boxes, which is what the relative
coordinate mode below exists for.

## Why a separate process

Field is a JVM application and d2 is Go, so the obvious move was to compile d2 to WebAssembly and
host it in-process on GraalWasm, next to libfaust (`afield.four.synth.FaustEngine`). That was
measured on 2026-09-08 and rejected on two independent counts.

**It does not run.** GraalWasm 25.0.2 registers `wasi_snapshot_preview1.poll_oneoff` as
`WasiUnsupportedFunctionNode`, returning `Errno.Nosys` unconditionally. Go's runtime calls it from
`runtime.netpoll`, which runs from `startTheWorldWithSema` on every GC cycle, and treats any
non-`EINTR` result as `fatal error: poll_oneoff failed`. Every Go `wasip1` module dies at its first
collection. This is not a version problem: the stub is identical on graal master, and the last WASI
function implemented in that file landed in August 2024. The fix in flight is Go's, not Graal's
([golang/go#78513](https://github.com/golang/go/issues/78513), FixPending, milestone Backlog).

**And it would be too slow anyway.** With the GC disabled to dodge the crash, warmed, on the
optimizing Truffle runtime (verified: `Engine.getImplementationName() == "GraalVM CE"`, 580 call
targets compiled), GraalWasm ran this code 20–30× slower than native — a 40-box document took ~5 s
against 0.24 s, and dining philosophers ~11.9 s against 0.49 s, with a 30–50 s cold start on top.

`GOOS=js` plus `wasm_exec.js` on GraalJS cannot help: the same Go code on the same engine, with a
JS host layer added to every syscall.

For the record, d2 v0.9.0 *does* build for `GOOS=wasip1 GOARCH=wasm` unpatched (19 MB). If the Go
fix lands and GraalWasm ever gets fast enough, the guest code here is unchanged.

## Optional, by construction

Field must build and run without this binary and without a Go toolchain. Nothing in the Gradle
build references it; it is built by hand, and the build output is gitignored. When it is absent the
caller is expected to drop the feature rather than degrade: Petri keeps its own solver, and the
organize-document command does not offer TALA.

Because layouts are deterministic (below), a docs figure can have its coordinates computed once and
committed, so the docs pipeline never needs this binary at build time.

## Build

d2 v0.9.0 pins Go 1.27.0 in its `go.mod`. You do not need exactly that: since Go 1.21 the default
`GOTOOLCHAIN=auto` makes any installed Go fetch the pinned toolchain on first build. So "a Go from
the last couple of years" is the real requirement. (Documented Go behaviour; not exercised here,
because the machine this was built on had no prior Go at all.)

There is no Go in Field's toolchain and no Gradle task for this. Install it yourself:

| | |
| --- | --- |
| macOS | `brew install go` |
| Windows | `winget install GoLang.Go` |
| Linux | your package manager; grab https://go.dev/dl/ instead if it ships something older than 1.21 |

Then:

```sh
cd native/field-d2-layout
go build -trimpath -ldflags="-s -w" -o build/field-d2-layout .
go test ./...
```

On Windows the output needs the extension — `go build -o build\field-d2-layout.exe .` — which is
also where `afield.layout.D2Layout` looks.

The result is ~15 MB and lands in `build/`, which is gitignored. Field prints the right one of these
lines itself when the helper is missing: it checks whether `go` is on PATH and tells you either to
build or to install first, for the platform you are actually on.

## Protocol

Newline-delimited JSON on stdin, one response object per request object on stdout. Diagnostics go
to stderr and are never mixed into the response stream. The caller may write one request and close
stdin, or keep the process alive and stream many; process startup is negligible next to layout, so
either is fine. A malformed request ends the session, because a `json.Decoder` cannot resynchronise
mid-stream and silently misreading everything after the error is worse than stopping.

### `{"op":"version"}`

```json
{"protocol":1,"ok":true,"d2Version":"v0.9.0","engines":["tala","dagre","elk"]}
```

`d2Version` is read from the Go build info, so it reports the d2 actually linked in and cannot
drift from a hand-maintained constant. Check `protocol` on startup and refuse a mismatch: a stale
helper that silently ignores newer fields is a failure mode this repo has hit before (see
`native/field-encode`).

### `{"op":"layout"}` (the default when `op` is absent)

```json
{
  "engine": "tala",
  "nodes": [{"id": "a", "w": 180, "h": 90, "parent": ""}],
  "edges": [{"id": "e1", "src": "a", "dst": "b"}],
  "seeds": [1, 2, 3],
  "concurrency": 4,
  "timeoutMs": 30000,
  "coordinates": "absolute",
  "direction": "",
  "hierarchy": false
}
```

`engine` is `tala` (default), `dagre` or `elk`. `parent` nests a node inside another by id; empty
means the graph root. `seeds` and `concurrency` are TALA-only. `id` on an edge is echoed back —
without it, parallel edges between the same pair are indistinguishable in the response.

Node ids are matched case-insensitively, because d2 keys a container's children by the lowercased
id. Two ids differing only in case are rejected rather than silently collapsed.

`direction` biases the whole layout: `up`, `down`, `left`, `right`, or empty to let the engine
decide. It is worth reaching for before a bigger seed budget — on a 30-node Petri net the unbiased
layout crossed itself five times and the best of the four biases three, at the cost of one extra
layout each rather than the four times longer that 16 seeds takes. `hierarchy` puts TALA in its
containment-tree mode, which suits a nesting and badly misreads a cyclic graph.

```json
{
  "protocol": 1, "ok": true, "engine": "tala", "elapsedMs": 222.9,
  "bounds": {"x": 0, "y": 0, "w": 1180, "h": 1435},
  "nodes": [{"id": "a", "x": 0, "y": 0, "w": 180, "h": 90}],
  "edges": [{"id": "e1", "src": "a", "dst": "b", "route": [[90, 90], [90, 152]]}]
}
```

`coordinates` is `absolute` (default) or `relative`. Under `relative`, a nested node's `x`/`y` is
measured from its parent's top-left, which is the form Field's nested canvases position children in;
root-level nodes are identical under both. `bounds` is always the absolute extent of the drawing,
and **edge routes are always absolute** — a route can cross a nest boundary, so there is no one
parent to measure it from. Each node echoes its `parent`, so absolute positions can be recovered
under either mode by walking up.

`w` and `h` come back because a layout may resize a node: a container's size is derived from the
children placed inside it, so a box declared 50×50 with two children returns as 240×300.

On failure: `{"protocol":1,"ok":false,"error":"..."}`. Bad input, an unknown engine, a timeout and a
panic inside an engine all arrive this way; the process stays up.

## Determinism

TALA runs several seeded attempts and picks the best deterministically. Verified: identical
geometry run-to-run, across processes, and between the native and `wasip1` builds of the same code,
on every graph tested. That is what committed-PNG docs captures need. `seeds` changes the drawing,
so treat it as part of the input.

## Measured

Apple M4, `-trimpath -ldflags="-s -w"`, TALA with its default three seeds, minimum of nine runs
(the machine was loaded; medians ran ~5% higher and the worst single readings ~3× higher).

| graph | nodes / edges | layout |
| --- | --- | --- |
| chain | 10 / 9 | 87 ms |
| synthetic document | 40 / 49 | 223 ms |
| dining philosophers | 25 / 40 | 423 ms |

TALA is edge-density-driven, not node-count-driven: the philosophers net is smaller than the
document and takes twice as long. For comparison on the same 5-node graph, dagre took 1.4 ms and
ELK 2.4 ms against TALA's 5.1 ms — if a caller wants a cheap layered drawing rather than a readable
arrangement, those are the cheaper answers.

Engine ceilings are 10,000 nodes, 50,000 edges and nesting depth 256.
