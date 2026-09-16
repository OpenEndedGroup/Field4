# Guide chat development runbook

The user guide and example are the "Per-slide chat" section of
`public/docs/interactive_guides.md`. Exact bridge behavior is documented
on `chat()` in `src/jvmMain/kotlin/afield/server/chat.kt`; chat-ID resolution is documented on the
two `withChat` APIs in `src/jvmMain/kotlin/afield/SlideShow.kt`.

## Run locally

Install and start the optional Flask service:

```bash
cd server
pip install -r requirements.txt
python3 guide_chat_server.py
```

In another shell, start Field with the configured application task:

```bash
./gradlew runJvm
```

Configuration:

- `CHAT_SERVER_PORT` and `CHAT_SERVER_DEBUG` configure the Flask process.
- `CHAT_SERVER_URL` configures Field's JVM proxy and defaults to `http://localhost:5002`.
- `?username=<name>` configures the browser identity and defaults to `anonymous`.

If the service uses a non-default port, both processes must agree:

```bash
CHAT_SERVER_PORT=5003 python3 server/guide_chat_server.py
CHAT_SERVER_URL=http://localhost:5003 ./gradlew runJvm
```

Check `curl http://localhost:5002/health`, then run the compiled slideshow example or the public
example. Open the same document in two tabs with different `username` parameters to verify Field's
post broadcast and reload path.

## Ownership

- `server/guide_chat_server.py` — HTTP and Socket.IO API, JSON persistence under `chat_data/`.
- `src/jvmMain/kotlin/afield/server/chat.kt` — Field RPC-to-HTTP proxy and Field-client broadcast.
- `src/jvmMain/kotlin/afield/SlideShow.kt` — per-step opt-in and thread-ID selection.
- `src/jsMain/kotlin/afield/Main.kt` — widgets, posting, reload, and rendering.
- `src/commonMain/kotlin/rpc.kt` — serialized Field messages.
- `src/jvmMain/kotlin/afield/examples/slideshow_example.kt` — compiled example.

The Field browser does not use Flask's Socket.IO endpoint; live updates between Field tabs are
triggered by the JVM bridge's `chat-message-posted` broadcast. The service has unrestricted CORS,
no authentication or rate limiting, development debug defaults, and full-file JSON rewrites. Keep
it on a trusted network unless those boundaries are changed.
