# Flush Clojure output per form so print is attributed to its own form

- **Handoff:** `a3faa246-c566-443d-87cb-d53d113c4967` (project)
- **Filed from:** https://openendedgroup.com/field4/clojure/
- **Filed by:** human, 2026-10-15 13:47
- **Close with:** `python tools/docs_notes.py handoff-close a3faa246 "what happened"`

Rule 25 sharp edge on `public/docs/clojure.md` ("Output lands beside the form that made it"):

> `print` without a newline is buffered until the next newline or the end of the run, so a bare
> `(print …)` is attributed to whichever form was running when it finally flushed, which is usually
> but not always its own.

Fix in code: `src/jvmMain/kotlin/afield/lang/clojure/ClojureInterface.kt` evaluates one top-level
form at a time; flush the box's `*out*` writer (and `*err*`) after each top-level form, before the
next form's attribution begins, so partial-line output is always attributed to the form that wrote
it. Add a test (a box of `(print "a")` then `(println "b")` puts `a` beside line 1).

Delete the paragraph above from `clojure.md` in the same change.
