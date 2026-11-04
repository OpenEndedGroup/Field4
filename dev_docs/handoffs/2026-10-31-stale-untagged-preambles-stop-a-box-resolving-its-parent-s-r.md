# Stale untagged preambles stop a box resolving its parent's renderer

- **Handoff:** `c562fccc-4415-459d-af7a-387f0e9b944d` (project)
- **Filed from:** https://openendedgroup.com/field4/stackauto_conversation/
- **Filed by:** human, 2026-10-31 16:47
- **Close with:** `python tools/docs_notes.py handoff-close c562fccc "what happened"`

## What happened

Re-running `tools/capture_stackauto_conversation.py` on 2026-10-31, the box `polytope_source` in a copy of
`dev_docs/agents/demos/stackauto_conversation_polytope` failed to compile with
`Unresolved reference 'renderer'` (`polytope_source/code:5`, `renderer += module`), even though its parent
`polytope_renderer` was launched and drawing frames, and declares `prop<Renderer>(renderer)`.

The starter document `stackauto_conversation` (same two boxes, same code) compiles. The difference is the
saved `preamble` property: in the example document both boxes carry preambles with untagged inherit-marked
lines (`var Box.renderer by Inheritance<Renderer>() /**/`, no `/*from:uid*/` supplier tag), saved before
supplier tags existed. The starter's preambles are empty.

Clearing the child's preamble in the copy (`set polytope_source preamble --value ""`) before running makes it
compile and run (verified: card and chat captures complete). Reconnecting the parent/child edge after launch
does not help.

## Why it matters

Any document saved before the supplier tags may hit this on reopen: a box beneath a renderer stops compiling
with no hint that its own stale preamble is the cause. The capture script now clears the preamble in its temp
copy; that is a workaround, not a fix.

## Where to look

`gatherPreambleUpwardsReport` in `src/jvmMain/kotlin/afield/runtime/macros/macros.kt`: untagged lines of the
box itself get rank `Int.MAX_VALUE`, and their names go into `declared`, so the parent's declaration expanded
from its code (`propDeclarationsFromCode`) is skipped for that name. Why the box's own untagged copy then does
not resolve (an import, or the line being dropped later) is unmeasured (conjecture, 2026-10-31).

## Proposed approach

Treat untagged inherit-marked lines in a box's own preamble as unknown origin and let a live ancestor's
declaration of the same name win (or drop them on load), with a test that reopens a document whose child
preamble carries the legacy line. Then remove the workaround from the capture script.

## Resolution (2026-10-31)

The theory above was wrong: untagged lines were not the cause. The one-off corpus formatter (`f8cd87437`,
"Format the frozen Kotlin and GLSL corpus") reformatted saved `preamble` strings inside `metadata.edn` as if
they were box code, leaving a second, headless copy of `MappedStructure` (indented `lateinit var` lines and a
stray `}`). That preamble no longer compiles, so `checkPreambleAndExecute` stops before `renderer` is declared.
Documents never run through that pass load fine, which is why it looked subtle.

Fixed by restoring the 31 preambles that commit rewrote, in six documents (`stackauto_conversation_polytope`,
`canonical_graphics`, `skinned_characters`, `frame_history_reprojection`, `gpu_probe_bench`,
`nesting_edge_demo`), each to its bytes before the formatter and only where nothing has edited it since. The
capture script's workaround is gone; the capture runs both documents clean. Today's `BoxCodeFormat` does not
touch EDN, so nothing running now repeats it.
