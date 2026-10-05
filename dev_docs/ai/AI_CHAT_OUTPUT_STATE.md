# AI4 chat size and reading position

The chat uses Field's existing disclosure output and transcript renderer. Its initial
size is bounded (up to 960 × 560 layout pixels, also limited by the viewport). A user's
manual resize overrides that initial cap. Repeated `open` calls during streamed output
must not reapply layout or scroll state to an already open pane.

Persist dimensions in layout pixels (`offsetWidth`/`offsetHeight`), not transformed
screen rectangles. A manual resize cancels queued auto-size passes. This state survives
updates and closing/reopening the disclosure within the client; it does not add
persistence across browser reloads.

Identical transcript updates preserve the message DOM. Changed transcripts retain known
image dimensions and carry the intended scroll offset through image loading. Pending
callbacks are scoped to their render and cancelled by reader input. Follow the newest
message only when the reader was already at the bottom or explicitly sent a message.

## Regression evidence

Run `uv run --project . --group backdoor python tools/run_ui_regressions.py --id ai-chat-resize`.
The fixture exercises the real Field renderer without any model request. It applies the
same inline dimensions as the native CSS resize grip, then verifies a 1080 × 820 pane
across streaming, close/reopen and a 0.5× transform. It also verifies unchanged-history
DOM reuse, image-heavy reading offsets, bottom following, reader interruption and draft
retention. This is a DOM resize regression, not a physical pointer-drag test.

On 2026-10-03, `ai-chat-resize`, `output-area-basics` and `viewer-panel-sizing` all passed
in the integration checkout. The captured chat output was visually inspected.
