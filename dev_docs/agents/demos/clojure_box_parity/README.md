# Clojure box parity — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`).
It is the saved form of `dev_docs/agents/transcripts/clojure_box_parity.yaml`; the written-up
version is [Clojure in a box](../../../../public/docs/clojure.md). Run the root first.

- **an error is marked where it happened**: run it, and the red marker sits on line 6, inside
  `mean`, where the division by zero happened, not on the call on line 8.
- **a slider and a checkbox, with Clojure handlers**: run it, then move the slider or tick the box;
  the handlers update an atom whose watch redraws the `main` panel. Run it again and the controls
  keep their values.
- **values from the parent**: run it to print the root's `speed` property and its Kotlin `palette`
  variable, both read with `(context ...)`.

The code sidecars end in `.kt` because Field names every `code` sidecar that way; the boxes'
`code_language` is Clojure.
