# Inline History: the History Lens

The [document history](document_history.md) view is a whole-document affair — you leave the editor, look at a timeline, and restore. Often that is more than you want. You are staring at one line of code, you *know* it used to say something else, and you just want to see what.

The **History Lens** answers exactly that question, in place, without leaving the editor.

![The History Lens open below a line of code](assets/history-lens.png)

## Open it

Put your cursor on a line (or select a range), **right-click**, and choose **Show History Lens for Line / Selection** from the editor context menu.

The lens opens as a small zone directly beneath your selection. There is no keyboard shortcut — it lives in the context menu so it is always attached to the thing you right-clicked on.

## What it shows

The lens traces the selected line or range through saved versions using line alignment and selection
change detection, then lists the versions relevant to that trace. It is a per-line time machine, a
little like `git blame` crossed with a scrubber; it is not a dump of every save.

Each row is one matching version, labelled the same way the [history panel](document_history.md)
labels it (`#2858`, `#2857`, …), with metadata such as box count, size, and age. The value at that
version is shown with changed tokens highlighted, so you can see *what* moved between the listed
versions, not just *that* it moved.

Step through the versions with the ‹ › arrows to preview each one. When you find the state you want, the **Rollback** buttons put it back at the scope you choose:

- **Property** — restore just this property to the selected version
- **Box** — restore the whole box
- **Document** — fall through to a full [document restore](document_history.md)

## How it relates to the history panel

The History Lens and the [document history panel](document_history.md) are two faces of the same underlying record — they read from the same saved versions. The difference is scope and ceremony:

- **History panel** — whole document, its own view, restore or cherry-pick whole boxes.
- **History Lens** — one line or selection, inline in the editor, lift a single past value back.

Reach for the lens when the question is "what did *this* used to be?" and for the panel when the question is "what did the *document* used to be?".

## A different "history" in the editor

Field has a second, unrelated inline visual that also happens to be called `history`, and it is worth not confusing them.

The **`history()` visualizer** (`m.history(value)`) draws a live chart of the last N *runtime* values of an expression, printed inline next to the code that produced it. That is about the history of a *value while your code runs* — not the history of your *source across saves*. It is covered with its siblings `histogram()` and `stats()` on the [Visualizers & Controls](visualizers.md) page.

| | History Lens | `history()` visualizer |
| --- | --- | --- |
| What it tracks | your source code across saved versions | a numeric value across runtime iterations |
| Backed by | saved document backups | a rolling in-memory buffer |
| Opened by | right-click → *Show History Lens* | calling `m.history(x)` in running code |
| Survives a restart? | yes — it is on disk | no — it is live only |
