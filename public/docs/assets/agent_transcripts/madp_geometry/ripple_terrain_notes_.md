# Geometry: models as recipes of triangles

This document is Field's version of **Geometry**, the madp toolbox page `3d.html`. There, a code
editor sits beside a three.js view. You write one `build()` function, and every edit rebuilds the
model and draws it on a dark studio ground with soft shadows. Eleven presets show what the language
can do, from a single triangle to a recursive tree.

## What it teaches

- **Everything is triangles.** A surface, a lathe-turned vessel, a tube along a knot and a box are
  all loops that emit triangles. `tri` is the one real primitive; the rest are recipes built on it.
- **Immediate mode with state.** There is a current colour, a current material and a current
  transform. Each call uses whatever is current, so `color(...)` before `box(...)` colours that box,
  and `push()` / `pop()` save and restore everything.
- **Colour per vertex.** Each corner can carry its own colour, and three.js blends it across the
  face. With flat shading, that blend and the facets do the drawing.
- **A fixed topology animates.** Re-run the recipe every frame with `t` moving, and as long as it
  emits the same triangles, only the vertices move. That is what makes a vertex-animated GLB.

## How to play

1. **Run this box** (alt+0). The terrain appears in its output. Drag to orbit, scroll to zoom.
2. **Change a number and run it again**: `3.6` in `sin(r * 3.6)` sets how tight the ripples are,
   `188` in `hsl(188 + …)` the hue. The view keeps your orbit.
3. **Run the other boxes**: each is one of the toolbox's presets, with a short note of its own.
   *breathing sheet* and *signal tear* animate: **launch** them (alt+up) to play, and stop them with
   alt+down. Running them (alt+0) draws one still frame.
4. **Export GLB**, in the view's toolbar, saves the model. If gltfpack is installed
   (`./field.sh --setup`, or `./gradlew prepareGltfpack`), the file is compressed. From code,
   `geometryScene { … }.writeGlb(file)` saves a still and `writeGlbAnimation(file, frames = 90) { … }`
   saves an animation as morph targets.
5. **Ask the AI.** `geometryAgent()` at the top of this box teaches the document's AI4 chat the
   language. It also gives the chat `geometry.look`, which returns a picture of any view here as it is
   on screen. Open the chat (ctrl/command-4) and ask for "a coral reef of tubes", or "make the terrain
   stormier". The chat edits a box, runs it, looks, and tries again.

## From `3d.html` to Field

| In the toolbox | Here |
| --- | --- |
| `function build() { … }` | `geometry { … }`, the box's code |
| **Animate** | `animateGeometry { … }`, then launch the box (alt+up) |
| `mat({ roughness: 0.8, flat: true })` | `mat(roughness = 0.8, flat = true)` |
| `[x, y, z]` | `vec(x, y, z)` |
| `{ pos: [x, y, z], color: hsl(…) }` | `vertex(x, y, z, hsl(…))` |
| `surface(c, r, (u, v) => ({ pos, color }))` | `surface(c, r) { u, v -> vertex(…) }` |
| `revolve([{ r, y, color }, …], 96)` | `revolve(listOf(profile(r, y, color), …), 96)` |
| `tube(n, sides, t => ({ pos, radius, color }))` | `tube(n, sides) { s -> section(pos, radius, color) }` |
| `hsl`, `seed`, `rand`, `noise` | the same names and the same numbers: `seed(18)` grows the same crystal bloom |
| `view({ background, ground, grid })` | `view(background = …, ground = …, grid = …)` |
| **Export GLB** (+ gltfpack) | the view's **Export GLB**, `writeGlb`, `writeGlbAnimation` |
| **Copy Guide**, AI completion, agent chat | `geometryAgent()` and AI4; Field's own completion |

## What is different

- **Kotlin, not JavaScript.** Numbers are `Double`, so a loop index sometimes needs `i.toDouble()`,
  and a local helper is a local `fun`, as in *fungal tree* and *menger lesson*.
- **One box per preset** instead of a menu, so you can run them side by side and keep your edits.
- **Lambda names.** A tube's parameter is `s` here, because `t` is already the animation time.
- **The camera** frames each model a little differently from the toolbox's fit.
- **Saving** is Field's: documents and their history, not the page's local saves.
- **The look** is the same rig (ACES tone mapping, a shadow-casting key, hemisphere and rim lights,
  ambient occlusion), provided by `three { studio() }`, so any `three` scene can use it.
