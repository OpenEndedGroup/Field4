# The WFC gallery, and the prose it pins

`afield.examples.stdlib.WfcGallery` (`stdlib.wfc.gallery`) renders the figures on
`public/docs/wfc_examples.md` and asserts as it draws.

Moved off that page on 2026-09-21 (fortieth firing): the page closed with two paragraphs of the
docs guard auditing itself, on a page a reader opens to learn wave-function collapse. The first
paragraph — what the gallery checks about every figure — stays on the page, because it is why a
reader can trust the pictures. This is the second.

## What is asserted of every grid

Every adjacent pair is an allowed pair, the adjacency relation is symmetric, and the output is not
degenerate. On the terrain figures, the set of terrain edges in the output is a subset of the set
in the source.

## The three checks that hold page prose to account

- **§2's twelve coastlines** must all solve, must use only pairs the source contained
  (`observedAllows`, not merely `allows`), and must come out more coherent than both the
  one-colour model and the source itself.
- **The all-deep window** must still have exactly one southward successor. If it stops having
  exactly one, the paragraph on the page explaining why that figure is unpinned is wrong.
- **The widening audit** pins 1 × 1 at zero invented pairs and 2 × 2 at more invented than
  observed, which is what stops §2's note about widening going stale.

## Open

- None of the three is reachable from the page any more, so a reader who wants to know which
  sentences are load-bearing has to find this note. That is the intended trade — the audience for
  "which prose is pinned" is someone editing the page, not someone learning the algorithm — but it
  does mean a future edit to §2 will not be warned by anything the editor can see. The durable fix
  is an assertion message on the `WfcGallery` check that names the page and the paragraph.
