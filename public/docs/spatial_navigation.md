# Spatial Navigation

Field can move the selection from box to box without the mouse. There are two ways to do it: hop to a
neighbouring box with the arrow keys, or jump to one of nine saved views with a function key. Both
end with a box selected and its code in the editor.

## Cmd Cmd and hold

1. Select a box.
2. Tap `Cmd` twice quickly, holding it down on the second tap.
3. Press an arrow key to move to the box in that direction.
4. Release `Cmd`.

The two taps have to land within about a third of a second, with no other key pressed in between and
no other modifier held. `Ctrl Ctrl` does the same thing, and whichever key you started with is the one
you have to keep holding. Nothing happens if no box is selected, since the overlay has to draw itself
around something.

Each arrow selects the box in that direction, scrolls it into view, and redraws the overlay around
its new position, so you can keep arrowing without letting go. `Escape` leaves the mode early, and so
does clicking away from the window.

Letting go of the trigger key puts the focus in the editor of whichever box you landed on, ready to
type.

## What the overlay draws

While the mode is up, Field draws over the canvas:

- a solid outline around the box you are on
- a dashed outline around each box you can reach, up to four, one per direction
- a line from your box to each of those, and an arrow badge on the far side of each one
- an `F` chip on any box that a bookmark slot points at

A direction with no box in it is absent from the overlay, and pressing that arrow does nothing.

## How the four are chosen

Field sorts the visible boxes by the dominant axis of their offset from the current one. A box
further to the left than it is above counts as west, and so on. Within each direction it takes the
nearest, counting sideways distance as a fifth of distance along the direction of travel, so a box
that is slightly off-axis does not beat one that is directly ahead.

Only one box per direction is offered, so a dense column collapses to its nearest member. Boxes that
are hidden, deleted, or drawn at zero size are not candidates.

## Bookmarks

Arrows reach neighbours. For everywhere else there are nine numbered slots.

- Save the current view: `Cmd+Shift+F1` through `Cmd+Shift+F9`
- Restore a saved view: `Shift+F1` through `Shift+F9`

`cmd` is Command on macOS and Control everywhere else. Both sets are rebindable as
`save-bookmark-N` and `restore-bookmark-N`, and both appear in the command palette as
`Save Bookmark N` and `Restore Bookmark N`. See [Keyboard Shortcuts](keybindings.md).

A slot stores a view rather than a box reference. Restoring one reapplies, in this order:

- the canvas pan position
- the open or closed state of the nested disclosures, before anything is selected
- the selected box, when exactly one box was selected at save time
- the property that was open in Monaco
- the editor cursor line and column

## Slots inside the overlay

Bring up the navigation overlay and every box on screen that a slot points at wears an `F` chip
with that slot's number. A box can wear more than one if several slots were saved on it. This is
where the two halves of the feature meet: the arrows tell you what is next to you, and the chips
tell you which of the nine keys reaches somewhere that is not.

The number on a chip is a slot, so `F3` means the view saved in slot 3, reached with `Shift+F3`.

## Persistence

Bookmarks live on the root box as the `bookmarks` property and are saved with the rest of the
document state. They survive a reload, and they travel with the document rather than with your
browser session.

## Limits

- one target per direction, so the overlay never offers a choice between two boxes on the same side
- a slot stores a single selected box, so a multi-selection is not restored as a set
- there are nine slots and the numbering is fixed
- restoring a slot changes the view and never the document content
- restoring a slot you have not set yet shows a notification and leaves the view alone

## Related features

- [Keyboard Shortcuts](keybindings.md) covers rebinding, and lists both commands for all nine slots.
- [Snapping](snapping.md) is the other half of arranging a document you intend to navigate this way.
- [Document History](document_history.md) saves the document's content rather than your view of it.
