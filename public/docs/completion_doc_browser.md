# Browsing code from the completion panel

When you trigger autocomplete (`ctrl-space`), the panel on the right of the
popup is clickable. Every `[Symbol]` cross-reference in a KDoc comment, and the
`source …:line` footer at the bottom of a card, is a live link. Click one and
the panel *re-renders as that symbol's own card*: the same category header, the
same highlighted signature, the same rendered documentation. A small back /
forward control with a breadcrumb trail appears so you can retrace your steps.

Links only appear when they resolve. A `[Symbol]` that Field can't find stays
plain, bracketed text.

## A worked example

Start typing a value and ask for completions. Here we type `val axis = vec` and
press `ctrl-space`. The best match is `vec`, the function that builds a 2D
vector, and its documentation reads *"Creates a mutable 2D `Vec2` from numeric
coordinates."* Because `Vec2` resolves — it is a real type alias in Field's
standard library — the `[Vec2]` token in that sentence is rendered as a link:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector=".afield-suggest" warm-completion=true screenshot="assets/docpanel-code-link.png" padding="6" wait="0" timeout="120" embed=true hidden=true name="docpanel-code-link" alt="A completion doc panel with a clickable Vec2 code-link in the KDoc"}
// Fresh, deterministic capture: forget recency ranking and hide the run-tick overlay.
ui.eval("window.localStorage.removeItem('field.suggest.recency.v1'); var s=document.createElement('style');s.textContent='.tick_text.ok{display:none !important}';document.head.appendChild(s);return 'setup';")
ui.openEditor(id)
ui.pause(700)
ui.waitForSelector(".monaco-editor", 15000.0)
ui.clickSelector(".monaco-editor")
ui.pause(300)
ui.press("Control+A")
ui.type("val axis = vec")
ui.pause(300)
ui.press("Control+Space")
// warm-completion=true built the K1 completion engine up front, so the enriched
// RPC — the type-based completion and its KDoc — resolves headlessly. Wait for the
// linkified [Vec2] token itself, so the still shows a live code-link (not just the
// item, and not the instant quick-stub).
ui.waitForSelector("a[href^=\"field-source:\"]", 15000.0)
ui.pause(800)
// Best-effort second frame: follow the [Vec2] link into its own card and grab the
// breadcrumb, then step back so the fence's end-screenshot above is still the
// linkified panel. Wrapped so a navigation hiccup can never fail the primary still.
// Target only the KDoc [Vec2] link (the source-footer link also uses field-source:,
// and Playwright's strict locator refuses a two-match click).
try {
  ui.clickSelector("a[href^=\"field-source:\"]:not(.afield-suggest-source-link)")
  ui.waitForSelector(".afield-suggest-nav-crumbs", 8000.0)
  ui.pause(700)
  ui.shotClip("public/docs/assets/docpanel-breadcrumb.png", ".afield-suggest")
  ui.clickSelector(".afield-suggest-nav-btn[data-nav=\"back\"]")
  ui.waitForSelector("a[href^=\"field-source:\"]:not(.afield-suggest-source-link)", 8000.0)
  ui.pause(400)
} catch (e: Throwable) {}
```

The `Vec2` in the documentation is underlined: that is the code-link. The same
sentence ends *"…converting each component to [Float]."*, and there `[Float]`
stays literal. `Float` is a classpath-only type that this build's source index
can't resolve, so Field leaves it unlinked rather than offer a link that would
go nowhere. The `source …:line` line at the very bottom of the card is a link
too, pointing at the definition's location in the source.

## Following a link

Click `[Vec2]` and the panel hops to the `Vec2` card. Nothing about the editor
or your code changes — only the documentation panel re-renders, now showing
`Vec2`'s own signature (it is a `typealias`) and its documentation. A floating
control has appeared in the corner with a `‹` / `›` pair and a breadcrumb trail
that reads back to where you started:

![The doc panel after following the Vec2 link: the Vec2 card with a back-forward and breadcrumb control](assets/docpanel-breadcrumb.png)

From here the browsing is recursive. `Vec2`'s card is itself linkified, so any
`[Symbol]` it mentions is another hop deeper. The `‹` back button (or the
breadcrumb) returns you to the `vec` card you came from; `›` goes forward again.
Closing the completion popup resets the trail.

The autocomplete underneath all this is covered in the
[Survival Guide](survivalGuide.md#autocomplete). Conversational code search is a
separate UI and retrieval path; see [Semantic retrieval](semantic_index.md).

## Local completion history

Field keeps a local, append-only history of autocomplete interactions, so the completion helper can
be measured against real use. A record includes the complete unsaved source,
the edited and related code properties, caret position, each distinct completion ordering shown in the
popup, and whether a row was accepted or the popup was dismissed. Accepted rows retain their visible
rank and the resulting source.

The files live under `~/.afield/logs/completion_interactions/`, one JSONL file per UTC day. They are not
uploaded by this feature. Because they contain full source, treat them with the same privacy and backup
care as the Field documents themselves. `AFIELD_COMPLETION_INTERACTION_LOG_PATH` can redirect the log
directory.

## Browsing the full index

The panel resolves symbols; it doesn't list them, so it can't help with a
question you can't yet name. [What's already in the box](reference/index.md) is
the index for that: the same declarations, read out of the source by the same
parser this panel uses, laid out to browse. Anything listed there resolves
here.
