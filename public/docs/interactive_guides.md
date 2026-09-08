# Interactive Guides

A guide is a box that walks someone else through your document. You write it in the box; the person
following it gets notes, moving selections and callout lines over the live document rather than a
recording of one. A guide can:

- show a note next to a box,
- move the editor to a property and line,
- branch through callbacks,
- render live values into Markdown templates,
- attach a discussion thread,
- draw callout lines to boxes or exact code locations.

The core API lives in `slideShow { ... }`. Each `step("...")` defines one stage of the guide, and `markdownNote(...)` or `note(...)` renders the overlay.

## Start with a single-step guide

You need five things for the smallest guide that does anything:

- a host box to write it in,
- a `slideShow { ... }` block,
- at least one `step("...")`,
- a note body,
- a coroutine to start the guide running.

```diffstatic {data-lang=kotlin}
:::static
import afield.markdownNote
import afield.slideShow
import kotlinx.coroutines.runBlocking

val show = slideShow {
    step("Welcome to Field") {
        markdownNote(
            """
            ## Welcome to Field
            This note is attached to a running box.

            - `slideShow { ... }` creates the guide.
            - `step(...)` defines the sequence.
            - `selectBox(...)` controls what the reader sees.

            [[home]] [[next:Continue ->]]
            """.trimIndent(),
            animated = true
        )

        selectBoxByName("My Guide Box", property = "code")
    }
}

runBlocking {
    show.start()
}
```

That is already a working guide. It opens a note, selects a box, and leaves the reader looking at the code the note is talking about.

## Focus the reader on the right box and property

A step usually explains the current stage with a note and moves the UI to the place it is talking about in the same breath. Use `selectBox(box, property = "code")` when you already have the target box in scope, and `selectBoxByName(...)` when the guide is driving another named box in the document.

```diffstatic_runner {data-lang=kotlin screenshot="assets/interactive_guides/basic_slideshow.png" wait="1.5" capture="box" open-outputs="false" embed=true name="basic-slideshow" w="560" h="140" padding="28"}
:::static
import afield.markdownNote
import afield.slideShow
import kotlinx.coroutines.runBlocking

println("basic slideshow ready")

val host = thisBox

val show = slideShow {
    step("Welcome to Field") {
        markdownNote(
            """
            ## Welcome to Field
            This guide is rendered by the same slideshow system used in live documents.

            - notes are Markdown-first,
            - boxes stay selectable while the note is open,
            - and the editor can stay visible during the walkthrough.

            [[home]] [[next:Continue ->]]
            """.trimIndent(),
            animated = true
        )

        selectBox(host, property = "code")
    }
}

runBlocking {
    show.start()
}
```

Pair notes with view changes. A note that talks about code should leave that code visible.

## Add callbacks and live template values

An interactive step wants a `#custom:name` link in the note body, a `linkCallback("name") { ... }` handler in the step to receive it, and `{{propertyName}}` template expansion for the live values. Together they cover choices, branching, and notes that update while the guide stays on the same step.

```diffstatic_runner {data-lang=kotlin screenshot="assets/interactive_guides/callbacks_and_templates.png" wait="1.5" capture="box" open-outputs="false" embed=true name="callbacks-and-templates" w="560" h="140" padding="28"}
:::static
import afield.logger.lprintln
import afield.markdownNote
import afield.notify
import afield.slideShow
import kotlinx.coroutines.runBlocking

var clickCount = 3
var userChoice = "Balanced Mode"
val version = "1.0.0"
val host = thisBox

println("interactive guide ready")

val show = slideShow {
    step("Interactive Choices") {
        markdownNote(
            """
            ## Interactive Guides with Callbacks
            Version: **{{version}}**

            - [Increment](#custom:incrementCounter)
            - [Choose performance mode](#custom:choiceA)
            - [Choose quality mode](#custom:choiceB)

            Count: **{{clickCount}}**
            Choice: **{{userChoice}}**
            """.trimIndent()
        )

        linkCallback("incrementCounter") {
            clickCount += 1
            here.properties["clickCount"] = clickCount
            show.goToStep(show.currentStepIndex)
            lprintln("info,slideshow", "Counter incremented to $clickCount")
        }

        linkCallback("choiceA") {
            userChoice = "Performance Mode"
            here.properties["userChoice"] = userChoice
            show.goToStep(show.currentStepIndex)
            notify("Selected: Performance Mode", "slideshow")
        }

        linkCallback("choiceB") {
            userChoice = "Quality Mode"
            here.properties["userChoice"] = userChoice
            show.goToStep(show.currentStepIndex)
            notify("Selected: Quality Mode", "slideshow")
        }

        selectBox(host, property = "code")
    }
}

runBlocking {
    show.start()
}
```

A template value can come from `this.properties[...]`, from a Kotlin variable that is still in scope, or from the shared template helpers the slideshow runtime exposes.

When a callback changes state, call `show.goToStep(show.currentStepIndex)` to rerender the current step immediately. That updates `{{clickCount}}` and `{{userChoice}}` without leaving the slide.

## Per-slide chat

Chat is optional. It belongs to the step that asks for it.

- `withChat()` creates a thread for that slide.
- `withChat(id = "shared-id")` shares one thread across repeated guide instances.

```diffstatic_runner {data-lang=kotlin screenshot="assets/interactive_guides/slide_chat.png" wait="1.5" capture="box" open-outputs="false" embed=true name="slide-chat" w="560" h="140" padding="28"}
:::static
import afield.markdownNote
import afield.slideShow
import kotlinx.coroutines.runBlocking

println("slide chat ready")

val host = thisBox

val show = slideShow {
    slideshowId("docs-guide-chat")

    step("Slide discussion") {
        markdownNote(
            """
            ## Per-slide Chat
            Use `withChat()` when a slide should carry its own discussion.

            Use `withChat(id = \"templateId\")` when multiple template instances should share one thread.

            [[back]] [[next]]
            """.trimIndent()
        )

        withChat(id = "templateId")
        selectBox(host, property = "code")
    }
}

runBlocking {
    show.start()
}
```

Use per-slide chat when comments should stay attached to a specific step instead of floating beside the whole guide.

If the guide chat server is not running, the slideshow still renders and
`withChat()` still produces the UI shell; only message load and post requests
fail.

### Setting up the chat server

Chat is the one guide feature that needs a companion service. It has four parts: a
Flask service (`server/guide_chat_server.py`) that persists messages as JSON, a
JVM bridge (`src/jvmMain/kotlin/afield/server/chat.kt`), the collapsible chat UI in
`src/jsMain/kotlin/afield/Main.kt`, and the metadata `SlideShow.kt` passes from the
guide runtime. The browser only ever talks to Field's normal RPC transport — Field
proxies HTTP to Flask and broadcasts successful posts back to clients, so the
browser never connects to Flask's Socket.IO endpoint directly.

Install the dependencies and start the service alongside Field:

```diffstatic {data-lang=bash}
cd server
pip install -r requirements.txt
python3 guide_chat_server.py   # then ./gradlew runJvm in another shell
```

It reads three optional environment variables: `CHAT_SERVER_PORT` (default
`5002`), `CHAT_SERVER_DEBUG` (default `true`), and `CHAT_SERVER_URL` (the URL
Field's JVM server uses, default `http://localhost:5002`).

The frontend reads a username from the URL query string —
`?file=myguide&username=student1` — and posts as `anonymous` without it. Messages
are stored under `chat_data/` in per-slideshow, per-slide JSON files
(`chat_data/slideshow_box123_.../slide_0.json`), each an array of
`{username, message, timestamp}` records. A deeper quickstart lives in
`dev_docs/GUIDE_CHAT_QUICKSTART.md`.

## Annotations and code callouts

Annotations let a note point at an exact target.

- `annotate(...)` points from the note to a box.
- `annotateToLine(...)` points to an exact property and line inside the editor.

Use them when a sentence is about one box, one property, or one line.

```diffstatic_runner {data-lang=kotlin screenshot="assets/interactive_guides/annotations.png" wait="5" capture="window-editor" open-outputs="false" embed=true name="guide-annotations" padding="12"}
:::static
import afield.markdownNote
import afield.slideShow
import kotlinx.coroutines.runBlocking

val host = thisBox
host.name = "guide-annotations"

val show = slideShow {
    step("Annotations") {
        markdownNote(
            """
            ## Annotation Lines
            Guides can point at whole boxes and exact code lines.

            - the green line targets the box,
            - the orange dashed line targets line 21 in this guide box.
            """.trimIndent(),
            animated = true
        )

        selectBox(host, property = "code", line = 21, highlight = 21)

        annotate(
            id = "target-box",
            targetBox = host,
            color = "rgba(128, 255, 164, 0.8)",
            strokeWidth = 2.5
        )

        annotateToLine(
            id = "target-line",
            targetBox = host,
            property = "code",
            line = 21,
            color = "rgba(255, 180, 100, 0.8)",
            strokeWidth = 2.0,
            dashed = true
        )
    }
}

runBlocking {
    show.start()
}
```

For line-level teaching, combine `selectBox(...)`, `line = ...`, `highlight = ...`, and `annotateToLine(...)`. The reader gets the note, the selected editor, the highlighted line, and the line drawn between them.

## Growing a guide

Guides tend to get written in one direction:

1. `slideShow { ... }` with one or more `step(...)` blocks.
2. `markdownNote(...)` or `note(...)` in each step, saying what stage the reader is at.
3. `selectBox(...)`, so the reader is looking at what the note describes. Stop here and you have a working guide.
4. `linkCallback(...)` and `#custom:name` links, when a step should branch or change state.
5. `{{templateValues}}`, when a note should show the current guide state.
6. `withChat()`, when a step needs discussion.
7. `annotate(...)` or `annotateToLine(...)`, when a sentence is about one exact target.

Steps 4 to 7 go only on the steps that ask for them.

## Example source map

For complete examples, read `src/jvmMain/kotlin/afield/examples/slideshow_example.kt`:

- `simpleInteractiveExample()` is the minimal callback-and-template example.
- `exampleSlideShow()` is a traditional guided tour that moves through multiple boxes.
- `advancedSlideShowExample()` adds `annotate(...)` and `annotateToLine(...)` so a note can point at a box or an exact code line.
- `interactiveGuideExample()` combines Markdown notes, callbacks, templates, choices, slideshow IDs, and both local and shared chat.

## API summary

The calls you will use most often:

- `slideShow { ... }` to define the guide.
- `step("Title") { ... }` to define each stage.
- `markdownNote(...)` or `note(...)` to render the overlay.
- `selectBox(box, property = "code", line = ..., highlight = ...)` to steer the UI.
- `linkCallback("name") { ... }` for custom links such as `#custom:name`.
- `withChat()` or `withChat(id = "shared")` for discussion threads.
- `slideshowId("...")` when the slideshow needs a stable identity across sessions.
- `annotate(...)` and `annotateToLine(...)` for visual callouts.
