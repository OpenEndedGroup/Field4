# Text frames (worktree prototype)

Text frames are ordinary graph boxes with Markdown on their canvas face. The direction is a canvas for
mind maps, shared documents and everyday work with people who do not write code, with AI4 available in
that work. This prototype establishes editable, persistent text objects; it does not yet provide a
character-level collaborative document editor.

## Writing

Press **Shift+N**, or choose **New Text Frame** in the palette. Double-click the text, press Enter on
a focused reader. Write Markdown as plain text, in the same Iosevka font and size as the normal editor. Click away,
press Escape, or press Cmd/Ctrl+Enter to show formatted text. Native spellcheck stays enabled, with
Electron's existing spelling-suggestion context menu. Spellcheck dictionaries/languages follow the
browser or OS configuration.

Shift-select boxes and press **g** to connect them as usual. Alt-click a text frame to open its marking
menu; **down** chooses **New text frame below**, which creates a child at the same X and width, 32 world units below the bottom,
with matching typography. For other box kinds this entry is in their Shift+Alt-click custom menu.
A new frame receives keyboard focus immediately. **Right** chooses **Open settings**: this force-opens
the normal editor on the `textFrame` YAML property, even with automatic editor opening switched off,
and fills missing defaults. Existing YAML comments and unknown keys are preserved.

Text starts at the frame's configured padding, without a title or button bar. The outline appears
on selection or while editing, and the background is pure black while editing. Unselected reading
text is bare on the canvas; an overset **+** remains visible when a fixed frame clips content. Clicking
it opens the full source in a scrollable editor. Selecting a frame follows the existing editor-on-selection
setting; turn that off for an inline-only writing session.

Growing frames fit the **formatted Markdown**, while typing as well as on release. Raw source can
have a different height: it scrolls inside that same frame rather than changing the persistent layout.
The frame can expand or contract to its typeset text, down to `minHeight` (80 world units by default).
Use `sizing: fixed` for a manually sized frame that clips; width stays fixed in either mode.

By default, expansion pushes colliding text frames down, leaving a 32-unit gap, and propagates through
a column. Contraction pulls frames wholly below the old bottom upward, preserving their spacing;
partly overlapping frames and other columns stay put. A frame in the chain moves once. Reflow stays
within the same nesting context, skips hidden frames, and leaves regular/proxy boxes alone. Set
`reflow: false` to resize this frame without moving others.

## Box properties

- `box_kind = "text-frame"` identifies the presentation. Existing boxes can be converted by setting it.
- `contents` holds the exact Markdown. It is persisted inline in `metadata.edn`, synchronized through the
  normal property protocol, and accessible from Kotlin as `thisBox.contents` (or `contents` on the
  implicit Box receiver). It is local to this box, not inherited from a parent.
- `contents_language = "markdown"` and `default_property = "contents"` let the existing property editor
  edit the source too. `code` remains available independently.
- `textFrame` holds YAML, edited in the normal property editor. Defaults are:

```yaml
fontFamily: '"Field Etoile", serif'
fontSize: 18
fontWeight: 400
lineHeight: 1.5
padding: 16
sizing: grow
connectionBorderLength: 100
autocomplete: false
minHeight: 80
reflow: true
```

The reading font is **Field Etoile**, the bundled Iosevka Etoile text face with Field's reverse-slashed zero.
Light, regular and bold each have real upright and italic webfont faces; Markdown code spans keep Field's code font.
The reproducible webfont derivation and license are documented in `tools/fonts/README.md`.

Connections reveal a short portion of the frame border on each side of their attachment, following
the rounded corners. Routing starts 10 screen pixels inside the source's bottom-right corner and
the destination's top-left corner, then trims the connecting ray to each frame boundary. Each ordinary
box endpoint still uses its centre. Frame curve radii respect the room along the endpoint normals so
wide frames with a small gap do not force large looping turns. The resulting bend radius is then
increased by 15% for a small switchback. `connectionBorderLength` sets the border distance in screen pixels (default 100,
range 0–200; 0 hides it), independent of canvas zoom. These segments follow the connection's color
and selection visibility; ordinary boxes and nested proxy viewport terminations keep their existing rendering.

Existing JSON settings remain readable and become YAML when opened through **Open settings**.
Invalid or partly typed YAML retains the last valid appearance and gets a diagnostic in the property
editor. YAML parsing uses the bundled MIT-licensed js-yaml 4.1.1 CORE_SCHEMA (`vendor/js-yaml/`). Typography uses CSS font family syntax; font files must
already be available to the client. `customization` can set `--text-frame-border` and
`--text-frame-background` on the box to give it a persistent outline or background.

`contents` is also used by file proxies. Their cached disk content stays out of document persistence;
text frames store their content in the document and never write a proxy file.

## Optional AI4 continuation

Set `autocomplete: true` in the YAML settings. After a typing pause at the end of the source, AI4's
configured fast model proposes a short prose continuation. Tab accepts it, Right Arrow takes a word,
and Escape dismisses it before a second Escape finishes editing. The existing chat autocomplete
browser and server switches still apply. Requests use `ai-text-frame-suggest`, the existing configured
provider/fast model, and a prose-specific prompt. Only a bounded document opening and draft suffix are
sent; chat tools are unavailable. Suggestions are never saved until accepted. This is not factual
verification or a substitute for editing.

## Synchronization and remaining work

Each edit uses the existing box property synchronization, autosave and history. An incoming contents
change during local editing keeps the local draft and shows **Keep my version / Use incoming** rather
than silently replacing it. This is conflict detection between property updates, not CRDT/OT or a
promise of lossless simultaneous typing. Publishing integration, presence, permissions,
search/navigation for noncoders, character-level collaboration and a richer AI4 document workflow
remain separate work.

Verification: `TextFramePersistenceTest` exercises save/read and protects proxy cache behavior;
`tools/run_ui_regressions.py --id text-frames` exercises real canvas creation, editing, overflow,
typography, YAML settings, source-font matching, typeset sizing, column reflow, incoming updates and connected creation in an independent browser. The existing
`ai-chat-autocomplete` regression protects the shared ghost-text interaction with controlled transport;
a live provider call is separate evidence.
