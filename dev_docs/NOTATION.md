# Notation: Field's Kotlin ↔ Excalidraw interface

Status: design + implementation in progress on branch `excalidraw-layer` (2026-09-28). The drawing
layer itself (Alt+D, camera, persistence) is described in `DRAWING_LAYER_HANDOFF.md`; this page is
about making that drawing *mean* something to code, in both directions.

## The idea

One scene — the root box's `drawing` property, a complete `.excalidraw` document — is read and
written from three places that run at three different speeds:

| Layer | Runs | Direction | For |
| --- | --- | --- | --- |
| **Scene API** (`afield.notation`) | JVM, in box code | read + write | querying what was drawn, drawing from code, relating marks to boxes |
| **Reactions** (`onNotation`, `onStroke`) | JVM, when the scene changes | drawing → code | treating a gesture, an arrow, a scribble as input |
| **Live scripts** (`// {{ notation }}` Kotlin/JS) | in the page, every frame | both, locally | animation, hit-testing, custom tools — anything that must not round-trip |

And one structural service underneath all three:

| **Anchoring** | JVM, when boxes move | boxes → drawing | marks and arrow endpoints that follow the boxes they annotate |

A mark is any Excalidraw element. Hand-drawn marks and code-written marks live in the same scene
and are the same kind of thing; the difference is only *ownership*.

## 1. Scene API — `notation`

In box code `notation` is the document's scene as seen from this box (the box is the implicit
receiver, so it is just `notation`).

Reading:

```kotlin
notation.marks                  // every live mark, in z order
notation.strokes                // freedraw marks;  .arrows .texts .shapes .frames likewise
notation["id"]                  // by Excalidraw id
notation.named("tempo")         // by label: its own text, the text bound to it, or a code-given name
notation.over(thisBox)          // marks whose centre lies on a box
notation.arrowsFrom(thisBox)    // arrows starting on a box; arrowsTo likewise
notation.selected               // what is selected in the drawing layer right now

mark.kind  mark.bounds  mark.center  mark.points  mark.angle
mark.stroke  mark.fill  mark.text  mark.label  mark.owner
mark.box                        // the box under the mark, if any
arrow.fromBox  arrow.toBox       // the boxes an arrow's ends are on
mark.toFLine()                  // world-space geometry, for anything FLine can do
```

Writing — marks a box writes are *owned* by it, and re-running the box replaces them, the way
`display("channel", …)` replaces a channel:

```kotlin
notation {
    rect(100, 100, 200, 80, stroke = "#e03131")
    text("peak here", 110, 70)
    arrow(from = thisBox, to = otherBox)        // ends anchored: they follow both boxes
    stroke(someFLine, width = 2)
}
notation("overlay") { … }                       // a second, independently replaced set
notation.clear()                                // remove this box's marks
notation.edit(mark) { stroke = "#2f9e44"; text = "done" }   // any mark, including hand-drawn
notation.delete(mark)
```

Owned marks carry `customData.fieldOwner` (box uid) and `fieldKey`, and deterministic ids, so a
re-run *updates* them (Excalidraw sees a version bump) rather than flickering through a delete.

## 2. Reactions

```kotlin
onNotation { n -> … }                   // any change; n.added / n.updated / n.removed
onStroke { stroke -> … }                // a freedraw stroke was finished
onArrow { arrow -> … }                  // an arrow was drawn or re-ended
```

Handlers are per box and per key: re-running the box replaces them, stopping it removes them. They
run in the box's context, after the client's debounce, on the server — so they see the merged
scene every client agrees on.

## 3. Live scripts — Kotlin compiled to JavaScript, running beside Excalidraw

A `kotlinjs` box whose first line is `// {{ notation }}` compiles against the notation surface and
is shipped to every Field page showing the document, where it runs next to Excalidraw's API:

```kotlin
// {{ notation }}
val sun = create(ellipse(0.0, 0.0, 40.0, 40.0, fill = "#ffd43b"))   // ephemeral: never saved
onFrame { t -> move(sun, 300 + 120 * cos(t), 200 + 120 * sin(t)) }
onPointer { p -> if (p.down) println("pressed at ${p.x}, ${p.y}") }
```

Marks a script creates are **ephemeral** — local to the page, never persisted, removed when the
script stops — so a 60 fps animation does not stream through the server. Edits a script makes to
persistent marks are ordinary edits and are saved. Stop disposes every frame loop, handler and
ephemeral mark the script registered. Compile errors land on the box at the author's line, as for
every Kotlin/JS box.

This reuses the existing Kotlin/JS machinery end to end (`KotlinToJsCompiler`, head profiles,
diagnostics, completion); the notation profile only supplies a surface and a transport whose
"head" is Field's own page.

## 4. Anchoring

A mark can be anchored to a box: `customData.fieldAnchor = {box, dx, dy}`. An arrow's ends can be
anchored separately: `fieldStart` / `fieldEnd = {box, u, v}` (position within the box's frame,
0..1). When a box's frame changes the server recomputes anchored marks from those — absolute, so
applying it twice is harmless — and the drawing follows the box.

- Arrows drawn by hand whose ends land on boxes are anchored automatically.
- **Anchor Drawing to Boxes** (palette) anchors selected marks to the box under each.
- `arrow(from = box, to = box)` and `mark.anchorTo(box)` do it from code.

## Consistency

The server holds the authoritative scene. Every incoming client scene is *merged* into it by
element version (Excalidraw's own rule: higher `version` wins, ties by `versionNonce`), so a code
write and a hand edit arriving together both survive. Server writes bump versions the same way.

## Build order

1. Scene model, read API, server merge, write API with ownership. Unit tests.
2. Reactions.
3. Anchoring (server recompute on frame change; auto-anchor arrows; palette command).
4. Selection sync (client → server, not persisted).
5. Live scripts: page runtime + notation profile + transport.
6. Demo document, docs, handoff.
