# Snapping

Field can snap boxes to each other while you drag or resize them. Turn it on with Caps Lock when
you want to tidy a layout, and off again when you go back to sketching.

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

- Drag near the left or right edge of another box to align vertical edges.
- Drag near the top or bottom edge of another box to align horizontal edges.
- Drag near another box's center line to align centers.

## Even spacing

If a dragged box can land between two other boxes with equal spacing, Field offers that as a snap
target and draws measurement ticks on the guide line, which is how you get an evenly spaced row or
a regular vertical stack without measuring by hand. Gaps under 5 px do not count as spacing, so a
tight cluster of boxes offers nothing to space evenly.

## Resize snapping

With Caps Lock on, the edge you drag while resizing snaps to nearby box edges, so adjacent boxes
can share a boundary.

## Thresholds

A box jumps to a snap once it is close enough to it. Alignment and spacing use different distances, and alignment draws its guide before it will take
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

Snapping helps when you arrange a document for presentation, or tidy a graph after a run of
exploratory edits. It gets in the way while sketching, which is why it is off until you press
Caps Lock.

## Related features

- Use [Interactive Guides](interactive_guides.md) when the layout itself is part of a walkthrough.
- Use a [bookmark](moving_around_the_canvas.md#bookmarks) to save a useful view once you have arranged it.
- Use [Document History](document_history.md) if you want to roll back to an earlier document state rather than manually undoing a large layout change.
