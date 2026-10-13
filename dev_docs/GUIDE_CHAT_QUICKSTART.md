# Guide chat: retained integration

The Python guide-chat service was retired from the repository in October 2026. There is no
bundled service to start. `withChat` and the existing frontend and JVM bridge remain for a later
redesign; without a compatible external backend, the Discussion UI appears but loading and
posting messages fail. The rest of the slideshow still works.

`CHAT_SERVER_URL` configures the JVM proxy and defaults to `http://localhost:5002`.
`?username=<name>` selects the browser identity and defaults to `anonymous`.

## Ownership

- `src/jvmMain/kotlin/afield/server/chat.kt` — Field RPC-to-HTTP proxy and Field-client broadcast.
- `src/jvmMain/kotlin/afield/SlideShow.kt` — per-step opt-in and thread-ID selection.
- `src/jsMain/kotlin/afield/Main.kt` — widgets, posting, reload, and rendering.
- `src/commonMain/kotlin/rpc.kt` — serialized Field messages.
- `src/jvmMain/kotlin/afield/examples/slideshow_example.kt` — compiled example.

The browser uses Field's JVM bridge; it does not connect directly to the former Flask Socket.IO
endpoint. The public authoring example remains in `public/docs/interactive_guides.md`.
