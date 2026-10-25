# tetra: four pictures as a tetrahedron

A port of the madp demo `tetra.html` (and its unlinked sibling `tetra2.html`).

## What it shows

CLIP is a neural network that turns a picture, or a sentence, into 512 numbers: a point in a
512-dimensional space, placed so that pictures of similar things land close together. Four inputs
give four points, and four points have six distances between them.

You can't look at 512 dimensions. But **any four points of a Euclidean space fit exactly in three
dimensions**, however many dimensions the space has: three points always lie in a plane, and a
fourth adds at most one more direction. So the tetrahedron in this box's output is not an
approximation of CLIP's geometry. Its six edges *are* the six distances, and its shape is the shape
of those four embeddings.

With the cat, the dog, the tiger and the car, the three animals sit close together and the car's
three edges are the three longest.

## How to play

- **Run the box.** The first run loads CLIP (a second or two); after that a change takes well under
  a second.
- **Drop a picture** from your desktop onto a slot, or click a slot to choose a file. Drop several
  at once and they fill that slot and the ones after it.
- **Drag a picture up from the tray** under the slots. Drop a folder on the tray (or use
  **folder…**) to fill it with your own pictures.
- **Orbit** the tetrahedron by dragging; it turns slowly by itself when you let go.
- **reset** puts the slots back as the code says.

While a new picture is being worked out the old one greys out, as on the madp page.

## How it works

The box's code is the whole pipeline:

1. `mediaSlots { … }` shows the slots and hands you their contents;
2. `it.clip()` embeds each slot, picture or text, as a unit-length 512-vector;
3. `embeddingSimplex(...)` computes the six chord distances `|a − b|` between those unit vectors
   and places the four points in 3D by classical MDS (the double-centred Gram matrix and its top
   three eigenvectors);
4. `view.show(...)` draws it: spheres in the slots' colours, an edge per pair with its length, and
   the pair table.

The note in the corner says *edges are the distances* when the fit is exact.

## What differs from the madp page

- **The model.** The madp page runs jina-clip-v2 on a Python server. Field runs OpenAI's CLIP
  ViT-B/32 through ONNX, inside Field, so the numbers differ from the page's. The geometry does not
  depend on the model.
- **No server, no Process button.** The box recomputes whenever a slot changes.
- **tetra2's bug is not ported.** The page's image + text route squared the distances twice before
  MDS. Here there is one MDS, on the distances.
- What you drop survives re-running the box, but not restarting Field. Change the paths in the code
  to keep a set.

The other three boxes are variations: a sentence in place of a picture, one picture swapped, and a
fifth point that does not fit.
