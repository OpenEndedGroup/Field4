# A Tour of the Interface

Field has one canvas of boxes, one code editor, and a handful of panels that open over them. This
page shows each one: what it is for, how to open it, its shortcut, and how to close it. Every
panel closes the same way, with the **×** in its top-right corner or with **Esc** while it has
the focus. The command palette and Open Anything have no ×; **Esc** closes them.

If you have not made a box yet, start with the [Survival Guide](survivalGuide.md). The other
tours go further into one subject each: [Connecting Boxes](tour_connecting_boxes.md),
[Graphics on the GPU](tour_gpu_graphics.md) and [Looking at Data](tour_data.md).

## Shortcuts at a glance

These are the shortcuts Field ships with. You can change any of them in
`~/.afield/keybindings.yaml` ([Keyboard shortcuts](keybindings.md)); a command with no shortcut
runs from the command palette. On Windows and Linux, `cmd` is `ctrl`.

| What | Command | Default shortcut |
| --- | --- | --- |
| Command palette | `open-command-palette` | `cmd+shift+p`, `f1` |
| Open Anything | `open-anything` | `cmd+p` |
| Shortcut card | `show-cheatsheet` | `?` |
| Documentation pane | `toggle-docs` | `cmd+/` |
| Run the line or selection | `execute-fragment` | `alt+enter` |
| Code history for a line | `history-lens-selection` | `alt+shift+h` |
| Search boxes | `document-search` | `cmd+f` (canvas focused) |
| Bookmarks panel | `show-bookmarks` | `alt+b` |
| Frame the selection | `frame-selected` | `alt+f` |
| Reset the view | `reset-view` | `alt+h` |
| Layers panel | `show-layers-panel` | `alt+l` |
| Drawing layer | `toggle-drawing-layer` | `alt+d` |
| Macro Recorder | `macro-transcript-panel` | none |
| Document history | `show-document-history` | none |
| Document guide | `show-document-guide` | none |
| Profiling timeline | `show-profiling-timeline` | none |
| Publish for the web | `publish-document-web` | none |

## The code editor

Click a box to select it, and its code opens in the editor on the right. The editor is
[Monaco](https://microsoft.github.io/monaco-editor/), the editor inside VS Code, so its own
shortcuts work: `cmd+f` finds in the code, `cmd+d` adds the next match to the selection.
`alt+enter` runs the current line or selection; the [Survival Guide](survivalGuide.md) (and its [Autocomplete](survivalGuide.md#autocomplete) section)
covers running code and autocomplete. Click the empty canvas to deselect and hide it.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x700" clip-selector=".monaco-editor-container" screenshot="assets/tour_interface/editor.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface.yaml" document="docs-tour-interface-editor" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-editor" alt="The code editor beside the canvas, showing the four lines of the box named sketch: a comment, val radius = 64.0, val count = 12, and a println of both."}
ui.openEditor("tour_sketch")
ui.waitForSelector(".monaco-editor", 15000.0)
ui.editorHeight(150)
ui.pause(600)
```

## The command palette and Open Anything

The command palette (`cmd+shift+p` or `F1`) lists every command Field has, with its shortcut,
and filters as you type. Open Anything (`cmd+p`) searches boxes, properties and text across the
document and jumps to what you pick. **Esc** closes either. The
[Survival Guide](survivalGuide.md#the-command-palette) has more on the palette.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x700" clip-selector=".afield-palette-shell" screenshot="assets/tour_interface/command-palette.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface.yaml" document="docs-tour-interface-command-palette" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-command-palette" alt="The command palette opened with F1: Document Commands beside its filter field, the list of commands with their shortcuts on the left and a preview of the highlighted one on the right."}
ui.eval("if (document.activeElement) document.activeElement.blur(); return true")
ui.press("F1")
ui.pause(1200)
```

Press `?` on the canvas to see every shortcut on one card. It reads your current bindings, so a
change to `keybindings.yaml` shows up there; see [Keyboard shortcuts](keybindings.md).

## Search boxes

With the canvas focused, `cmd+f` opens a search bar at the bottom left of the canvas. It finds
text in every box's name and code (shaders and metadata too), outlines the boxes that match, and
marks each with the properties it matched in. `Enter` and `Shift+Enter`, or the ↑ and ↓ buttons,
step through the matches: each one is selected in the editor and its box scrolled into view.
With the editor focused, the same key opens the editor's own Find instead, at the top of the
editor, for the code in front of you.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x700" clip-selector="#document-search" screenshot="assets/tour_interface/document-search.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface.yaml" document="docs-tour-interface-document-search" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-document-search" alt="The search bar: the query radius, the count 1 of 3 · code, and three square buttons, up, down and close."}
ui.eval("if (document.activeElement) document.activeElement.blur(); return true")
ui.press("Meta+f")
ui.pause(500)
ui.type("radius")
ui.pause(400)
ui.press("Enter")
ui.pause(1200)
```

## Bookmarks

`shift+F1` to `shift+F9` restore a saved view of the canvas; `cmd+shift+F1` to `F9` save one.
`alt+b` opens the bookmarks panel, which lists the slots with their names and a small map of
what each one shows. [Moving around the canvas](moving_around_the_canvas.md) explains views,
framing (`alt+f`) and resetting (`alt+h`).

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x700" clip-selector="#bookmarks-panel" screenshot="assets/tour_interface/bookmarks.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface.yaml" document="docs-tour-interface-bookmarks" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-bookmarks" alt="The Bookmarks panel: its title, a status line with how many of the nine slots are saved, and the list of slots."}
ui.eval("if (document.activeElement) document.activeElement.blur(); return true")
ui.press("Alt+b")
ui.pause(800)
```

## Layers

Layers hide and show groups of boxes. `alt+l` opens the layers panel, where you can show, hide,
solo and activate each layer; new boxes go on the active layer.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x700" clip-selector="#layers-panel" screenshot="assets/tour_interface/layers.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface.yaml" document="docs-tour-interface-layers" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-layers" alt="The Layers panel with its single default layer, shown and active."}
ui.eval("if (document.activeElement) document.activeElement.blur(); return true")
ui.press("Alt+l")
ui.pause(800)
```

## The drawing layer

`alt+d` turns on a drawing layer over the whole canvas, for notes, arrows and sketches beside the
boxes. While it is on, the mouse and keyboard draw; press `alt+d` again to go back to the boxes.
The drawing stays visible either way and moves with the canvas. Boxes can draw on it too: here
the `notes` box draws the arrow with `notation { arrow(from = sketch, to = thisBox) }`, and its
ends follow the boxes when they move. Boxes can also read what you draw:
[Drawing and Notation](drawing_and_notation.md).

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1400x300" screenshot="assets/tour_interface/drawing-layer.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface.yaml" document="docs-tour-interface-drawing-layer" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" clip="0,0,1400,280" embed=true hidden=true name="tour-interface-drawing-layer" alt="The canvas with the drawing layer on: the drawing toolbar across the top with the hand tool chosen, and below it the boxes sketch and notes joined by a blue arrow labelled notes on, drawn by the notes box."}
ui.eval("if (document.activeElement) document.activeElement.blur(); return true")
ui.press("Alt+d")
ui.pause(2000)
// The hand tool, which moves the canvas; with it chosen Excalidraw shows no hint line under the toolbar.
ui.press("h")
ui.pause(800)
// Fail rather than photograph a toolbar cut off at the frame's edge.
val tb = ui.eval("const t = document.querySelector('#field-drawing-layer .App-toolbar'); if (!t) return 'missing'; const r = t.getBoundingClientRect(); return [Math.round(r.left), Math.round(r.right), innerWidth].join(',')").toString().trim('"')
check(tb != "missing" && tb.split(",")[1].toInt() <= 1390) { "the drawing toolbar is not inside the 1400px frame: " + tb }
```

## The Macro Recorder

The Macro Recorder records what you do to boxes (making them, editing, connecting, running) and
saves it as a macro you can run again, here or in another document. Open it from the command
palette: **Macro: Recorder Panel**. Press **Record**, do something on the canvas, give the macro a
name and press **Save**; below, the recording is one drag of the `sketch` box.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x700" clip-selector="#macro-recorder-panel" screenshot="assets/tour_interface/macro-recorder.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface.yaml" document="docs-tour-interface-macro-recorder" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-macro-recorder" alt="The Macro Recorder after recording one macro: the name move_sketch_right, Record, Save and Run, the document's saved macro move_sketch_right in the list, and the transcript of the recorded move below."}
ui.eval("const a = (window.lastActions || []).find(a => a.id === 'macro-transcript-panel'); if (!a) throw new Error('no action macro-transcript-panel'); a.run(); return true")
ui.pause(1000)
// Record a real macro: Record, close the panel so the canvas is clear, drag the sketch box to the
// right with the pointer, reopen the panel, name the recording and Save.
ui.clickSelector("#macro-recorder-panel [data-action='start']")
ui.pause(600)
ui.clickSelector("#macro-recorder-panel [data-action='close']")
ui.pause(400)
val r = ui.eval("const b = [...document.querySelectorAll('.box')].find(b => b.uid === 'tour_sketch'); const q = b.getBoundingClientRect(); return [q.x + q.width / 2, q.y + q.height / 2]").toString().trim('[', ']').split(",").map { it.trim().toDouble() }
ui.moveMouse(r[0], r[1])
ui.mouseDown()
ui.moveMouse(r[0] + 160.0, r[1], steps = 12)
ui.mouseUp()
ui.pause(1000)
ui.eval("const a = (window.lastActions || []).find(a => a.id === 'macro-transcript-panel'); a.run(); return true")
ui.pause(1000)
ui.clickSelector("#macro-recorder-panel [data-role='name']")
ui.type("move_sketch_right")
ui.clickSelector("#macro-recorder-panel [data-action='save']")
ui.pause(2000)
val listed = ui.eval("return [...document.querySelectorAll('#macro-recorder-panel [data-role=document-list] [data-macro-name]')].map(b => b.getAttribute('data-macro-name'))").toString()
check("move_sketch_right" in listed) { "the recorded macro is not listed: " + listed }
check(!listed.contains("_ui_")) { "runner plumbing is listed: " + listed }
val ops = ui.eval("return document.querySelector('#macro-recorder-panel [data-role=content]')?.value || ''").toString()
check("- move:" in ops) { "the recorded macro has no move: " + ops }
```

## Document history

Every save is kept. **Show Document History** in the command palette opens the history panel
along the bottom of the canvas: a timeline of saves, what changed in each, and buttons to bring
back a box or the whole document. [Document history](document_history.md) walks through it.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x700" clip-selector=".history-iframe-container" screenshot="assets/tour_interface/history.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface.yaml" document="docs-tour-interface-history" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-history" alt="The document history panel along the bottom of the window: the timeline of saves, the change counts, and the Apply buttons and close button at the top right."}
ui.eval("const a = (window.lastActions || []).find(a => a.id === 'show-document-history'); if (!a) throw new Error('no action show-document-history'); a.run(); return true")
ui.pause(2500)
```

For one line of code rather than the whole document, put the cursor on it and press
`alt+shift+h`: the editor opens that line's history in place, version by version.

## The document guide

The document guide is a reader for a document's own Markdown files and notes, beside the
canvas. Open it with **Show Document Guide** in the command palette.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x480" clip-selector="#document-guide" screenshot="assets/tour_interface/document-guide.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface.yaml" document="docs-tour-interface-document-guide" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-document-guide" alt="The Document Guide panel: its title and close button above the list of the document's files and notes."}
ui.eval("window.showDocumentGuide(); return true")
ui.pause(1500)
```

## The profiling timeline

**Show Profiling Timeline** records what your boxes' coroutines, the CPU and allocations are
doing for a few seconds, and lays it out as a timeline you can zoom into.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x520" clip-selector="#profile-timeline" screenshot="assets/tour_interface/profiling-timeline.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface.yaml" document="docs-tour-interface-profiling-timeline" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-profiling-timeline" alt="The profiling timeline across the bottom of the window: the duration menu, Record and Latch, an empty overview, and the lanes."}
ui.eval("window.showProfilingTimeline(); return true")
ui.pause(1200)
```

## Box output

A box's output (`println`, plots, controls, `display(...)`) appears in panels under the box,
one per channel. Each panel has a title bar to open or fold it; drag its corner to resize it.
[Box output](box_output.md) has the details. Some outputs are small tools of their own, such as
the keyframe browser, which lists saved keyframes and closes with its own **×**.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x700" clip-selector="iframe.field-keyframe-browser" screenshot="assets/tour_interface/keyframe-browser.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface_keyframes.yaml" document="docs-tour-interface-keyframe-browser" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-keyframe-browser" alt="The keyframe browser in a box's output panel: the folder it is reading, a read-only tag and a close button, and a line saying no key files matched yet."}
ui.waitForSelector("iframe.field-keyframe-browser", 15000.0)
ui.pause(1500)
```

## Publish for the web

**Publish document for the web (.zip)** in the command palette captures every box's output and
packages the document as a folder you can put on any static web host. A panel shows its
progress and, when it is done, a download button.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x700" clip-selector="#field-publication" screenshot="assets/tour_interface/publish.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface.yaml" document="docs-tour-interface-publish" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-publish" alt="The Publish document panel: its title, a status line with its progress, and the close button."}
ui.eval("const a = (window.lastActions || []).find(a => a.id === 'publish-document-web'); if (!a) throw new Error('no action publish-document-web'); a.run(); return true")
ui.pause(2500)
```
