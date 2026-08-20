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

- **state-changing HTTP routes** — 40 of them — belong to
  `FieldApiCorsExposureTest.everyMutatingRouteAndItsGuardIsPinned`, which has pinned them
  since 2026-08-12 and counts the unguarded remainder. This page does not duplicate that
  table;
- **the CORS block and the three WebSocket channels** belong to the same file's other tests.

What was uncovered before this page existed, and is what it adds:

| surface | count | previously guarded by |
| --- | --- | --- |
| HTTP **read** routes | 34 | nothing — the mutating census skips `get` outright |
| `staticFiles` directory mounts | 5 | nothing — invisible to every route regex in the tree |
| `/ws` **command** names | 105 | nothing |

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

34 routes, 31 of them with no route-specific guard. `guard` is computed from the handler
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
| `GET /controls` | `controllers.kt` | none |
| `GET /field-audio/{...}` | `fieldAudioProxy.kt` | none |
| `GET /field-docs/{...}` | `docsAnnotations.kt` | loopback |
| `GET /health` | `Server.kt` | none |
| `GET /index.html` | `Server.kt` | none |
| `GET /local/{...}` | `Server.kt` | none |
| `GET /min/{...}` | `Server.kt` | none |
| `GET /outputs` | `controllers.kt` | none |
| `GET /project/{...}` | `Server.kt` | none |

### The four that serve files off disk, which deserve reading twice

`/project/{...}`, `/min/{...}`, `/local/{...}` and `/field-docs/{...}` turn a URL into a
path on your machine. Three of them are careful in the same way and one is not:

- `Server.kt#project file not found or outside project directory` canonicalises the
  resolved file and requires `Path.startsWith` against the canonical workspace. The comment
  there is explicit about why a string prefix check would be wrong — it would accept
  `/tmp/workspace-evil` for workspace `/tmp/workspace`;
- `Server.kt#Monaco asset not found` does the same for `/min/`, against the Monaco
  directory;
- `docsAnnotations.kt#Two independent checks now` is the strongest of the four: after a
  percent-encoding bug let `%2e%2e` through a textual `..` check, it now checks the
  *decoded* path textually **and** re-checks the assembled URI after `URI.normalize`;
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

## Static directory mounts

Five `staticFiles` mounts, all in `Server.kt`, and **none of them is visible to any route
regex in the tree** — a `staticFiles` call is not a verb declaration, so every census
written before this page missed all five.

| mount | directory | declared in |
| --- | --- | --- |
| `/` | `File("./build/js/packages/afield/kotlin")` | `Server.kt` |
| `/` | `File("./src/commonMain/resources/")` | `Server.kt` |
| `/` | `File("./src/jsMain/resources/")` | `Server.kt` |
| `/` | `File("./src/jsMain/resources/monaco")` | `Server.kt` |
| `/` | `File(home)` | `Server.kt` |

Four serve build output and bundled front-end resources. The fifth,
`Server.kt#staticFiles(remotePath = "", dir = File(home))`, serves **the workspace
directory** at `/`, which is the same tree `/project/{...}` serves with a containment check.
Ktor's own `staticFiles` does the containment check here, which is why this is a note rather
than a second `/local`.

## The `/ws` command surface

105 command names. This is the real command API, it is larger than the HTTP write surface by
a factor of two and a half, and until this page nothing enumerated it.

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
| `agent_backdoor.kt` | 18 |
| `history.kt` | 13 |
| `executing.kt` | 9 |
| `Ai4Installer.kt` | 4 |
| `importing.kt` | 4 |
| `templating.kt` | 3 |
| `UserKeybindingsManager.kt` | 2 |
| `chat.kt` | 2 |
| `ButtonControlHandler.kt` | 1 |
| `DocsSearchInstaller.kt` | 1 |
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
| `DocsSearchInstaller.kt` | documentation search |
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
| `create-file-proxy-box` | `fileproxy.kt` |
| `custom-marking-fire` | `Server.kt` |
| `custom-marking-request` | `Server.kt` |
| `debug-probe` | `Server.kt` |
| `debug-source-probe` | `Server.kt` |
| `diff-history-version` | `history.kt` |
| `docs_search_input` | `DocsSearchInstaller.kt` |
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

## What the guard does and does not prove

`ServerSurfaceLedgerTest` reads source with regular expressions. That is the same choice
`FieldApiCorsExposureTest` made and for the same reason — a route nobody remembered to
install in a test application is exactly the route an inventory needs to catch — and it has
the same limits, which are worth stating rather than implying:

- it is not a Kotlin parser. It sees a declaration whose path is a string literal, and joins
  a relative path to the nearest preceding `route(…)` prefix in the same file, resolving a
  `const val` prefix where the argument is not a literal (which is how `/field-docs/{...}`
  resolves);
- it attributes a guard by textual containment between one declaration and the next, so a
  guard named in a comment would count;
- **a route whose path is a bare word — `get("resolve")` — would be invisible to it**, because
  a bare-word path is textually indistinguishable from `JsonObjectBuilder.put("resolve")`, of
  which this tree has hundreds. The test therefore *asserts its own blind spot away*: in the
  four files that declare Ktor routes it requires that no verb call has a quoted path lacking
  a leading `/` or `{`. Add one and the test tells you the discovery would miss it.

It also asserts a **floor** on every count and a set of **landmark** entries — `GET /health`,
`File(home)`, `set-string`, `create-file-proxy-box`, `launch-all`. The floors and landmarks
exist to separate the two ways a census can shrink: a route was deleted, which is fine and
wants a ledger edit, or *the discovery regex stopped matching*, which is not fine and would
otherwise present as a green tick over an empty set.

Demonstrated rather than asserted, on 2026-08-20: a planted `get("/api/nightly-probe")` in
`Server.kt` failed the test by name, and reverting it returned the suite to green.
