# Interactive Guides

Field's guide system turns a box into a guide. Use guides to:

- show a note next to a box,
- move the editor to a property and line,
- branch through callbacks,
- render live values into Markdown templates,
- attach a discussion thread,
- and draw callout lines to boxes or exact code locations.

The core API lives in `slideShow { ... }`. Each `step("...")` defines one stage of the guide, and `markdownNote(...)` or `note(...)` renders the overlay.

## Start with a single-step guide

The smallest useful guide needs:

- a host box,
- a `slideShow { ... }` block,
- at least one `step("...")`,
- a note body,
- and a coroutine that starts the guide.

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

This produces a working guide. The step can change selection, open the editor, and point to the relevant code.

## Focus the reader on the right box and property

Most steps do two things together:

- explain the current stage with a note,
- and call `selectBox(...)` or `selectBoxByName(...)` to move the UI to the relevant place.

Use `selectBox(box, property = "code")` when you already have the target box in scope. Use `selectBoxByName(...)` when the guide is driving another named box in the document.

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

Pair each note with an explicit view change so the reader sees the code the note refers to.

## Add callbacks and live template values

Guides become interactive when you combine three features:

- `#custom:name` links in the note body,
- `linkCallback("name") { ... }` handlers in the step,
- and `{{propertyName}}` template expansion for live values.

Use this pattern when a guide needs choices, branching, or live note updates.

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

Template values can come from:

- values you store in `this.properties[...]`,
- Kotlin variables that are still in scope,
- and shared template helpers that the slideshow runtime exposes.

When a callback changes state, call `show.goToStep(show.currentStepIndex)` to rerender the current step immediately. That updates `{{clickCount}}` and `{{userChoice}}` without leaving the slide.

## Per-slide chat

Chat is optional and local to a step.

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

Use per-slide chat when comments should stay attached to a specific step.

If the guide chat server is not running, the slideshow still renders normally; the discussion pane just will not be able to post or load messages.

The chat server setup and storage details live in [guide_chat.md](guide_chat.md).

## Annotations and code callouts

Annotations let a note point to exact targets.

- `annotate(...)` points from the note to a box.
- `annotateToLine(...)` points to an exact property and line inside the editor.

Use them when the guide text refers to a specific box or line.

```diffstatic_runner {data-lang=kotlin screenshot="assets/interactive_guides/annotations.png" wait="1.5" capture="box" open-outputs="false" embed=true name="guide-annotations" w="560" h="140" padding="32"}
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
            - the orange dashed line targets line 8 in this guide box.
            """.trimIndent(),
            animated = true
        )

        selectBox(host, property = "code", line = 8, highlight = 8)

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
            line = 8,
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

Combine `selectBox(...)`, `line = ...`, `highlight = ...`, and `annotateToLine(...)` when the explanation is tied to one line or one decision point in the code.

## How the features fit together

A full guide usually combines the sections above in this order:

1. Start with `slideShow { ... }` and one or more `step(...)` blocks.
2. Use `markdownNote(...)` or `note(...)` to explain the current stage.
3. Use `selectBox(...)` to move the reader to the right box, property, and line.
4. Add `linkCallback(...)` plus `#custom:name` links when the guide should branch or mutate state.
5. Add `{{templateValues}}` when the note should reflect the current guide state.
6. Add `withChat()` when the step needs discussion.
7. Add `annotate(...)` or `annotateToLine(...)` when the text should point to exact visual targets.

Use that sequence as the default structure for a multi-step guide.

## Example source map

For complete examples, read `src/jvmMain/kotlin/afield/examples/slideshow_example.kt`:

- `simpleInteractiveExample()` is the minimal callback-and-template example.
- `exampleSlideShow()` is a traditional guided tour that moves through multiple boxes.
- `advancedSlideShowExample()` adds `annotate(...)` and `annotateToLine(...)` so a note can point at a box or an exact code line.
- `interactiveGuideExample()` combines Markdown notes, callbacks, templates, choices, slideshow IDs, and both local and shared chat.

## API summary

The core calls you will use most often are:

- `slideShow { ... }` to define the guide.
- `step("Title") { ... }` to define each stage.
- `markdownNote(...)` or `note(...)` to render the overlay.
- `selectBox(box, property = "code", line = ..., highlight = ...)` to steer the UI.
- `linkCallback("name") { ... }` for custom links such as `#custom:name`.
- `withChat()` or `withChat(id = "shared")` for discussion threads.
- `slideshowId("...")` when the slideshow needs a stable identity across sessions.
- `annotate(...)` and `annotateToLine(...)` for visual callouts.

Use these calls to define steps, move the UI, render the note, respond to reader input, and point at the relevant target.
