# RectSL examples

RectSL is meant to be small enough to mutate live, but concrete enough that the CPU can sample exact rectangle state. These examples use `afield.rectsl.*` from normal Field Kotlin.

## Two mirrored rectangles

```diffstatic {data-lang=kotlin}
import afield.rectsl.*

val rectsl = """
#rectsl
wall 4 4 meters
duration 4
fps 20

"left" rect
  -1 0 xy
  1 square
  '( 0 -1  2 0  4 3 ) x
  '( 0 0   2 1  4 0 ) alpha
;

dup "right" rename
  mirror-x
;
"""

val program = RectAnimationOracle.compile(rectsl).program!!
val mid = program.sample(2.0)
val mesh = lowerSampledScene(mid)
```

`dup` copies the active rectangle, `rename` gives the copy a stable id, and `mirror-x` mirrors constants and x tracks. When lowered, the rect id is carried as metadata for material lookup rather than being written into clip-space z.

## Named tracks and draw layers

```diffstatic {data-lang=kotlin}
import afield.rectsl.*

val rectsl = """
#rectsl
wall 16 9 meters
duration 6
fps 30

linear-track "rise" '( 0 -3   3 0   6 3 )
hold-track "pulse" '( 0 0.2   2 1.0   4 0.45 )

"bar-template" rect
  0 -2 xy
  0.5 1.4 wh
  "rise" track-ref y
  "pulse" track-ref smooth alpha
  0xeeeeee solid fill
;

8 '(
  "bar-template" select
    clone
    i layer
    i 0.1 * z
    i 3.5 - 0.8 * -2 xy
) repeat
"""
```

The template rect owns the common geometry and tracks. The loop clones it eight times and assigns per-copy position, layer, and z metadata. `repeat` provides `i`, and `smooth` turns the hold pulse into a softened animated track.

## Groups, selections, and StackSL material

```diffstatic {data-lang=kotlin}
import afield.rectsl.*

val rectsl = """
#rectsl
wall 10 10 meters
duration 8
fps 30

material petals-tint '(
  uv 4.0 * sin abs
)

"orbit" group
  "cell" rect
    0 0 xy
    0.75 square
    "petal" tag
    '( 0 0   4 3.14159   8 6.28318 ) rot
    0xffffff solid fill
  ;

  0 6 '(
    "cell" select
      clone
      i 2 * 5 - 0 xy
  ) range
end-group

"orbit" select descendants
  "petal" tagged
  "petals" group-selected
  selected descendants rects '(
    0.85 alpha
    petals-tint material
  ) each
;
"""
```

The group creates a small hierarchy, `descendants` expands the selection, `tagged` filters it, and `group-selected` wraps the result. Materials belong to rects, so `selected descendants rects '( ... ) each` applies alpha and material to the grouped rectangles. The `material petals-tint '( ... )` body is StackSL, so shader behavior stays in the same language as the rest of Field's shader stack.

## Rendering JPG frames

```diffstatic {data-lang=kotlin}
import afield.rectsl.*

val settings = RectSLRenderSettings(
    pixelsPerMeter = 100.0,
    fps = 60.0,
    outputDirectory = "rectsl".path,
    renderer = renderer,
    jpegQuality = 92,
    includeFinalFrame = false,
    driveRendererWhileWaiting = false
)

frames {
    val result = RectSLRenderRunner.renderJpgSequence(rectsl, settings)
    _print(result)
}
```

Use `includeFinalFrame = false` when you want a looping sequence without duplicating the first frame at the end.
