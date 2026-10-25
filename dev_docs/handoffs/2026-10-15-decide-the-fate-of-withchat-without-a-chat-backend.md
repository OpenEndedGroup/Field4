# Decide the fate of withChat without a chat backend

- **Handoff:** `57422948-f3a1-4e03-90a5-6c5e6667f7ce` (project)
- **Filed from:** https://openendedgroup.com/field4/interactive_guides/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close 57422948 "what happened"`

`public/docs/interactive_guides.md`, "Per-slide chat" and "Setting up the chat server", say:

> The capture above shows the collapsed Discussion UI. The Python chat-service prototype has been retired; `withChat()` and its UI remain available for a later redesign. Without a compatible external backend, message loads and posts fail, while the rest of the slideshow still renders.

> Field no longer bundles a chat server. Its retained JVM proxy uses `CHAT_SERVER_URL` ...

The public page documents an API whose backend does not ship, so every reader who tries it gets failing loads and posts. Decide (Marc): ship a minimal built-in chat backend, hide `withChat()` until the redesign, or make `withChat()` render a clear "chat unavailable" state when no backend answers. Delete both paragraphs (and the chat items in "Growing a guide" / "API summary" if the API is hidden) in the same change.
