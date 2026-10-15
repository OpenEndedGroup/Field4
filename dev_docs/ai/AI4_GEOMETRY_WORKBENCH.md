# Geometry inspection and review inside AI4

AI4 uses the same geometry inbox as the Three.js viewer and the external inspection tools.
The tools are registered whenever the conversation has Field graph tools, including the
provider-neutral backdoor registry. No house-specific source or policy is part of this adapter.

| Tool | Result |
| --- | --- |
| `ListGeometryViewsRequest` | Views belonging to live boxes in this document, their current geometry revisions and note counts |
| `ReadGeometryReviewsRequest` | Paged summaries, open notes by default; reading never acknowledges them |
| `ReadGeometryNoteRequest` | The full saved reference, replies and history; context/crop PNGs as model-facing images |
| `ReplyGeometryNoteRequest` | Persistent reply, preserving open/resolved status |
| `ReviewGeometryNoteRequest` | Explicit resolve/reopen with an explanation and optimistic concurrency |
| `InspectGeometryViewRequest` | A fresh independent viewer capture, part/source identities, pick result and restorable state |

The caller supplies the geometry revision it inspected and the note version it read for writes.
Changing geometry or a competing reply invalidates an old write. Old notes keep their original
geometry identity and pictures. Capturing an old note against new geometry is refused; its saved
pictures remain available through the read tool. Image captions identify the historical and current
revisions, and image bytes are checked against their saved SHA-256 before attachment.

Inspection creates and closes its own browser context. It does not navigate the human's browser or
the shared docs capture page. The viewer's own restore, pick, region, section and x-ray operations
do the work; programmatic picks leave the human selection untouched and remain nearest-surface
picks even in x-ray mode. Capture checks the revision before and after rendering and again on the
Field owner thread. A late picture is never reported as a successful view of current geometry.

`stateJson` accepts the `state` object from a previous inspection, serialized as JSON. It can restore
camera position as well as all other inspection settings. Alternatively, `noteId` restores the
annotation's saved camera and feature at that exact revision. Further request fields adjust the
inspection after restoration. The returned PNG path retains the full-size image; AI4 receives a
bounded JPEG image input. At most 100 geometry captures are kept with AI4's other capture evidence.

Tests exercise the shared registry schemas, document boundaries (including identical box IDs),
actual inbox read/write behavior, concurrent replies, historical image delivery, read-only documents,
pagination, rejected capture requests and the sight-disabled eval context.

## Verification

Run the focused JVM checks with `./gradlew jvmTest --tests afield.ai.agent.GeometryReviewToolsTest
--tests afield.graphics.three.ThreeReviewTest --tests afield.ai.agent.Ai4ChatGraphWiringTest` (23 tests).

Run the actual viewer path with `uv run --project . --group backdoor python
tools/verify_ai4_geometry_review.py`. It creates its own named document in the checkout's automation
workspace, verifies server ownership, and closes a server it started. It does not use the human's
document or browser. `--port` and `--output` are optional.

The live check runs 19 assertions through the shared AI4 registry and the shipped viewer: scene
execution, actual fresh screenshots, oriented regions and state restoration, nearest-surface picks
in x-ray mode, browser-authored notes with context/crop images, independent agent inspection,
unchanged human camera and manual selection, durable replies, explicit resolution, stale-write
refusal and historical evidence after rebuilding geometry. Its JSON report and saved annotation
pictures are in `build/ai4-geometry-review/`; full inspection PNG paths are in the report. Both
the focused tests and this browser check passed on 2026-10-01. This proves the tool transport and
viewer behavior; it does not claim a model-driven house-design session has been exercised.

The browser check caught a transport issue that the unit tests could not: Playwright's Java
argument codec rejects `Long` values in saved viewer states. Inspection passes one JSON string as
data and decodes it in the page, preserving integer values without interpolating executable source.

## Derived stages

`StudyBuilds` now supplies the next connection: asynchronous stage jobs over a coherent snapshot
from the design's existing authority. It deliberately does not introduce another editable source.
Stage recipes declare named input keys, dependency stage IDs and an explicit recipe version.
Every attempt retains those values and the exact upstream job/artifact identities. Successful
publication checks the fingerprint again. Reading stages derives currency from current inputs,
recipes, dependencies and artifact checksums, so source edits immediately invalidate downstream
results without relying on a separately maintained invalidation flag.

Each runner has one OS file lock and an atomically replaced manifest. Restarted queued/running
attempts become interrupted; they never automatically repeat work. Cancellation revokes
publication but leaves an uncooperative operation running until it returns. Close/box stop revokes
all publication and preserves the records. This is trusted local recipe execution, not a sandbox.
Recipes must keep external side effects isolated per attempt and publish displays from current
results, not directly from an in-flight worker.

`Box.studyBuilds` attaches a runner to a document box; `showStudyBuilds` adds live Build/Cancel
controls, a real progress bar and selectable errors through Field's existing button transport.
The four native tools are `ListStudyBuildsRequest`, `InspectStudyBuildsRequest`,
`StartStudyBuildRequest` and `CancelStudyBuildRequest`. Listings are compact; inspecting a job ID
returns its full frozen inputs and result directory. Document and read-only boundaries match the
geometry tools. The card and agent read the same records.

Library-generated cards also enter the normal retained output inventory. The private integration
caught a gap here: a build failure was visible in the already-open browser, while
`ViewBoxOutputRequest` captured only its unrelated control channel. `BoxStudies.show` now retains
the card before sending it, so captures and late pages see it immediately. The older delayed replay
uses that same inventory, respecting replacements and clears instead of keeping a second copy.
`OutputAreaReplayTest` covers a library card on a box that has never run, replacement, open state
and clear behavior.
The output-replay/source-study suite passed eight tests, and the codebase reference regenerated.
After restarting the integration host, a real `ViewBoxOutputRequest` returned both the controls
and the complete error card as an attached image; its channel inventory includes `builds`.

`StudyBuildsTest` covers source-study edits, transitive/selective invalidation, stale starts,
late completion, structured errors, retained prior results, running/queued cancellation,
reopen/crash recovery, recipe changes, changed result files, exclusive ownership and confined
artifact paths. `StudyBuildToolsTest` covers the shared registry and document boundaries.
`tools/verify_study_builds.py` exercises the real Field card and AI4 tools with a native Manifold
Boolean, OBJ export and independent OBJ readback. Its verification barrier is explicit test
orchestration for in-flight edits/cancellation; progress and results come from the real worker.
Evidence goes to `build/study-builds/`.

The focused suite (build lifecycle/tools, geometry review and shared chat wiring) passed 38 tests
on 2026-10-01. The live build/card check passed 23 assertions, with actual current/error screenshots.
It also checks that a changed design is identified while the older build is still running and that
an error for previous inputs is no longer shown as an error in the current design. These checks
call the native AI4 tools deterministically; they do not substitute for a complete model-led design
session.

## First private integration

The separate private adapter now connects an existing constrained design authority to this runner.
Mouse edits and native document-defined AI4 tools share its serialized revision-checked operations.
Two Field output canvases display the same design. A downstream stage freezes the design and
recipe files, invokes the private checked geometry backend in a separate per-attempt document,
retains its native reports and full-precision meshes, and refuses late publication after edits.
The original study document is not used as the build scratch space.

The integration exercises actual geometry, an edit during its build, obsolete-result retention,
shared undo, successful rebuild and an impossible opening that leaves the design unchanged.
The opening error identifies the intersecting wall, required clearance and available intervals.
The specimen and its recipe, reports, images and domain policy remain private.

`solidParts` is extracted independently into `graphics/SolidParts.kt` (its outline, topology and artifact
identity functions in `graphics/SolidInspection.kt`): a map of named native
boundaries becomes a precise viewer with crease outlines, canonical topology and an artifact
identity. Display origin/Z-up rotation do not change canonical coordinates; assembly displacements
use the same display rotation. The focused extraction/lifecycle/tool suite passed 27 tests.

For persistent box watchers, launch the box. A `Frames` return from Run only constructs the loop;
it does not run it. The private adapter's invalidation watcher is a launched frame scope and
replaces stale geometry with a rebuild message. Explicitly open output disclosures before visual
verification: a successful capture of a collapsed header is not a canvas test.

## Next connections

The private integration now exercises structural edits through both mouse interaction and a real
model-led conversation, with a persistent chat, two shared design canvases and a checked downstream
build. Custom editor selections also map to authored entities through document-defined tools.
Further derived stages and the complete range of editable geometry still need adapters that consume
current frozen inputs. Automatic chat mounting/layout and the complete end-to-end workflow remain
unfinished. The private recipes keep their own admission/material/assembly checks; a successful
stage runner is not geometric certification.

## Provider names for authored tools

The first real conversation caught a transport failure invisible to direct native tool calls:
a box tool named `house.inspect` produced an OpenAI 400 for an invalid function name. Existing
Field DSL examples also use dotted namespaces, so rejecting or globally renaming them would break
authored tools. `BoxTool` now exports stable bounded ASCII aliases for names needing conversion;
its description retains the original name. The OpenAI registry dispatches the same alias back to
the original tool. MCP/native descriptors and direct Kotlin calls keep authored names.
The alias prefix is reserved through re-encoding, and collisions fail before sending a request.
Three focused tests cover punctuation/Unicode/long names, attempted alias takeover, descriptor and
dispatch agreement, unchanged MCP identity and explicit collisions. The combined focused suite
passed 17 tests after this change.

The repaired transport was then exercised by a real in-Field AI4 conversation using gpt-6-astra
through OpenAI. It called the aliased document inspection tool and `ListStudyBuildsRequest`,
then correctly reported the live revision, planarity result and current stage. The conversation
was read-only and the design revision remained unchanged. This is one model/tool turn, not proof
of the complete conversational authoring goal. The private document retains its chat and capture.

## Custom canvas review surfaces

`Box.geometryReviewSurface` registers a document-owned canvas using the same `ThreeReviewInbox`,
image sidecars and native AI4 review tools. It does not create a placeholder Three.js scene.
Bindings name their output box separately from the source/persistence box; listings give the
appropriate capture tool and box. The canvas supplies actual visible-feature selections and
pictures, while domain identities and remapping remain outside the public adapter.

The focused tests cover custom-canvas discovery, document isolation, image delivery after binding
replacement, source deletion, stale replies and revision changes. An ephemeral inbox now keeps
its image directory on the source box, so replacing its binding does not strand saved pictures.

The custom-surface/review suite passed 16 tests. Live verification then saved four notes through
real browser interaction, retrieved eight actual context/crop images through native AI4 tools,
and captured both editable output canvases using the discovery-provided output box. Notes and
images survived a host restart. A real in-Field Astra conversation read the pictures, located a
feature through a document-defined tool and appended a durable reply without changing the design.

That conversation caught a genuine selection error: invisible enlarged drag targets leaked into
region annotations. The editor now distinguishes visible selection surfaces from drag affordances;
repeating the same rotated region excludes the spurious feature. The original evidence and finding
remain retained, and verification notes were explicitly resolved. Region sampling is bounded, so
this is not a claim that every subpixel feature is enumerated. Domain code and evidence stay private.

## Reuse boundary and tool pool

The editable 3D application adapter now supplies geometry and a drag extension to
`three { tools(...) }`. Standard rendering, inspector, part hierarchy, explode,
x-ray, picking, evidence capture, GLB export and note transport remain the viewer's.
The standard note inbox is also available as `geometry_review_inbox.js` to custom
canvases. A plan's semantic picking and input adapter can differ; the saved-note
inbox, replies, resolution history, images and concurrency rules are shared.

Identical camera specifications now establish an initial view rather than resetting
it on each geometry update. User-positioned views survive resize. An explicitly
changed camera specification still changes the view. The live viewer regression
covers those cases as well as the annotation round trip.

Field outputs and their execution logs are document-scoped, including boxes with
the same UID in different documents. A library card or retained output must not
appear in whichever document happens to be listening.

The [tool-pool recipe](../agents/tool-pool/README.md) is ordinary in-Field source. It
adds discovery and curation around `aiTool`; the existing registry still executes
every call. Tests and failure diagnostics attach to provider source identities.
The live two-document verification exercises reuse, destination testing, retained
errors and invalidation after source edits.

The acceptance path is a request to the existing AI4 chat, discovery/reuse of
capabilities, execution and inspection with actual evidence, then a durable tested
improvement. A hand-operated outside-agent sequence is useful debugging, but is not
proof that this path works. Application choices should arise from that conversation
and the existing controls/tool extension points, rather than become a fixed UI for
one demonstration. The agent remains involved at every stage; this is not intended
to turn the whole process into a closed preset application.

The 2D adapter and Three.js viewer now mount `geometry_review_controls.js` for the same
Pick / Load note / Notes toolbar and composer, and `geometry_review_inbox.js` for threads.
Surface adapters retain only picking, revision-bound references, capture and transport.
Both support copy/load, rotated regions, image evidence, explicit replies and resolution.
Unsupported geometry pick kinds are omitted by the adapter; the remaining controls are identical.
The shared controls keep diagnostics selectable while toolbar labels remain inert.

The shared-control verification passed the live viewer regression and exercised a custom
canvas's point save, reference copy/load and rotated region. A subsequent real AI4 turn
(`gpt-6-astra`, 2026-10-01) compared an existing provider with the curated pool source,
launched it unchanged, ran its attached test and called the newly registered tool directly
on current design values. It received both historical annotation images, located the current
semantic feature, replied once and independently captured the standard viewer. The authored
source digest was unchanged. The verification note was then explicitly resolved.

That acceptance run exposed three general integration faults. Native OpenAI tool schemas now
preserve nullable root request parameters; the tool list refreshes document providers between
model rounds; and a blocking call to a suspend box tool on Field's UI thread fails with an
actionable message instead of deadlocking. The focused schema, registry and tool-pool tests
cover these repairs. The agent-task entry point also consults ordinary chat-history recovery
before rejecting a persisted running flag left by an interrupted server. Recovery preserves
the partial transcript; a genuinely active turn is still refused. Its four focused tests pass.

## Codemode integration — 2026-10-04

The Pi program/context/session tools and the general geometry/study/tool-pool
infrastructure are reconciled in the combined topic checkout. Specialized tools
retain deferred discovery; read-only effects are refused centrally and at their
native adapters. Authored tool names remain available to programs while provider
wire aliases are collision-safe. Forced-final responses preserve the discovered
catalogue. Long builds return a handle and are observed separately from a short
program deadline; increasing a nested wait does not extend that deadline.

Two focused runs passed 129 tests across 13 AI4 integration suites and 145 tests
across 20 infrastructure suites, with zero failures/errors/skips and the tracked
write guard passing. The generated codebase reference also completed successfully.
A private copied-document exercise additionally verified compact program results
with retrievable complete evidence, refusal of an unqualified downstream stage,
and obsolete publication after a real mouse edit during a running build. A real
AI4 panel turn used programs, showed progress and completed a derived build without
changing authored input. This is integration evidence, not full fabrication
qualification; private source, model data and artifacts are kept outside this tree.
