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
