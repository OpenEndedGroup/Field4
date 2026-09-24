# Snapping

Field can snap boxes while you drag or resize them. There is no separate mode or tool palette: snapping is a toggle you turn on for a moment of layout cleanup.

<video controls autoplay loop muted playsinline style="width: min(100%, 460px);">
	<source src="assets/snapping-guide.mp4" type="video/mp4">
</video>

## Turn snapping on

Snapping is active while Caps Lock is on.

1. Press `Caps Lock`.
2. Drag a box near another box.
3. Release the mouse when the guide line lands where you want it.

When Caps Lock is off, dragging and resizing stay freeform.

## What snaps

Field looks for nearby layout relationships on each axis independently. That means one drag can snap horizontally and vertically at the same time.

Field can snap to:

- left-edge alignment
- right-edge alignment
- top-edge alignment
- bottom-edge alignment
- center alignment
- even horizontal spacing between neighboring boxes
- even vertical spacing between neighboring boxes

During a snap, Field draws cyan guide lines and tick marks so you can see what relationship it found.

## Edge and center alignment

The basic use case is lining up boxes.

- Drag near the left or right edge of another box to align vertical edges.
- Drag near the top or bottom edge of another box to align horizontal edges.
- Drag near another box's center line to align centers.

## Even spacing

Snapping also looks for gaps between nearby boxes.

If a dragged box can land between two other boxes with equal spacing, Field offers that as a snap
target and draws measurement ticks on the guide line, which is how you get an evenly spaced row or
a regular vertical stack without measuring by hand. Gaps under 5 px do not count as spacing, so a
tight cluster of boxes offers nothing to space evenly.

## Resize snapping

The same Caps Lock toggle applies while resizing.

With snapping enabled, a resize gesture can snap the edge you are dragging to nearby box edges. This is useful when you want adjacent boxes to share a boundary or keep a row visually clean.

## Thresholds

Snapping is proximity-based. A box jumps when it gets close enough, not when it lands exactly.
Alignment and spacing use different distances, and alignment draws its guide before it will take
the snap.

<div class="field-data-table" markdown>

| Alignment | guide is drawn within | box jumps within |
| --- | --- | --- |
| edge and center alignment | 20 px | 10 px |
| even spacing | 30 px | 30 px |

</div>

So a spacing snap engages anywhere its guide is visible, while an alignment guide can appear while
you are still too far out for the box to move. Resizing uses the same 10 px as alignment. Drag near
the relationship you want and watch for the guide line.

## When to use it

Arranging a document for presentation, or tidying a graph after a run of exploratory edits. It
gets in the way while sketching, so it is off unless you turn Caps Lock on. Leave Caps Lock off
and boxes go where you put them.

## Related features

- Use [Interactive Guides](interactive_guides.md) when the layout itself is part of a walkthrough.
- Use [Spatial Navigation](spatial_navigation.md) to save a useful view once you have arranged it.
- Use [Document History](document_history.md) if you want to roll back to an earlier document state rather than manually undoing a large layout change.
