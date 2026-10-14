# Interactive Guides

A guide is a box that walks someone through a document they have not seen before. You write it
in the box, and the person following it gets notes, moving selections and callout lines over the
live document itself. A guide can:

- show a note next to a box,
- move the editor to a property and line,
- branch through callbacks,
- render live values into Markdown templates,
- attach a discussion thread,
- draw callout lines to boxes or exact code lines.

A guide is a `slideShow { ... }` block. Each `step("...")` is one stage of the guide, and
`markdownNote(...)` in a step writes the note the reader sees.

A guide's notes and callout lines come and go with its steps. For marks that stay on the canvas,
such as a hand-drawn diagram beside the boxes it explains or arrows a box draws for itself, see
[Drawing and Notation](drawing_and_notation.md), the Excalidraw layer (`alt-d`) that is saved with
the document.

## A three-step guide

This box is a whole guide: three steps, each with a note and a link to the next one. Run it and
its first note opens beside the box.

```diffstatic_runner {data-lang=kotlin screenshot="assets/interactive_guides/three_step_tour.png" wait="8" capture="box" outputs="none" embed=true name="three-step-tour" w="560" h="140" padding="28" alt="The guide box on the left with a green running dot, and beside it the first note: the heading A three-step tour, the sentence saying the note belongs to the box on the left and to follow the link at the bottom, and a Next link."}
:::static
val guide = thisBox

val show = slideShow {
    step("Hello") {
        markdownNote(
            """
            ## A three-step tour
            This note belongs to the box on the left.
            Follow the link at the bottom to move on.

            [[next]]
            """.trimIndent(),
            animated = true
        )
        selectBox(guide)
    }
    step("The code") {
        markdownNote(
            """
            ## Each step is a `step(...)`
            The editor now shows this box's code.

            [[back]] [[next]]
            """.trimIndent()
        )
        selectBox(guide, property = "code", line = 5, highlight = 5)
    }
    step("Done") {
        markdownNote(
            """
            ## That is the whole tour
            [[home:Start again]] [[finish]]
            """.trimIndent()
        )
    }
}

runBlocking { show.start() }
```

`slideShow { ... }` builds the guide and stores it on the box; nothing is shown until
`show.start()` opens the first step. `animated = true` types the first note in rather than showing
it all at once. `selectBox(box)` selects a box, and with `property`, `line` and `highlight` it also
opens the editor on that property, scrolls to a line and marks it. Use `selectBoxByName("name")`
for a box you do not have in a variable; it searches the whole document.

## Moving between steps

The reader moves through a guide with links in its notes. A `[[...]]` shortcut in a Markdown note
becomes one of them:

| shortcut | goes to | label |
| --- | --- | --- |
| `[[next]]`, `[[start]]` | the next step | Next →, Start → |
| `[[back]]`, `[[previous]]`, `[[prev]]` | the previous step | ← Back |
| `[[home]]` | the first step | Home |
| `[[stop]]`, `[[finish]]` | closes the guide | Finish |

Give a link your own label after a colon: `[[next:Onward →]]`, `[[home:Start again]]`. The
shortcuts are ordinary links to `#slideshow:next`, `#slideshow:previous`, `#slideshow:home` and
`#slideshow:stop`, so a note written in HTML with `note(...)` can use those directly, and
`#slideshow:2` jumps to a step by number, counting from 0.

From code the same moves are `show.next()`, `show.previous()`, `show.home()`, `show.stop()` and
`show.goToStep(n)`, all called from a coroutine. `show.hasNext()` and `show.hasPrevious()` say
whether there is anywhere to go. This guide opens on its second step, whose note uses each kind of
link, and marks line 13, the line that writes those links:

```diffstatic_runner {data-lang=kotlin screenshot="assets/interactive_guides/navigation.png" wait="5" capture="window-editor" outputs="none" embed=true name="guide-navigation" w="240" h="60" padding="12" alt="The Field window. On the left the small guide box, selected, and beside it the second step's note, headed Moving between steps, ending in four links: Back, First step, Onward and Finish. On the right the editor shows the guide's code with a red mark in the gutter at line 13, the line holding the four bracket shortcuts."}
:::static
val guide = thisBox

val show = slideShow {
    step("Start") {
        markdownNote("## Step one\n[[next]]")
    }
    step("Links") {
        markdownNote(
            """
            ## Moving between steps
            Step two of three. Each link below came from a `[[...]]` shortcut.

            [[back]] [[home:First step]] [[next:Onward →]] [[stop]]
            """.trimIndent()
        )
        selectBox(guide, property = "code", line = 13, highlight = 13)
    }
    step("End") {
        markdownNote("## The last step\n[[back]] [[finish]]")
    }
}

runBlocking { show.goToStep(1) }
```

A document can hold several guides. The links in a note always drive the guide that opened it.

## Doing something as a step opens or closes

`onEnter { ... }` runs each time the reader arrives at a step, before its note is shown, and
`onExit { ... }` runs as they leave it, including when the guide is stopped. Use them to launch the
box a step is about and stop it again, or to update a value the note shows. Both blocks may
suspend. The [slideshow_guide](agent_transcripts.md#slideshow_guide) starting point counts visits
to one of its steps this way, and shows the count in that step's note with a `{{visits}}`
template, described next.

## Add callbacks and live template values

An interactive step uses a `#custom:name` link in the note body, a `linkCallback("name") { ... }` handler in the step to receive it, and `{{propertyName}}` template expansion for the live values. Together they cover choices, branching, and notes that update while the guide stays on the same step.

```diffstatic_runner {data-lang=kotlin screenshot="assets/interactive_guides/callbacks_and_templates.png" wait="1.5" capture="box" outputs="none" embed=true name="callbacks-and-templates" w="560" h="140" padding="28" alt="The guide box beside its note, headed Interactive Guides with Callbacks. Three blue callback links are listed as bullets: Increment, Choose performance mode, Choose quality mode. Above them the line reads Version: 1.0.0, and the footer reads Count: 3 and Choice: Balanced Mode."}
:::static
var clickCount = 3
var userChoice = "Balanced Mode"
val version = "1.0.0"
val host = thisBox
//%-

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

A template value can come from `this.properties[...]`, from a Kotlin variable the box has already run, or from the shared template helpers the slideshow runtime exposes. A variable becomes visible to templates once the code declaring it has finished running, so the [`//%-` line](running_code.md#the-fragment-break) after the declarations matters here: it runs `version`, `clickCount` and `userChoice` as their own fragment before `show.start()` renders the first note. Without it the first note shows those slots empty.

When a callback changes state, call `show.goToStep(show.currentStepIndex)` to rerender the current step immediately. That updates `{{clickCount}}` and `{{userChoice}}` without leaving the slide.

A value that comes from a box property or a script variable is inserted as text: `<`, `>`, `&` and quotes are escaped, so a property holding `<b>hi</b>` shows those characters rather than bold text. Put markup in the note itself, not in the value.

## Annotations and code callouts

Annotations let a note point at an exact target.

- `annotate(...)` points from the note to a box.
- `annotateToLine(...)` points to an exact property and line inside the editor.

Use them when a sentence is about one box, one property, or one line.

```diffstatic_runner {data-lang=kotlin screenshot="assets/interactive_guides/annotations.png" wait="5" capture="window-editor" outputs="none" embed=true name="guide-annotations" w="240" h="60" padding="12" alt="The Field window. On the left the small guide box, selected, with a green arrowhead at its top-right corner, and beside it the note headed Annotation Lines with its two bullets. An orange dashed line runs from the note across the canvas and curves down to the editor's left edge, ending at the top of line 17. On the right the editor shows the snippet, with a red mark in the gutter at line 17, the selectBox call."}
:::static
val host = thisBox
host.name = "guide-annotations"

val show = slideShow {
    step("Annotations") {
        markdownNote(
            """
            ## Annotation Lines
            Guides can point at whole boxes and exact code lines.

            - the green line targets the box,
            - the orange dashed line targets line 17 in this guide box.
            """.trimIndent(),
            animated = true
        )

        selectBox(host, property = "code", line = 17, highlight = 17)

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
            line = 17,
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

## Per-slide chat

A step can carry its own discussion thread.

- `withChat()` creates a thread for that slide.
- `withChat(id = "shared-id")` shares one thread across repeated guide instances.

```diffstatic_runner {data-lang=kotlin screenshot="assets/interactive_guides/slide_chat.png" wait="1.5" capture="box" outputs="none" embed=true name="slide-chat" w="560" h="140" padding="28" alt="The guide box beside a note headed Per-slide Chat, giving the two withChat forms in inline code, then a Back and Next link pair, a horizontal rule, and a collapsed Discussion disclosure with its triangle still closed."}
:::static
println("slide chat ready")

val host = thisBox

val show = slideShow {
    slideshowId("docs-guide-chat")

    step("Slide discussion") {
        markdownNote(
            """
            ## Per-slide Chat
            Use `withChat()` when a slide should carry its own discussion.

            Use `withChat(id = "templateId")` when multiple template instances should share one thread.

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

The capture above shows the collapsed Discussion UI. The Python chat-service prototype has
been retired; `withChat()` and its UI remain available for a later redesign. Without a compatible
external backend, message loads and posts fail, while the rest of the slideshow still renders.

### Setting up the chat server

Field no longer bundles a chat server. Its retained JVM proxy uses `CHAT_SERVER_URL`, defaulting
to `http://localhost:5002`, if a compatible service is provided separately. A browser identity can
still be selected with `?file=myguide&username=student1`; the default is `anonymous`.

The retained bridge is described in
[`dev_docs/GUIDE_CHAT_QUICKSTART.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/GUIDE_CHAT_QUICKSTART.md).

## Notes outside a guide

The note overlay works without a guide too. `showMarkdownNote(...)` (or `showNote(...)` for HTML)
opens a note beside the box that calls it, with the same `{{...}}` templates, and `hideNote()`
closes it. In a step, `anchorNoteTo(box)` or `markdownNote(..., anchorBox = box)` places the note
beside a different box from the guide's own, and `persistent = true` gives the note a × button so
the reader can close it.

## Growing a guide

Most guides are written in this order:

1. `slideShow { ... }` with one or more `step(...)` blocks, and `[[next]]` links between them.
2. `markdownNote(...)` in each step, saying what stage the reader is at.
3. `selectBox(...)`, so the reader is looking at what the note describes. Stop here and you have a working guide.
4. `linkCallback(...)` and `#custom:name` links, when a step should branch or change state.
5. `{{templateValues}}`, when a note should show the current guide state.
6. `onEnter { }` and `onExit { }`, when a step should start or stop something.
7. `annotate(...)` or `annotateToLine(...)`, when a sentence is about one exact target.
8. `withChat()`, when a step needs discussion.

Steps 4 to 8 go only on the steps that ask for them.

## A guide to open

The [slideshow_guide](agent_transcripts.md#slideshow_guide) starting point on the welcome screen
is a four-step guide over two small boxes. It uses selections, both kinds of callout line,
`onEnter`, templates and a callback. Open it, run `tour`, and follow the note:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1500x620" clip="0,0,1500,620" hide-toasts=true video="assets/interactive_guides/slideshow_guide.mp4" fps="10" transcript="dev_docs/agents/transcripts/slideshow_guide.yaml" padding="0" wait="0" timeout="240" embed=true hidden=true name="guide-slideshow-guide" alt="The slideshow_guide document in a Field window: tour, numbers and squares on the left, the editor on the right. Tour's note types itself in and Start is clicked; numbers is selected and a green line runs from the note to it, with one visit counted; squares opens in the editor with line 3 marked and a dashed line to it; the last note's star link is clicked twice and the count reads 2; Finish closes the note."}
// The transcript built numbers, squares and tour and ran them, so tour's first note is open.
fun follow(href: String) {
    ui.clickSelector("#slide-note-overlay a[href='$href']")
    ui.pause(3500)
}
ui.waitForSelector("#slide-note-overlay a[href='#slideshow:next']", 15000.0)
ui.pause(1500)
follow("#slideshow:next")
follow("#slideshow:next")
follow("#slideshow:next")
follow("#custom:star")
follow("#custom:star")
follow("#slideshow:stop")
```

For more examples, read
[`slideshow_example.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/examples/slideshow_example.kt):

- `simpleInteractiveExample()` is the minimal callback-and-template example.
- `exampleSlideShow()` is a traditional guided tour that moves through multiple boxes.
- `advancedSlideShowExample()` adds `annotate(...)` and `annotateToLine(...)` so a note can point at a box or an exact code line.
- `interactiveGuideExample()` combines Markdown notes, callbacks, templates, choices, slideshow IDs, and both local and shared chat.

## API summary

The calls you will use most often:

- `slideShow { ... }` to define the guide, and `show.start()` to open it.
- `step("Title") { ... }` to define each stage.
- `markdownNote(...)` or `note(...)` to write the note.
- `[[next]]`, `[[back]]`, `[[home]]`, `[[finish]]`, or `#slideshow:` links, to move between steps.
- `show.next()`, `show.previous()`, `show.goToStep(n)`, `show.stop()` to move from code.
- `selectBox(box, property = "code", line = ..., highlight = ...)` or `selectBoxByName(...)` to steer the editor.
- `onEnter { }` and `onExit { }` to act as a step opens and closes.
- `linkCallback("name") { ... }` for custom links such as `#custom:name`.
- `annotate(...)` and `annotateToLine(...)` for callout lines.
- `withChat()` or `withChat(id = "shared")` for discussion threads.
- `slideshowId("...")` when the slideshow needs a stable identity across sessions.
- `showMarkdownNote(...)` and `hideNote()` for a note without a guide.
