# Agent tool extensions — how code outside `afield.ai.core` adds tools to AI4

| | |
|---|---|
| the seam | `src/jvmMain/kotlin/afield/ai/core/AgentToolExtensions.kt` |
| the registry it feeds | `src/jvmMain/kotlin/afield/ai/core/Tools.kt` (`ToolRegistry`) |
| where core calls it | `src/jvmMain/kotlin/afield/ai/core/AgentToolRegistrations.kt:17` |
| the worked example | `src/jvmMain/kotlin/afield/four/modules/GeometryAgentTools.kt` |
| the tests | `src/jvmTest/kotlin/afield/ai/AgentToolExtensionsTest.kt`, `GeometryAgentToolsTest.kt` |
| the evidence generator | `src/jvmMain/kotlin/afield/examples/AgentToolDemoEvidence.kt` |
| the committed evidence | `public/docs/assets/ai_tool_demo/` |
| the loadable demo | `public/docs/assets/ai_tool_demo/metadata.edn` |
| the user-facing page | `public/docs/aiFeatures.md`, "Give the AI a tool of your own" |

## Why the dependency points this way

`registerBuiltInAgentTools` used to name a domain cluster directly — it called
`afield.rect.registerHouseTools(tools)`, and `OpenAiIdeCodexBridge` had a `when` branch on that
cluster's result type so its images and log lines came out right. Both worked. Both were the wrong
shape, because they put "what subject matters exist" inside the AI plumbing.

The forcing function was moving that cluster into `src/personal`, which is a **separate compilation**
(`build.gradle.kts` — `compilations.create("personal") { associateWith(jvmMainCompilation) }`). The
dependency only ever runs personal → core, so the compiler simply refuses the reference, and the
reference had to be inverted.

That is the right outcome independent of the move, and the two `when` branches are the reason. Core
knowing one cluster's result type is a bug you can live with; core knowing *every* cluster's result
type is not a design. Both branches became interfaces — `SummarisesForToolLog` for the log line,
`CarriesToolImages` for the pictures — so a result opts into each behaviour by implementing it, and
core never learns a name.

What that buys, concretely: a cluster in `src/personal`, a cluster in core like
`GeometryAgentTools`, and a handful of lines typed into a box in somebody's document all add tools
through the identical call, and none of the three requires an edit to `afield.ai.core`.

## The shape of a registration

```kotlin
AgentToolExtensions.register(
    MyToolRequest::class.java,
    available = { ctx -> myThingExists() },
) { ctx, request -> MyToolResult(...) }
```

Three things are worth saying about that signature.

**The request class is the schema.** `ToolSchema.forClass` reflects over the primary constructor:
`@JsonClassDescription` becomes the tool description, `@JsonPropertyDescription` the per-argument
one, a parameter without a default becomes `required`, an enum becomes `{"type": "string", "enum":
[…]}`, and a parameter literally named `unused` is dropped (the conventional "this tool takes no
arguments" marker). There is nothing else to write, and nothing extra for either the OpenAI path or
the MCP face — both read the same descriptors.

**`available` is asked every turn.** It is not a one-time install check; it is the thing that decides
whether a conversation sees the tool set at all. A closed gate removes the tool from
`describe`/`availableToolNames` *and* makes `execute` throw, deliberately — a tool that is listed and
then fails teaches a model that your tool set is broken. Seventeen tool schemas on every request is a
real cost for a conversation that is not about the subject, which is why the house cluster gates on
"is there a house in the store" and `GeometryAgentTools` on "has anyone added a search root".

**The handler's `ctx` is `ToolExecutionContext`**, the same one core's own tools get: attachments, the
Kotlin-evaluation callbacks, the agent memory. Most domain tools ignore it and close over their own
state instead, which is fine.

## The document-side face: `aiTool { }`

`AgentToolExtensions.register` needs a request class, which box code can declare but rarely wants
to. The `aiTool` DSL (`src/jvmMain/kotlin/afield/ai/core/BoxAiTools.kt`, in the box `intro`
imports) is the lighter face for a document:

```kotlin
val echo = aiTool("echo.box") {
    describe("Echo text with the owning box id.")
    val text by param<String>("text to echo")          // name from the val, type from the generic
    val times by param<Int>("repeat count", default = 2)
    action { mapOf("boxId" to thisBox.id, "echo" to text().repeat(times())) }   // suspend, on the runloop
}
start { println(echo("hi", 3)) }                      // the handle is directly callable
```

It does not go through `ToolRegistry`'s handler map at all. Tools live in the per-document
`DocumentToolRegistry`, reach a request as `ToolExecutionContext.dynamicTools` (the in-app chat and
the MCP backdoor both fill it), and `ToolRegistry` honours that list in `addAllTo`/`execute`/
`describe`/`executeByName`, with built-in names winning a collision. A result implementing
`CarriesToolImages` (`BoxToolScope.image(...)` returns one) reaches both bridges' image paths.

Each registration is an `ai-tool` ownership in the `LifecycleLedger`, owned `ACCUMULATING` by the
registering run: a re-run replaces it without a gap, a re-run that stops registering it drops it, a
stop leaves it answering, and retiring the box releases it. `ai.boxtools.mechanism`
(`examples/ai/BoxAiToolsSmokeTest.kt`) checks all four; `ai.rectsl.tools` exercises the RectSL
studio set (`rectsl.check`, `rectsl.run`) built on it.

## Studies: one registrar for the standard verbs

A study's standard verbs — Inspect, Edit, Patch, SetNumbers, Observe, Compare, Sweep, TryVariants,
AdoptVariant, Restore — are wired once, in `src/jvmMain/kotlin/afield/stdlib/LiveStudy.kt`:

- A library study keeps its request classes (their names are the tool names, their fields and
  descriptions the schemas; `StudyToolSchemaPinTest` pins all of them), makes each implement the
  matching `StudyRequest.*` interface, adapts its study class to `LiveStudy`, and calls
  `registerStudyTools(scope, gate, studyOf, StudyToolClasses(...))`. What stays in its `*StudyTools.kt`
  is the declarations, the guide and its extras (Trace, Knobs, Timeline, Export).
- A document calls `registerStudyTools("Prefix", study)` from box code: the same ten verbs become
  box-authored tools (`InspectPrefixStudy`, …) on a `LiveStudy` — its own, or a `SourceFramesStudy`
  built from `sourceFramesStudy(property, studyFrames(…) { … })`, which implements every verb from
  the shared pieces. `study.document.registrar` (`examples/ai/DocumentStudySmokeTest.kt`) is the
  worked example, driven through all ten verbs.

## What a result should implement

`SummarisesForToolLog` is close to mandatory in practice. Without it the tool I/O log records
`type=MyResult`, which cannot distinguish a turn where the model got what it needed from one where it
got an error and gave up — and that log is what `AiToolUseImprovementMonitor` reads. Say whether it
succeeded, how many issues came back, the worst of them, and how many images went with it. One line.

`CarriesToolImages` has one thing to get right, and the interface exists so you get it by
inheritance: `toolImages` is annotated `@get:JsonIgnore`. A tool result is serialised and sent to the
model as the function-call output. A base64 PNG that reaches *that* path is spent as text tokens,
invisibly, and buys nothing — a model cannot see an image encoded inside a JSON string. The bridge
appends the images as a separate user-role message with `ToolImage.caption` as the text
(`OpenAiIdeCodexBridge.kt:433`). `GeometryAgentToolsTest.theRenderedImageIsRealPngAndStaysOutOfTheJson`
asserts both halves.

### Which bridges honour `CarriesToolImages`

| surface | tool images out | image input (a person pastes one) |
|---|---|---|
| `OpenAiIdeCodexBridge` (OpenAI, OpenRouter) | **yes** — a following user-role message (`:433`) | yes |
| `AnthropicBridge` | **yes**, since 2026-08-11 — inside the `tool_result` | **no** — `imageDataUrls` is warned and dropped (`:100`) |
| MCP backdoor (`agent-ai-tool-call`) | **no** — the reply is `aiToolMapper` JSON, from which `@get:JsonIgnore` correctly removes them | n/a |

The Anthropic row was "no" until this branch, and it was the interesting kind of no.
`CarriesToolImages`' KDoc said "the bridge appends the images to the conversation"; there are two
bridges and only one did. Every Anthropic tool result went out as `content(resultJson)`, a bare
string, so under `AFIELD_AI_PROVIDER=anthropic` — the provider this project is built around — a
geometry tool's whole purpose evaporated with no error and no log line. The only available symptom
was a model reasoning confidently about a picture it had never been shown.

**It was an unfinished feature, not a limitation.** The Messages API has accepted images inside a
`tool_result`'s content for as long as this bridge has existed, and the SDK models it as
`ToolResultBlockParam.Content.Block.ofImage`. The conversion was simply never written. Measured by
`AnthropicToolImageTest.theImageReachesTheSerialisedRequestPayload`, using the same Jackson mapper
the SDK's HTTP layer uses — so this is the wire body, not a rendering of it:

```json
// before
{"tool_use_id":"toolu_02","type":"tool_result","content":"{\"note\":\"x\"}"}

// after
{"tool_use_id":"toolu_02","type":"tool_result","content":[
  {"text":"{\"note\":\"x\"}","type":"text"},
  {"text":"a caption","type":"text"},
  {"source":{"data":"iVBORw0…","media_type":"image/png","type":"base64"},"type":"image"}]}
```

Images go *inside* the `tool_result` rather than in a following message, which is what the OpenAI
bridge does only because the Responses API has no equivalent nesting. Here the nesting exists and is
better: the picture is bound to the call it came from, so a turn with three tool calls cannot leave
the model guessing which image answers which. A result with no images still serialises to the plain
string it always did — pinned by a test, because turning every request into a one-element block list
would be a gratuitous change to every tool call the bridge makes.

Dropped rather than sent: a payload over `ANTHROPIC_MAX_IMAGE_BASE64_CHARS` (5 MB — the ToyCar render
is about 2% of it), a non-`data:` URL, or a media type the API does not accept. The text result still
goes, because a 400 from an oversized attachment would lose the answer in order to save a bonus, and
the drop is reported through the bridge's `warn` rather than being silent. Silence is the bug this
whole section is about.

**What is still not carried:** image *input* on the Anthropic path, and images over MCP. The second is
correct behaviour — an MCP tool reply is JSON and `@get:JsonIgnore` is doing its job — but it does
mean a tool whose answer is only a picture has nothing to say to an agent on the backdoor. Design for
it: put anything the model must not miss in the result as well as in the picture. `GeometryAgentTools`
is written that way, which is why every measurement in its image caption is also a field on
`InspectGeometryFileResult`.

## Live coding — what actually happens

This is the part the KDoc used to get wrong, and it is worth writing down in full because the failure
was silent in both directions.

**How the registry gets built.** `AgentToolExtensions.applyTo(tools)` is called from
`registerBuiltInAgentTools`, which is called from a *bridge constructor* — `AnthropicBridge.init` and
`OpenAiIdeCodexBridge`'s `registerBuiltIns()`. `Ai4Installer` builds each bridge lazily under
`if (bridge == null)` on the first AI turn and then caches it for the life of the window
(`Ai4Installer.kt:831`, `:845`, `:2528`). Field keeps **two** — `bridge` for the editor-location chat
and `chatBridge` for the panel — so there are two `ToolRegistry` instances in a normal session, and
each is built once and outlives every box run after it.

**What that meant before 2026-08-11.** `applyTo` *copied* the registrations into the registry, so the
registry was a snapshot of whichever tool sets had loaded before the human's first message.

- Register before the first AI turn → works. This is the only order anyone had tried, and it is what
  the author's own house document does: run the box, then press `command-4`.
- Register after → the model never sees the tool. No error, anywhere.
- Re-run the box → `AgentToolExtensions` swapped its registration while the agent went on calling the
  **previous** handler, out of a REPL classloader that no longer corresponded to anything on screen,
  for the rest of the session. This is the dangerous one, because it looks like it works.

There was a second, independent bug underneath. `register` de-duplicated with
`it.toolClass == toolClass` — by `Class` *identity*. That holds for a `register` call from a compiled
package, which is why nothing noticed; but a live REPL recompiles a box into a fresh classloader, so
the second run's request class is a different `Class` object with an identical name, and **both**
registrations survived. `describe` then offered the model the tool twice, and `execute`'s
`firstOrNull { it.toolClass.simpleName == name }` picked the older one.

**What happens now.** `applyTo` *subscribes* the registry rather than copying into it, and
`register`/`unregister` push through to every subscriber. De-duplication is by simple name, which is
what every other layer already keys on — `ToolRegistry.handlers`, `execute`, `isAvailable`, and the
tool name a model sends back in its call. So:

- registration order does not matter;
- re-running a box replaces the handler under a conversation that is already open;
- `unregister` really retires the tool rather than leaving a zombie in the registry.

The subscription set is **weak** (`Collections.newSetFromMap(WeakHashMap())`). A bridge can be
discarded — every AI CLI builds one per run — and a strong reference would pin the bridge, every
handler lambda, and everything those close over, for the life of the process.

**Name precedence had to become explicit.** Before, an extension could not shadow a built-in only
because `applyTo` ran at the top of `registerBuiltInAgentTools` and core's own `register` calls
overwrote it afterwards; the precedence was an accident of call order. Now a registration can arrive
*after* the built-ins, so `ToolRegistry` tracks which names an extension owns and
`registerExtension` refuses the rest. `AgentToolExtensions.register` turns that refusal into an
`IllegalArgumentException` naming the clash. Core always wins, and loudly.

The one case that is still quiet: a collision noticed inside `applyTo` — i.e. the tool was registered
*before* any agent existed, so `register` had no registry to ask. That runs inside a bridge
constructor, on whatever thread opened the chat, and throwing there would take out the chat rather
than the offending tool. It is dropped. If you register a tool and the model never sees it, check
whether you have named it after one of core's.

## The two faces

The same registration reaches both surfaces, which is the property that keeps them from drifting:

- **The OpenAI / Anthropic agent loop.** `ToolRegistry.addAllTo` for the schemas,
  `ToolRegistry.execute` for the dispatch.
- **The MCP backdoor.** `ToolRegistry.describe` and `ToolRegistry.executeByName`, surfaced through the
  agent backdoor's `agent-ai-tools-list` / `agent-ai-tool-call` handlers — see
  `dev_docs/agents/FIELD_MCP.md`. `AgentToolExtensions.describe` and `.execute` emit and accept
  exactly the same shapes, so an extension's tools are indistinguishable from a built-in's from
  outside, and nothing reading descriptors needs a special case.

That second bullet was **false until 2026-08-11**, and it is worth knowing why, because it is the
same class of failure as the live-coding one. The KDoc has always said there is "nothing extra to
write for the OpenAI path or the MCP face" — but the MCP registry is not built by
`registerBuiltInAgentTools`. `Ai4BackdoorTools` assembles its own `ToolRegistry` by hand, tool by
tool, and so had no route to `AgentToolExtensions` at all. The result was a genuinely confusing
asymmetry: the house cluster's seventeen tools worked in AI Chat and were simply absent from the
tool list an agent driving Field over the backdoor saw, with no error and no note anywhere. The fix
is one line at the top of that builder; the ordering matches `registerBuiltInAgentTools`, so a name
collision resolves the same way in both registries.

The lesson generalises: "the tools go in one registry" is only true if every registry is built the
same way, and there are two. If a third appears, it needs the `applyTo` line too, and
`AgentToolExtensionsTest.theMcpBackdoorRegistryCarriesExtensionToolsToo` is the shape of the test
that would notice.

`execute` being public is also what makes a tool set testable. Core's own registry is `internal`, so
a cluster living outside core cannot inspect itself through it; that gap is what made the house tool
set's smoke untestable after the move into `src/personal`.

## The motivating story

The document this seam exists for is the author's own: one Kotlin box named "House project · ask AI4
here", whose `notes` read

> Select it and open AI Chat (Cmd-4), then ask: "Preview a 10.4 by 6.8 metre version… do not commit
> until I approve." The model should call `HouseCreateVanishingEnvelope` with `commit=false`.

Seventeen tools over a two-level rectilinear house model — describe, set-from-script, validate, place
fittings, move a wall and let the rooms follow, panelize, export, undo — registered through
`AgentToolExtensions` from a private, gitignored tool cluster that is not in this repository; it is described here because it is the reason the seam
is shaped the way it is, and because two of its habits are worth stealing:

- **`commit=false` as a first-class argument.** A tool that changes something takes a preview flag and
  returns what *would* happen, with a rendered image, so the human approves the picture rather than
  the prose. `GeometryAgentTools` deliberately has no writing tool for this reason: getting a
  confirmation protocol half-right in a page people copy from is worse than leaving it out.
- **`undoDepth` in the result.** Every mutating result reports how far back the model could step, so
  it knows recovery is available without having to ask.

## What is not covered

- **Nested request objects.** `ToolSchema.schemaForType` falls through to `{"type": "object"}` for
  anything it does not recognise, with no properties. A request class with a nested data class
  parameter will deserialise correctly but the model is shown an opaque object, so it will not fill
  it in. Keep request classes flat, or flat plus lists of primitives.
- **Suspending handlers.** The handler signature is `(ToolExecutionContext, T) -> Any`, synchronous,
  and runs on the agent loop's thread, so a slow tool blocks the turn. There is no suspend-capable
  variant in the tree today; a handler that needs one has to bridge it itself (`runBlocking`, or
  return a job id and offer a second tool that polls it).
- **Per-conversation registration.** Registration is process-global. Two documents open in one Field
  see each other's tools, gated only by whatever `available` does. If that matters, gate on something
  document-scoped.
- **A registration surviving a provider switch.** Changing the provider on `/welcome` builds a new
  bridge; the subscription handles that correctly now, but nothing re-runs your box, so a
  registration made by a box that has since been edited to something else is gone.
- **Compiling *every* example on the docs page.** The two substantial Kotlin examples in
  `public/docs/aiFeatures.md` carry `FIELD_TEST … runner: backdoor-box` annotations, so
  `tools/run_field_tests.py` executes the page's own fence in a live Field — which is stronger than
  `BriefDocsTest`'s scrape-and-parse, because the snippet runs rather than being re-implemented. The
  remaining short fences (the gating example, the result-interface example) are fragments and are
  checked only by `AiToolAuthoringDocsTest`, for the symbols they name. Also note that
  `run_field_tests.py` is not part of `./gradlew check`, so those two are CI-executed only where that
  runner is invoked.

  A trap for the next person writing one: the `FIELD_TEST` block is parsed by a mini-YAML in
  `tools/discover_field_tests.py` that turns any list item containing a colon into a mapping,
  whatever the quoting (`:167`). Expected substrings have to be colon-free.

## Regenerating the evidence

```
./gradlew runAgentToolDemo                                  # rewrites public/docs/assets/ai_tool_demo/
python3 tools/run_field_tests.py --id ai.tool-extensions.demo   # drift-checks it, writing nothing
./gradlew jvmTest --tests 'afield.ai.*'                     # 23 tests
```

The artefacts are byte-reproducible: the rasteriser is deterministic CPU code and the absolute repo
path is redacted to `<repo>` on the way out, so the drift check is meaningful on a machine that is not
this one.
