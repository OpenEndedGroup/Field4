# Package hierarchy and repeatable source migration

Proposal, 2026-10-13. Inventory taken from checkout `2bd96d75f`.
This proposes the hierarchy and tool contract. The initial offline import/source-move framework is
implemented in `tools/refactor.py`; see [its supported scope and workflow](../tools/refactoring/README.md).
The package cutover and K2 binding-equivalence layer are not implemented.

## Why this needs a coordinated migration

The current JVM source tree has 1,413 Kotlin files declaring 116 packages, with 43 immediate
children of `afield`. Of those files, 167 declare `afield.stdlib`, 142 declare `afield`, and 107
declare `afield.four.synth`. These are counts of files with package declarations, excluding generated
build output, not counts of public declarations. Some runtime probes and tests live in these roots.

52 JVM files have a package that disagrees with their directory. For example, files under
`afield/visualization`, `afield/workspace`, `afield/notation`, and `afield/internal` still declare
plain `afield`. Moving folders alone has already reached its limit.

The authored corpus is substantial too: tracked `public/docs` contains 991 Kotlin files and 251
`metadata.edn` files, including generated starting points. There are 257 YAML files under
`dev_docs/agents/transcripts`. Templates, demos, tools and external workspaces add further code.
These counts overlap generated/source representations; they are not distinct executable examples.

Recommendation: build a reusable migration engine, rehearse on copies, and then switch the repository
in one coordinated landing. Keep the migration recipe available to upgrade workspaces afterwards.
Separate package relocation from behavioral redesign and Gradle module extraction.

## Proposed hierarchy

Packages answer “what is this for?” A recognizable subsystem keeps its name under its domain.
The package root `afield` has no routine implementation; the launcher can retain a tiny entrypoint
there if that materially simplifies distribution. The default script imports supply convenience.

| Root | Children / responsibilities | Principal current sources |
| --- | --- | --- |
| `afield.document` | `graph`, `properties`, `context`, `persistence`, `workspace`, `history` | `Box`, properties/inheritance/context resolution, saving/EDN, workspace helpers |
| `afield.runtime` | `execution`, `kotlin`, `completion`, `macros`, `lifecycle` | execution, Kotlin engines/parser, completion, shared types, run scopes/runloop, macro expansion |
| `afield.app` | `server`, `protocol`, `client`, `editor`, `canvas`, `settings`, `diagnostics` | server/RPC transport, browser UI, launch/settings, logs/profiling |
| `afield.ui` | `output`, `controls`, `notation`, `slides`, `visualization` | box-facing outputs/widgets, notation, slideshow, charts/tables/value visualizers |
| `afield.math` | `linalg`, `functions`, `autodiff`, `statistics`, `optimization`, `gp` | numerical `stdlib`, function graphs, Ceres, existing `math.gp` |
| `afield.geometry` | `path`, `mesh`, `solid`, `polytope`, `spatial` | FLine geometry, mesh helpers, Manifold, polytopes, tessellation/TetGen geometry |
| `afield.graphics` | `vulkan`, `scene`, `material`, `render`, `shader`, `text`, `three`, `paint` | renderer/device/buffers/targets, fabricated scenes/passes/path tracing, GPU drawing, Three, paint |
| `afield.audio` | `synth`, `analysis`, `spatial`, `ai`, `csound`, `pd`, `tone` | `four.synth`, Steam Audio, Loris/Essentia, `stdlib.noir.audioai`, audio integrations |
| `afield.media` | `image`, `video`, `capture`, `vision`, `reconstruction` | imaging helpers, video/stream/camera work, vision and reconstruction |
| `afield.animation` | `keyframes`, `interpolation`, `timeline`, `gesture` | keyframe engine/editor helpers, animation-specific interpolants and gesture timing |
| `afield.simulation` | `physics`, `particles`, `agents`, `automata`, `petri`, `kratos` | `four.physics`, alife families, particle systems, Petri, Kratos |
| `afield.data` | `table`, `graph`, `query`, `formats` | data-frame support, graph algorithms/query, Datalog, JSON/data codecs |
| `afield.io` | `osc`, `midi`, `dmx`, `mqtt`, `timecode`, `syphon`, `process` | external protocols, devices and process transport |
| `afield.ai` | `models`, `inference`, `conversation`, `agent`, `tools`, `completion` | AI4 split by responsibility, ONNX/inference; model-assisted completion lives here |
| `afield.lang` | `stacksl`, `rectsl`, `solidsl`, `fieldml`, `nano`, `morpho`, `forth`, `brief`, `python`, `clojure`, `lua`, `remotejs` | named languages, compilers/interpreters and their adapters |
| `afield.study` | `source`, `build`, `search`, `evidence`, `ui` | live studies, variants/search, source-backed blocks, evidence and study panels |
| `afield.tools` | `automation`, `refactor`, `docs`, `testing` | backdoor/transcripts, migration, doc generators/guards, runtime test launchers |
| `afield.examples` | domain-named children | runnable examples and demos; ordinary tests mirror production packages in test source sets |

This reduces the immediate domain roots from 43 to 18 while breaking up the largest mixed packages.
It is a destination taxonomy, not a demand to create every listed child immediately. A child package
needs a coherent body of code. Most normal imports should have two to four components after `afield`.

### Decisions that make this more than folder tidying

- Retire `stdlib` and `four` as ownership categories. The standard library is the collection of
  public domain APIs, not one package; audio is independent of the Vulkan renderer.
- Separate geometry from rendering. `FLine` belongs in `geometry.path`; SVG/Vulkan display adapters
  belong with their renderers or UI. Three and Vulkan can consume the same geometry.
- Keep recognizable language names. `afield.ml` currently contains FieldML compiler code, so it
  becomes `afield.lang.fieldml`, not a machine-learning package. Audio languages stay with audio
  when their primary public contract is an audio engine; the runtime language registry can register
  them without moving their implementations into `lang`.
- Split `ai.core` into specific responsibilities. Kotlin compiler completion belongs in
  `runtime.completion`; LLM-assisted completion belongs in `ai.completion`.
- Use local `internal` subpackages where they clarify ownership, plus Kotlin visibility where
  appropriate. A global `util`, `internal`, `core`, or `modules` package is not a destination.
- Keep source paths and packages aligned, including common/JVM/JS counterpart declarations.
  Package organization is separate from platform availability and Gradle module boundaries.
- Split mixed source files before assigning packages. For example, `fromContext` is document
  context lookup even though it currently lives in `four/modules/Mesh.kt`.
- Distinguish library code from Field integration: pure geometry/math should not acquire a
  dependency on `Box` merely because a sibling adapter renders a control panel. Use domain-local
  `field` adapters where needed, rather than creating a second duplicate library under another root.

Target dependency direction: reusable math/data/geometry and domain engines; then document/runtime
integration and UI; then application composition and tooling. This is an intended direction, not a
claim that the current graph is acyclic. Inventory actual dependency cycles and register specific
temporary exceptions before enforcing a new edge policy. Moving a declaration cannot fix a cycle.

### Preserve the live-coding experience

`intro` in `Boxes.kt` currently imports a large portion of this surface implicitly. A box with no
imports can therefore break during this refactor. Migration must preserve its resolved API too.

Create one versioned default-import profile shared by execution, completion, docs, compilation
fixtures and migration. Derive it initially by translating the current profile, including explicit
imports that disambiguate names. Keep `vec`, `FLine`, `display`, etc. convenient without forwarding
all of their declarations through a new catch-all package. Review slimming the defaults separately.

`introImports` already carries live imports to completion. The semantic sidecar also contains
fallback/default lists; the migration must cover those and offline clients explicitly.

## Refactoring mechanism

Build a headless Kotlin engine with a thin `tools/refactor.py` command wrapper. The same engine can
later serve a Field “Upgrade workspace” panel. It consumes a versioned migration bundle and produces
a reviewable plan. It does not need a running document to edit persisted source.

Scope for version one: package/type/top-level declaration relocation and renaming, import repair,
source path moves and known embedded-code containers. Signature changes are manual findings until
an explicit, tested transformation is added. The framework must be extensible without claiming to
be a universal program rewrite system.

### 1. Migration bundle: a durable mapping of symbols

Author the recipe under `refactors/<migration-id>/`. Include source/target API versions, recipe
schema version, default-import profiles, expected dependency/compiler environments and mappings.
Use exact symbol moves as the final authority; package rules are shorthand expanded against the
old declaration index. Match package components, not arbitrary string prefixes.

Illustrative configuration, not an existing supported format:

```yaml
id: preview-packages-v1
schema: 1
fromApi: pre-preview
toApi: preview-1
packages:
  afield.stdlib.linalg: afield.math.linalg
  afield.stdlib.polytope: afield.geometry.polytope
  afield.rectsl: afield.lang.rectsl
symbols:
  afield.Box: afield.document.graph.Box
  afield.graphics.FLine: afield.geometry.path.FLine
  afield.stdlib.Vec3: afield.math.linalg.Vec3
  afield.stdlib.vec: afield.math.linalg.vec
  afield.four.modules.fromContext: afield.document.context.fromContext
splitPackages:
  - afield
  - afield.stdlib
  - afield.graphics
  - afield.four.synth
```

The simple `symbols` notation means a whole overload family when several callables share an FQN.
The expanded index stores kind, receiver and signature for individual declarations, nested owners,
platform/source set, visibility, old/new source location and old/new JVM names when applicable.
Reject ambiguous rules, destination collisions and unmapped declarations in retired packages.
Cover Java/JVM consumers as well as Kotlin, including generated file-facade names and entrypoints.
Persist the expanded mapping; an external workspace must not require an old Git checkout to migrate.

### 2. Corpus adapters: extract code without losing its container

Each adapter yields a code unit with container path, stable identity, language, original byte spans,
decoding/encoding information, compilation context and generated/source ownership. It patches only
changed spans where possible, preserving comments, indentation and line endings.

| Surface | Handling |
| --- | --- |
| `.kt`, `.kts`, Java consumers | Syntax-aware source/import/package edits and planned file moves; source-set-aware resolution |
| Markdown/KDoc | Kotlin fences including attributed/nested fences, imports and qualified links; prose references reported separately |
| Transcript/macro YAML | Registered Kotlin-bearing properties and operations, including preambles, run strings, and edit `from`/`to`; round-trip quoted/block scalars |
| Field documents/templates | EDN codec plus language/property metadata; inline code, sidecars, preambles, shared types and personal initialization code |
| Generated starting points/reference pages/linalg/corpus fixtures | Edit generator inputs/configuration and regenerate outputs in dependency order |
| Build scripts, launchers, manifests, Python/JS/shell-embedded Kotlin | Registered typed fields or code-region extractors; unresolved textual occurrences become findings |
| Other workspace content | Inventory and report suspected code; leave unidentified strings and binary files unchanged |

Use repo tracking and generator ownership to select repository inputs, not a blind recursive rewrite.
External roots are explicitly supplied; include their relevant untracked documents. Resolve paths,
deduplicate overlapping roots, confine sidecars to their documents and reject escaping symlinks.
Private workspaces produce local reports/backups outside the repository.

The existing import census is useful inventory, not a sufficient editor: it covers selected Markdown
fences, top-level transcript code blocks and `_code_.kt` sidecars. General migration also needs
inline EDN, other Kotlin properties, nested/quoted YAML and Kotlin embedded in tooling.

### 3. Rewrite with graded evidence

- Exact explicit imports can be mapped syntactically when the destination is verified, preserving
  aliases and comments. Importing a nested type or a member requires mapping its owning declaration.
- For a whole-package move, translate its star import. A package split needs resolution evidence:
  introduce the destination imports for what that code actually uses, including extension functions,
  operators, delegates and overloads. Expanding one star into every possible destination is only a
  review candidate: compilation can succeed while selecting a different overload.
- Resolve qualified expressions before editing them. A token spelled `afield` could be a local
  value; matching text alone does not prove it is a package qualifier. Type references in annotations,
  callable references, class literals and supertypes are included.
- Moved source files can lose same-package visibility of unqualified declarations. Repair those
  imports from old bindings even if the original file contains no import statements.
- Analyze boxes with the actual default imports, inherited preambles/shared types, language and
  dependency environment. Resolve before migration, then check that post-migration bindings match
  the symbol map. Compilation alone is a weaker check and is reported as such.
- Separate executable references from reflection/class-name strings, serialization names, source
  links, native entrypoints and documentation prose. Registered consumers can migrate these;
  arbitrary string literals are findings rather than guessed edits.
- Classify each finding as automatic, needs semantic context, manual, generated, or deliberately
  retained. An incomplete resolution is never reported as a successful complete migration.

Reuse `FieldKtParser` for syntax and the K2 infrastructure for semantic work. The current
`k2ImportEvidence` endpoint returns FQN sets and an unresolved count and documents that its source
analysis lacks the full library classpath. It is not sufficient for declaration-accurate edits.
Extend analysis to return per-reference spans and stable symbol identities under the actual old
classpath, and provide a comparable target-environment pass. Unavailable old dependencies or runtime
context become explicit review findings, with compilation-only evidence labeled separately.

Build/package the migrator before moving Field itself. Preserve old symbol/default-import snapshots
and isolate old/new compiler analysis environments, preferably in separate processes. The tool must
still run while the target source tree is temporarily uncompilable.

### 4. Document integrity and repeatability

`SavingContext` already understands `#afield/file` and `#afield/file-inline`. A migration must update
the authoritative sidecar and its inline fallback together. Inline-only source must also migrate;
a missing referenced file must be reported, with a valid fallback handled according to the document
codec rather than silently discarded. Preserve box IDs, connections, layout and unrelated properties.

`canonicalInlineMetadata` / `refreshInlineFilesFromDisk` provide useful building blocks, but the
current implementation requires sidecars to exist and may normalize unrelated EDN text. Implement a
focused migration adapter and expose unavoidable normalization in the preview.

Keep historical backups and conversation logs intact. Reusable macros are current source and their
forward/reverse edits migrate together. Restoring historical document content must invoke the same
migration check so undo/restore cannot silently reintroduce an incompatible API.

Default application target for somebody's workspace is a sibling migrated copy. In-place mode
requires an exclusive document/workspace lease, flushing and quiescing any live writer. A migrator-only
lock cannot stop an older Field autosave process; that case requires closing the workspace first.

Plans record recipe hash, tool version, root identity, source hashes, every edit/move, findings and
validation results. Before any write, validate the whole plan and reject stale input. Stage outputs
and journal original bytes before applying. Atomic replacement is per file; the journal supplies
recovery for a multi-file interruption. Undo checks current output hashes and refuses to overwrite
later edits. Copied workspaces can be published by a directory rename on the same filesystem.

Repeated application produces no further edits. An applied-migration marker is advisory; rescan
content because users may copy an older box into an upgraded workspace. Explicit API version paths
support composing later migrations; mixed-version or unknown-version code is reported rather than
forced through a guessed chain.

### 5. Proposed command workflow

These commands describe the original intended interface. The working CLI takes a recipe file and
uses the application journal to locate its target; see the implementation guide linked above:

```text
uv run python tools/refactor.py inventory --repo . --workspace /path/to/workspace
uv run python tools/refactor.py plan preview-packages-v1 --repo . --out /local/plan
uv run python tools/refactor.py plan preview-packages-v1 --workspace /path/to/workspace --out /local/workspace-plan
uv run python tools/refactor.py apply /local/workspace-plan --copy-to /path/to/workspace-preview
uv run python tools/refactor.py verify /local/workspace-plan --root /path/to/workspace-preview
uv run python tools/refactor.py undo /local/workspace-plan --root /path/to/workspace-preview
```

The plan contains a readable summary, unified diffs and structured findings. A document-level unit
is applied completely or held for review; applying safe units must still leave the overall status
“partial” while findings remain. Report files/units scanned, skipped and unsupported, not just edits.
Verification tiers are parse, resolve/compile, and explicitly selected runtime tests. Scanning and
applying user code do not execute it; runtime checks run only the chosen workload.

## The stop-the-world moment

1. **Prepare while ordinary work continues.** Ship the engine, corpus inventory and package policy.
   Complete symbol destinations, enumerate generator owners and dynamic references, and capture the
   existing bindings/build/smoke baseline. Rehearse the full migration on an isolated checkout and
   copies of the nominated workspaces. First prove a simple package move and a difficult `stdlib`
   split, including default imports and an EDN sidecar/fallback pair.
2. **Freeze at a known main revision.** Finish preceding queue work and hold subsequent landings by
   an agreed coordination mechanism. Snapshot the selected workspaces and stop their writers. Finish
   outstanding topic branches first where practical; give remaining branches the same migration
   recipe for reapplication/rebase after the switch. The freeze covers landing and affected workspace
   edits, not every running Field instance on the machine.
3. **Apply on a topic branch.** Move declarations/files and repair references, default imports,
   generators, reflection/build entrypoints, embedded code and hand-authored assets. Preserve RPC
   names, document keys and serialization tags unless a separately specified schema migration needs
   to change them. Source package names and persisted protocol identities are different contracts.
4. **Regenerate and verify.** Update generator implementations before generating linalg, reference
   pages, starting points and completion fixtures. Compile both platforms and tools, check Java/JS
   exports and Kotlin `expect`/`actual` pairing, then exercise box compilation/completion, transcript
   replay, docs and representative migrated documents. Perform visual/runtime checks where needed;
   GPU/audio/platform-specific coverage must come from supported hardware. Finish with the existing
   full landing guard and required docs/smoke checks, serialized per checkout.
5. **Land once through the queue.** Use the normal topic-branch enqueue/wait process and resume
   subsequent landings after success. A bounce is a failed cutover, not completion. Publish the
   migration bundle with the preview so older workspaces can upgrade independently.

The cutover is ready when retired executable references are zero except individually reviewed
compatibility cases; all current-source code units are migrated or explicitly accounted for;
binding comparisons show no unintended target changes; generated output is current; required checks
pass; and each selected workspace has a verified copy plus a recovery record. Distinguish historical
references intentionally retained from active code. Unavailable required hardware/context is an
outstanding validation item, not a green result.

Prefer a clean source break before preview plus a permanent upgrader. Add narrowly scoped deprecated
aliases/wrappers only for dependencies that cannot yet migrate. Type aliases can help Kotlin source
but do not recreate old JVM class identities or automatically forward top-level functions; binary,
reflection and serialized-name compatibility require separate treatment.

## Keep the hierarchy healthy afterwards

Add a machine-readable package policy and a `checkPackageArchitecture` gate. It checks declared
package/path agreement, allowed roots, ownership of new public APIs, forbidden dependency edges
with explicit temporary exceptions, public APIs leaking implementation packages, and new references
to retired packages. Report package growth as a review signal rather than a hard arbitrary size cap.
Generated sources are checked against generator ownership too.

Every later public relocation ships a small migration bundle with adapter fixtures, resolution
checks, idempotence/stale-plan/undo tests and a documented API-version transition. This makes the
large preview migration the first user of a maintained capability rather than a disposable script.

## Existing implementation anchors

- Default scope: `src/jvmMain/kotlin/afield/Boxes.kt` (`intro`, `introImports`).
- Syntax/import analysis: `src/jvmMain/kotlin/afield/internal/FieldKtParser.kt`,
  `src/jvmMain/kotlin/afield/internal/boxImports.kt`.
- Corpus census: `src/jvmMain/kotlin/afield/examples/BoxImportCensus.kt`.
- Semantic infrastructure: `tools/code-use-semantic-sidecar/src/main/kotlin/afield/tools/codeuse/`.
- Persistence: `src/jvmMain/kotlin/afield/saving.kt`, `src/jvmMain/kotlin/afield/edn.kt`.
- Transcript semantics: `src/jvmMain/kotlin/afield/operation_transcript.kt`.
- Starting points: `src/jvmMain/kotlin/afield/examples/AgentTranscriptMaterializer.kt`.
- Reference generation: `src/jvmMain/kotlin/afield/docs/StdlibReferenceGenerator.kt`.
- Regeneration ownership: `tools/check_staged_generated_artifacts.py`, `tools/merge_regen.py`,
  `tools/linalg-generator/`, and the corresponding Gradle tasks.
