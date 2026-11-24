# Annotate selectBoxByName's name with @CompletesWith(BoxNames)

- **Handoff:** `de7b93f0-df83-4ad8-817b-389122639c26` (project)
- **Filed from:** https://openendedgroup.com/field4/interactive_guides/
- **Filed by:** agent:claude, 2026-11-24 19:05
- **Close with:** `python tools/docs_notes.py handoff-close de7b93f0 "what happened"`

## The notes that led here

- `4cfcb0c1` (public/docs/interactive_guides.md:85): selectBoxByName should use the new completion annotation to complete box names
  > selectBoxByName

The human's note on public/docs/interactive_guides.md (the `selectBoxByName("numbers")` line in "A tour of your own document") asks that `selectBoxByName` complete box names with the new string-literal completion annotation.

This is a source change to `src/jvmMain/kotlin/afield/ui/slides/SlideShow.kt` (`SlideStep.selectBoxByName`), outside the docs file this pass owns.

Evidence:
- `StringLiteralCompletion.kt#CompletesWith` is the annotation; it targets value parameters.
- `StringCompletionProviders.kt#BoxNames` already lists the document's box names (it uses `context.receiver as? Box ?: context.box`).
- `SlideShow.kt#selectBoxByName` takes `name: String` without an annotation, so the string gets no completion.

Proposed change: `fun selectBoxByName(@CompletesWith(BoxNames::class) name: String, ...)`, plus the import. While there, look for other box-name string parameters in SlideShow.kt (for example annotation targets that take a name) and annotate them too. Verify it in the editor: typing `selectBoxByName("` inside a `step {}` should offer box names. The receiver inside the step lambda is a `SlideStep`, not a Box, so `BoxNames` falls back to `context.box`. That fallback is correct here.

Questions for the human: none, unless other slideshow name parameters should be left out.

Page once done: no prose change is needed. Optionally add one sentence in "A tour of your own document" saying the editor completes the name.
