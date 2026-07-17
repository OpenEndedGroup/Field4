# Interactive Guides with Callbacks, Templates, and Chat

This is the cross-cutting overview of Field's guide/slideshow DSL. The authoritative
per-symbol reference lives as KDoc on `src/jvmMain/kotlin/afield/SlideShow.kt`
(class/function docs for `slideShow{}`, `linkCallback()`, `withChat()`, template
`{{prop}}` syntax, and the `#custom:` / `#slideshow:` link formats). Runnable
end-to-end examples are in `src/jvmMain/kotlin/afield/examples/slideshow_example.kt`
(`simpleInteractiveExample`, `interactiveGuideExample`, `exampleSlideShow`,
`advancedSlideShowExample`).

## Overview

Field's guide system supports:

1. **Custom Link Callbacks** — links that execute Kotlin code when clicked.
2. **Template System** — `{{propertyName}}` placeholders that display live data.
3. **Per-Slide Chat (opt-in)** — discussion threads for questions and feedback.

## Per-Slide Chat

Chat is opt-in per slide. Call `withChat()` in the slide builder to enable it; only
slides that call it render a "▸ Discussion" disclosure. Messages persist on the chat
server and are visible to later viewers; usernames come from the `?username=` URL
parameter (default `anonymous`).

```kotlin
slideShow {
    slideshowId("my-tutorial-v1")   // custom ID -> chat persists across sessions
    step("Introduction") {
        note("<h2>Welcome!</h2><p>Ask questions in the Discussion below.</p>")
        withChat()                   // enable chat for this slide
    }
    step("No Chat") {
        note("<h2>No discussion here</h2>")
        // no withChat() -> no Discussion section
    }
}
```

To post, expand "▸ Discussion", type in the message box, and press Enter/Return
(there is no Send button; Shift+Enter inserts a newline). `withChat(id = "templateId")`
shares one thread across template instances; any other string is a custom shared-chat
ID.

**See [../public/docs/guide_chat.md](../public/docs/guide_chat.md)** (and the runbook
[GUIDE_CHAT_QUICKSTART.md](GUIDE_CHAT_QUICKSTART.md)) for chat server setup, the API
reference, storage format, and troubleshooting.

## Custom Link Callbacks

Links of the form `#custom:callbackName` run Kotlin registered with
`linkCallback("callbackName") { ... }`. The callback receives `here` (the host box)
and `show` (the slideshow instance).

```kotlin
var count = 0
this.properties["count"] = count

slideShow {
    step("Counter") {
        note("""
            <p>Count: {{count}}</p>
            <p><a href="#custom:increment">Increment</a></p>
        """)
        linkCallback("increment") {
            count++
            here.properties["count"] = count
            show.goToStep(show.currentStepIndex)  // refresh to show new value
        }
    }
}
```

## Template System

`{{propertyName}}` placeholders are substituted just before the HTML is sent to the
browser, so values are always fresh. Templates resolve against box properties
(`this.properties[...]`), Kotlin variables in scope, and the global `templates` /
`knownValues` maps.

## Built-in Navigation Links

- `#slideshow:next` — next step
- `#slideshow:previous` — previous step
- `#slideshow:home` — first step
- `#slideshow:start` — start slideshow
- `#slideshow:stop` — stop slideshow

## Notes

- Existing guides keep working unchanged; interactivity is additive — replace static
  text with `{{...}}`, store values in `this.properties`, and register `linkCallback`s.
- Template substitution is a single string-replacement pass per step display, and
  callbacks only run when their link is clicked.
- Callbacks execute server-side in a coroutine with full access to the Field
  environment (they may `suspend`); the browser intercepts `#custom:` / `#slideshow:`
  clicks and forwards them, suppressing default navigation.
