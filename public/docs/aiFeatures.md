# AI features

Field's AI features share the current box and editor context, but they serve
different jobs: conversation, targeted edits, completion, and generated helper
functions. They require an OpenAI or OpenRouter API key configured from
[`/welcome`](history_overview.md) or the environment.

## Chat about one box

Select one box and press `command/ctrl-4`, or run **AI Chat (panel)** from the
command palette. Field opens the `ai-chat` output area for that box. The chat
receives the box's current code and can use the Field tools made available by
the server to inspect or propose edits to box properties and related source.

![](assets/screenshot_47.png)

You can paste images into the chat input. Field sends those attachments with
the next message, which is useful when the desired change is easier to point at
than describe.

![](assets/screenshot_53.png)

## Chat at an editor location

Press `command/ctrl-3`, or run **AI Chat in editor**. With a selection, Field
marks that range as the requested rewrite; without a selection, it marks the
cursor position. The request also identifies the current box property and line.

![](assets/screenshot_46.png)

An edit can arrive as a *provisional* value rather than immediately replacing
the saved property:

![](assets/screenshot_48.png)

Monaco shows additions and deletions. Use the editor controls or the command
palette actions **Accept All Provisional** / **Reject All Provisional**, either
for the current editor or everywhere, to resolve them.

![](assets/screenshot_49.png)

## Ask AI about a displayed error

**Apply Intelligence to Error message** sends the current editor context plus
Field's last displayed error. Any resulting code change follows the same
provisional-edit path, so it remains reviewable before acceptance.

![](assets/screenshot_51.png)

![](assets/screenshot_52.png)

## AI-assisted completion

`ctrl-space` opens Field's completion panel. Kotlin completions come from
Field's compiler-backed completion path; Python completion uses Jedi. Field can
also request AI candidates in the background and add at most three of them to
the same list when they arrive.

```diffstatic_runner {data-lang=kotlin capture="ui" region="editor" warm-completion=true screenshot="assets/ai-autocomplete.png" wait="0" timeout="90" embed=true hidden=true name="ai-autocomplete"}
ui.eval("window.localStorage.removeItem('field.suggest.recency.v1'); var s=document.createElement('style');s.textContent='.tick_text.ok{display:none !important}';document.head.appendChild(s);return 'setup';")
ui.openEditor(id)
ui.pause(700)
ui.clickSelector(".monaco-editor")
ui.pause(300)
ui.press("Control+A")
ui.type("var time = 0.0\nval speed = 0.5\nval wobble = si")
ui.pause(300)
ui.press("Control+Space")
ui.waitForSelector(".afield-suggest-row", 30000.0)
ui.waitForSelector("text=AI 1", 20000.0)
ui.pause(600)
```

AI completion is asynchronous. The ordinary completion list can be used before
the AI request finishes, and a missing key or remote failure should not prevent
compiler-backed candidates from appearing.

## The `ai()` function macro

Inside a named Kotlin function with explicit parameter and return types,
`ai("description")` asks Field to generate the implementation of a private
forwarded function:

```diffstatic {data-lang=kotlin}
fun randomWiggle(radius: Double, center: Vec3): FLine {
    ai("makes an FLine that wiggles around center")
}
```

On the first expansion, Field asks the configured model for a function with the
derived name and exact signature. It retries compilation up to three times.
The generated block is stored in the box's `attic` property. Later expansions
reuse it only while the function signature and prompt still match; changing
either causes Field to generate a replacement.

![](assets/screenshot_56.png)

## Give the AI a tool of your own

Everything above is Field's AI working on Field's own material — boxes, code,
errors. A tool lets it work on *yours*. You write an ordinary Kotlin class and an
ordinary Kotlin lambda, register the pair, and from the next turn onward the
model can call it by name, with arguments it chose, and read what you return.

The whole surface is `afield.ai.core.AgentToolExtensions`. Nothing needs to be
edited in Field to add a tool: the registration goes the other way.

### The smallest version

```diffstatic {data-lang=kotlin}
import afield.ai.core.AgentToolExtensions
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

// The request class IS the schema. Its name is the tool's name, and these two
// annotations are the only prompt this tool gets — write them for a reader who
// cannot see the code.
@JsonClassDescription("Report the current position and radius of the orbiting marker.")
data class MarkerState(
    @JsonPropertyDescription("Return the value in millimetres instead of scene units.")
    val millimetres: Boolean = false,
)

AgentToolExtensions.register(MarkerState::class.java) { _, request ->
    val scale = if (request.millimetres) 1000.0 else 1.0
    mapOf("x" to marker.x * scale, "y" to marker.y * scale, "radius" to marker.r * scale)
}
```

Run the box. Select any box, press `command/ctrl-4`, and ask *"where is the
marker?"* — the model now has a way to find out that does not involve guessing
from your source.

A parameter with no default is required; a parameter with one is optional, and
the default is Kotlin's. Enums become a string with an enumerated list. That is
the whole schema language, and `AgentToolExtensions.describe(...)` will print
exactly what the model is shown.

### Live coding

Registration is keyed on the tool's **name**, so re-running the box replaces the
tool rather than adding a second copy of it, and there is no order you have to
follow: register before you open the chat or after, and a conversation that is
already running picks the change up on its next turn. Editing the handler and
re-running is the intended way to work.

`AgentToolExtensions.unregister("MarkerState")` takes it away again.

One rule: the class's simple name has to be unique across the whole
conversation, which includes Field's own built-in tools. Naming your request
class `Commentary` or `EditFileRequest` is rejected with an error saying so,
rather than quietly shadowing a tool the model has been told it can rely on.

### Deciding when a tool is offered

Every registered tool costs its schema on every request, in every conversation,
whether or not the subject ever comes up. The second argument is a gate, asked
each turn:

```diffstatic {data-lang=kotlin}
AgentToolExtensions.register(
    MarkerState::class.java,
    available = { markerExists() },
) { _, request -> /* … */ }
```

A closed gate removes the tool from the list the model is shown, rather than
leaving it visible and failing when called.

### Returning something the model can read — and see

Two optional interfaces, both worth implementing:

- **`SummarisesForToolLog`** — one line for Field's tool I/O log. Without it the
  log records only your result's class name, which cannot distinguish a turn
  where the model got what it needed from one where it got an error and gave up.
- **`CarriesToolImages`** — pictures. The bridge lifts them onto the conversation
  as a real image message. This is how a tool lets a model *look* at what it just
  did instead of taking your word for it.

```diffstatic {data-lang=kotlin}
data class MarkerReport(
    val x: Double,
    val y: Double,
    override val toolImages: List<ToolImage>,
) : SummarisesForToolLog, CarriesToolImages {
    override fun toolLogSummary() = "marker at %.1f, %.1f".format(x, y)
}
```

`toolImages` is annotated `@get:JsonIgnore` on the interface, and it matters that
you inherit that rather than reinventing it. A tool result is serialised to JSON
and sent to the model as the function output; a base64 PNG that reaches *that*
path is spent as text tokens and buys nothing, because a model cannot see an
image encoded inside a JSON string. The picture has to travel as a picture.

### A worked example you can run

`afield.four.modules.GeometryAgentTools` is a real, public tool set built exactly
this way, over the loaders in [Geometry from disk](geometry_from_disk.md). Point
it at a directory and the model can find out what geometry you have and look at
any of it:

```diffstatic {data-lang=kotlin}
import java.io.File

GeometryAgentTools.addSearchRoot(File(System.getProperty("user.home"), "scans"))
GeometryAgentTools.register()
```

Two tools appear. This is the schema the model is shown for the second one —
not a paraphrase, but the literal output of `AgentToolExtensions.describe(...)`,
regenerated and checked by Field's tests:

```diffstatic {data-lang=json}
{
  "name": "InspectGeometryFile",
  "description": "Load one 3D geometry file and report its measurements — vertex and triangle counts, bounding-box extent, surface area, watertightness — and, unless render is false, return a rendered image of it to look at. Paths come from ListGeometryFiles.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "path": {"type": "string", "description": "The file to inspect: a path from ListGeometryFiles, or an absolute path inside a search root."},
      "render": {"type": "boolean", "description": "Return a rendered image of the mesh. True by default; set false when only the numbers are wanted."},
      "yawDegrees": {"type": "number", "description": "Camera orbit around the vertical axis in degrees. Ask again with a different value to see another side."},
      "pitchDegrees": {"type": "number", "description": "Camera height above the horizon in degrees. Positive looks down on the object."},
      "wireframe": {"type": "boolean", "description": "Draw the triangle edges over the shading. Useful for judging mesh density; illegible above about 50,000 triangles."}
    },
    "required": ["path"]
  }
}
```

Called on the glTF sample that ships with Field, it answers with 77429 vertices,
108936 triangles, an extent of 0.073 × 0.029 × 0.074 metres, and this picture:

![The rendered mesh a model receives when it calls InspectGeometryFile](assets/ai_tool_demo/toycar_inspect.png)

The full session — both descriptors, both calls, both results — is committed
alongside it as `public/docs/assets/ai_tool_demo/session.md`, produced with no
API key and no model.

### Trying a tool without a model

`execute` is the same entry point a model's tool call goes through, so you can
drive your own tool from a box and see precisely what it would return:

```diffstatic {data-lang=kotlin}
import afield.ai.core.AgentToolExtensions
import afield.ai.core.ToolExecutionContext

val ctx = ToolExecutionContext()
println(AgentToolExtensions.availableToolNames(ctx))
println(AgentToolExtensions.describe(ctx).joinToString("\n"))
println(AgentToolExtensions.execute(ctx, "MarkerState", """{"millimetres": true}"""))
```

That needs no API key. It deserialises your JSON with the same Jackson call the
bridge uses, so a request class whose defaults or enums do not survive the trip
fails here rather than in the middle of a conversation.

There is a loadable document that does all of the above cold:
`public/docs/assets/ai_tool_demo/metadata.edn`.

### If your tool takes a path, or anything else from the outside

A tool argument is a value a language model chose. For most tools that is
unremarkable, but a tool that reads a file, runs a command or calls a network
service needs a boundary of its own, and needs it in the handler rather than in
the prompt. `GeometryAgentTools` will only read inside directories you added as
search roots: it canonicalises the requested path and then walks up the parent
chain, which refuses a `..` that escapes, a symlink that points out, and a
sibling directory whose name merely starts with a root's name.

The design notes — why the dependency points this way, what the two bridges do
with your registration, and what is still not covered — are in
`dev_docs/ai/AGENT_TOOL_EXTENSIONS.md`.

## Provider and key settings

The simplest setup is the AI settings card on `/welcome`, where you can select
OpenAI or OpenRouter, save the corresponding key, and optionally override model
names. Saved keys live under `~/.afield/`; they are not stored in a document.

For environment-based setup, Field reads:

- `OPENAI_PLATFORM_KEY` or `OPENAI_API_KEY` for OpenAI
- `OPENROUTER_API_KEY` for OpenRouter
- `AFIELD_AI_PROVIDER` to choose the provider

Environment variables take precedence over saved provider and credential
files. `AFIELD_AI_BASE_URL` can override the active provider's base URL.

Some AI features maintain remote conversation or source-search state with the
configured provider. The documentation/code search path currently indexes
selected Kotlin source roots, with separate GLSL stores selected by the current
context; it does not upload `public/docs` or `dev_docs` as a Markdown manual.
See [Semantic retrieval](semantic_index.md) for Field's separate local indexes.
