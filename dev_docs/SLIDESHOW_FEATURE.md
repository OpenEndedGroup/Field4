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
