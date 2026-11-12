# Individual reading planes

Example **7 · Elastic sentences** places six text pieces at different depths. Its visible words
are verbatim excerpts from `files/source-passage.txt`; authored line breaks do not change their
word order or punctuation. `nonexistence.` uses a thin face; `sipping.` uses italics.
All five links have small endpoint discs, and the entrance uses opacity rather than stroke reveal.

Each run can face the reader independently:

```kotlin
val graph = readingDiagram {
    text("motes", "stray motes", point("p", .5, .1, -.4)) {
        faceReader = true
        facingHalfLife = .7f
        facingMaximumTurnRate = 1.8f
    }
}
```

`facingHalfLife` is in seconds; the maximum rate is radians/second. Turning follows the fixed
physics steps, with current reader position supplied from the average eye pose in diagram
coordinates. The target is a gravity-up plane whose positive Z faces the reader. Within 5 cm
of its anchor, it retains the previous direction to avoid an unstable flip. Opacity and stroke
style do not affect facing.

For an authored fixed plane, leave `faceReader = false` and rotate `orientation`:

```kotlin
text("fixed", "pulled taut", point("p", -.5, 0, .3)) {
    orientation.rotateY(.35f).rotateX(-.15f)
}
```

When facing is enabled, `orientation` is an offset from the reader-facing plane. The existing
`rotation` property adds local roll in radians. These are live settings and need no font rebuild.
Font weight/style changes still require preparing the player and rebuilding Drawing.

Glyphs, word bounds, underlines, local FLines, sliding attachments and cached Gaussian blur use
the same full orientation. A link therefore terminates on its word even while the word turns.
The physics chain keeps its own world-space inertia; it is not rotated rigidly with either word.
All render parts of a paragraph, including a dropped initial, share that paragraph's orientation.

If the current scene contains independently facing text, the renderer holds the whole-diagram
yaw rather than swinging the stage around the reader. Recenter still positions the stage once.
Other examples retain the existing whole-diagram following behaviour. Turning off **Each run
faces the reader** restores that behaviour if **Gently face the reader** is enabled in Drawing.
