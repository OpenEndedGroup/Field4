# The K2 Analysis-API Completion Backend (AA sidecar)

**Audience:** a developer who needs to understand, debug, or extend Field's Kotlin autocomplete.
**Status:** AA (the out-of-process K2 Analysis-API backend) is the **default** completion backend as of
commit `fd8f660b`. This doc is the architecture map. For the migration *narrative* (how we got here,
stage-by-stage), see [`completion_k2_migration_plan.md`](completion_k2_migration_plan.md); for the older
K1 engine mechanics (preambles, `prop<>` macros, `@exported` aliases) see
[`COMPLETION_ENGINE.md`](COMPLETION_ENGINE.md). This doc links to those rather than repeating them.

---

## TL;DR

When you type in a Kotlin box, the editor sends a `complete` RPC to the Field server. The server calls
`KotlinInterface2.complete()`, which — when the AA backend is active (the default) — POSTs the box's
REPL context to an **out-of-process K2 Analysis-API sidecar** (`/complete`). The sidecar computes
candidates with the Kotlin Analysis API and returns them as structured rows. The Field server enriches
them with KDoc/AI docs and ships them to the browser, where `fieldSuggest.js` does the ranking — and the
grouping, the category titles and the documentation pane; see
[`completion_popup_cover.md`](completion_popup_cover.md) for that half and the test that covers it. If the
sidecar is absent, slow, erroring, or returns nothing, the request transparently falls back to the
compiler-grade **K1** delegate — so AA is never *worse* than K1 in coverage.

```
browser editor
   │  "complete" RPC (CompletionRequest: code, cursor, box uid, property)
   ▼
Server.kt  handlers["complete"]                                  (Server.kt:2162)
   ▼
execution.kt  KotlinExecution.complete(inside, req, reply)       (execution.kt:2757)
   │  runs on the main runloop: inside.ki.complete(allCode, position)   (execution.kt ~3123)
   ▼
KotlinInterface2.complete(s, cursor)                             (KotlinInterface2.kt:552)
   │  backend == AA ?
   ├── AA ──► AaCompletionClient.complete(...)                   (KotlinInterface2.kt:566, AaCompletionClient.kt:84)
   │            │  HTTP POST /complete  (snippet, cursor, preamble, declarations,
   │            │                         implicitReceivers, classpathRoots)
   │            ▼
   │          sidecar process  POST /complete                    (CodeUseSemanticSidecar.kt:194)
   │            ▼  K2CompletionAnalyzer.complete()               (CodeUseSemanticSidecar.kt:658)
   │            ▼  AaCompleter.complete()  → Analysis API        (AaCompletion.kt:295)
   │            ▲  List<CompletionCandidate>
   │          (empty / error / no-URL / timeout) ─────────────┐
   └── K1 (or fallback) ──► k1CompletionDelegate().complete() ◄─┘   (KotlinInterface2.kt:577)
   ▼
execution.kt  enrich with KDoc / AI docs, categorize            (execution.kt:2886+)
   ▼  reply(Message("complete").payload(Completion(...)))
browser editor  fieldSuggest.js ranks (fuzzy + category + recency + contextScore)   (fieldSuggest.js:685)
```

---

## 1. Why

### K1 vs K2 / the Analysis API

Field's Kotlin *execution* backend is K2, but historically completion was **~100% K1**:
`KotlinInterface2.complete()` delegated to a K1 `KotlinInterface` (`k1CompletionDelegate`). K1's IDE
services give compiler-grade completion, but K1 is a dead-end: JetBrains is retiring it in favour of the
**Analysis API** (AA), the same stable public API the IntelliJ Kotlin plugin now uses. The REPL-native
AA completer JetBrains ships is K1-only in our compiler fork (KTNB-916 is unfinished upstream), so Field
builds its own AA candidate generation. See `completion_k2_migration_plan.md` §1 for the full backstory.

### Why out-of-process

The standalone Analysis API pulls a large **IntelliJ-style classpath** (`analysis-api-standalone-for-ide`,
`analysis-api-k2-for-ide`, and friends — see `tools/code-use-semantic-sidecar/build.gradle.kts:14+`).
Mixing those IntelliJ platform classes into Field's live-coding runtime JVM is undesirable (class
conflicts, memory, startup). So the AA work runs in a **separate JVM** — the *code-use semantic sidecar*
— and Field talks to it over a tiny local HTTP contract. This is the same design already used for
code-use telemetry (`/analyze`); completion just added a second endpoint (`/complete`) to the same
process. The in-runtime side (`AaCompletionClient`) is a thin `java.net.http` bridge with **no** AA
classes on its classpath (`AaCompletionClient.kt:19`).

---

## 2. The request path, end-to-end

### 2.1 Editor → server

The browser editor issues a `complete` RPC. `Server.kt` registers the handler
(`Server.kt:2162`) and decodes the payload into a `CompletionRequest`
(`Server.kt:2164`) carrying the full code buffer, caret `position`, box uid, and the `property` being
edited (`code`, a shader stage, `metadata`, …).

### 2.2 Server dispatch (`execution.kt`)

`KotlinExecution.complete(inside, completionRequest, reply)` (`execution.kt:2757`, declared on the
`BoxExecution` interface at `execution.kt:86`) routes by property: YAML `metadata`, GLSL/Slang shader
stages (`GlslCompletionSupport`, `Ai4GlslAutocomplete`), or — for ordinary Kotlin `code` — the language
engine. For Kotlin it calls, **on the main runloop**:

```kotlin
inside.ki.complete(allCode, position)     // execution.kt ~3123
```

The main-runloop hop matters: the K1 fallback delegate is not re-entrancy safe, so completion must run on
the same non-reentrant dispatcher used elsewhere (see the `warmCompletion` note below and the
`completion-capture-runloop-pump` memory).

### 2.3 `KotlinInterface2.complete()` — backend switch

`KotlinInterface2.complete(s, cursor)` (`KotlinInterface2.kt:552`) is the fork:

- If `KotlinEngineConfig.activeCompletionBackend == AA` (`KotlinInterface2.kt:561`):
  1. gather the box's inherited preamble: `normalizePreambleText(gatherPreambleUpwards(box))`
     (`KotlinInterface2.kt:562`; `gatherPreambleUpwards` in `macros/macros.kt:93`,
     `normalizePreambleText` in `stdlib/shaders.kt:1861`).
  2. collect implicit receiver type names (e.g. `afield.Box`).
  3. call `AaCompletionClient.complete(s, cursor, completionPreamble, receiverTypes)` on
     `Dispatchers.IO` (`KotlinInterface2.kt:565-566`).
  4. If AA **served** the result, log `backend=AA … (detail)` and return it (`KotlinInterface2.kt:569-571`).
  5. Otherwise log `backend=K1 … (AA→K1: detail)` and fall through (`KotlinInterface2.kt:573`).
- Otherwise (backend `K1`, or after an AA miss) it calls `k1CompletionDelegate().complete(s, cursor)`
  (`KotlinInterface2.kt:577`).

Every request logs which backend actually served it on the `completion` log channel, so the source of any
given completion is never a mystery.

### 2.4 `AaCompletionClient` — the HTTP bridge

`AaCompletionClient` (`src/jvmMain/kotlin/afield/AaCompletionClient.kt:21`) is an `object` with no AA
dependencies. `complete()` (`:84`):

- resolves the endpoint (`endpoint()`, `:65`): prefer `-Dafield.completion.sidecarUrl`, else
  `AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL`; append `/complete` if needed. **No URL → return
  `Outcome(null, "no-sidecar-url")`** (a K1-fallback signal).
- computes the running JVM's binary classpath once from `java.class.path`
  (`classpathRoots`, `:60`) — this includes afield's own compiled output, which the sidecar needs to
  resolve Field's own types.
- POSTs a `Req { snippet, cursor, preamble, declarations, implicitReceivers, classpathRoots,
  limit=200 }` JSON
  (`:95-108`) with a bounded timeout (default 4 s).
- maps the response to `CompletionResult.Success` (`:133`), mirroring the K1 delegate's `extraInfo`
  (`receiverType`, `receiverQualifiedName`, `:125-130`) so the server's doc-enrichment pipeline attaches
  KDoc to AA dot-members too.

The `Outcome(result, detail)` type (`:80`) is the fallback contract: **non-null `result` = AA served;
`null` = caller should fall back to K1.** Crucially, an **empty** AA candidate list is treated as a miss
(`:116`) — if the headless session can't model some preamble/receiver form, the request falls through to
K1 rather than showing the user nothing.

### 2.5 Sidecar `/complete` → `K2CompletionAnalyzer` → `AaCompleter`

The sidecar's HTTP server (`CodeUseSemanticSidecar.kt:183`) exposes `/health` (`:190`), `/analyze`
(telemetry, `:212`) and `/complete` (`:194`). `/complete` decodes a `CompletionRequest`
(`CodeUseSemanticSidecar.kt:143`) and calls `K2CompletionAnalyzer.complete()` (`:658`). Any thrown
exception is caught and returned as `CompletionResponse(success=false, …)` with HTTP 200 (`:204-209`) —
so the client sees a clean "server-fail" and falls back to K1, never an HTTP error.

`K2CompletionAnalyzer.complete()` (`:658`):

1. resolves `classpathRoots` on disk; empty → failure response.
2. looks up (or lazily builds, in the background) a cached AA **session** for that classpath root set
   (`cachedSession`, `:683`). **The session build takes ~1 minute**, so it is kicked off on a background
   single-thread pool and the current request returns an *empty* success immediately
   (`:664-667`) — the client reads empty as "fall back to K1", so the user gets K1 completions during
   warmup, then AA takes over transparently once the session is ready.
3. once warm, runs `cached.completer.complete(...)` under a per-session lock (`:668-676`) — the FIR
   caches aren't concurrency-safe, so analysis over a shared session is serialized. Snippet turnaround is
   milliseconds, so this doesn't meaningfully limit throughput at the sidecar's small thread count.
4. maps each `AaCandidate` to a wire `CompletionCandidate` via `AaCandidate.toVariant()` (`:746`):
   methods get a trailing `(` in `text` and a rendered param list in `displayText`; the return type goes
   in `tail`; members/extensions carry the receiver type (`recvSimple`/`recvQual`) for KDoc enrichment.

#### What `AaCompleter` does with the Analysis API (`AaCompletion.kt`)

`AaCompleter.complete()` (`AaCompletion.kt:295`) reconstructs the box's editing context as a synthetic,
compilable, **in-memory dangling `KtFile`** and asks the Analysis API what's in scope at the caret:

- **Preamble → local stubs, *and* file scope.** A dangling file's own *top-level* declarations don't
  enter completion **scope** (they are not enumerated by `scopeContext`), so `splitPreamble()` (`:349`)
  splits the inherited preamble into (a) import lines kept at file top level and (b) every
  `prop<>`/`@exported`/plain declaration re-emitted as a *local* stub (name + type preserved,
  receiver/delegate stripped) inside the completion function. This is the load-bearing invariant that
  surfaces inherited REPL names — see `COMPLETION_ENGINE.md`.

  They are also emitted **verbatim at file scope**, gated on the preamble text parsing cleanly on its
  own (`parses()`; an unparseable preamble degrades to stubs-only rather than breaking the request).
  Top-level declarations don't *enumerate*, but they do **resolve** — and a stub cannot stand in for a
  class, nor for an extension whose parameter is a lambda with a receiver. The graphics `/*supermap*/`
  macro writes both (`class MappedStructure { lateinit var tri: IntBuffer … }` +
  `fun Mesh.set(…, builder: MappedStructure.() -> Unit)`), so without them the body of
  `m.set(3, 1) { tri.‸ }` had no `MappedStructure` receiver: `tri` resolved to the next thing in scope
  by that name — `afield.four.synth.tri`, a triangle oscillator, via intro's `import afield.four.synth.*`
  — and the panel answered with `TriOsc`'s members. Because file-scope declarations don't enumerate, the
  dot path adds the file's own callables explicitly (`fromFile`) and the name path its own classifiers
  (`fileClassifiers`), which is what makes the preamble's `m.set(`/`m.pos` reachable on `m.`.
- **Synthetic declarations → file scope plus discovery stubs.** Language runtimes such as KotlinJS
  can supply JVM-analysis-safe type models separately from the inherited REPL preamble. Classes and
  objects remain at file scope, including nested classifiers such as `THREE.Vector3`; top-level
  callables/properties are additionally stubbed locally so bare-name discovery remains reliable.
- **Implicit receivers → `with(...)` scopes.** Each implicit receiver type becomes a
  `with<Type, Unit>(__rN) { … }` wrapper (`:320-328`) so receiver members resolve as bare names and
  dot-completion on the receiver works. Both type arguments are **explicit**: leaving `R` to be inferred
  from the block means a box whose last expression doesn't type — i.e. any box mid-edit — fails to infer
  `with` itself, putting the receiver scope at risk for the whole box. That was every injected-region
  error the lowering invariant found (5 boxes, all visionOS/remoteAR); pinning `R = Unit` costs nothing,
  since a block's value is coerced to Unit anyway.
- **The empty selector slot → the caret marker.** `recv.‸` at the end of a line parses to *no*
  `KtQualifiedExpression`, so the dot path had no receiver to work from and bailed ("no qualified expr" —
  11 of the caret sweep's misses, every one of them the most ordinary thing a user does). The marker
  already spliced at untyped expression slots is now spliced into an empty selector too, making it
  `recv.__fieldCaret__`; as a bonus this gives the IDE's extension-applicability checker the selector it
  needs, which could never be built at a bare dot before. Only when the slot really is empty: if the next
  character could continue an identifier there *is* a selector, and appending would rename it.
- **Analysis** (`completeAt`, `:381`) runs inside `analyze(file) { … }`:
  - **Dot completion** (`recv.` / safe `recv?.`, `:401`): resolves the receiver's expression type, then
    lists substituted member callables via `type.scope.getCallableSignatures(...)`, nested classifiers
    via `getClassifierSymbols(...)`, plus **applicable extensions** filtered through the IDE's
    extension-applicability checker and receiver-conformance fallback.
  - **Name / argument position** (`:436`): scope callables (dropping importing scopes on an empty prefix
    to avoid flooding), implicit-receiver members, index-backed global symbols once a prefix is typed,
    receiver-applicable extension functions, classifiers, and — at an argument position — the enclosing
    call's not-yet-supplied **named parameters** (`:442-453`). Named-argument completion is AA-only; the
    K1 delegate produces none (this is what the E2E probe asserts to prove AA actually served). When the
    enclosing call doesn't resolve — one bad argument is enough, which is the normal state of a call being
    written — `resolveToCallCandidates` returns nothing and the parameter names went with it. That is
    backwards, since the names are *most* wanted while the call is still wrong, so there is a fallback
    through the callee's **name**: every function in scope or in the index under that name, unioned.
  - **Package qualifiers** (`afield.‸` → `stdlib`, `four`): see the ASM index section.
  - **Visibility** (`accessible`): every candidate has to be something the box could actually *write*. A
    type's member scope is the declaration's, not the use site's, so it hands back `private`, Java
    package-private and other-module `internal` members alike — `TriOsc` offered its `private var
    integrator`/`phase`/`freqBuf` and `Signal`'s `private var _cache*`, `java.nio.IntBuffer` offered
    `Buffer`'s package-private `address`/`base()`/`checkIndex()`. `PUBLIC` (the overwhelming majority)
    and `LOCAL` (the preamble's own stubs) short-circuit; anything else asks AA's own
    `createUseSiteVisibilityChecker`, which gets the cases a hand-rolled rule wouldn't (private IS
    visible inside its own class, `protected` from a subclass). Over the differential corpus this drops
    exactly 9 candidates and adds none; all 9 are `private`/`internal` on inspection.
  - **The expected type at the caret** — see below.

#### Measuring it — and gating it: `completion.lowering-invariant.probe`

Two ground-truth measurements over a corpus of real boxes (`CompletionLoweringProbe`), so completion
quality stops being a matter of opinion and reported screenshots. It was report-only for exactly one day —
the right way round, since inventing thresholds before measuring is how you get a gate that asserts today's
bugs — and it is now **also the gate**: see [The ratchet](#the-ratchet-what-the-numbers-assert) below.

- **The lowering invariant.** `/lower` builds the same synthetic file `/complete` would (both go through
  `AaCompleter.lower`, so they cannot drift) and hands it to the compiler's own checkers. Each error is
  attributed to a region: an error in the *injected* regions (file-scope preamble, local stubs, the
  `fun __c` wrapper) is the lowering's own fault; an error in the *snippet* region is the box needing
  runtime context a headless session can't have, which predicts where completion will be weak.
- **Caret recall against the source.** Working box code is a free labelled corpus: at a caret in front of
  `pos.add(…)` the answer is `add`, with no fixture to write. Carets are sampled in two modes — `blank`
  (rest of the line removed: what happens while *typing*) and `keep` (left in place: *editing* existing
  code) — and at 0 and 2 characters typed on the same caret, so the buckets are comparable.

#### The corpus is harvested, not read (`CompletionCorpus` / `completion.corpus-harvest.probe`)

A document as materialised on disk is **not** the context a user edits in. A large part of a box's
inherited preamble is written by macro expansion, not by an author: `prop<Int>(sgL)` becomes
`var Box.sgL by Inheritance<Int>()`, `moduleHere { }` becomes the `/*supermap*/ class MappedStructure` +
`fun Mesh.set(…, builder: MappedStructure.() -> Unit)` pair plus the generated `Structure` classes and
push-constant bindings, `@exported` republishes a box's own values. Measured against raw documents, the
probe's top unresolved names were `renderer` (33), `window` (28) and the whole `sg*` family — every one a
macro expansion that hadn't happened.

`CompletionCorpus.harvest` gets them without running anything: `applyMacros(previewOnly = false)` is the
engine's own expansion pass, and it merges each macro's generated segments into the box's preamble
*without evaluating a line of box code* — no window, no GPU, no runloop, and a box that would loop forever
at runtime harvests in milliseconds. The result is checked in under `tools/completion-corpus/boxes/` as one
manifest line per box plus a `.preamble.kt` per box that has one, so the measurement is reproducible and
the interesting part — what context completion gains or loses when a macro changes — shows up as a diff.
`completion.corpus-harvest.probe` regenerates it with `AFIELD_HARVEST_WRITE=1` and otherwise **guards**
it: it re-harvests, compares, and fails on `DRIFT` if a fixture no longer matches the code it came from.
Not captured: `@exported` republication, which needs real evaluation of a parent box.

Baselines on the 49 bundled starting points (182 Kotlin boxes, ~7.1k lines, 19 with a macro-expanded
preamble of which 9 carry the `/*supermap*/` class), 2026-08-07: **182/182 boxes lower, 0 preambles dropped
as unparseable, 0 injected-region errors.** 223 snippet-region errors, 116 of them unresolved references —
all of them the box wanting runtime context, none of them the lowering. Caret recall **95% overall** over
1708 carets: `dot` 98% at a bare dot while typing / 95% editing, `name` 94% / 94%, `pkg` 97% throughout.
p50 27ms, whole sweep 50s.

> **The sample has to spread across the corpus.** The sweep used to ask its questions box by box and stop
> at the request cap, so 400 requests only ever reached the first ~30 of 182 boxes — every miss it reported
> came from `alife_*`, and `dot` was measured on 15 carets. The questions are now built up front and walked
> round-robin across boxes, and the cap (4000) is a safety valve rather than a sampler. Read the 2026-08-06
> numbers below with that in mind: they are the same code measured on a narrower sample.

Six rounds of findings from this sweep, each of which is a defect class rather than a single bug:

1. **Companion members off a class qualifier.** `Img.generate` was unreachable and `Img.` answered with the
   one useless row `Companion`, because the companion was hunted for in `declaredMemberScope`, which is
   empty for a *binary* class. Reading `KaNamedClassSymbol.companionObject` instead: dot recall 36%→51%.
2. **Harvesting the corpus** (above): 51%→65%, and half the unresolved references gone.
3. **Extensions by receiver instead of by prefix** (see the ASM index section): 65%→89% at a bare dot, and
   the 25-point cliff between "nothing typed" and "two characters typed" closed to 6.
4. **Measurement hygiene, twice.** Sampling carets inside comments and string literals made `the`, `and` and
   `whether` the most-missed "identifiers"; counting an already-written named argument as a miss faked a
   13-point typing-vs-editing gap that is really 1 point. Both are now excluded, and *package paths* are
   reported as their own `pkg` kind — mixed into `dot`, a handful of fully-qualified type references were
   the largest single cluster of "misses" while saying nothing about member completion.

5. **Four things the residual named, 2026-08-07** — each found by reading the sweep's own miss notes and
   each confirmed over the wire against `/complete` before a line was written. Together **91% → 95%**
   overall (on the representative sample; see the note above):
   - **A comment ending in a full stop was a dot position.** `isDot` was read off raw text, so the last
     non-space character before the caret was whatever the previous line ended with — and people end
     comments with full stops. `animateEvolution(‸` under `// …rather than pixels.` took the dot path,
     found no `KtQualifiedExpression`, and returned `no qualified expr` with nothing: 19 misses, every one
     an ordinary call at the start of a statement, and worse than empty when the caret sits inside an
     enclosing qualified expression (then it answers with *that* receiver's members). The position's KIND is
     now decided on comment/string-blanked text — with `${…}` template spans put back, since those are real
     code and a caret inside one must still see its receiver.
   - **A top-level property was indexed by its accessor.** The ASM scan recorded raw JVM method names, so
     `val CYCLIC_LABYRINTH` appeared as `getCYCLIC_LABYRINTH` — which nobody types, and which
     `findTopLevelCallables` (exact Kotlin name) was therefore never asked for. No top-level `val`/`var` in
     a binary package was reachable at a name position. Two halves: record `kotlinNames(name)` as the
     receiver index already did, *and* un-mangle to BOTH spellings, because `getPos` is `pos` but
     `getCYCLIC_LABYRINTH` is `CYCLIC_LABYRINTH` — a SHOUTING_CASE property is not decapitalised, and
     `cYCLIC_LABYRINTH` is exactly as unreachable as the accessor. Index 6547 → 7508 names.
   - **Qualifier resolution guessed packages.** It built candidate `ClassId`s from the file's imports plus
     two hardcoded `afield` ones, so Kotlin's DEFAULT imports were invisible: `Math.` resolved to no
     qualifier, fell through to the value path, and answered with `equals`/`hashCode`/`toString` and the
     `Any` extensions — 13 rows, no `abs`, no `toRadians`. It now asks `mainReference.resolveToSymbol()`
     first, which resolves against the file's real import scope (`java.lang`, `as` aliases and all), and
     keeps the text search as the mid-edit fallback. Same hole covered `System.`, `Integer.`, `Files.`.
   - **The transport deleted the answer.** Ranking is client-side, so `limit` does not trim the tail of a
     ranked list — it deletes rows the ranker never sees, from a list sorted BY NAME. At 200, a bare `s.`
     dropped 52 members silently: every `toInt`/`toDouble`/`toList`/`toRegex`, `trim`, `trimIndent`,
     `uppercase`, `zip`, `takeIf`. 31 misses read exactly `200 candidates`. The client now asks for 1200
     (ceiling 2000; the richest real receiver measured, `Vec3` with ~978 swizzle extensions, yields 337
     rows after de-duplication, and the cost is serialization only — 43KB → 63KB, 13ms either way), and
     when the cap does bind it destroys the least useful rows: prefix match first, then expected-type fit,
     then name order.
6. **A package path can answer with the package's own members.** `kotlin.math.‸sin` came back with one row
   and no `sin` — a package qualifier listed only sub-packages and classifiers, its callables gated behind a
   typed prefix — and `java.‸util.Random` came back `0 matched in package java`, because the JDK's packages
   are in modules, not on the classpath, and nothing could enumerate them. Now: callables are enumerated at
   an empty prefix too, but only for a package that fits the receiver index's 800 cap (resolving 800 of
   `afield.stdlib`'s 3877 names alphabetically is the same truncate-the-alphabet mistake and measured 233ms
   of one keystroke; over the cap the note says "type a character"), and the package index is seeded with
   the boot module layer's package names. `pkg/0` 86% → 97%.

Still open, in miss-count order, and the diagnosis for each:

- **Macro keywords are not symbols** (~20 misses: `strategy` 10, `choice`, `topdown`, `repeat`, plus
  `rule`/`from`/`to` in the unresolved-name histogram). `strategy { bottomup("fg") }` is expanded by
  `applyMacros`, and `afield.macros.strategy` is an `erased()` stub in a package no box imports — so AA is
  right that nothing by that name is in scope. Offering them needs macro *vocabulary* in completion, which
  is a feature rather than a lowering defect.
- **Runtime-only receivers** (8 `receiver type UNRESOLVED (error)`, e.g. `cube.position` in a remoteAR box,
  and `uniform`/`onFrame`/`onStart` in the visionOS ones). These are `@exported` republications and
  JS-bridge values that only exist after a parent box has actually run — the limitation
  `CompletionCorpus.harvest` documents, and the snippet-region error histogram's main content.
- **JDK classifiers** (`java.util.‸Random`): the boot layer gives package names, not class names, so a JDK
  package still lists only its sub-packages. Enumerating module contents with `ModuleReader.list()` would
  fix it; nothing in the corpus needed it yet.
- 5 `0 candidates at name position`, which now carry their own breakdown (`prefix=… index-names=…
  resolved=… scope=… receivers=…`) so the next reader can tell "the index never held that name" from "it
  held it and resolution came back empty" — opposite bugs with opposite fixes.

#### The ratchet: what the numbers assert

`CompletionLoweringProbe` §3 (`FLOORS`) turns the measurements into a verdict, and the FIELD_TEST block
asserts both the exit code and the absence of `RATCHET FAIL`. `--report` (or `AFIELD_LOWERING_GATE=0`) keeps
the exploration mode: same verdict lines, exit 0, for a run whose point is the residual.

**Where it lives, and why.** The smoke catalog, not `./gradlew check`: it needs a live AA sidecar, and a
`check` task has to be deterministic and offline. Where no sidecar is running the probe SKIPs (exit 0), so
it never turns a sidecar-less machine red.

**What is absolute.** Every box lowers; no preamble is dropped as unparseable; **zero injected-region
errors**. Those are not "5 fewer than yesterday" numbers — an error in a region the lowering itself writes is
our bug by construction, so the floor is 0.

**What is a floor, and why there.** One floor per (kind × characters-typed × mode) bucket, each cut ~5
points below the number measured over the whole corpus. Reasons for each choice, so the next person raises
them rather than deleting them:

- **`blank` and `keep` are gated apart** — typing vs editing. The probe's own KDoc explains why: a gap
  between them is a parse-recovery problem and a gap in both is a scope problem, so one aggregate number
  would hide the diagnosis the measurement exists to give.
- **~5 points, not 1 and not 20.** The buckets are deterministic given the same corpus and backend, so any
  movement is a real change; the margin is there for the ordering churn a candidate-set change causes near
  a truncation boundary. Every regression this measurement has actually caught moved a bucket 10–40 points.
- **`minAttempts` is a floor too**, at ~90% of the observed sample size. Recall over a bucket nobody
  sampled is 0/0 — vacuously fine — so a change that stopped recognising `dot` sites would otherwise turn
  the gate green by asking nothing. Same reasoning behind the 1600-request floor on the sweep itself.
- **`TOTAL` is gated as well**, not because the buckets don't cover it but because it is the number that
  gets quoted, and a change trading one bucket against another should have to say so.
- **No latency floor.** p50 is reported and is machine-dependent; a threshold on it would fail on a busy
  laptop and teach people to ignore the gate.

**A failure names the regression**, not just a number: the bucket, what it fell from, and the sidecar's own
note on those misses. Verified by deliberately reverting the client's candidate cap to 200:

```
✗ RATCHET FAIL dot/0/blank recall fell from 98% to 89% (floor 93%, 54/61);
  the sidecar's own note on those misses: 6× "200 candidates"
✗ RATCHET FAIL pkg/0/blank recall fell from 97% to 88% (floor 92%, 106/120);
  the sidecar's own note on those misses: 10× "200 candidates"
RATCHET FAIL: 4 of 15 floors broken — dot/0/blank, dot/0/keep, pkg/0/blank, pkg/0/keep
```

Two things that exercise came with. The failure message itself was broken (`${observed}%` before a
`.format(…)` is an invalid conversion, so the line threw and the run failed with no diagnosis) — a bug only
reachable by breaking a guarded fix on purpose, which is the argument for doing it. And `TOTAL` fell just
95% → 93% and stayed above its floor, so an aggregate-only gate would have passed that regression.

**Raising a floor is part of a fix.** A ratchet that keeps yesterday's floors after the numbers improve
protects nothing: the regression it would catch is the one that undoes the work that just landed. Each fix
commit above moved its bucket and then raised its floor.

#### The index cache invalidates on its own bytecode

`AaSymbolIndex.buildOrLoad` caches the ASM scan under `build/…/completion-index`, keyed by a fingerprint of
the classpath. That fingerprint used to fold in a hand-maintained `FORMAT_VERSION`, which meant a change to
*what the scan records* — the only reason to touch that code — left every cache file valid and the sidecar
kept serving the old index. It cost an hour on 2026-08-07: a corrected `kotlinNames` looked to have no
effect at all, through two rebuilds and two restarts. The fingerprint now includes a hash of the scan's own
class files (`FacadeVisitor`, `MetadataVisitor`, the index companion), so the bytecode *is* the version; a
class file that cannot be read fingerprints as `nanoTime`, bypassing the cache rather than trusting it.

Same family as the stale-sidecar trap: when measuring the sidecar, confirm the process you are talking to
is built from your checkout (`/health` reports `workspaceRoot`; `lsof -p <pid>` its cwd) **and** that no
cache is answering for a previous build.

#### The type expected at the caret (`expectedTypesAt` / `enumerableValuesOf`)

At `d.scheme = ‸` the answer is the three entries of the `Scheme` enum, and until this existed completion
answered with every name in scope instead, because neither backend asked what type belonged there. AA can
be asked — `KaExpressionTypeProvider.expectedType` — which is why this is AA-only: K1 has no equivalent,
so `InfixCompletion` (`execution.kt:751`) had to *guess* the analogous thing for infix operands by
regexing the source index.

Three pieces, all in the non-dot branch of `completeAt`:

1. **A dummy name at the caret** (`CARET_MARKER = __fieldCaret__`, spliced in `complete()`). An empty slot
   has no expression to ask about, and the parse is worse than empty: `d.scheme =` followed by
   `display(…)` on the next line parses as `d.scheme = display(…)`, because a newline does not end an
   assignment's RHS. Splicing `__fieldCaret__` gives both a `KtExpression` to query and the intended
   parse. This is what IntelliJ does with `IntellijIdeaRulezzz`. It is gated to *untyped* expression slots
   (`wantsCaretMarker`: after `=`, `(`, `,`, `[`, `->`, `?:`, `&&`, `||`, `return`) — a typed prefix
   already *is* a name expression, and outside those slots the marker would only risk the PSI recovery the
   other paths depend on. The marker starts with `__`, so `AaCandidate.isSynthetic` guarantees it can
   never itself be offered.
2. **`expectedTypesAt()`** returns `expectedType` on that expression, with a fallback for argument slots:
   the parameter types from `resolveToCallCandidates()` unioned over overloads (named argument matched by
   name, positional by index). The fallback matters because `expectedType` needs the call to resolve, and
   code being typed usually doesn't. Expectations too weak to act on are dropped
   (`isUsefulExpectation`: `Any`, `Any?`, `Unit`, `Nothing`, error types — `errorType.isSubtypeOf(x)` is
   vacuously true, the same trap the dot path documents).
3. **`enumerableValuesOf()`** turns an expected type whose inhabitants are enumerable into candidates:
   an enum's entries, a sealed type's inheritors (`sealedClassInheritors` — works for binary sealed types
   too), and `Boolean` → `true`/`false`. Everything else gets no values, only the fit flag.
   - Entries are **displayed bare** (`BLEND`) and **inserted qualified** (`FabricateAlphaMode.BLEND`, via
     `AaCandidate.insertText`), because Kotlin only resolves a bare entry from the expected type inside a
     `when` subject. `qualifierFor()` picks the shortest form that actually compiles in the file's import
     context, falling back to fully qualified.
   - A binary enum's entries are **not** in `declaredMemberScope** — that comes back empty, and they are in
     the static scopes. All three are unioned, the same lesson `staticScopeCandidates` learned on the dot
     path.

Every other candidate the branch produces is flagged `fitsExpected` when its (substituted) return type
`isSubtypeOf` the expectation, so a local, a member or a factory function of the right type ranks with the
literals. Named parameters at an argument position are flagged too — at `f(‸` the parameter names *are* the
answer, and the collapse below must not hide them behind type-fitting values.

Because the candidate list is name-sorted before `limit` is applied, the sidecar puts fitting candidates
first *before* truncating (`CodeUseSemanticSidecar.kt`) — otherwise `UD`/`SD_*` fall off the end of a
200-row cut behind every `Box` member.

**Server side** (`execution.kt`): a fitting candidate arrives as `extraInfo["expectedTypeMatch"]`
(`AaCompletionClient.EXPECTED_TYPE_MATCH`) and gets `InfixCompletion.OPERAND_MATCH_BOOST`, the same boost
the infix path uses. And with **nothing typed yet**, the reply is collapsed to *only* the fitting
candidates (`typeFitFilterActive`) — that is what makes the panel at `d.scheme = ‸` three rows instead of
three hundred, regardless of how the client ranks or how old its `fieldSuggest.js` is. One keystroke
restores the full list with the fits merely boosted, so nothing is permanently hidden, and the collapse
needs ≥2 fits (≥3 for the infix path) so a bad resolution can never empty the panel.

Not done on **dot** positions: `d.scheme = foo.‸` could boost members returning `Scheme`, but the dot
branch resolves from the qualified expression and was left byte-for-byte alone.

#### The ASM symbol index (`AaSymbolIndex`)

Standalone AA **cannot** enumerate a *binary* package's members by prefix
(`packageScope.callables(nameFilter)` returns empty for binary packages), but it *can* resolve them by
exact name (`findTopLevelCallables`, `findClassLike`, `findTypeAlias`). So `AaSymbolIndex`
(`AaCompletion.kt:90`) scans the binary classpath roots once with ASM to learn which callable /
classifier / type-alias names exist per imported package, then AA resolves them on demand by exact name.
The index is disk-cached under the sidecar scratch dir, keyed by a stat-only classpath fingerprint
(`buildOrLoad`, `:112`; fingerprint `:130`), so a recompile transparently rebuilds it and an unchanged
classpath loads it instantly. Type aliases (which have no `.class`) are read from each file facade's
`@Metadata` proto (`MetadataVisitor`, `:238`). `FORMAT_VERSION` invalidates every cache when the scan's
semantics change.

The scan carries three maps beyond the by-package name lists, each answering a question the by-prefix
lists cannot:

- **`extensionsByReceiver`** — ASM internal receiver name → the extensions on it. An extension compiles to
  a static method whose *first parameter is the receiver*, so this costs nothing extra to collect, and it
  is what makes a **bare `recv.`** able to offer extensions at all: enumerating by name prefix means there
  is nothing to enumerate until a character is typed, which on Field's extension-heavy API is most of what
  anyone types after a dot (measured: a 25-point recall cliff between 0 and 2 characters typed). It is
  over-inclusive by construction — a plain `fun display(box: Box)` lands under `Box` too — and
  `isExtension` plus receiver conformance filter it, so a false entry costs one lookup, not a wrong
  candidate. Lookup keys are the receiver's own classifier **plus every supertype, plus the JVM built-in
  mapping** (`JavaToKotlinClassMap.mapKotlinToJava`): `fun Iterable<T>.map` compiles to
  `map(Ljava/lang/Iterable;…)` and `String.format` to `format(Ljava/lang/String;…)`, so without the
  mapping a `List` receiver finds nothing in `kotlin.collections` and a `String` receiver nothing in
  `kotlin.text` — the two largest extension families there are.
- **`allPackages`** — every package seen on the classpath, in scope or not, recorded before the
  in-scope filter. This is the vocabulary for **package completion** (`afield.‸` → `stdlib`, `four`,
  `graphics`): standalone AA cannot enumerate a binary root's sub-packages, and a package path is written
  out by hand precisely when nothing has been imported. Before it existed, `afield.` fell through to the
  value path and answered a package with `also`/`apply`/`let` — the `Any?` extensions, matching a receiver
  that has no real type. A value and a class both shadow a package, so the package branch is tried last
  and only when the receiver does not resolve to a callable — a lambda's `it.` otherwise matched the
  package `it.unimi.dsi` (fastutil).
- The scan does **not** filter on `ACC_PUBLIC`. A `@kotlin.internal.InlineOnly` function is `public inline`
  in Kotlin and *private in bytecode* — it has no callable entry point, only an inlined body — so
  filtering on public silently excluded `let`, `run`, `also`, `apply`, `takeIf` and `String.format`: the
  most-typed extensions in the language. (`"%.3f".format(…)` was in the caret sweep's miss list beside a
  200-candidate panel that could not contain it.) Genuinely private declarations get indexed too and are
  dropped by the use-site visibility check — one wasted lookup against a whole family of unreachable names.

### 2.6 Results back → enrichment → client-side ranking

Back in `execution.kt`, the `CompletionResult` metadata is enriched: KDoc is resolved from
`extraInfo["kdoc"]`, then `KotlinSourceIndex.getKDocForSymbol(...)` (using the receiver type for
dot-members), categorized, and infix operand type-matches get a `contextScore` boost
(`execution.kt:2886+`, ~`3012` for enrichment; `InfixCompletion` boost ~`3060`). The server then replies
`Message("complete").payload(Completion(boundsStart, boundsEnd, metadata))`.

**Ranking is client-side.** The server returns everything; `fieldSuggest.js` scores each item as
`fuzzy + categoryScore + recency + aiLateBonus + memberScore + contextScore − originalIndex*0.012`
(`src/commonMain/resources/fieldSuggest.js:685-701`) and sorts (`:712`). The AA backend deliberately does
**not** rank — it returns a de-duplicated, name-sorted candidate set and lets the client decide order
(`completion-ranking-clientside` memory).

---

## 3. The sidecar: process & lifecycle

**Module:** `tools/code-use-semantic-sidecar/` — a *separate* Gradle project (own `settings.gradle.kts`),
intentionally outside the main Field build so the IntelliJ-style AA classpath never mixes into the
runtime. Entry point `afield.tools.codeuse.CodeUseSemanticSidecarKt` (`build.gradle.kts:54`).

**Launch.** `field.sh` manages it (`ensure_semantic_sidecar`, `field.sh:71`):
- The port is derived from the checkout path so each worktree gets its own sidecar:
  `17841 + (cksum(path) % 1000)` (`derive_sidecar_port`, `field.sh:19-24`), overridable with
  `AFIELD_CODE_USE_SEMANTIC_SIDECAR_PORT`.
- It health-checks any configured/running sidecar (`sidecar_status` verifies the reported
  `workspaceRoot` matches this checkout), and if none is serving *this* checkout it starts one:
  `nohup ./gradlew -p tools/code-use-semantic-sidecar run --args="--port <p> --workspace <dir>"`
  (`field.sh:102`), logging to `build/code-use-semantic-sidecar/sidecar-launcher.log`.
- It exports `AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL` for the Field JVM (which `AaCompletionClient` reads).
- Disable launcher-managed startup with `AFIELD_CODE_USE_SEMANTIC_SIDECAR=off`.

Run it manually:
```bash
./gradlew -p tools/code-use-semantic-sidecar run --args="--port 17841 --workspace $PWD"
```

**Transport:** plain `com.sun.net.httpserver.HttpServer` on `127.0.0.1` (default port 17841,
`SidecarConfig`, `CodeUseSemanticSidecar.kt:249`), daemon thread pool of 2 (`--threads`, 1-8).

**Warmup / session lifecycle:** an AA session (`buildStandaloneAnalysisAPISession`, `:713`) indexes the
JDK + Field's binary classpath and costs ~1 min to build. It is built **once per classpath-root set**,
in the background, and held for the process lifetime (never disposed — `build()`, `:708-709`). Startup
progress is logged to the sidecar's stderr: `"[k2-completion] building AA session …"` then
`"[k2-completion] AA session ready in Nms (index: …)"` (`:689-694`). During the build, `/complete`
returns empty-success so the client uses K1; after it's ready, AA serves.

---

## 4. Backend selection & fallback

**Selection** lives in `KotlinEngineConfig` (`KotlinScriptEngine.kt`):

```kotlin
enum class CompletionBackend { K1, AA }                               // KotlinScriptEngine.kt:697
var activeCompletionBackend = when (System.getProperty(
        "afield.kotlin.completion", "aa").lowercase()) {              // :699
    "k1" -> CompletionBackend.K1
    else -> CompletionBackend.AA                                      // DEFAULT = AA
}
```

- **Default:** AA. Force legacy K1 with `-Dafield.kotlin.completion=k1`.
- This is independent of the *execution* backend (`activeBackend`) — completion and execution are
  separate knobs.

**Fallback to K1** happens (each logged as `AA→K1: <detail>` on the `completion` channel) when:

| Condition | Where | `detail` |
|---|---|---|
| No sidecar URL configured | `AaCompletionClient.kt:91` | `no-sidecar-url` |
| Empty classpath | `:92` | `no-classpath` |
| HTTP non-200 | `:110` | `http-<code>` |
| Sidecar reported failure | `:112` | `server-fail: …` |
| **Empty AA result** (incl. warmup) | `:116` | `empty…` |
| Timeout / network / any exception | `:143` | `error <Exception>: …` |

So AA is strictly additive: worst case you get exactly the K1 experience. The K1 delegate itself is
warmed ahead of time (`warmCompletion`/`warmCompletionAsync`, `KotlinInterface2.kt:587,603`) so the first
real request — and every fallback — is fast and doesn't re-enter the compiler mid-analysis.

---

## 5. Verification tooling

### `CompletionAaE2EProbe` — live end-to-end (Stage D)

`src/jvmMain/kotlin/afield/CompletionAaE2EProbe.kt` — FIELD_TEST `kotlin.completion-aa-e2e.probe`
(runner `jvm-main`, entrypoint `afield.CompletionAaE2EProbe`). Sets `activeCompletionBackend = AA`,
builds a parent→child box pair with an inherited preamble, and drives the **real**
`KotlinInterface2.complete()` chain against a running sidecar. It asserts AA-only signals (named-argument
completion, inherited `prop<>`/`@exported` names), measures warm latency, and verifies that a bad sidecar
URL falls back to a non-empty K1 result. **It SKIPs cleanly (exit 0, prints `AA E2E DONE`) when no
sidecar URL is configured**, so it never fails CI where no sidecar is up. Needs a live sidecar (URL via
`-Dafield.completion.sidecarUrl`, the env var, or `build/completion-corpus/sidecar-url.txt`).

Run: `python3 tools/run_field_tests.py --id kotlin.completion-aa-e2e.probe`.

### `CompletionDiffK1Probe` — the K1 side of the differential (Stage B)

`src/jvmMain/kotlin/afield/CompletionDiffK1Probe.kt` — FIELD_TEST `kotlin.completion-diffdump.probe`.
Runs the **real production K1 path** over the shared corpora and dumps raw candidates per fixture to
`build/completion-corpus/k1-candidates.json`. The AA side is dumped out-of-process by the sidecar spike
(`runSpike --json-out … aa-candidates.json`).

### `tools/completion_diff.py` — the K1-vs-AA differential

Reads both dumps and diffs them per fixture on **recall** (is the intended member present?) and
**anti-goals** (no synthetic-wrapper leak, no keyword-after-dot). Prints a side-by-side table and a
verdict. Report-only by default; `--gate` exits nonzero on a failing verdict (for CI); `--run`
regenerates both dumps first (recompiles, regenerates the Field classpath, runs the AA spike and the K1
probe). A small `KNOWN_HARD_REGRESSIONS` set (dynamic GPU-DSL receivers) is tolerated so the gate flags
only *unexpected* regressions.

```bash
python3 tools/completion_diff.py            # diff existing dumps, report only
python3 tools/completion_diff.py --run       # regenerate both dumps, then diff
python3 tools/completion_diff.py --run --gate # regenerate + fail on regression (CI gate)
```

### Corpus

`tools/completion-corpus/` holds the shared fixtures both sides consume:
`scraped.tsv` (real completion sites) and `name-fixtures.tsv`. Fixtures are `‸`-caret-marked, base64
snippet + intended member; the K1 probe and AA spike line up by fixture id.

### What a real run looks like

Running the canonical single runner on this branch:

```text
$ python3 tools/completion_diff.py --run --json-summary build/completion-corpus/diff-summary.json
... (compileKotlinJvm, regenerate classpath, AA sidecar spike, K1 jvm-main probe) ...
FIXTURE                           POS  MEMBER          K1        AA          NOTES
...
arg#Vector3f-empty                NAME x               ✗         ✓#7           ⟵ win
arg#Vector3f-afterX               NAME y               ✗         ✓#8           ⟵ win
hand#safe-dot                     DOT  length          ✓#1       ✓#1         type K1[Int] AA[kotlin/Int]
────────────────────────────────────────────────────────────────────────────
RECALL:   K1 23/31   AA 23/31
REGRESSIONS (K1 had, AA lost): 2  ['field_probes#0 .vec4s', 'field_probes#0 .buffer']
  ├─ known-hard (dynamic GPU-DSL, Stage C): 2  ['field_probes#0 .vec4s', 'field_probes#0 .buffer']
  └─ UNEXPECTED: 0  []
WINS (AA has, K1 lacked):      2  ['arg#Vector3f-empty', 'arg#Vector3f-afterX']
ANTI-GOAL leaks:      K1 2 [...]   |   AA 0 []
ANTI-GOAL kw-after-.: K1 4 [...findAttractors(field,file,finally)...]   |   AA 0 []
════════════════════════════════════════════════════════════════════════════
VERDICT: PASS  (AA recall 23 >= K1 23; AA anti-goals clean; unexpected regressions 0)
```

That run is real (captured 2026-07-20 on this branch): AA matches K1 recall (23/31), adds the two
named-argument wins K1 can't produce, and stays clean on both anti-goals (K1 leaks the two synthetic
arg-position candidates and emits four `keyword-after-dot` warts that AA never does). The two AA
"regressions" are the documented `KNOWN_HARD_REGRESSIONS` — dynamic GPU-DSL receivers that only resolve
inside a live box runtime, not the headless spike.

---

## 6. Where to look when completion misbehaves

- **"Why did I get K1?"** — grep the `completion` log channel for `AA→K1: <detail>` (the table in §4
  decodes each reason). Common: `no-sidecar-url` (sidecar not launched), `empty…` (session still warming
  or a preamble form the headless session can't model).
- **Sidecar not starting** — `build/code-use-semantic-sidecar/sidecar-launcher.log`; check the derived
  port with `derive_sidecar_port` and `lsof -i`.
- **Wrong/stale candidates for Field's own types** — the ASM index is classpath-fingerprint cached; a
  recompile rebuilds it, but confirm `classpathRoots` (the client sends `java.class.path`) points at the
  fresh `build/classes`.
- **Named-args / inherited names missing** — that's the AA-specific surface; check the preamble
  (`gatherPreambleUpwards`) and implicit receivers actually reached the sidecar (they're logged), and see
  `AaCompleter.splitPreamble`.

## 7. Related docs

- [`completion_k2_migration_plan.md`](completion_k2_migration_plan.md) — migration narrative & stage log.
- [`COMPLETION_ENGINE.md`](COMPLETION_ENGINE.md) — preambles, `prop<>` macros, `@exported` aliases.
- `tools/code-use-semantic-sidecar/README.md` — running the sidecar; the `/analyze` telemetry contract.
