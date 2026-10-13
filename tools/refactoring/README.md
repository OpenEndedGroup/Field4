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
review. Source moves do not stage Git changes. Qualified body/type references, reflection-like
strings, split star imports, KDoc/comment references and names outside recognized code units produce findings.

`refactors/examples/linalg-package.json` is deliberately a **rehearsal** recipe: its destination
package does not yet exist. Use it on copies to exercise the tool, then undo. It is not an upgrade
for a current Field installation.

## Containers and document boundaries

- Kotlin source and scripts: PSI edits preserve import aliases, comments, LF/CRLF and Unicode offsets.
- Markdown: fenced Kotlin, including attributed, nested and tilde fences. Prose references are findings.
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
qualified references, operators and individual declaration moves. Other outstanding pieces are
automatic generator scheduling, typed Java/build/embedded-command adapters, version-chain selection,
live workspace leases and an upgrade UI. The current report distinguishes these gaps from completed
mechanical changes; it is not yet an unattended whole-repository cutover engine.
