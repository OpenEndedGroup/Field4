# Snapping

Field can snap boxes while you drag or resize them. The snapping system is designed for quick layout cleanup rather than a separate mode or tool palette.

<video controls autoplay loop muted playsinline style="width: min(100%, 460px);">
	<source src="assets/snapping-guide.mp4" type="video/mp4">
</video>

## Turn snapping on

Snapping is active while Caps Lock is on.

Use this workflow:

1. Press `Caps Lock`.
2. Drag a box near another box.
3. Release the mouse when the guide line lands where you want it.

When Caps Lock is off, dragging and resizing stay freeform.

## What snaps

Field looks for nearby layout relationships on each axis independently. That means one drag can snap horizontally and vertically at the same time.

The current system can snap to:

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

This is useful when you are cleaning up a graph after adding a few boxes quickly and want the layout to read more clearly.

## Even spacing

Snapping also looks for gaps between nearby boxes.

If a dragged box can land between two other boxes with equal spacing, Field offers that as a snap target and draws measurement ticks on the guide line.

Use this when you want:

- evenly spaced rows of processing boxes
- regular vertical stacks
- a quick visual rhythm without measuring by hand

## Resize snapping

The same Caps Lock toggle applies while resizing.

With snapping enabled, a resize gesture can snap the edge you are dragging to nearby box edges. This is useful when you want adjacent boxes to share a boundary or keep a row visually clean.

## Thresholds

Field treats snapping as proximity-based rather than exact.

- edge and center candidates are considered when they are near the current drag position
- the applied snap uses a tighter threshold before the box actually jumps into place
- spacing snaps tolerate a larger search area so they are easier to discover

In practice, the easiest way to use it is simply to drag near the intended relationship and watch for the guide line.

## When to use it

Snapping is most useful when:

- arranging a document for presentation or teaching
- cleaning up a box graph after exploratory edits
- building rows or columns of related boxes
- making parent and child regions feel visually intentional

It is less useful when you are sketching quickly and do not want layout constraints. In that case, leave Caps Lock off and place boxes freely.

## Related features

- Use [Interactive Guides](interactive_guides.md) when the layout itself is part of a walkthrough.
- Use [Bookmarks](bookmarks.md) to save a useful view once you have arranged it.
- Use [Document History](document_history.md) if you want to roll back to an earlier document state rather than manually undoing a large layout change.
