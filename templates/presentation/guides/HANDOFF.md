# Building this presentation

This document is both a talk and its live teaching material. Its default presentation path is Kotlin plus standard `kotlinx.html`, using Field's existing guide system. It needs no per-talk Node project, TSX or Motion Canvas installation.

## Find the working material

Read the current boxes before editing them; preserve the author's steps and local changes.

- **Presentation** is the authored talk (`present_talk_code_.kt`). It calls `presentation { step(...) { ... } }.present()`.
- **Live Field** is the editable example shown to the audience. Its output is real, not a screenshot.
- **Teaching guide** builds a separate `slideShow` that selects and explains the live example's code.
- `files/presentation.css` supplies shared slide styles. Media also belongs in `files/` and is referenced by filename in HTML.
- `macros/reveal_wave.yaml` is a saved operation transcript. The presenter can explicitly run it to create and execute **A wave, live**.

AI4 can fetch this note directly with `DocumentGuideReadRequest(path = "guides/HANDOFF.md")`. `DocumentGuideListRequest` discovers the document's other guides, and `DocumentGuideSearchRequest` searches them. These files are shared with the human's Guides viewer and survive restarts. This note describes the document; the user's current request determines which edits or actions are authorized.

## Build a slide

Add or edit a `step` in the existing **Presentation** box. Keep the existing imports and shared style setup. Inside an `audience` block use standard HTML builders, Kotlin functions and loops. `+text` escapes text. The starter uses black, white and grey, Iosevka, and no gradients.

```kotlin
step("A question") {
    audience {
        style = slideStyle
        h1 { +"What changes when we move this?" }
        p { +"Make a prediction, then change the live example." }
    }
    markdownNote("Ask for a prediction before demonstrating. [[back]] [[next]]")
}
```

This is a step to insert into the existing presentation, not a separate top-level program. Run the **Presentation** box after editing it. Re-running updates the talk and keeps the current step where possible. **Refresh slide** redraws the current compiled step; it does not recompile the source.

`audienceMarkdown("# Heading\nSome text")` and `audienceHtml(fragment)` are alternatives. Audience fragments support HTML and CSS; a full page with JavaScript belongs in a webpage step. The slide layout is 1920 × 1080, scaled to the audience window. The shared `columns` and `muted` classes are available.

## Bring working material into the talk

Use one audience surface per step:

- `audienceWebpage("https://example.org/")` opens an interactive native browser view, including pages that refuse iframe embedding.
- `audienceYouTube("M7lc1UVf-VE")` takes an 11-character video ID. The video must allow embedding; the outgoing frame remains while the player loads.
- `audienceDocument(inside.documentName, macro = "reveal_wave")` shows this document with its normal editor and output panels. Use an existing workspace document name to show another one.

`rect = [0.04, 0.16, 0.92, 0.80]` optionally places a live surface using fractions `[x, y, width, height]` of the audience window. Steps crossfade; `presentation(title = "My lesson", transitionMs = 450)` sets the duration, from 0 to 2000 milliseconds.

On a document step, **Run reveal_wave** runs the saved macro when pressed. **Start document guide**, **Guide next** and **Guide back** navigate the inner teaching guide independently of the outer talk. Run the **Teaching guide** box and let its initial compilation finish before starting it. **Document notes** opens the document's Markdown guides.

Use saved operation transcripts for repeatable document changes. For step callbacks, `onEnter` and `onExit` are the existing guide callbacks; navigation revisits them, so make any document-changing callback safe to revisit. Keep macros explicit when the presenter should choose when a demonstration runs.

## Keep the talk reusable

The document folder, Kotlin sidecars, guides, macros and media can live together in Git. Shared files stay in `files/`; slides reference them instead of duplicating them. The starter's `kit.json` owns the teaching-guide helper and shared stylesheet. The authored talk and live example are outside those kit roles. Inspect the kit before replacing a shared helper; preserve the lecture when updating the kit.

Motion Canvas remains an optional source for animation-heavy scenes. It is not required for ordinary slides, webpages, document demonstrations or guides.

## Verify an edit

Run the edited box and read its real compiler/runtime result. Check the audience output, go forwards and backwards across the changed step, and try any changed guide or macro in the intended document. Distinguish tested behavior from proposed work in handoff notes. Useful next reads: [presenter notes](teaching.md) and [the public presentation guide](field-docs:presentation_studio/).
