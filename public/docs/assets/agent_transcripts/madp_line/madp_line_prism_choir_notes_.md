# Line: strokes that come alive

**Line** is one of the madp classroom demos: a black page beside a code editor. Every stroke you draw
becomes an object with a life of its own. It moves, it makes sound, and the way it moves and sounds
comes from code you wrote. The point it teaches is that a gesture can be the start of a program
rather than its output: the shape of your hand sets up a synth and a motion, and from then on the
stroke plays and draws itself.

This document is that demo in Field. You draw on Field's own canvas, with the drawing layer, and the
strokes come alive on the canvas where you drew them.

## Playing with it

1. Run this box. It takes the **pen**.
2. Press **Alt+D** for the drawing layer, choose the pencil, and draw on the empty canvas to the right.
   Long arcs and loops suit this one. Press Alt+D again when you are done.
3. Each stroke turns into a ribbon that hums. The longer the stroke, the louder; the more it rises,
   the brighter; left-to-right or right-to-left sets the pan. The louder the choir plays, the wider
   the ribbon swings.
4. Edit the code and run the box again. Strokes you draw from then on use the new code; the ones
   already on the canvas keep the code they were drawn with.
5. Run another box to give it the pen. `clearStrokes()` in any box wipes the page.

No pen handy? Run **draw for me**: it sends three strokes the way the drawing layer does when you
lift the pencil.

## How a stroke's code is written

`eachStroke(trail = 0.18) { … }` is the whole program. Its block runs **once for each stroke**, when
it is drawn: that is where you look at the stroke's `points` and start a `sound(…)`. Inside it,
`draw { t -> … }` runs **sixty times a second** and returns the lines to show (FLines). Local `var`s
in the block belong to that one stroke and are shared by its sound and its drawing, which is how the
ribbon and the choir talk to each other.

`points` are physics points (`x`, `y`, `vx`, `vy`, `ax`, `ay`): set `ax`/`ay` to push them and Field
moves them after each frame. `trail` is how much of each frame the next one keeps, so 0.9 leaves long
trails. The verbs are the demo's: `resample`, `smooth`, `arcs`, `dots`, `atomize`, `wiggle`, `warp`,
`add`, `subtract`, `project`, `scale`, `transformTo`, `closestPoint`, `closestOutput`, `rms()`, and
the noise helpers `triWave`, `tri01`, `heldNoise`, `driftNoise`, `perlin2D`.

## From the demo to Field

| the demo | here |
| --- | --- |
| `onStart(inputPoints, lineIndex)` | the `eachStroke { }` block, with `points` and `index` |
| `manipulatePoints(inputPoints, t)` | `draw { t -> }` |
| `ctrl.x`, shared by image and sound | a local `var x` in the block |
| draw commands (`smooth`, `arc`, `dot`, `{x0,y0,x1,y1}`, `connected:false`) | FLines: `smooth(curve)`, `arcs(curve)`, `dots(curve, r)`, `segment(…)`, `moveTo` |
| `color`, `fill`, `overshoot`, `ignore` | `line.color`, `line.filled`, `line.overshoot`, `line.ignore` |
| `'hsla(205, 96%, 68%, 0.9)'` | `cssHsla(205, 0.96, 0.68, 0.9)` |
| Strudel: `note("c3 e3").sound("sawtooth").gain(signal(() => …))` | `pat("c4 e4") with s("sawtooth") with gain(signal { … })` |
| `.stack(…)` | `stack(a, b)` |
| the editor's code, snapshotted at mouseup | the box's code when it last ran |
| the preset menu | one box per preset |
| Reset | `clearStrokes()` |

The sound runs on the same `dough.wasm` engine as the other madp demos, through Field's pattern
language, which is Tidal's. Two things differ from Strudel. Note names are an octave higher here
(`c5` is middle C), so every pattern is written up an octave and sounds at the same pitch. And
Strudel's synths are about a quarter as loud as dough's at the same gain, so the strokes play at that
level: the demo's gains sound as loud as they did, and `rms()` reads what it read there.

## What is different

- The demo's own editor, AI completion, agent chat and saves are Field's editor, completion, AI4 and
  documents.
- Strokes are not saved with the document, as in the demo.
- The demo steps its physics once per screen refresh, so on a 120 Hz display its strokes move twice
  as fast as on a 60 Hz one. Here they always step 60 times a second.
- Panning or zooming the canvas clears the trails.
- Five of the demo's seventeen presets are here: ribbon bloom, comet dust, curve choir, magnet mesh
  and step lattice. The rest repeat mechanisms these show.

The design notes are in `dev_docs/madp/line.md`.
