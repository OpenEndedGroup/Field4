# Slideshow Feature Documentation

The canonical reference for Field's slideshow feature: the authoring DSL, the
runtime, and how the pieces fit together. For the annotation/line-drawing and
text-animation sub-feature see [SLIDESHOW_ANNOTATIONS.md](SLIDESHOW_ANNOTATIONS.md);
for embedding images see [SLIDESHOW_IMAGES.md](SLIDESHOW_IMAGES.md).

## Overview

The slideshow functionality provides a way to create guided tours through Field
documents. This is ideal for:
- Onboarding new users
- Creating interactive tutorials
- Documenting complex workflows
- Teaching Field concepts step-by-step

Key capabilities:
1. **Kotlin DSL for slideshow authoring** — clean, type-safe API
2. **Box selection & navigation** — select boxes and scroll to specific lines
3. **Rich notes** — HTML or markdown content with links, images, and styling
4. **Interactive navigation** — click links to move between steps
5. **Lifecycle callbacks** — run code on entering or leaving a step
6. **Choose-your-own-adventure** — non-linear navigation via step-jump links
7. **Custom link callbacks and per-slide chat** — see `withChat`/`linkCallback`

## Getting Started

Create a box that holds the slideshow script and run it:

```kotlin
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

// Get references to your boxes (by reference or by name)
val box1 = root.children.first { it.name == "Box 1" }

val tour = slideShow {
    step("Welcome") {
        note("""
            <h2>Welcome to My Field Document!</h2>
            <p>This tour will show you the key features.</p>
            <p><a href="#slideshow:next">Start Tour →</a></p>
        """)
        selectBox(this@slideShow)
    }

    step("First Feature") {
        note("<p>Check out line 1.</p><p><a href='#slideshow:next'>Next →</a></p>")
        selectBox(box1, property = "code", line = 1)
    }

    step("Second Feature") {
        note("<p>Line 10 is where the magic happens.</p><p><a href='#slideshow:stop'>Finish</a></p>")
        // selectBoxByName searches recursively and opens parent disclosure areas
        selectBoxByName("Box 2", property = "code", line = 10, highlight = 10)
    }
}

// Start automatically...
GlobalScope.launch { tour.start() }
// ...or add control actions to the box's actions menu:
this.installSlideShowControls()
```

See `src/jvmMain/kotlin/afield/examples/slideshow_example.kt` for comprehensive
examples (multi-step tours, interactive navigation, lifecycle callbacks, control
helpers).

## DSL Reference

### Creating a Slideshow

```kotlin
fun Box.slideShow(init: SlideShowBuilder.() -> Unit): SlideShowInstance
```

Creates and returns a slideshow instance attached to the box.

### Defining Steps

```kotlin
fun step(title: String, init: SlideStepBuilder.() -> Unit)
```

Available within `SlideStepBuilder`:

#### `note(html: String, persistent: Boolean = false, anchorBox: Box? = null, animated: Boolean = false)`

Sets the note content (HTML or markdown — see `SlideMarkdown` in `SlideShow.kt`).
If `persistent = true`, the note includes a close button and won't auto-hide.
`anchorBox` anchors the note next to a different box than the slideshow host.

When `animated = true`, the client's `show-slide-note` handler reveals the note
letter-by-letter via `animateSlideNoteText()`.
See [SLIDESHOW_ANNOTATIONS.md](SLIDESHOW_ANNOTATIONS.md#text-animation) for details.

```kotlin
note("""
    <h3>Step Title</h3>
    <p>Description...</p>
    <a href="#slideshow:next">Next</a>
""")
```

#### `selectBox(box: Box, property: String = "code", line: Int? = null, highlight: Int? = null)`

Selects a box and optionally scrolls/highlights a line.

#### `selectBoxByName(name: String, property: String = "code", line: Int? = null, highlight: Int? = null)`

Selects a box by searching for it by name (recursively through all children,
starting from `hostBox.root`). If the box is nested, the slideshow will
automatically open parent disclosure areas and scroll it into view.

#### `onEnter(action: suspend () -> Unit)` / `onExit(action: suspend () -> Unit)`

Code to run when entering / leaving this step.

#### `annotate(...)` / `annotateToLine(...)`

Draw annotation lines from the note to a box or a specific code line. Documented
in [SLIDESHOW_ANNOTATIONS.md](SLIDESHOW_ANNOTATIONS.md).

#### `withChat(id: String? = null)` / `linkCallback(name, action)`

Enable a per-slide discussion thread, or register a handler for a
`<a href="#custom:name">` link. See the KDoc in `SlideShow.kt`.

### Controlling a Slideshow

```kotlin
val show: SlideShowInstance = box.slideShow { ... }

show.start()           // Start from beginning
show.next()            // Go to next step
show.previous()        // Go to previous step
show.goToStep(index)   // Jump to specific step
show.home()            // Return to first step
show.stop()            // Stop and hide notes

show.currentStep       // Current step or null
show.currentStepIndex  // Current index
show.hasNext()         // Check if there's a next step
show.hasPrevious()     // Check if there's a previous step
```

### Helper Functions

```kotlin
box.selectAndEdit(property = "code", line = 10, highlight = 10)
box.showNote("<p>A quick note</p>", persistent = false)
hideNote()
box.installSlideShowControls()   // adds slideshow control actions to the box menu
```

## Note Content & Navigation Links

Notes accept full HTML with inline styles (or markdown, rendered by
`SlideMarkdown`). Use links for navigation:

```html
<!-- Navigation links -->
<a href="#slideshow:next">Next →</a>
<a href="#slideshow:previous">← Previous</a>
<a href="#slideshow:start">Restart</a>
<a href="#slideshow:home">Home</a>
<a href="#slideshow:stop">Exit Tour</a>

<!-- Jump to a specific step (0-indexed) -->
<a href="#slideshow:5">Go to step 5</a>

<!-- Custom callback link (see linkCallback) -->
<a href="#custom:myCallback">Do something</a>

<!-- Images from the project directory (see SLIDESHOW_IMAGES.md) -->
<img src="/project/myimage.png" alt="Description" style="max-width: 100%;">
```

Markdown notes can use `[[next]]`, `[[previous]]`, `[[home]]`, `[[start]]`,
`[[stop]]` / `[[finish]]` shortcuts (optionally `[[next: Custom label]]`), which
`SlideMarkdown` rewrites into the `#slideshow:` links above.

## Styling

The slide-note overlay (`#slide-note-overlay`) is styled in
`src/commonMain/resources/field.css` (rules starting around `#slide-note-overlay`):
semi-transparent dark background with a blur effect, a custom scrollbar, and
responsive max-width. By default the overlay is positioned next to the anchored/
selected box by `positionSlideNoteOverlay()` (in `Main.kt`); it falls back to a
fixed top-right position (the `.fallback` class) only when there is no anchor box.

There is no separate `slideshow.css`; customize by editing the
`#slide-note-overlay` rules in `field.css`.

## Architecture

### Component overview

```
┌─────────────────────────────┐        ┌──────────────────────────────┐
│  Browser (JS)               │        │  Server (JVM / Kotlin)       │
│                             │        │                              │
│  Main.kt (message handlers) │◄──────►│  Server.kt ("slideshow-      │
│    + slideshowSupport.kt    │  WS    │    command" handler)         │
│         │                   │        │        │                     │
│         ▼                   │        │        ▼                     │
│  #slide-note-overlay (HTML) │        │  SlideShow.kt (DSL + runtime,│
│  #slide-annotation-layer    │        │    SlideShowInstance)        │
│  Monaco editor              │        │  Box (state)                 │
└─────────────────────────────┘        └──────────────────────────────┘
```

### Message flow

- **Start / advance:** `SlideShowInstance.goToStep(n)` (in `SlideShow.kt`) sends
  `show-slide-note` (and, when a box is targeted, `select-and-edit-box`) over the
  WebSocket. `Main.kt`'s handlers build/update the overlay, wire up link handlers,
  and select/scroll the target box in Monaco.
- **Navigate via link:** clicking `<a href="#slideshow:next">` in `Main.kt` sends
  a `slideshow-command` message; `Server.kt`'s `slideshow-command` handler finds
  the active slideshow and calls `next()`/`previous()`/`start()`/`home()`/`stop()`,
  a `callback:<name>` (`executeCallback`), or a numeric `goToStep(n)`.
- **Chat sub-feature:** per-slide discussion uses `load-chat-messages` /
  `post-chat-message` (server: `src/jvmMain/kotlin/afield/server/chat.kt`) and
  `chat-messages-loaded` / `chat-message-posted` (client: `Main.kt`).

### Data structures (source of truth: `SlideShow.kt`)

```
SlideShowInstance          SlideStep
├─ hostBox: Box            ├─ title, noteHtml, noteAnchorBox
├─ steps: List<SlideStep>  ├─ targetBox, targetProperty, targetLine, highlightLine
├─ slideshowId: String     ├─ persistent, animated, withChat, customChatId
├─ currentStepIndex        ├─ annotations: List<SlideAnnotation>
└─ start/next/previous/    ├─ onEnter / onExit
   goToStep/home/stop      └─ linkCallbacks: Map<String, ...>
```

### Message types (source of truth: `src/commonMain/kotlin/rpc.kt`)

```
SelectAndEditBox(uid, property="code", scrollToLine=null, highlightLine=null)
ShowSlideNote(html, uid="", persistent=false, animated=false,
              annotations=emptyList(), slideshowId="", slideNumber=-1)
HideSlideNote(dummy=true)
SlideShowCommand(command)   // "next"|"previous"|"start"|"home"|"stop"
                            //   |"callback:<name>"|<step#>
SlideAnnotation(id, targetUid, targetProperty=null, targetLine=null,
                color="rgba(128,255,164,0.7)", strokeWidth=2.0, dashed=false)
```
(The chat sub-feature adds `PostChatMessage`, `LoadChatMessages`, `ChatMessage`,
`ChatMessagesLoaded`, `ChatMessagePosted`.)

### File map

```
src/commonMain/kotlin/rpc.kt                       Message / data-class types
src/jvmMain/kotlin/afield/SlideShow.kt             DSL + runtime (SlideShowInstance)
src/jvmMain/kotlin/afield/examples/slideshow_example.kt   Examples
src/jvmMain/kotlin/afield/Server.kt                "slideshow-command" handler
src/jvmMain/kotlin/afield/server/chat.kt           Chat handlers
src/jsMain/kotlin/afield/Main.kt                   Client message handlers, overlay
src/jsMain/kotlin/afield/slideshowSupport.kt       Annotation + text-animation client code
src/commonMain/resources/field.css                 #slide-note-overlay styling
src/commonMain/resources/index.html                Loads field.css (no slideshow.css)
```

## Best Practices

1. **Keep steps focused** — one concept per step.
2. **Use clear navigation** — always provide next/previous links.
3. **Test the flow** — run through the tour as a new user would.
4. **Provide context** — explain what the user is looking at.
5. **Use highlighting** — draw attention to specific lines of code.
6. **Add callbacks** — use `onEnter`/`onExit` for dynamic content.
7. **Consider branching** — create different paths for different goals.

## Troubleshooting

**Notes don't appear**
- Check the browser console for errors.
- Verify `field.css` is loaded (it contains the `#slide-note-overlay` styles).
- Ensure the HTML is well-formed.

**Box selection doesn't work**
- Verify the box exists and its name/UID is correct.
- Ensure the box is visible.

**Line scrolling issues**
- Line numbers are 1-indexed.
- Monaco editor must be loaded and the property must contain code.
