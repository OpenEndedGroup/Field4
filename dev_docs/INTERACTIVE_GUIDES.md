# Interactive guides: developer map

The canonical authoring guide is `public/docs/interactive_guides.md`; chat setup is
`public/docs/guide_chat.md`. Runnable examples are in
`src/jvmMain/kotlin/afield/examples/slideshow_example.kt`.

Implementation ownership:

- `src/jvmMain/kotlin/afield/SlideShow.kt` — builders, steps, navigation, callbacks, template
  resolution, chat IDs, note messages, and per-symbol KDoc.
- `src/jsMain/kotlin/afield/Main.kt` — note/chat DOM and link handling.
- `src/jsMain/kotlin/afield/slideshowSupport.kt` — annotation geometry and text reveal.
- `src/commonMain/kotlin/rpc.kt` — serialized note, annotation, selection, and chat payloads.
- `src/jvmMain/kotlin/afield/server/chat.kt` and `server/guide_chat_server.py` — optional chat path.

Keep examples and behavior in those locations. This page intentionally does not repeat the DSL.
