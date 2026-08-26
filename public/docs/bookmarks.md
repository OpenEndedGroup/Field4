# Bookmarks

Bookmarks let you save a working view of a document and jump back to it later.

![Bookmark badges shown in the spatial navigation overlay](assets/bookmarks-overlay.png)

A bookmark stores UI state rather than a box reference, so what it restores is a combination of camera position, selection, editor state and opened nests.

## Save and restore

Field provides nine bookmark slots.

Save the current view:

- `Shift + Cmd + F1` through `Shift + Cmd + F9` on macOS
- `Shift + Ctrl + F1` through `Shift + Ctrl + F9` on other platforms

Restore a saved view:

- `Shift + F1` through `Shift + F9`

The same actions are also available from the command palette as `Save Bookmark N` and `Restore Bookmark N`.

## What a bookmark remembers

The bookmark system currently captures:

- canvas pan position
- the selected box, when there is exactly one selected box
- the property open in Monaco
- the editor cursor line and column
- the open or closed state of nested disclosures

Restoring a bookmark reapplies those pieces in that order so the document returns to roughly the same working context.

## Typical workflow

A document with several working areas gets one bookmark each — the overview of the graph, the code
box you are editing, the nested region you keep opening — and then `Shift + F1` through `Shift + F3`
instead of panning. That is most of the value during live coding and demos, where the panning is
what costs you.

## Persistence

Bookmarks live on the root box as the `bookmarks` property and are persisted with the rest of the
document state. So they survive a reload, they travel with the document rather than with your
browser session, and saving the document saves them.

## Limits

- only one selected box is stored; multi-selection is not restored as a set
- bookmark slots are fixed from 1 through 9
- restoring a bookmark does not change document content; it only restores view and editor context
- if a slot has not been set yet, restoring it shows a notification instead of changing the view

If you want to save the document's content state rather than the current view state, use [Document History](document_history.md) instead.
