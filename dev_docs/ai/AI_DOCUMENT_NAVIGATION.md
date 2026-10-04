# Semantic navigation inside Field

AI4 and the backdoor share `DocumentBoxNavigateRequest`. Reuse a known UID from
`ListBoxesRequest` or a tool result; do not walk the canvas by screenshots or invoke
the command palette to locate an already known box.

- `{"uid":"…","surface":"output","channel":"geometry"}` opens that existing
  output disclosure and pans it into view. It preserves its DOM, viewer camera,
  X-ray, selection and user sizing. It does not open Monaco or execute a provider.
- `surface="box"` centers the box itself.
- The backward-compatible default `surface="editor"` opens `property` (default
  `code`) at optional `line`.

Blank output channels resolve only when exactly one exists. An ambiguous or
missing channel returns the available channel names, with no navigation. Missing
outputs require the ordinary source-box launch or its document-defined display
tool, not a fabricated viewer. For example, call a document's target-editor tool,
then reveal its output channel.

The UID is resolved within the calling document. Requests use Field's existing
WebSocket transport and canvas/disclosure navigation. They are sent to connected
frontends of that document; a response confirms visibility in **one connected
frontend**, not necessarily the user's focused window and not correctness of the
rendered content. No frontend acknowledgement within five seconds is an explicit
failure. This is navigation, not a replacement for visual inspection.

Verification: `uv run --project . --group backdoor python tools/verify_document_navigation.py --port 9840`.
The test uses an isolated document and the real Three viewer; it checks offscreen
navigation, retained camera/X-ray/iframe, errors, editor behavior, disconnected
frontend, and cross-document UID rejection without making a model request.
