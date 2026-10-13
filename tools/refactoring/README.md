# Field source migration

`tools/refactor.py` inventories, previews, applies and undoes explicit import migrations across
tracked repository files and external Field workspaces. It uses a standalone Kotlin PSI parser,
container adapters, content hashes and a recovery journal. It never runs box code or replays a macro.

The current engine provides **syntax evidence**, not compiler binding equivalence. A ready change
is a mechanically applicable rewrite, not proof that the destination API exists or that the result
compiles. This is the first working layer of the [package refactor proposal](../../dev_docs/PACKAGE_REFACTOR_PROPOSAL.md).
The public-preview package migration itself has not been applied.

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
`"markdownProse": ["public/docs/*.md"]` enables the same operation in selected Markdown prose;
patterns match paths relative to each root (`*` can span directories). Kotlin fences still use
the parser, and other fenced languages are excluded from prose rewriting. Both options default
off. Matches use whole name components; slash paths and similar-but-unrelated package names are
preserved. Strings in Kotlin remain findings even when the comment pass is enabled.

Use narrow paths for current documentation. Historical discussions, mixed-language examples and
embedded commands need context: an old name may be intentionally explaining the migration. The
broad rehearsal recipe selects public docs and all Markdown under `dev_docs`, including nested
notes and handoffs. This produces reviewable proposals for internal prose too; inspect historical
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

## Containers and document boundaries

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

## Refactor pass: contextual review in Field

Open `/refactor-pass.html` in the same Field server as the docs pass (there is a link from the
Docs pass page), and import the absolute directory of a migration plan. The pass creates one
`codex/refactor-pass-…` integration worktree and new workspace copies, excluding the same backups
and thumbnails as the migration tool. Private pass state, copied plans, briefs and decisions live
under `~/.afield/refactor-passes/`; the checkout keeps only an ignored pointer. The original
repository and workspaces are not edited by review sessions.

The first version provides:

- The recipe, root counts, file moves and proposed diffs, including held changes.
- Review groups with actual source excerpts and decoded code-unit line numbers. Before/after
  blobs preserve the evidence even when the original source later changes.
- Suggested batches for prose, display labels, dynamic names, languages, incomplete syntax,
  generated outputs and source layout. These are suggestions for review, not automatic approvals.
- Decisions per document/file group: patch proposed, preserve with a reason, adapter needed,
  question or open. Recipe, group-source and finding fingerprints reopen changed decisions.
- The existing interactive terminal, using the configured agent. A generated private brief includes
  exact findings and proposed changes. One writer lease serializes agent sessions, replanning and
  rehearsal. Decisions can be recorded while the agent owns the lease.
- Replanning against the working copies, followed by apply/verify/undo/verify of ready groups.
  Each plan can be rehearsed once; replan for another rehearsal.

The page's state survives a reload and the terminal can be reattached. A decision does not alter
an immutable plan or waive its held groups. The agent stops after recording outcomes; its brief
forbids partial commits/landings. This first pass does not enqueue the namespace cutover or publish
workspace upgrades. Compilation, runtime validation, deliberate preservation rules, and final
coordinated delivery remain required. Sidecar filename conventions are unchanged.

The same operations are available from the CLI:

```sh
uv run --project . --group backdoor python tools/refactor_pass.py create --plan /path/to/migration
uv run --project . --group backdoor python tools/refactor_pass.py status
uv run --project . --group backdoor python tools/refactor_pass.py item --item <id>
uv run --project . --group backdoor python tools/refactor_pass.py decide --item <id> --state preserve --reason 'Dated historical description'
uv run --project . --group backdoor python tools/refactor_pass.py brief --batch dynamic-names
uv run --project . --group backdoor python tools/refactor_pass.py replan
uv run --project . --group backdoor python tools/refactor_pass.py rehearse
```

The server must stay in its hosting checkout while the migration is developed in the integration
worktree. Agent sessions use `tools/field_agent.py`'s choice; browser reads and writes use the same
loopback/same-site boundary as docs passes and terminals because reports contain private source.
