# Slideshow implementation map

User-facing behavior and the DSL are documented in `public/docs/interactive_guides.md`. Exact API
contracts are KDoc in `src/jvmMain/kotlin/afield/SlideShow.kt`.

`Box.slideShow { ... }` builds and stores an instance; it does **not** start it. Call `start()` (or
another navigation method) from a coroutine. Each step may run suspendable exit/enter callbacks,
render an HTML or Markdown note, select a box/property/line, install link callbacks, add annotations,
and opt into chat. Template placeholders resolve when a step is shown, in this order: host-box raw
property, live Kotlin context variable, `knownValues`, registered `templates`, then empty string.

The durable file map is:

- `src/jvmMain/kotlin/afield/SlideShow.kt` — server-side state machine and DSL.
- `src/jsMain/kotlin/afield/Main.kt` — browser note overlay, navigation links, and chat widgets.
- `src/jsMain/kotlin/afield/slideshowSupport.kt` — annotations and animated text.
- `src/commonMain/kotlin/rpc.kt` — wire types.
- `src/jvmMain/kotlin/afield/examples/slideshow_example.kt` — compiled examples.

The browser regression catalog currently has no dedicated slideshow case. Changes should add or run
a focused real-browser workflow covering navigation, selection, callbacks, note cleanup, and at
least one annotation rather than relying only on compilation.

## Routing and known sharp edges (2026-10-13)

- Note links (`slideshow-command`) go to `SlideShowInstance.activeIn(root)`, the instance whose step
  was last shown in that document (set by `goToStep`, released by `stop`); before any step has been
  shown, the first box holding a `slideShow` property. Note, hide and select messages go only to the
  host box's document (`sendToHostDocument`). `SlideShowTest` covers both and `anchorNoteTo`.
- Open: `annotateToLine` ends about two editor lines above its target line in the docs captures
  (`public/docs/assets/interactive_guides/annotations.png`: line 17 targeted, the path ends near 15).
  The y comes from `getTopForLineNumber - getScrollTop` in `slideshowSupport.kt`; not yet diagnosed.
- Open: template values are inserted into the note HTML unescaped, so a property holding `<` breaks
  the markup (also lets a value carry HTML on purpose; decide before changing).
- Open: `withChat` mounts the Discussion collapsed with no way to ask for it open, so no capture can
  show messages without a click.
- `previous()` on step 0 is a no-op; `next()` on the last step logs an out-of-bounds warning.
- The public guide and its demo: `public/docs/interactive_guides.md`,
  `dev_docs/agents/transcripts/slideshow_guide.yaml`.
