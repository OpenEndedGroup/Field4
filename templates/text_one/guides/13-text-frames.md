# Text frames and connection standoff

Frames are ordinary additive FLine strokes in the text's coordinate system. Their clearance
is in metres, independent of font size. The actual glyph ink includes used descenders;
without descenders the lower edge reaches the baseline plus the chosen margin, without
reserving the font's unused descent. The default rectangular mode encloses the complete run.

```kotlin
val firstFrame = textFrame("first-frame", first, standoff = .025f) {
    width = .0008f
    color = vec(1f, 1f, 1f, .22f)
}
val secondFrame = textFrame("second-frame", second, standoff = .025f)
val thread = connection("thread", firstFrame.attach(), secondFrame.attach()) {
    responseSeconds = 1.2f
}
```

`frame.attach()` slides on the same exposed perimeter that the border draws. The connection
endpoints and border share `frame.standoff`; changing it updates both. `LEFT`, `RIGHT`,
`LEFT_MARGIN` and `RIGHT_MARGIN` select specific sides or landmarks. Use `frame.path` for
normal stroke styling or opacity scores. The border remains sharp when its word blurs.

Direct text attachments also accept `standoff`:

```kotlin
first.word("door").attach(DiagramTextSite.BOUNDARY, standoff = .025f)
```

They retain their per-line behavior for multiline spans. A frame attachment instead uses
one outer rectangle, so its sliding endpoints always lie on the drawn frame.

Example 7 exposes **Text frames** controls for the shared standoff, opacity and line width.
Example 8 frames the whole drop-cap paragraph and the separate aside.

The reading scene accumulates opacity-weighted light with destination factor **ONE**.
Strokes, markers, sharp and blurred text, borders and diagnostics never attenuate existing
scene light. The final compositor no longer normalizes RGB by accumulated opacity or masks
the background by coverage. The green blur selector and spatial antialiasing remain available.

## Unions of line rectangles

Choose **Per-line rectangle union** in **Text frames** for a stepped outline following
one measured rectangle per line:

```kotlin
val frame = textFrame("frame", paragraph, standoff = .025f,
    mode = DiagramTextFrameMode.LINE_UNION) {
    width = .0008f
}
connection("thread", frame.attach(), otherFrame.attach())
```

The outline is the union's exposed perimeter: no shared seams are drawn or offered to sliding
endpoints. Concave notches, holes and disconnected contours remain valid. `BOUNDARY` slides
on any exposed edge, `LEFT_MARGIN` and `RIGHT_MARGIN` use the corresponding outward-facing
vertical edges, and `UNDERLINE` uses bottom-facing edges. `CLOSEST` may stay inside the filled
union. Fixed `LEFT` and `RIGHT` landmarks project the outer side's midpoint onto the boundary.

**Merge small steps** expands a shorter row toward wider immediately adjacent rows. A side
snaps outward when its difference is at most twice the standoff and the padded rows touch.
Left and right sides are considered independently; genuine large steps remain. Blank lines
and larger vertical gaps do not participate. Set `frame.mergeSmallSteps = false` for the raw
union. `DiagramTextFrameMode.BOUNDS` retains the original enclosing rectangle.

Changes to mode, standoff, simplification, typography or scale rebuild a cached text-local
union. Moving, turning or animating the plane's position reuses that boundary. Drawing and
connection projection consume the same geometry, including after live mode changes.

## Mechanical corner extensions

`overstrokeFraction` and `overstrokeDistance` add eight outward marks at the overall
bounding corners, leaving the frost mask and sliding boundary intact. See
[Mechanical text holders](17-mechanical-text-holders.md) for the builder API,
seekable overstroke cues and two-ended frustum holders.
