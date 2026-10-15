# Field source migration

`tools/refactor.py` inventories, previews, applies and undoes explicit import migrations across
tracked repository files and external Field workspaces. It uses a standalone Kotlin PSI parser,
container adapters, content hashes and a recovery journal. It never runs box code or replays a macro.

The current engine provides **syntax evidence**, not compiler binding equivalence. A ready change
is a mechanically applicable rewrite, not proof that the destination API exists or that the result
compiles. This is the first working layer of the [package refactor proposal](../../dev_docs/PACKAGE_REFACTOR_PROPOSAL.md).
The repository package relocation is implemented; the [migration recipe](../../refactors/preview-packages-v1/README.md)
is the entry point for migrating external workspaces.

## Build once, before moving packages

From the checkout:

```sh
uv run --project . --group backdoor python tools/refactor.py build-engine
```

This runs the standalone engine's tests and builds
`tools/refactor-engine/build/install/field-refactor-engine/`. Copy that whole distribution if using
it elsewhere, then pass its `bin/field-refactor-engine` executable with `--engine` when planning.
It has no runtime dependency on Field's source tree, custom compiler or native libraries. It needs
Java 17 or newer. The Python layer uses the repo's `uv` environment and PyYAML. This initial CLI is
supported on macOS/Linux; Windows process/locking integration is still outstanding.

## Inventory and plan

```sh
uv run --project . --group backdoor python tools/refactor.py inventory \
  --workspace /path/to/AFieldWorkspace --out /path/to/reports/inventory

uv run --project . --group backdoor python tools/refactor.py plan /path/to/migration.json \
  --repo . --workspace /path/to/AFieldWorkspace --out /path/to/reports/migration
```

Repeat `--repo` or `--workspace` for independent roots. Overlapping roots are rejected. Reports must
be outside the inputs and the output directory must be new. Workspace source and reports remain
local; keep private workspace plans outside public repositories.

The plan directory contains:

- `report.md`: counts, evidence limits and finding categories, with numbered roots.
- `changes.diff`: proposed changes, including candidates marked `HELD`.
- `findings.json`: exact file/unit locations and reasons, including excluded/retained content.
- `review.md` and `review-groups.json`: migration-relevant findings grouped by document/file,
  including reference-only cases. Unrelated inventory parse findings remain in `findings.json`.
- `plan.json`: immutable recipe, root identities, input hashes, changes and syntax evidence.
- `blobs/`: original and proposed bytes, addressed by hash, used for apply and undo.

Repository input is Git-tracked content. Stage new source files if they should join an inventory.
Git attributes marking generated or vendored files are honored automatically; recipes can name
additional generator-owned paths. Generators are reported with their commands, never launched
implicitly. Workspace discovery includes untracked documents, but excludes `.git`, `.gradle`,
`.kotlin`, `.venv`, `node_modules`, `build`, `backups`, `backup`, `.backups`, `thumbnails` and
`.field-backdoor` directories. Historical `transcript.edn` and `history_metrics.edn` are retained.
Symlinks are reported and not traversed or edited. Non-UTF-8 and oversized text files are findings;
the per-file limit is 16 MiB. Other binary/unrecognized extensions are counted as uninspected.

Inventory uses the same adapters and parser with an empty recipe. Syntax errors are therefore
visible before choosing any move. The parser is pinned independently of Field's customized
compiler: incomplete snippets or custom syntax can require review even if Field accepts them.

## Recipe

JSON and YAML are accepted. A small example:

```json
{
  "schema": 1,
  "id": "move-example-v1",
  "fromApi": "before",
  "toApi": "after",
  "packages": {"afield.oldarea": "afield.newarea"},
  "symbols": {"afield.mixed.Thing": "afield.model.Thing"},
  "splitPackages": ["afield.mixed"],
  "kotlinProperties": ["myKotlinProperty"],
  "qualifiedNameProperties": {"tools/example/packages.properties": ["api.package"]},
  "generated": [{"glob": "generated/*", "regenerate": "run the owner generator"}]
}
```

Package rules move a whole package tree, matching name components. Exact symbol rules take
precedence; a callable name denotes its entire overload family. Chains, cycles and direct
destination collisions are rejected. Target names are supplied by the recipe author; they are not
inferred or resolved against a target classpath in this version.

Explicit imports, aliases, nested-type imports and whole-package star imports can be rewritten.
Source package declarations and matching directories move for whole-package rules. A mismatched
source directory, occupied destination, split package or individually moved declaration needs
review. Source moves do not stage Git changes. Qualified types, constructor calls and other dotted
code references are rewritten at the mapped package/symbol prefix. Type arguments, nested calls
and unrelated text stay intact. Locally declared/imported names shadowing either package root,
comments or type arguments inside the moved prefix, and unmapped split-package references remain
findings. This is syntactic evidence; inherited or external value receivers still require compiler
validation. Reflection-like strings, split star imports, and names outside recognized units produce
findings. KDoc/comments are findings unless the explicit text pass below is enabled.

Generator package names belong in a configuration file, rather than repeated code-emission strings.
`qualifiedNameProperties` registers `.properties` path patterns and the exact keys whose values are
qualified Kotlin names. Those values use the same package/symbol rules as imports; unrelated keys,
comments and whitespace remain intact. The adapter accepts literal `key=value` lines and rejects
escapes, continuations, duplicate keys, missing registered keys and invalid names. Arbitrary strings
are still findings. The linalg example registers its generator's `linalg-packages.properties`; that
one file drives emitted packages, imports, annotations, documentation and output directories.
Generated outputs are reported with `generateLinalg` as their owner, not edited as ordinary source.

When emitted code refers to a class already available to the emitter, prefer
`Quaternionf::class.qualifiedName` over a copied qualified-name string. The import then follows an
ordinary class move. Use the known constructor class, rather than `value::class`, when the emitter
supports a base type but might receive anonymous subclasses. `kotlinValueLiteral` in the keyframer
uses this pattern; generators that must build independently of their output use configuration.

`refactors/examples/linalg-package.json` is deliberately a **rehearsal** recipe: its destination
package does not yet exist. Use it on copies to exercise the tool, then undo. It is not an upgrade
for a current Field installation.

`refactors/examples/preview-domains.json` broadens the rehearsal to 18 whole-package families:
linalg, synthesis, physics, polytope geometry, vision, six language packages, graph/datalog,
OSCQuery/RTP-MIDI, Loris and audio AI. Mixed roots such as `afield`, `afield.stdlib` and `afield.four`
need individual declaration decisions and are not moved wholesale. This recipe does not assume
that every script receives Field's default imports.

### Comments, Markdown and contextual review

`"rewriteComments": true` enables exact qualified-name replacement in Kotlin comments and KDoc.
`retainedFindings` records narrowly reviewed historical exceptions. Each entry has an exact
root-relative `path`, extracted `unit`, `kind`, `detail`, SHA-256 `codeSha256` of the decoded
UTF-8 unit, and a human-readable `reason`. Only unmapped imports and prose/comment/string
references qualify. The exception releases that finding only while the entire unit remains
unchanged and the engine proposes no rewrite to it. New occurrences, changed units, and
structural errors still require review. Retained findings remain in the audit report; other
edits in the document can proceed. This does not assert that historical code compiles.

Set `parseErrorPolicy: "retain"` when existing incomplete or invalid Kotlin units should
remain diagnostic evidence rather than block this migration. The default is `"hold"`.
Only `parse-error` findings from unchanged units are retained; the parser does not rewrite
those units. Independent edits in the same document can proceed. Rewrite parse failures,
container errors, sidecar drift and all other findings still block. Retained errors remain
in `findings.json` and are counted in `report.md`; this policy does not waive delivery checks.

Generated bundle filenames `afield.mjs` and `afield.mjs.map` are preserved in text and do not produce package-reference findings.
Field URLs (`afield:/...` and `afield://...`) are likewise opaque identifiers: the scheme,
path and query are preserved and excluded from package-reference findings in logs, prose,
comments and strings. A real qualified name elsewhere on the same line is still inspected.
Hyphenated root labels such as `afield-stacksl-token-comment` are also preserved; they are resource names, not Kotlin packages.
`"markdownProse": ["public/docs/*.md"]` enables the same operation in selected Markdown prose;
patterns match paths relative to each root (`*` can span directories). Kotlin fences still use
the parser, and other fenced languages are excluded from prose rewriting.
`"workspaceMarkdownProse": ["*.md"]` adds all Markdown, including root-level and nested files,
only for workspace roots; it does not widen repository scope. These options default off. Matches use whole name components; slash paths and similar-but-unrelated package names are
preserved. Strings in Kotlin remain findings even when the comment pass is enabled.

Use narrow paths for current documentation. Historical discussions, mixed-language examples and
embedded commands need context: an old name may be intentionally explaining the migration. The
broad rehearsal recipe selects public docs, all Markdown under `dev_docs` and `native`, and
all workspace Markdown, including nested notes and handoffs. This produces reviewable proposals for internal prose too; inspect historical
references before a real cutover. Non-Kotlin fences still require their own adapters.
`review.md` and `review-groups.json` collect affected groups rather than treating every pre-existing
parse error as a migration blocker. They are inputs for contextual review, including `codex exec`;
the tool does not itself launch a reviewer. Keep private workspace reports outside the public repo.
Review source as data, propose source/recipe changes, and replan before applying; never edit a
hashed plan to approve a finding. Compilation and runtime checks remain separate requirements.

### Removing imports already supplied by the script intro

An optional `scriptImports` list records the **destination** host imports, without the `import`
keyword, for example `"scriptImports": ["afield.math.linalg.*", "afield.math.linalg.Vector3f"]`.
Only use this profile for roots whose script units actually receive that intro. It applies to all
recognized script units in those roots, including Markdown/YAML snippets and `.kts` files;
compiled `.kt` source imports are retained. The list is pinned inside the hashed plan.

The engine first maps each import to its destination, then removes exact unaliased duplicates of
the supplied intro. Aliases and comments are preserved. An intro wildcard alone does **not** justify
removing an explicit named import: it might disambiguate a name provided by another wildcard.
Likewise, fully qualified references are relocated, not shortened on an assumption about scope.
Field's current intro in `Boxes.kt` explicitly imports the six `Vector2f`/`Vector3f`/`Vector4f`
and `fc` types, plus the linalg, arena and storage wildcards. A future intro profile must use their
post-migration names. The existing live-box `analyzeBoxImports`/`formatBoxImports` helper can do
broader cleanup with classpath/K2 evidence; this standalone tool does not infer that evidence.

## Generate a split-package symbol map

The map needs a declaration inventory and reviewed destination choices. Generate the inventory
from the unmodified session worktree before changing source:

```sh
uv run python tools/refactor.py declarations --repo /path/to/session/repository \
  --out /path/to/session/declaration-index
```

This indexes Git-tracked `.kt` files in Kotlin source directories, including common/JVM/JS and
standalone tools. It uses PSI rather than regex to identify top-level classes, objects, aliases,
properties and functions. It records source hashes, signatures, visibility and individual overloads.
Class members and local declarations are not separate package-level symbols; nested references
follow the owning type's mapping. Parser errors are explicit inventory gaps. This is syntax
inventory, not a resolved symbol/ABI index.

Assign coherent files to destination packages, with exact symbol choices for mixed files:

```json
{
  "schema": 1,
  "files": {
    "src/jvmMain/kotlin/afield/visualization/Colormaps.kt": "afield.graphics.color"
  },
  "symbols": {
    "afield.four.modules.fromContext": "afield.document.context.fromContext"
  },
  "keep": []
}
```

```sh
uv run python tools/refactor.py symbol-map --repo /path/to/session/repository \
  --index /path/to/session/declaration-index/declarations.json \
  --assignments /path/to/assignments.json --recipe /path/to/session/recipe.json \
  --out /path/to/session/symbol-map
```

The output `recipe.json` expands file choices into exact symbol families; `symbol-map.json` keeps
per-declaration evidence and unassigned families, and `report.md` groups remaining choices by file.
`assignments.template.json` lists the remaining files with null destinations: fill in coherent
file destinations, leaving null entries unresolved, then generate into a new output directory.
A file assignment covers all overloads/platform counterparts of each named family, even when some
are declared elsewhere. Conflicting family choices are rejected rather than picking the last one.
Private top-level declarations stay in the inventory but are not exported into reference rules.
Explicit `keep` families become `retainedSymbols`; they cannot overlap move rules. Stale source
hashes, added/removed tracked sources, unknown choices, parser errors in assigned files and direct
destination collisions fail closed.

The generated `knownPackages` records old and proposed package ownership. A split applies to its
own declarations, not automatically to every descendant package. This avoids treating an unmoved
child such as `afield.math.gp` as part of a split of the root `afield` package. Unknown qualified
names remain findings.

A name ending in `Kt` (`afield.stdlib.StdlibKt`, `ImagingKt`, `afield.tools.refactor.MainKt`)
names the class kotlinc generates for a file's top-level declarations — the file facade of
`stdlib.kt`, `imaging.kt`, `Main.kt` — not a declaration in the inventory. Map it by its file:
it goes to the package that the file's top-level declarations move to, and keeps its name only
while the file keeps its name and has no `@file:JvmName`. When a file splits across packages, the
old facade has no single destination. Each new file gets its own facade, so a `Class.forName`,
`hostClass` or `run-jvm-main` string that names the old one has to name the file holding the
member it uses. Do not invent a symbol rule for a `...Kt` name or treat it as unknown. The
`symbol-map` step should derive these facade rules from the file assignments and report the split
files; once it does, this paragraph can go.

Review the generated recipe, copy it to the session's `recipe.json`, then replan. This step does
not move declarations out of mixed files, add same-package imports, or resolve split star imports.
Those remain held until extraction and binding-aware repair are done. Do not treat an empty
unassigned list as proof of equivalent JVM names, overload resolution or script defaults.

## Containers and document boundaries

- `dependencies.edn`: Field dependency telemetry version 1 is a separate container from box
  metadata. `FROM_CONTEXT` edge names are mapped when they contain literal qualified class names.
  Property names, box IDs, counts, timestamps and other edge fields are preserved.
  Unsupported versions, nested-class binary names and array descriptors remain review findings.
- Kotlin source and scripts: PSI edits preserve import aliases, comments, LF/CRLF and Unicode offsets.
- Markdown: fenced Kotlin, including attributed, nested and tilde fences. Selected prose can use
  explicit qualified-name replacement; other prose references are findings. Field's `diffstatic`,
  `diffstatic_runner`, `diff` and `diff_runner` Kotlin fences preserve section/fold markers while
  parsing their code. Before and after sections are independent programs. Unknown markers stay
  syntax findings; ordinary Kotlin fences do not accept Field's documentation wrapper syntax.
- YAML: registered Kotlin properties, plus `edit`/`set` property `from`/`to`/`value` strings. Literal,
  folded and quoted scalars are decoded and checked after re-encoding. Aliases, duplicate keys and
  malformed YAML are held. Arbitrary run-command strings require an additional typed adapter.
- `metadata.edn`: a lossless structural reader selects registered properties (`code`, `preamble`,
  `sharedTypes`, plus recipe additions), `.kt`/`.kts` sidecars and properties explicitly marked as
  Kotlin in Field's document envelope. Unknown tags and unrelated
  bytes are preserved. Unsupported EDN reader forms are held, not guessed.
- `#afield/file-inline`: sidecar and inline fallback migrate together. Existing drift, missing or
  escaping sidecars hold the document. Inline-only code is supported; missing sidecars are not
  silently recreated from fallbacks. Explicit/inherited non-Kotlin language markers need review.

The nearest containing `metadata.edn` defines a transaction group, including its macros and loose
Kotlin files. Any blocking finding in that group holds all of its proposed edits. Other files are
individual groups. Generated starting points remain generator-owned in repository mode. Backups
and logs are preserved; migrating code restored from history is a later explicit scan, not an
automatic restore hook in this version.

## Expanding wildcard imports from split packages

The symbol-map generator emits `splitStarImports` for each fully assigned split package:

```json
{"splitStarImports": {"old.mixed": ["new.geometry", "new.graphics"]}}
```

The engine replaces `import old.mixed.*` with both destination wildcard imports. It deduplicates
against existing destination stars and the configured script host imports, preserves line endings
and trailing comments, and leaves explicit/aliased imports to their normal symbol rules.
Explicitly retained declarations keep the original package among the destination stars.

For scripts, expansion requires an explicit `scriptImports` destination-host profile. A missing
profile keeps split stars held; it must not spray dozens of imports into box code whose host
already supplies them. When the intro covers all destination stars, the old import is removed.
When it covers some, only the missing ones are added. An explicit empty list opts into expansion
for standalone scripts with no host imports. The profile is a caller assertion for all scripts in
the selected roots, so do not mix incompatible script hosts in one plan. Migrate the central Field
intro first and populate this profile from its destination imports. Compiled source files never
use the script profile to remove imports.

The generator recomputes these lists from the inventory every time. Unassigned declarations,
parser gaps in that package, renamed symbols, or destinations that are another split package
prevent expansion. Missing mappings never mean that a declaration stays put. Comments inside
an import keep that import held for review. These are syntactic proposals: broader imports can
introduce ambiguity or change overload resolution, so build, runtime and binding checks remain
necessary. Regenerate the symbol map and replan after completing or changing assignments.

## Apply, verify, undo

The default approach for somebody else's workspace is a new copy:

```sh
uv run --project . --group backdoor python tools/refactor.py apply /path/to/reports/migration \
  --root-id 1 --copy-to /path/to/AFieldWorkspace-upgraded
uv run --project . --group backdoor python tools/refactor.py verify /path/to/reports/migration --root-id 1
uv run --project . --group backdoor python tools/refactor.py undo /path/to/reports/migration --root-id 1
```

Copy mode uses the discovery exclusions above. It stages the copy beside the destination and
publishes it by rename after applying. A failed copy is retained at its reported staging path.
For an already disposable copy or an isolated repo worktree, `--offline` applies in place. That
flag asserts **all writers of that root are closed**; the tool's locks coordinate other migrator
processes but cannot stop an older Field's autosave or an editor. Undo also requires an offline root.

Plans with findings require `--allow-partial` to apply only ready groups. The journal and verification
continue to say `partial: true`. Resolve findings in the source/recipe and make a new plan for the
rest. Editing the plan JSON invalidates its checksum. A second apply of the same plan is refused;
make a new plan after changes. Replanning already migrated imports yields no further edits.

Apply validates every scanned text input and the input file set, destination paths and recovery
blobs before writing. It records all intended edits before the first replacement. Original file
modes are retained. Each replacement is atomic; the journal supports recovery across a multi-file
interruption. `undo` handles an interrupted apply, including a source move interrupted between
writing the destination and removing the original, and refuses to overwrite subsequent edits.

`verify` checks the written bytes and unchanged scanned inputs against the plan. It explicitly reports
`compiled: false` and `bindingEquivalence: false`. After a real package migration, run the relevant
Kotlin/JVM/JS builds, generated-artifact gates, docs and selected workspace runtime checks separately.

## Tests and next layer

```sh
./gradlew -p tools/refactor-engine test installDist
uv run --project . --group backdoor python -m unittest tools.test_refactor -v
```

The root `checkPythonTools` task first runs `checkRefactorEngine`, so its full suite includes real
parser/container integration. Tests cover container round trips, document grouping, stale inputs,
tampered plans/blobs, escaped paths, interrupted writes, source moves, copy mode, undo and idempotence.

The next layer is K2 per-reference binding evidence with old/new classpaths and actual inherited
preambles/default imports. It will resolve the current manual findings for package splits,
ambiguous qualified references, operators and individual declaration moves. Other outstanding pieces are
automatic generator scheduling, typed Java/build/embedded-command adapters, version-chain selection,
live workspace leases and an upgrade UI. The current report distinguishes these gaps from completed
mechanical changes; it is not yet an unattended whole-repository cutover engine.

## Refactor sessions in Field

Open **Refactoring** from welcome or `/refactor-pass.html`. A session owns a repository
worktree, workspace copies, a recipe, immutable plans, review decisions and delivery receipts.
Session data lives under `~/.afield/refactor-passes`; the hosting checkout stores only an
ignored pointer. Keep the host server in that checkout while working in the session worktree.

1. **Create:** describe the refactor, enter old → new package pairs (or a complete recipe), and
   choose workspace directories. The repository starts at the host checkout's HEAD; uncommitted
   host edits are not copied. Workspace copies exclude backups and thumbnails. Leave the map
   empty to develop it with the session agent. An existing plan can still be imported.
2. **Review:** inspect mechanical diffs and highlighted findings. Batch terminals use the same
   configured agent as Docs pass and open as separate Electron tabs. **Open worktree terminal**
   handles cross-batch work or development of the recipe. Close the agent session when finished
   so it releases the writer lease, then replan. Decisions remain review records; they never alter
   the immutable plan. Preserve permits an intentional unchanged reference at delivery only if
   that group has no proposed edits. Held edits must be resolved in source or by an adapter.
   **Review all unresolved** opens a keyboard review round across batches, with the previous
   decision and its reason above the source. **P** preserves the remaining references, **E**
   records a Needs edit instruction (a `question` for the batch agent), **N** skips, and **B**
   goes back. An optional note replaces the standard reason. Save success advances immediately;
   a failed save stays on the current group. Shortcuts are inactive while typing. Preservation
   is unavailable for stale source or groups with proposed edits. Needs edit and skipped groups
   remain unresolved: run the relevant batch agent, then replan. The default list hides resolved
   Preserve groups; choose All decisions to inspect them. Quick review does not apply patches.
   Large agent batches should write dispositions as a JSON array of
   `{"item":"ITEM_ID","state":"patch","reason":"What changed"}` records, then run
   `tools/refactor_pass.py decide-many --file /absolute/path/to/decisions.json` once.
   The command checks every item and its current source hashes under the session state lock,
   then saves all records together. An invalid or stale preservation record rejects the whole
   submission without partial writes. These records do not clear migration holds.
3. **Rehearse:** apply ready groups in the copies, verify their bytes, undo, and verify restoration.
   Rehearsals use fresh journals and can be repeated. This is not build or runtime verification.
4. **Prepare:** once the relevant findings are resolved, apply the complete plan and leave it
   applied. The original repository and workspaces remain untouched. Inspect the repository diff
   from the session's base, the listed new files and workspace copies before delivery.
5. **Verify:** configure named commands with `kind` (`generate`, `build`, `test`, `runtime`) and
   `argv` (an argument array, never shell text). At least build, test and runtime are required.
   Generators run first. Other checks must not change source. Commands run in the worktree;
   `{worktree}` and `{root:1}` arguments expand to session roots. `FIELD_REFACTOR_SESSION` and
   `FIELD_REFACTOR_ROOT_<id>` are also available. Tailored runtime commands should exercise the
   migrated documents. Exit codes and log paths are retained. A successful receipt binds the
   recipe, check configuration and content/modes of all nonexcluded input files, including binaries.
6. **Deliver:** **Commit and enqueue repository** commits the dedicated worktree and invokes the
   existing lander. The queue receipt is displayed; queued does not mean landed. **Publish verified
   workspace copy** writes to a new, nonoverlapping directory without overwriting an original.
   Publication refuses workspace symlinks and rechecks copied content. Open that copy with the
   migrated Field version after landing. Either action refuses files changed since verification.

**Undo preparation** restores the mechanical edits only when doing so will not overwrite later
work. If verification exposes a defect, use the worktree terminal to fix it and rerun verification.
Use **Keep edits and replan** to retain the applied migration and any fixes as the input to a new
review plan. Earlier delivery journals remain retained for inspection. Undo never discards later changes. Interrupted preparation remains visible and can be undone.
An interrupted verification can be rerun. **Close session · keep worktree** detaches the page;
resume from the saved-session list or directory. It does not delete files or stop active writers.
The writer lease prevents closing or mutating a session while an agent/check is active.

The default commands are a starting point for Field; add required generators and adapt runtime
checks to the particular migration. Passing configured commands proves those commands passed,
not that every possible document or API binding was tested. The example preview recipe still
represents a proposed hierarchy; it does not authorize silently dropping remaining holds.

The same operations are available in the CLI:

```sh
uv run --group backdoor python tools/refactor_pass.py start --recipe-json '<recipe JSON>' --workspace /path/to/workspace
uv run --group backdoor python tools/refactor_pass.py create --plan /path/to/existing-plan
uv run --group backdoor python tools/refactor_pass.py replan
uv run --group backdoor python tools/refactor_pass.py rehearse
uv run --group backdoor python tools/refactor_pass.py prepare
uv run --group backdoor python tools/refactor_pass.py configure --checks-json '<named argument arrays>'
uv run --group backdoor python tools/refactor_pass.py validate
uv run --group backdoor python tools/refactor_pass.py delivery-diff
uv run --group backdoor python tools/refactor_pass.py land
uv run --group backdoor python tools/refactor_pass.py publish --root 1 --destination /new/workspace
uv run --group backdoor python tools/refactor_pass.py close
uv run --group backdoor python tools/refactor_pass.py resume --session /path/to/session
```

Sidecar filename conventions are unchanged. Private source and operations retain the
loopback/same-site boundary used by Docs pass and terminals.

### Field box intro coverage

Use `boxImports` (import paths without the `import` keyword) when the destination defaults are
Field's `intro`. It overrides `scriptImports` only for Kotlin properties in `metadata.edn` and
sidecars explicitly registered by those documents. Standalone scripts, unregistered `.kt` files,
and Markdown fences do not inherit this profile. Compiled Kotlin never uses host coverage.

Migrate the central intro first and copy its exact import list into `boxImports`. An identical
host import is redundant even while a split package still has unresolved declarations: remove it
from boxes rather than expanding it there. During a staged migration the central intro may keep
an old split wildcard as a temporary fallback alongside its known destinations. That is not a
complete split map or permission to leave the package unchanged; finish the declaration assignments
and remove the fallback before delivery. A complete `splitStarImports` entry lets boxes drop the
old wildcard once all destinations are supplied centrally. Named imports are removed only when
the identical explicit import is in the intro; wildcard coverage alone cannot prove they are
redundant. Syntax errors and existing EDN/sidecar drift continue to hold their document groups.

For a multiplatform package, set `boxSplitStarImports` to its JVM/common declaration destinations
from the expanded inventory. Each entry must be a subset of the complete `splitStarImports` map,
and every selected destination must have a wildcard in `boxImports`. This limits registered box
code to its host platform; it does not change the global map used for compiled source or unrelated
scripts. Review this runtime projection with the intro: validation checks membership and coverage,
while the inventory's source sets provide the evidence that no JVM declaration was omitted.

### Overload families that split across domains

A qualified function name may identify several different receivers. Assign these with
`declarations: {inventoryDeclarationId: destinationQualifiedName}` in the assignments file.
Provide every public/internal declaration in that family; partial or stale assignments fail.
Per-declaration assignments override file defaults, but cannot contradict an explicit whole-family
assignment. The inventory and expanded map retain source file, signature and source set.

Generation emits `splitSymbols` for families with multiple destinations. These are assigned
owners, not unassigned work. Complete split-star imports include their destination packages;
explicit imports (including aliases) and qualified call sites remain `split-symbol` findings
that list the candidate destinations. The syntax-only engine never guesses a receiver's type.
A compile/binding pass must resolve these uses. Reuse the declaration assignments when regenerating;
a previous recipe's split list alone is not evidence of current declaration coverage.

Declaration IDs include source path and declaration signature, not line numbers: moving a comment
or changing the intro does not discard ownership decisions. Inventories still check complete file
hashes before generation, so refresh the inventory after any source edit. Renamed or moved
declarations and changed signatures need refreshed assignments.

### Screenshot for public documentation

Marc selected [this refactor review screenshot](../../public/docs/assets/refactoring/refactor-pass-review.png) on 2026-10-14 for the future public documentation. It is the original, unmodified capture of the live review UI (Plan 23), including the session copies, replan controls, review batches, and red/green attention indicators. Use it when documenting this workflow; the counts describe that session, not a completed or verified migration.

### Recorded prose rules and exported preambles

`proseReplacements` records exact qualified-name replacements in selected Markdown prose
and Kotlin comments (with `rewriteComments` enabled), for example
`"proseReplacements": {"afield.four": "Field 4"}`. It does not rename executable
imports, qualified descendants, wildcard package references, or string values. Rules must
be idempotent. Kotlin comments inside typed EDN/YAML/document units use the same rule.

Field's exported `text [field-property box="…" name="preamble"]` fences are recognized
as Kotlin box preambles, with the recipe's box imports and split-package rules. Ordinary
text fences and other non-Kotlin properties remain untouched. The exported fence and
its attributes are preserved.
