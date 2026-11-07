# Knight tours: a drawing laboratory

[Open the editable starting point](offline.html?file=assets/agent_transcripts/knight_tour_workshop/metadata.edn)

Run **knight tours** first. It finds eighteen 8×8 tours in three rows: exhaustive search,
a Pósa-rotation walk, and independent randomised restarts. It draws the straight moves and
publishes tours, sampled three-point arcs, board dimensions and cell size as inherited properties.
All four connected child views refresh after publication; no child repeats the search.
Everything draws with FLines; no renderer, GPU or external service is needed.

## Four views to change

- **Arc growth:** one tour accumulates its 62 stride-one triples. Gold is the new arc,
  muted strokes are earlier arcs; white marks A/C and cyan marks B. Every arc goes through B,
  even when that requires a long, nearly complete sweep.
- **Perpendicular arcs:** scale each arc about its exact B; retain the perpendicular A–C
  shift as a separate parameter (zero by default). Try scales 0.5 and 0.25.
- **Glued arcs:** shrink, reorder and reverse arcs, then connect their endpoints into one
  open path. Multi-start nearest-neighbour plus 2-opt reduces connector length, not a proof
  of the globally shortest route. The saved scale preserves the current session value.
- **Line experiments:** draw each move as an independently transformed quadratic segment.

## Layered parameters

At the top of **knight tour line experiments**, edit `startTrim`, `endTrim`, `nextPull`,
`previousPull`, `rotationRadians`, `perpendicularOffset`, `alongSlide` and `strokeWidth`.
Use collection literals such as `[0.3, 0.5, -0.1]` or a scalar such as `0.3`.
Layers are zipped, not a Cartesian product: their count is the longest list, and every shorter
list holds its final value. Scalars repeat throughout. Empty lists are rejected.
Values are not clamped: negative trims extend a move and large trims may reverse it.

Fractional trims define the segment ends. The quadratic control point starts at their midpoint
and is pulled fixed distances towards the next and previous sequence points (missing neighbours
contribute nothing). Rotation uses `quat` in radians about the midpoint; perpendicular offset and
along-line slide use the rotated original move direction. Geometry uses native `Vector2f`/`vec`.
Each layer is drawn over the same board. The default rotation list is `[PI/20, 0.0, -PI/20]`.

![Three overlaid quadratic drawings per tour](assets/knight_tour_workshop/layers.png)

## The conversation that made it

[Download/read the saved chat](assets/knight_tour_workshop/chat.txt), with its
[shared-computation notes](assets/knight_tour_workshop/SHARED_TOURS.txt).
This is a snapshot of Field’s actual persisted chat, including tool progress and embedded images,
not an edited reconstruction. It begins with the rolling-circle question and includes the
publication request; later publication messages are outside this snapshot. Associated image blobs
are retained beside it. The runnable YAML is a compact final-scene export rather than a replay
of every conversational edit.
