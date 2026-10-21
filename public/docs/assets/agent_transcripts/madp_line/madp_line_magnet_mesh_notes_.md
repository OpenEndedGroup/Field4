# Magnet mesh: between strokes

Draw two or three strokes near each other. Every point is pulled towards the nearest point of
another stroke (`closestPoint`), and every fifth point grows a filament to it, so neighbouring
strokes slowly knit together. This is the physics: the block sets `ax`/`ay`, and Field moves the
points. A single stroke on its own just sits there. No sound.
