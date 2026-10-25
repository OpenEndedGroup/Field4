# Box code revision rehearsal

This rehearsal was run on 2026-10-15 in `codex/box-code-revision-pilot`, starting at
`c483d75fd`. It enumerates the teaching corpus and exercises three small review groups.
The main checkout's concurrent work was preserved. No landing or push was requested.

## Inventory

The [group inventory](inventory.md) lists **3,560 Kotlin/GLSL code occurrences in 577 review
groups**: 3,122 Kotlin/JVM, 10 Kotlin/JS and 428 GLSL. The [target list](targets.tsv) gives
each occurrence's path, line, box/property where available, content hash and group.
These counts include repeated create/execute bodies and independently copied docs examples.
They are a workload census, not a count of unique programs.

The full manifest also records generated starting points, reference fences, metadata fallbacks,
document mirrors, capture drivers, other languages and exclusions. Regenerate it with:

```bash
uv run --project . --group backdoor python tools/inventory_box_code.py --out build/box-code-revision/inventory
```

The uncompressed full manifest is under `build/box-code-revision/inventory/`; the compressed
[snapshot](inventory.json.gz) accompanies this report. It records source hashes, exact content
matches and the starting commit. Hash matches help find copies; they do not establish a shared
authoritative source.

The census includes standalone Kotlin/shader files, stored document properties, operation YAML
(including quoted code), Kotlin/GLSL docs fences, code inside YAML docs fences, and built-in
shader defaults. It separates JavaScript stored in legacy `.kt` files and Kotlin/JS head markers.
Compiled application/test source, internal library shaders, archived chat snapshots, backups and
tombstoned sidecars are outside the editing queue. The runnable archive-viewer demo is included.
Shader strings embedded in Kotlin belong to that Kotlin occurrence.

There are 43 valid metadata fallbacks without a tracked loose file: their inline code is a
source to review, not a generated copy to discard. One deliberately partial YAML fence on
`how_field_is_built.md` could not be parsed; it is explicitly listed as an inventory finding.
The eight census tests cover these container and language distinctions, including raw-property
review flags. Cross-checking against
the existing idiom checker left only explicitly excluded historical/tombstoned files outside
the census.

## Rehearsal outcomes

| Review group | Decision | Evidence |
| --- | --- | --- |
| `two_box_context` | Teach typed property inheritance: the parent declares `prop<String>(greeting) = "value from parent"`; the child reads `greeting` directly. Replace both repeated execute bodies too. The initial qualifier-only cleanup was rejected because it retained the raw-property escape hatch. | Typed-property replay and reruns passed. Assigning `greeting = "edited in parent"` in the parent was asserted and printed from the child, then restored. The focused transcript guard passed. |
| `templates/first-steps` | Test a candidate with a separate `petals = 5` line. Keep the shipped template pending a coordinated guide/capture revision. | The editor's actual Alt-Enter shortcut changed the captured value while the animation kept advancing. Declaration-only execution was the negative control. Relaunching retained one active job; stop released it. |
| `afield_four_traditional_triangle` | Keep its Kotlin and GLSL. The named mesh and shader attribute names explain their connection; further shortening does not improve this lesson. | Ran beneath the real renderer parent. Both shader stages rendered. Rerun retained one registered render module, and before/after native images were pixel-identical. |

The small applied transcript change was regenerated through Field's normal materializer.
Only that transcript's generated parent and child sidecars, document mirror and metadata changed.

## The Alt-Enter result

The candidate changes the top of the moving drawing to:

```kotlin
var petals = 5
petals = 5 // Change this 5 to 8, then Alt+Enter this line while the drawing runs.
```

Both tests launched the real shipped drawing, then captured readers of its `petals` and `phase`
variables. The browser driver edited and selected one line and pressed Alt-Enter. Subsequent
fragments asserted the values and verified that phase continued to advance:

| Line executed after editing | Current `petals` | Captured `petals` | Animation |
| --- | ---: | ---: | --- |
| `var petals = 8` | 8 | 5 | Continued |
| `petals = 8` | 8 | 8 | Continued |

The [candidate diff](first_steps_drawing.diff) also contains the deterministic formatter's
changes. It is a reviewed candidate, not a patch already applied to the shipped template.

The existing first-steps guide explicitly edits `var petals = 5`, relaunches, and expects eight
petals. Its capture driver selects that declaration too. Adding an assignment of five below
the declaration would override their edit on relaunch. A coordinated revision needs the guide,
its driver, the quoted capture transcript, the template sidecar and metadata, and its recording.
The pilot establishes the live-edit behavior; it leaves that editorial decision visible.

The coordinated revision is queued as [handoff d6dc958a](../../handoffs/2026-10-15-coordinate-the-first-steps-live-reset-line-with-its-guide-an.md).

These are actual separate-browser captures from the isolated rehearsal, rather than the user's
Electron tab. Their phases differ because the animations were running when captured.

| Declaration edited: loop retains five | Assignment edited: loop uses eight |
| --- | --- |
| ![Five-petal drawing after a declaration edit](drawing_original.png) | ![Eight-petal drawing after an assignment edit](drawing_candidate.png) |

The graphics capture is the real native renderer's output:

![Traditional triangle rendered by its actual Kotlin and shaders](triangle.png)

## What this changes about the larger pass

Teaching the normal API is a separate acceptance criterion. The initial context revision removed
`thisBox.` but left `setPropertyRaw` and a nullable string-based lookup in place. That passed
compilation and behavior checks while still teaching the wrong pattern. The corrected example
publishes a typed property and reads it directly. The inventory now flags raw property access for
review; deliberate introspection or an explanation of the escape hatch can justify an exception.
It currently flags 38 review occurrences, including repeated bodies and examples that need
contextual judgment.

An example earns a reviewed outcome even when the decision is to keep it. A reset line can
be worth adding, and an apparently redundant line can be essential. The review should name
the intended edit gesture and verify it. Comparing output after full launch alone would have
missed the difference demonstrated here.

The worker should own a complete example's sources and repeated operation bodies. Generated
copies belong to a serial materialization step. Related guide instructions and capture drivers
must be linked before an edit can be called ready. A candidate with a broken guide contract
remains a candidate even when its Kotlin and runtime checks pass.

The review can run in parallel across groups; compilation, materialization and renderer checks
remain serialized within a checkout. Large pages need bounded review chunks and one consolidating
owner. Exact duplicate bodies can share initial reading, while each occurrence retains its context.
The census provides source fingerprints; the future queue still needs stale-input detection and
stable anchors for unnamed docs fences, whose current locators include their opening line.

The adversarial check in this rehearsal was a local challenge plus the declaration negative
control, not an independent model review. Before scaling, another small batch should exercise
existing `//%-` boundaries, asynchronous renderer capture, audio ownership and a substantive
shader edit. These three examples do not establish those cases.

## Verification

The [evidence record](evidence.json) preserves the runtime assertions, lifecycle counts,
source fingerprints and capture hashes. The raw macro inputs, command results and replay scripts
are under `build/box-code-revision/pilot/`.

- `compileKotlinJvm` passed after restoring this worktree's Git LFS compiler files.
- The selected idiom/format pass succeeded; the source transcript compile/replay and corpus
  layout checks passed.
- Starting-point regeneration succeeded for 224 documents; only the intended document changed.
- All eight census tests and all five corpus-count tests passed. The generated count table was
  refreshed for the new test module.
- The initial Python run reported 1,213 passed, ten failures and 35 skipped. All ten failures
  are now repaired: the new census made the generated module count stale; the reference-root
  parser consumed past a one-line list; guard tests assumed the former narrow catalog; and
  documentation detectors mishandled inline code, labelled KDoc links and trailing block tags.
- Final `./gradlew checkPythonTools` passed: **1,264 passed / 1,268 run, four skipped**.
  Its prerequisite built the standalone refactor engine and passed all **26 parser tests**.
  All 31 refactor integration tests that the direct fresh-worktree run skipped were exercised.
  The four remaining skips require a built documentation site, optional submodule checkouts
  (two tests), or Linux's native-launch preflight.
- Direct documentation checks found zero dead anchors across 5,318 links and exact matches for
  all 424 published file overviews. Regression cases cover the repaired parsing and detectors.

### Why the earlier verification missed this

The refactor landing did run the complete Python suite: its retained log records
**1,248 passed / 1,252 run, four skipped**. The following reference-catalog landing selected
reference, KDoc, idiom and citation checks, but omitted the Python suite. The guard selector now
selects `checkPythonTools` for the reference generator and generated reference pages, Python
producers/tests, the standalone refactor engine, and build/dependency configuration.
[Handoff 33e71cfd](../../handoffs/2026-10-15-repair-reference-catalog-guard-selection-and-documentation-d.md)
is closed with the repair and full verification results.

### Toolchain repair

The initial compile and runtime rehearsals accepted GraalVM 25.0.2 despite the 25.4.4.1.1 pin.
Gradle's vendor/Java-major specification admitted both builds; plain wrapper calls omitted the
cached pin path, and the build only warned about a mismatch. Both supported property flag forms
selected the pinned JDK in live Gradle probes.

The wrappers now discover the cached exact pin. Field compile/test/run checks reject a mismatched
JDK. A bare `./gradlew verifyFieldToolchain compileKotlinJvm --info` passed and reported
GraalVM **25.4.4.1.1**, Java **25.0.4.1.1+1-jvmci-25.4-b23**, with the same pinned home in the
Kotlin compiler's `jdkHome`. Forcing the old 25.0.2 installation failed with the explicit mismatch
error. Gradle's own Corretto 17 bootstrap and the standalone parser's Java 17 remain separate.
The Windows discovery helper was reviewed here; execution verification was on macOS.
