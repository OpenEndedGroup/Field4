# RemoteJS box parity — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`).
It is the saved form of `dev_docs/agents/transcripts/remotejs_box_parity.yaml`; the written-up
version is [Javascript in a browser tab](../../../../public/docs/remotejs.md). **Run the root first**: it opens the
browser head the Javascript boxes run in. Without it, running a child says no head is connected.

- **print and console.log beside their lines**: both print beside the calling line; `true` (the last
  value) appears beside line 5.
- **an error is marked where it happened**: the red marker sits on line 4, inside `mean`.
- **display, a slider and a checkbox**: run it, then move the slider or tick the box; the callback
  in the head redraws `main`.
- **output printed after the run finished**: "half a second later" appears beside line 3 after the
  run has ended.
- **Stop stops launch loops**: run it, then Stop; `window.spins` in the head stops counting.

The code sidecars end in `.kt` because Field names every `code` sidecar that way.

The right-hand column is the same contract for **Kotlin → head** boxes, Kotlin compiled to
Javascript and sent to the same head ([Kotlin in the head](../../../../public/docs/kotlin_in_the_head.md)):

- **Kotlin: println and console.log beside their lines**: both appear beside lines 2 and 3.
- **Kotlin: an exception is marked on its line**: the red marker sits on line 5, the `throw`.
- **Kotlin: display, a slider and a checkbox**: the same controls, with Kotlin callbacks.
- **Kotlin: output printed after the run finished**: `setTimeout` prints beside line 4.
- **Kotlin: Launch, then Stop, a frame loop**: Launch it and `main` counts frames; Stop freezes it.
