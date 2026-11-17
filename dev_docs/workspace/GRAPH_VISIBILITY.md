# Hide / Reveal graph branches

Alt-click a text frame, or Shift+Alt-click another kind of box, and move over **Hide / Reveal**.
A short hover opens its submenu. Releasing on **Hide / Reveal** opens it too, so the next choice can
be clicked. The six choices are:

| Action | Scope |
| --- | --- |
| Hide all children | All descendants, keeping this box visible |
| Hide this and all children | This box and every descendant |
| Reveal all children | All descendants |
| Hide all parents | All ancestors, keeping this box visible |
| Hide this and all parents | This box and every ancestor |
| Reveal all parents | All ancestors |

Shared branches are visited once. Cycles do not pull the starting box into an action that excludes
“this”. The document root and deleted boxes are traversal barriers. Connections and code execution
stay intact.

These commands use Field's existing session visibility. Reopening a document or changing visible
layers can reset it; active layer filters still apply. **Reveal All Boxes** in the command palette
recovers hidden branches even when no box is selected. Hiding a selected box clears its selection.

**Back**, in the centre of a submenu, returns to its parent. Escape goes back one level and cancels
at the root. Moving into a submenu does not execute an action until a leaf is chosen.

## Box-code menus

`Box.markingMenu { item("N", "Action") { ... }; submenu("E", "More") { ... } }` installs
a live backend menu, with nested serializable entries and callbacks. The legacy `customMarking` map
still works. See the public [marking menu guide](../../public/docs/marking_menus.md).

## Frontend submenu entries

The existing `goMarkingMenu` accepts a `children` array in place of an entry's callback. Children use
the same text, angle, radius and callback fields, and may themselves have children. A leaf closes
its menu before running its callback, so that callback can open another menu. Selection is delivered
once even when both native mouseup and interact.js's drag-end fallback arrive.

Verification: `GraphVisibilityTest` covers cycles, shared descendants, root/deletion barriers and
visibility-only changes. `tools/run_ui_regressions.py --id graph-visibility` exercises the actual
marking gestures, all six actions, Back/Escape and command-palette recovery in an independent browser.
