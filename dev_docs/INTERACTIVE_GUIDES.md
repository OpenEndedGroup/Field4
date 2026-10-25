# Interactive guides: developer map

The canonical authoring guide is `public/docs/interactive_guides.md`, including chat availability in its
"Setting up the chat server" section. `public/docs/guide_chat.md` was folded into that page by
`4e478e34c` on 2026-07-20; this line and `GUIDE_CHAT_QUICKSTART.md`'s first line went on pointing
at it until 2026-09-16. Runnable examples are in
`src/jvmMain/kotlin/afield/examples/slideshow_example.kt`.

Implementation ownership:

- `src/jvmMain/kotlin/afield/ui/slides/SlideShow.kt` — builders, steps, navigation, callbacks, template
  resolution, chat IDs, note messages, and per-symbol KDoc.
- `src/jsMain/kotlin/afield/app/client/Main.kt` — note/chat DOM and link handling.
- `src/jsMain/kotlin/afield/app/client/slideshowSupport.kt` — annotation geometry and text reveal.
- `src/commonMain/kotlin/afield/app/protocol/rpc.kt` — serialized note, annotation, selection, and chat payloads.
- `src/jvmMain/kotlin/afield/app/server/chat.kt` — retained chat proxy; the Python service was retired.

Keep examples and behavior in those locations. This page intentionally does not repeat the DSL.
