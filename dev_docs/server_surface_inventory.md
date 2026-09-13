# Field's server surface: the route and command inventory

The maintainer's companion to `public/docs/server_surface.md`. That page explains the trust
boundary and the defences; this one is the **ledger** — every door in the wall, counted, with
the file it is declared in and the guard that runs on it.

Every table below is asserted against the source by
`ServerSurfaceLedgerTest`, **in both directions**: a route or command that exists in the
code but not in a table fails the build, and a row in a table with no corresponding
declaration fails the build too. So this page cannot quietly drift out of date. It can only
be wrong at the moment somebody changes the surface, and then the build says so.

If a table below is out of date, **do not** hand-edit it to make the build green without
reading what changed. The failure message prints the exact rows to add or remove and names
the category, which is enough to decide whether the new door wanted a guard.

## What this page is not

It is not the whole story of Field's exposure, and two other guards own the parts it leaves
out:

- **state-changing HTTP routes** — 53 of them, 40 with a named verb and 13 that name no verb
  at all — belong to `FieldApiCorsExposureTest.everyMutatingRouteAndItsGuardIsPinned`, which
  has pinned them since 2026-08-12 and counts the unguarded remainder (**49 of 53**). This
  page does not duplicate that table, but it does count the thirteen verbless routes below,
  because a door that answers every method has to appear in an inventory of doors;
- **the CORS block and the three WebSocket channels** belong to the same file's other tests.

What was uncovered before this page existed, and is what it adds:

| surface | count | previously guarded by |
| --- | --- | --- |
| HTTP **read** routes | 37 | nothing — the mutating census skips `get` outright |
| **all-method** routes (`route(…) { handle { … } }`) | 13 | nothing — they name no verb, so no verb regex saw them |
| `staticFiles` directory mounts | 6 | nothing — invisible to every route regex in the tree |
| `/ws` **command** names (`handlers`) | 108 | nothing |
| `/ws` **raw channels** (`rawHandlers`) | 10 | nothing — a second dispatch table nothing had counted |

The second and fifth rows arrived on 2026-08-22, and the first row moved from 34 to 36 the
same day. All three changes have the same cause, described under
[what the guard does and does not prove](#what-the-guard-does-and-does-not-prove): both
censuses were looking for a *verb call with a quoted path*, and Field declares routes three
other ways.

## The thing to understand before reading the tables

The brief that produced this page assumed Field's attack surface was its HTTP routes, and
that `src/jvmMain/kotlin/afield/server/*.kt` was nine thousand lines of them. It is not.

`fileproxy.kt`, `h2explorer.kt`, `browsing.kt`, `rendererBrowsing.kt`, `assimpBrowsing.kt`,
`importing.kt`, `inspecting.kt`, `templating.kt`, `spreadsheet.kt`, `logstream.kt` and
`executing.kt` declare, between them, **zero HTTP routes**. They are reached over the `/ws`
WebSocket, through a single dispatch table — `handlers`, a
`MutableMap<String, MutableSet<Handler>>` declared at `Messages.kt:18#val handlers` and keyed
by a message name. The `/ws` handler decodes a frame and fans it out to whatever is
registered under that name.

So the reach a reader would most want guarded — the file proxy, the H2 database explorer,
box execution, the agent backdoor — is not behind an HTTP route at all. It is behind the
door that got its **first** guard on 2026-08-19, in `3aa64af4d`, and that guard is **off by
default**. That inversion is the single most useful sentence on either page.

## HTTP read routes

37 routes, 34 of them with no route-specific guard. `guard` is computed from the handler
body by the same three-name vocabulary the mutating census uses
(`docsAnnotations.kt:734#fun ApplicationCall.refuseUnlessSameSiteDocsClient`,
`docsAnnotations.kt:718#fun ApplicationCall.refuseUnlessLocalDocsClient`,
`docsAnnotations.kt:313#internal fun refuseDocsImprovementLaunch`); there is no `install(Authentication)`
anywhere in the tree, so `none` means precisely none.

Reads matter on their own terms, and it is worth saying why rather than assuming it. A read
route does not change your document, so it escapes both the mutating census and the
`AFIELD_API_REQUIRE_SAME_SITE` interceptor, which only inspects mutating methods. But
`GET /api/instance-info` returns the absolute path of your checkout, `GET /api/welcome-changelog`
returns your recent commit messages, and `GET /api/source` and `GET /api/workspace-search`
reach source text. All three were read by a foreign origin in the 2026-08-11 measurement
recorded in `dev_docs/DOCS_IMPROVEMENT_AGENT.md`. Exfiltration is not a lesser outcome than
mutation; it is a different one.

| route | declared in | guard |
| --- | --- | --- |
| `GET /api/agent-worktrees` | `Server.kt` | none |
| `GET /api/ai-tool-use-improvement` | `Server.kt` | none |
| `GET /api/code-use/failures` | `Server.kt` | none |
| `GET /api/code-use/launches` | `Server.kt` | none |
| `GET /api/code-use/snippets` | `Server.kt` | none |
| `GET /api/code-use/summary` | `Server.kt` | none |
| `GET /api/code-use/symbols` | `Server.kt` | none |
| `GET /api/code-use/unresolved` | `Server.kt` | none |
| `GET /api/completion-doc-polish` | `Server.kt` | none |
| `GET /api/docs-annotations` | `docsAnnotations.kt` | loopback + same-site |
| `GET /api/docs-improvement` | `docsAnnotations.kt` | loopback + same-site |
| `GET /api/document-guide-asset` | `documentGuides.kt` | none |
| `GET /api/export-tagged-documents` | `Server.kt` | none |
| `GET /api/instance-info` | `Server.kt` | none |
| `GET /api/maven-classpath` | `Server.kt` | none |
| `GET /api/maven-search` | `Server.kt` | none |
| `GET /api/python-settings` | `Server.kt` | none |
| `GET /api/semantic-search/model` | `Server.kt` | none |
| `GET /api/semantic-search/settings` | `Server.kt` | none |
| `GET /api/semantic-search/status` | `Server.kt` | none |
| `GET /api/source` | `Server.kt` | none |
| `GET /api/welcome-changelog` | `Server.kt` | none |
| `GET /api/welcome-docs` | `Server.kt` | none |
| `GET /api/welcome-docs/stream` | `Server.kt` | none |
| `GET /api/workspace-search` | `Server.kt` | none |
| `GET /app-url` | `Server.kt` | none |
| `GET /controllers` | `controllers.kt` | none |
| `GET /controllers/controls` | `controllers.kt` | none |
| `GET /controllers/outputs` | `controllers.kt` | none |
| `GET /field-audio/{...}` | `fieldAudioProxy.kt` | none |
| `GET /field-docs/{...}` | `docsAnnotations.kt` | loopback |
| `GET /health` | `Server.kt` | none |
| `GET /history-screenshot` | `Server.kt` | none |
| `GET /index.html` | `Server.kt` | none |
| `GET /local/{...}` | `Server.kt` | none |
| `GET /min/{...}` | `Server.kt` | none |
| `GET /project/{...}` | `Server.kt` | none |

### The five that serve files off disk, which deserve reading twice

`/project/{...}`, `/min/{...}`, `/local/{...}`, `/field-docs/{...}` and
`/api/document-guide-asset` turn a URL into a path on your machine. Four enforce path
boundaries and one does not:

- `Server.kt#project file not found or outside project directory` canonicalises the
  resolved file and requires `Path.startsWith` against the canonical workspace. The comment
  there is explicit about why a string prefix check would be wrong — it would accept
  `/tmp/workspace-evil` for workspace `/tmp/workspace`;
- `Server.kt#Monaco asset not found` does the same for `/min/`, against the Monaco
  directory;
- `docsAnnotations.kt#Two independent checks now` checks both encodings: after a
  percent-encoding bug let `%2e%2e` through a textual `..` check, it now checks the
  *decoded* path textually **and** re-checks the assembled URI after `URI.normalize`;
- `/api/document-guide-asset` resolves only inside an already-open document, using canonical
  path containment, an image-extension allowlist, and a 20 MiB limit. Responses include a
  sandbox Content Security Policy and `nosniff`; these are content restrictions, not an
  origin guard;
- **`/local/{...}` has no containment check at all.** It takes the longest matching prefix
  from `dynamicRoutes`, joins the remainder onto the mapped directory with `File(parent,
  child)`, and calls `respondFile` if the result exists. No canonicalisation, and
  `java.net.URI.getPath` does not normalise `..` away.

`/local/{...}` is nevertheless **not reachable today**, and the reason is worth writing down
precisely rather than either alarming or reassuring: `DynamicDirectoryRoutes.addRoute` —
`dynamicRouting.kt:10#fun addRoute` — has **zero callers in the repository**. The map is
always empty, every longest-prefix match fails, and the route answers 404 unconditionally.
It is dead code with a latent traversal in it.

Confirmed against a real Field on port 9603, 2026-08-20, rather than read off the source:

| probe | result |
| --- | --- |
| `GET /local/anything` | 404, body `No dynamic route for /local/anything` |
| `GET /local/../../../../etc/passwd` | 404, same body |
| `GET /project/../../../../etc/passwd` | 404 — the containment check refused it |
| `GET /min/vs/loader.js` | 200 — that route is live and serves real files |
| `GET /min/../../../../etc/passwd` | 404 — the containment check refused it |

The `/local` **body** is the load-bearing detail. `No dynamic route for …` is the message from
the branch that runs when no prefix matched *at all*, which is direct evidence that the map is
empty — rather than evidence that some containment check saved us, which is what a bare 404
would have left ambiguous.

That is a finding, not a vulnerability. But it is the kind of dead code that stops being dead
the moment somebody wires up the feature it was written for, and at that point the traversal
arrives with it. **If you add a caller to `addRoute`, add the canonical containment check
that `/project` and `/min` already have in the same file.**

## All-method routes

Thirteen routes are declared as `route("/x") { handle { … } }`. `handle` installs a handler
with **no** `HttpMethodRouteSelector`, so the route answers `GET`, `POST`, `PUT`, `DELETE` and
everything else. That is not read off Ktor's documentation:
`FieldApiCorsExposureTest.aVerblessRouteReallyAnswersEveryMethodWhichIsWhyItIsInTheTable`
stands a `handle { }` and a `get { }` up in a real `testApplication` and asks both the same
four questions. Against Ktor 3.5.2 (`build.gradle.kts:61`) the verbless route answered all
four with the method name in the body; the named one answered `GET` and returned `405` to the
other three.

Until 2026-08-22 **none of these thirteen was in any census or on any page**, and that is
worse than the read-route gap it sat next to. A read route leaks; these accept writes. The
discovery on both sides was looking for `^\s*(get|post|put|delete|patch)\("…` — a verb call
with a quoted path — and a verbless route matches none of it. Demonstrated rather than argued:
an unguarded `route("/nightly-probe-verbless") { handle { … } }` planted in `Server.kt` left
the mutating census **BUILD SUCCESSFUL** on `d7d8dccc2`.

| route | declared in | guard |
| --- | --- | --- |
| `ANY /browse` | `Server.kt` | none |
| `ANY /code-use` | `Server.kt` | none |
| `ANY /dir` | `Server.kt` | none |
| `ANY /h2-explorer` | `Server.kt` | none |
| `ANY /history` | `Server.kt` | none |
| `ANY /inspect` | `Server.kt` | none |
| `ANY /keyframe` | `Server.kt` | none |
| `ANY /lazy-value-fragment` | `Server.kt` | none |
| `ANY /logs` | `Server.kt` | none |
| `ANY /threejs` | `Server.kt` | none |
| `ANY /tree` | `Server.kt` | none |
| `ANY /vision` | `Server.kt` | none |
| `ANY /welcome` | `Server.kt` | none |

Twelve of the thirteen read one HTML file out of `src/commonMain/resources/` and respond with
it through `filter()`. Serving a page to a `POST` is untidy rather than dangerous, and the
honest reading of those twelve is that they are `get { }` written the short way.

`ANY /lazy-value-fragment` is the exception and the one worth a decision. It takes four query
parameters — `root`, `kind`, `offset`, `depth` — straight into
`ValueVisualizer.renderLazyFragment`, on any method, from any origin, with no guard. It is how
a box's output panel expands a large value lazily, so it is a real feature with a real client;
it is also the only member of this table that does anything with its input.

**Open question for the human, not for a nightly agent:** should these thirteen be `get { }`?
Writing them as `get { }` would cost nothing — no client sends anything but `GET` — and would
take thirteen rows off the state-changing census, which would then read 40 rather than 53. The
reason this branch did not do it: it is a change to `Server.kt`'s behaviour rather than to a
test's accuracy, the `/vision` block's sibling `POST /vision/stream` shows the prefix is used
for more than pages, and nothing in the repository records whether any of these was ever
*meant* to answer a `POST`. Recorded here rather than decided.

## Static directory mounts

Six `staticFiles` mounts, all in `Server.kt`, and **none of them is visible to any route
regex in the tree** — a `staticFiles` call is not a verb declaration, so every census
written before this page missed all six.

| mount | directory | declared in |
| --- | --- | --- |
| `/` | `File("./build/js/packages/afield/kotlin")` | `Server.kt` |
| `/` | `File("./src/commonMain/resources/")` | `Server.kt` |
| `/` | `File("./src/jsMain/resources/")` | `Server.kt` |
| `/` | `File("./src/jsMain/resources/monaco")` | `Server.kt` |
| `/` | `File(home)` | `Server.kt` |
| `/sample-assets` | `File("./public/docs/assets/fabricate_demo")` | `Server.kt` |

Four serve build output and bundled front-end resources. The fifth,
`Server.kt#staticFiles(remotePath = "", dir = File(home))`, serves **the workspace
directory** at `/`, which is the same tree `/project/{...}` serves with a containment check.
Ktor's own `staticFiles` does the containment check here, which is why this is a note rather
than a second `/local`.

The sixth arrived on 2026-09-13 and is the only one with a mount point of its own. It serves
the shared sample assets — the CC0 Khronos ToyCar GLB and the Venice sunset environment —
because a **head** box (a browser or a headset running Kotlin compiled to JavaScript) can
only fetch an asset over http, and the thing that reads as the answer to that,
`mapDirectory`, is dead: it sends a `files.map` message no route in this tree consumes, and
the `/local/{...}` route it was presumably written for is backed by the callerless
`addRoute` below. One directory, of files already published on the documentation site, and
Ktor's containment check applies to it as it does to the five above.

## The `/ws` command surface

108 command names, in the `handlers` table. This is the real command API, it is roughly twice
the size of the HTTP write surface, and until this page nothing enumerated it. It is not the
only `/ws` dispatch table: ten more channels live in `rawHandlers` and are counted in
[their own section](#the-other-ws-dispatch-table-rawhandlers) below.

The `declared in` column is derived mechanically from where the `handlers[…]` assignment
sits, and that is deliberate. An earlier draft of this page tried to compute a *reach* column
per command — filesystem, process, database — from markers in each handler body. It was
abandoned because it was confidently wrong: the last handler in a file has no next handler
to bound it, so its "body" ran to end-of-file and absorbed every marker in the file, and
`launch-all` in `executing.kt` — which runs box code — came out tagged `document-state`. A
derived column that is wrong is worse than a coarse column that is right, so the column is
the file, and the reach is described per file below in prose that a human checked.

| declaring file | commands |
| --- | --- |
| `Server.kt` | 47 |
| `agent_backdoor.kt` | 19 |
| `history.kt` | 13 |
| `executing.kt` | 9 |
| `Ai4Installer.kt` | 4 |
| `importing.kt` | 4 |
| `templating.kt` | 3 |
| `UserKeybindingsManager.kt` | 2 |
| `chat.kt` | 2 |
| `ButtonControlHandler.kt` | 1 |
| `CoroutineProfiling.kt` | 1 |
| `DocsSearchInstaller.kt` | 1 |
| `documentGuides.kt` | 1 |
| `fileproxy.kt` | 1 |

What each of those reaches, checked against the code rather than inferred from the name:

| file | what its commands reach |
| --- | --- |
| `Server.kt` | document and box state, the completion engine, clipboard, layers, FLine editing, and `set-string`, which writes box source to disk |
| `agent_backdoor.kt` | the agent path: read/write boxes, run transcripts, screenshot, macros. The published automation surface |
| `history.kt` | the document history store — versions, screenshots, restore |
| `executing.kt` | **box execution.** `launch-all`, `launch-batch`, `launch-fragment`, `stop-box`, `stop-all`. This is arbitrary Kotlin in Field's own JVM |
| `Ai4Installer.kt` | AI chat sessions and tool use |
| `importing.kt` | notebook and EDN import — parses foreign documents into boxes |
| `templating.kt` | template read and write, under the templates directory |
| `chat.kt` | proxies to the configured chat server |
| `UserKeybindingsManager.kt` | reload the user keybindings file; report the command inventory |
| `ButtonControlHandler.kt` | one control-widget callback |
| `CoroutineProfiling.kt` | starts, latches, and reads bounded in-memory coroutine captures for the attached document; at most one recording process-wide, with a 30-second maximum |
| `DocsSearchInstaller.kt` | documentation search |
| `documentGuides.kt` | document-relative guide listing and reading, revision-checked saves, and shared file-proxy creation; writes reject read-only documents |
| `fileproxy.kt` | **creates a file-proxy box**, which writes a new file into the workspace |

`executing.kt` is the row that decides how seriously to read the rest. A command that reaches
`launch-all` runs the code in the document, in Field's JVM, with Field's privileges. There is
no sandbox between a `/ws` frame and that.

| command | declared in |
| --- | --- |
| `agent-ai-tool-call` | `agent_backdoor.kt` |
| `agent-ai-tools-list` | `agent_backdoor.kt` |
| `agent-box-screenshot` | `agent_backdoor.kt` |
| `agent-box-video` | `agent_backdoor.kt` |
| `agent-create-box` | `agent_backdoor.kt` |
| `agent-dispose-graphics-context` | `agent_backdoor.kt` |
| `agent-ensure-graphics-context` | `agent_backdoor.kt` |
| `agent-get-property` | `agent_backdoor.kt` |
| `agent-native-window-screenshot` | `agent_backdoor.kt` |
| `agent-open-document` | `agent_backdoor.kt` |
| `agent-open-editor` | `agent_backdoor.kt` |
| `agent-open-playwright` | `agent_backdoor.kt` |
| `agent-playwright-input` | `agent_backdoor.kt` |
| `agent-run-jvm-main` | `agent_backdoor.kt` |
| `agent-set-property` | `agent_backdoor.kt` |
| `agent-snapshot` | `agent_backdoor.kt` |
| `agent-wait-frames` | `agent_backdoor.kt` |
| `agent-window-screenshot` | `agent_backdoor.kt` |
| `agent-window-video` | `agent_backdoor.kt` |
| `ai-assist` | `Ai4Installer.kt` |
| `ai-chat-screenshot` | `Ai4Installer.kt` |
| `blame-history-line` | `history.kt` |
| `button-update` | `ButtonControlHandler.kt` |
| `chat_input` | `Ai4Installer.kt` |
| `checkbox-update` | `Server.kt` |
| `cherry-pick-history-version` | `history.kt` |
| `color-update` | `Server.kt` |
| `complete` | `Server.kt` |
| `complete-kotlin-ai` | `Server.kt` |
| `completion-interaction` | `Server.kt` |
| `continue-all` | `executing.kt` |
| `controller-selection` | `Server.kt` |
| `copy-boxes` | `Server.kt` |
| `coroutine-profile` | `CoroutineProfiling.kt` |
| `create-file-proxy-box` | `fileproxy.kt` |
| `custom-marking-fire` | `Server.kt` |
| `custom-marking-request` | `Server.kt` |
| `debug-probe` | `Server.kt` |
| `debug-source-probe` | `Server.kt` |
| `diff-history-version` | `history.kt` |
| `docs_search_input` | `DocsSearchInstaller.kt` |
| `document-guide` | `documentGuides.kt` |
| `execute-all` | `executing.kt` |
| `execute-fragment` | `executing.kt` |
| `execute-symbol` | `executing.kt` |
| `export-document` | `Server.kt` |
| `export-ipynb` | `importing.kt` |
| `file-upload-update` | `Server.kt` |
| `fline-click` | `Server.kt` |
| `fline-edit-add-line` | `Server.kt` |
| `fline-edit-append-line` | `Server.kt` |
| `fline-edit-begin-line` | `Server.kt` |
| `fline-edit-clear` | `Server.kt` |
| `fline-edit-end-line` | `Server.kt` |
| `fline-edit-translate` | `Server.kt` |
| `get-all-layers` | `Server.kt` |
| `glsl-context` | `Server.kt` |
| `hello` | `Server.kt` |
| `hello_chat` | `Ai4Installer.kt` |
| `history-line-churn` | `history.kt` |
| `history-property-version` | `history.kt` |
| `history-rail-line-stats` | `history.kt` |
| `history-selection-versions` | `history.kt` |
| `import-ipynb` | `importing.kt` |
| `import-metadata-edn` | `importing.kt` |
| `import-metadata-edn-nested` | `importing.kt` |
| `initialize` | `Server.kt` |
| `insert-template` | `templating.kt` |
| `launch-all` | `executing.kt` |
| `launch-batch` | `executing.kt` |
| `launch-fragment` | `executing.kt` |
| `list-history-versions` | `history.kt` |
| `load-chat-messages` | `chat.kt` |
| `macro-transcript-command` | `Server.kt` |
| `name-history-version` | `history.kt` |
| `output-ack` | `Server.kt` |
| `paste-boxes` | `Server.kt` |
| `perform-action` | `Server.kt` |
| `post-chat-message` | `chat.kt` |
| `provisional-accept` | `Server.kt` |
| `provisional-accept-all` | `Server.kt` |
| `provisional-record-accepted-value` | `Server.kt` |
| `provisional-reject` | `Server.kt` |
| `provisional-reject-all` | `Server.kt` |
| `reload-user-keybindings` | `UserKeybindingsManager.kt` |
| `report-command-inventory` | `UserKeybindingsManager.kt` |
| `request-templates` | `templating.kt` |
| `resolve-completion` | `Server.kt` |
| `resolve-source` | `Server.kt` |
| `restore-history-version` | `history.kt` |
| `save-as-template` | `templating.kt` |
| `search-history-versions` | `history.kt` |
| `set-active-layer` | `Server.kt` |
| `set-rect` | `Server.kt` |
| `set-rects` | `Server.kt` |
| `set-string` | `Server.kt` |
| `set-viewport` | `Server.kt` |
| `set-visible-layers` | `Server.kt` |
| `shared-ui-intent` | `Server.kt` |
| `slider-update` | `Server.kt` |
| `slideshow-command` | `Server.kt` |
| `stop-all` | `executing.kt` |
| `stop-box` | `executing.kt` |
| `store-history-screenshot` | `history.kt` |
| `string-update` | `Server.kt` |
| `symbol-context` | `Server.kt` |
| `transcript-line-activity` | `history.kt` |
| `vec2-update` | `Server.kt` |

## The other `/ws` dispatch table: `rawHandlers`

Ten **raw channels**, and until 2026-08-22 the one server surface of the five with no row
anywhere. `handlers` is keyed by a decoded message `address`; `rawHandlers` is keyed by a
channel name, and `Server.kt#rawHandlers.values` fans the **whole frame text** out to every
callback registered under it. So a `rawHandlers` channel is a `/ws` endpoint that parses the
frame itself.

Worth reading the near-miss rather than only the fix. The `handlers[…]` discovery regex was
unanchored, which *looks* as though it would have swept these up by accident and mis-filed
them. It did not: `rawHandlers` capitalises its H, so `handlers[` never matched it. The two
tables were disjoint by accident of spelling, and nobody had counted the second one. Anchoring
the regex (`ServerSurfaceLedgerTest#private val commandName`) therefore changed the command
census from **105 to 105** — the fix that mattered was adding the table below, not the anchor.

| channel | declared in |
| --- | --- |
| `rawHandlers["__browsing__"]` | `browsing.kt` |
| `rawHandlers["__h2_explorer__"]` | `h2explorer.kt` |
| `rawHandlers["__inspecting__"]` | `inspecting.kt` |
| `rawHandlers["__keyframe_browser__"]` | `keyframeBrowsing.kt` |
| `rawHandlers["__keyframes__"]` | `keyframes.kt` |
| `rawHandlers["__logs__"]` | `logstream.kt` |
| `rawHandlers["__spreadsheet__"]` | `spreadsheet.kt` |
| `rawHandlers["__threejs__"]` | `ThreeJsViewer.kt` |
| `rawHandlers["__visionmetal__"]` | `VisionMetal.kt` |
| `rawHandlers["__visionpose__"]` | `VisionPose.kt` |
| `rawHandlers["remote"]` | `RemoteJS.kt` |

Five of the eleven are keyed by a `const val` rather than a literal — `H2_EXPLORER_CHANNEL`,
`KEYFRAME_BROWSER_CHANNEL`, `SPREADSHEET_CHANNEL`, `VISION_METAL_CHANNEL`, `VISION_POSE_CHANNEL` —
which is why the discovery resolves the key expression the same way it resolves a `route(…)`
prefix, and fails loudly rather than filing a channel under a made-up name.

That failure is not hypothetical: `__visionpose__` arrived with a smoke test that reached the
registered handler as `rawHandlers[VISION_POSE_CHANNEL]` from *another* file, where the constant
cannot be resolved, and the guard refused the commit. The fix was to keep the reference beside its
constant (`visionPoseRawHandler()` in `VisionPose.kt`) rather than to loosen the resolution — a
channel reachable only through a name this page cannot follow is the case the table exists to catch.

`__visionpose__` is also the only channel here that runs **device to host**: the visionOS head
publishes head and per-eye viewing geometry up it, so the Mac can render the frame the headset is
about to display. Everything else on `/ws` is the browser or a native client asking this process for
something.

These sit behind the same `/ws` upgrade as the 108 commands, so the same sentence applies: the
guard arrived 2026-08-19 in `3aa64af4d` and is off by default.

## What the guard does and does not prove

`ServerSurfaceLedgerTest` reads source with regular expressions. That is the same choice
`FieldApiCorsExposureTest` made and for the same reason — a route nobody remembered to
install in a test application is exactly the route an inventory needs to catch — and it has
the same limits, which are worth stating rather than implying:

- it is not a Kotlin parser. It sees three declaration shapes — a verb call with a quoted
  path, `route(…) { handle { … } }`, and a bodyless `route(…) { get { … } }` — and resolves
  each one's path from the `route(…)` blocks it is lexically **inside of**;
- it attributes a guard by textual containment between one declaration and the next, so a
  guard named in a comment would count;
- **a route whose path is a bare word — `get("resolve")` — would be invisible to it**, because
  a bare-word path is textually indistinguishable from `JsonObjectBuilder.put("resolve")`, of
  which this tree has hundreds. The test therefore *asserts its own blind spot away*: in the
  four files that declare Ktor routes it requires that no verb call has a quoted path lacking
  a leading `/` or `{`. Add one and the test tells you the discovery would miss it.

### The three things that were wrong on 2026-08-20, found on 2026-08-22

Worth writing down, because two of the three were *wrong answers* rather than missing ones,
and a wrong answer in an inventory is what a reader trusts:

- **the verbless shape.** Covered above. Thirteen routes, in no table anywhere;
- **the bodyless shape.** `route("/history-screenshot") { get { … } }` and
  `route("/controllers") { get { … } }` write no path, so no path regex saw them. Two read
  routes, which is why this page went from 34 to 36. Being invisible was not only a missing
  row: the guard column is computed from the text between one declaration and *the next*, so a
  shape the census cannot see is a boundary it cannot honour, and one route's guard can leak
  into the row above it;
- **the path resolution.** It used to join a relative path to the nearest *preceding*
  `route(…)` and return early on anything starting with `/`. `route("/controllers") { get("/controls") }`
  is served at `/controllers/controls`; it was filed as `/controls` here **and** written as
  `/controls` on this page, so the assertion asserted their agreement rather than the truth of
  either. Three declarations were wrong that way — `GET /controls`, `GET /outputs`, and
  `POST /stream`, which is really `POST /vision/stream` and lives in the mutating census.

Answering "which `route(…)` am I inside of" needs brace nesting, and brace nesting over Kotlin
needs a lexer, because brace counting is wrong the moment it counts a brace inside a string.
`KotlinRouteScan` blanks comments and literal *text* while preserving every offset, and
`KotlinRouteScanTest.everyKotlinFileIsBraceBalancedAfterSanitising` asserts that all 755 `.kt`
files under `src/jvmMain/kotlin/afield` come out brace-balanced with no underflow — the premise,
over the real input. Two cases in this tree broke a naive version, and neither was the obvious
one: `Server.kt:1622` is a `"…${` whose template expression continues on the *next line*, and
`MarkdownOutput.kt:129` closes a raw string with four quotes, which Kotlin ends at the **last**
`"""` of the run rather than the first.

### Floors, landmarks, and what they cannot do

It asserts a **floor** on every count and a set of **landmark** entries — `GET /health`,
`GET /controllers/controls`, `ANY /lazy-value-fragment`, `File(home)`, `set-string`,
`create-file-proxy-box`, `launch-all`, `rawHandlers["__logs__"]`. The floors and landmarks
exist to separate the two ways a census can shrink: a route was deleted, which is fine and
wants a ledger edit, or *the discovery regex stopped matching*, which is not fine and would
otherwise present as a green tick over an empty set.

They are also not a completeness proof, and it is worth being blunt about that here because
all of them were in place on 2026-08-20 and none of them found the verbless routes. A floor
detects a census that **shrank**. It cannot detect a category the census never had a regex
for, because that category was never in the number the floor was set below. What found these
was a second, independently written census that disagreed on the totals. For a census, "I read
the source carefully" is not a measurement; two implementations forced to agree is.

Demonstrated rather than asserted:

| probe, planted then reverted | result |
| --- | --- |
| `get("/api/nightly-probe")` in `Server.kt`, 2026-08-20 | failed by name; green again on revert |
| `route("/nightly-probe-verbless") { handle { … } }` in `Server.kt`, 2026-08-22 | **green on `d7d8dccc2`** — that is the blind spot; after this change it fails naming `ANY /nightly-probe-verbless -> none` |
| `rawHandlers["__nightly_probe__"]` in `logstream.kt`, 2026-08-22 | failed naming the channel and its file |
| one ledger row's path edited by hand, 2026-08-22 | failed naming the row as both `missing` and `stale` |
