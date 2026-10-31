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
