# A Tour of the Interface

Field has one canvas of boxes, one code editor, and a handful of panels that open over them. This
page shows each one: what it is for, how to open it, its shortcut, and how to close it. Every
panel closes the same way, with the **×** in its top-right corner or with **Esc** while it has
the focus. The command palette and Open Anything have no ×; **Esc** closes them.

If you have not made a box yet, start with the [Survival Guide](survivalGuide.md). The other
tours go further into one subject each: [Connecting Boxes](tour_connecting_boxes.md),
[Graphics on the GPU](tour_gpu_graphics.md) and [Looking at Data](tour_data.md).

## Shortcuts at a glance

These are the shortcuts for the parts of Field on this page; [Keyboard shortcuts](keybindings.md)
has the complete list. You can change any of them in `~/.afield/keybindings.yaml`, and a command
with no shortcut runs from the command palette. On Windows and Linux, `cmd` is `ctrl`.

| What | Command | Default shortcut |
| --- | --- | --- |
| Command palette | `open-command-palette` | `cmd+shift+p`, `f1` |
| Open Anything | `open-anything` | `cmd+p` |
| Insert a template | `insert-from-workspace` | `cmd+t` |
| AI chat for the selected box | `ai-chat-panel` | `cmd+4` |
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

**Insert template** (`cmd+t`) is the same kind of palette over your saved templates: pick one and
a copy of its boxes lands where the mouse is. [Templates](templates.md) covers saving them.

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
what each one shows; below, two slots are saved and named, `ideas` and `render`.
[Moving around the canvas](moving_around_the_canvas.md) explains views,
framing (`alt+f`) and resetting (`alt+h`).

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x900" clip-selector="#bookmarks-panel" screenshot="assets/tour_interface/bookmarks.png" transcript="../../dev_docs/agents/transcripts/docs_capture/bookmarks_panel.yaml" document="docs-tour-interface-bookmarks" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-bookmarks" alt="The Bookmarks panel with two of its nine slots saved, F1 named ideas and F2 named render: each shows a small map of the document's boxes with the saved view as a dashed rectangle, the view position, the box being edited and the output panels that were open, with Restore, Save and delete buttons. The other slots read empty."}
// Slot 1: `sketch` selected with its editor open. Slot 2: the same for `render`.
ui.openEditor("bm_sketch")
ui.pause(900)
ui.down("Meta")
ui.down("Shift")
ui.press("F1")
ui.up("Shift")
ui.up("Meta")
ui.pause(300)
ui.openEditor("bm_render")
ui.pause(900)
ui.down("Meta")
ui.down("Shift")
ui.press("F2")
ui.up("Shift")
ui.up("Meta")
ui.pause(300)
ui.down("Alt")
ui.press("KeyB")
ui.up("Alt")
ui.waitForSelector("#bookmarks-panel.is-visible", 8000.0)
// Name the two slots through the panel's own name fields, the way a reader would.
ui.eval(
    """
  const names = Array.from(document.querySelectorAll("#bookmarks-panel input[type=text]"));
  if (names.length < 2) return "only " + names.length + " saved slots";
  [["ideas", 0], ["render", 1]].forEach(([value, i]) => {
    names[i].value = value;
    names[i].dispatchEvent(new Event("change"));
  });
  return "named";
""",
)
ui.pause(600)
```

## Layers

Layers hide and show groups of boxes. `alt+l` opens the layers panel, where you can show, hide,
solo and activate each layer; new boxes go on the active layer. Here a document has four
layers, and `draft` is active.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x700" clip-selector="#layers-panel" screenshot="assets/tour_interface/layers.png" transcript="../../dev_docs/agents/transcripts/docs_capture/layers_guide.yaml" document="docs-tour-interface-layers" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-layers" alt="The Layers panel with four layers, default, draft, final and sketch, all shown, each with its eye, its box count and a Solo button; draft is the active layer."}
ui.eval("if (document.activeElement) document.activeElement.blur(); return 'blurred'")
ui.down("Alt")
ui.press("KeyL")
ui.up("Alt")
ui.waitForSelector("#layers-panel.is-visible", 8000.0)
// A fresh document shows only `default`. Show every layer, then make `draft` active, through the
// panel's own buttons.
ui.eval(
    """
  const all = Array.from(document.querySelectorAll("#layers-panel button")).find(b => b.textContent === "Show all");
  all.click();
  return "showed all";
""",
)
ui.pause(700)
ui.eval(
    """
  const rows = Array.from(document.querySelectorAll("#layers-panel .layer-row"));
  const draft = rows.find(r => r.querySelector(".layer-name").textContent === "draft");
  if (!draft) return "rows: " + rows.map(r => r.querySelector(".layer-name").textContent).join(",");
  draft.querySelector(".layer-active").click();
  return "activated draft";
""",
)
ui.pause(900)
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
val tb =
    ui.eval(
            "const t = document.querySelector('#field-drawing-layer .App-toolbar'); if (!t) return 'missing'; const r = t.getBoundingClientRect(); return [Math.round(r.left), Math.round(r.right), innerWidth].join(',')",
        )
        .toString()
        .trim('"')
check(tb != "missing" && tb.split(",")[1].toInt() <= 1390) {
    "the drawing toolbar is not inside the 1400px frame: " + tb
}
```

## The Macro Recorder

The Macro Recorder records what you do to boxes (making them, editing, connecting, running) and
saves it as a macro you can run again, here or in another document. Open it from the command
palette: **Macro: Recorder Panel**. Press **Record**, do something on the canvas, give the macro a
name and press **Save**; below, the recording is one drag of the `sketch` box.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x700" clip-selector="#macro-recorder-panel" screenshot="assets/tour_interface/macro-recorder.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface.yaml" document="docs-tour-interface-macro-recorder" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-macro-recorder" alt="The Macro Recorder after recording one macro: the name move_sketch_right, Record, Save and Run, the document's saved macro move_sketch_right in the list, and the transcript of the recorded move below."}
ui.eval(
    "const a = (window.lastActions || []).find(a => a.id === 'macro-transcript-panel'); if (!a) throw new Error('no action macro-transcript-panel'); a.run(); return true",
)
ui.pause(1000)
// Record a real macro: Record, close the panel so the canvas is clear, drag the sketch box to the
// right with the pointer, reopen the panel, name the recording and Save.
ui.clickSelector("#macro-recorder-panel [data-action='start']")
ui.pause(600)
ui.clickSelector("#macro-recorder-panel [data-action='close']")
ui.pause(400)
val r =
    ui.eval(
            "const b = [...document.querySelectorAll('.box')].find(b => b.uid === 'tour_sketch'); const q = b.getBoundingClientRect(); return [q.x + q.width / 2, q.y + q.height / 2]",
        )
        .toString()
        .trim('[', ']')
        .split(",")
        .map { it.trim().toDouble() }
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
val listed =
    ui.eval(
            "return [...document.querySelectorAll('#macro-recorder-panel [data-role=document-list] [data-macro-name]')].map(b => b.getAttribute('data-macro-name'))",
        )
        .toString()
check("move_sketch_right" in listed) { "the recorded macro is not listed: " + listed }
check(!listed.contains("_ui_")) { "runner plumbing is listed: " + listed }
val ops = ui.eval("return document.querySelector('#macro-recorder-panel [data-role=content]')?.value || ''").toString()
check("- move:" in ops) { "the recorded macro has no move: " + ops }
```

## Document history

Every save is kept. **Show Document History** in the command palette opens the history panel
along the bottom of the canvas: a timeline of saves, what changed in each, and buttons to bring
back a box or the whole document. Below, the box `sketch` has been edited and saved four times,
and version #1 is selected: the counts say what differs from now, **This Box** lists `sketch`'s
code version by version, and the diff shows that version against the current code.
[Document history](document_history.md) walks through it.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x1000" clip-selector=".history-iframe-container" screenshot="assets/tour_interface/history.png" transcript="assets/transcripts/document_history_setup.yaml" document="docs-tour-interface-history" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="180" embed=true hidden=true name="tour-interface-history" alt="The document history panel after four saved edits of the box sketch: the timeline of saves with version #1 selected, the counts of boxes, properties and lines that differ from the current document, This Box listing sketch's values version by version, and the diff of that version against the current code."}
val uid =
    ui.eval(
            """
            const b = [...document.querySelectorAll(".box")].find(b => (b.textContent || "").includes("sketch"))
            return b ? (b.uid || b.getAttribute("uid") || b.getAttribute("data-uid")) : null
            """
                .trimIndent(),
        )
        .toString()
        .trim('"')
check(uid.isNotBlank() && uid != "null") { "the setup transcript's sketch box is not on the canvas" }
ui.openEditor(uid)
ui.waitForSelector(".monaco-editor", 15000.0)
ui.eval(
    """
    const c = document.querySelector(".monaco-editor-container")
    for (const k of ["width", "height", "top", "right", "left", "bottom", "transform"]) c.style[k] = ""
    if (window.globalEditor && window.globalEditor.layout) window.globalEditor.layout()
    return true
    """
        .trimIndent(),
)
ui.pause(6500)
// Three more saved edits of sketch's code: radius 64, then a ring of points, then radius 80.
val edits = [
    "// Successive radius values, saved as three source versions.\nval radius = 64.0\nprintln(\"radius \" + radius)\n",
    "// Successive radius values, saved as three source versions.\nval radius = 64.0\nval ring = (0 until 12).map { it * 30.0 }\nprintln(\"radius \" + radius + \", \" + ring.size + \" points\")\n",
    "// Successive radius values, saved as three source versions.\nval radius = 80.0\nval ring = (0 until 12).map { it * 30.0 }\nprintln(\"radius \" + radius + \", \" + ring.size + \" points\")\n",
]
for (text in edits) {
    val quoted = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    ui.eval("window.globalEditor.setValue($quoted); return true")
    ui.pause(6500)
}
ui.eval("window.switchToHistoryForFile(window.currentDocumentFilename || \"default\"); return true")
// Wait for the sidebar's This Box list to hold sketch's values.
var story = ""
for (i in 0 until 100) {
    ui.pause(250)
    story =
        ui.eval(
                """
                const f = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe")
                const d = f && f.contentDocument
                return "rows=" + (d ? d.querySelectorAll("#box-story-list .box-story-item").length : 0)
                """
                    .trimIndent(),
            )
            .toString()
    if ((Regex("rows=(\\d+)").find(story)?.groupValues?.get(1)?.toInt() ?: 0) >= 4) break
}
check((Regex("rows=(\\d+)").find(story)?.groupValues?.get(1)?.toInt() ?: 0) >= 4) {
    "This Box never listed sketch's four values: " + story
}
ui.pause(800)
// Click the row holding version #1 (radius 64.0), where it is drawn.
val at =
    ui.eval(
            """
            const f = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe")
            const fr = f.getBoundingClientRect()
            const row = [...f.contentDocument.querySelectorAll("#box-story-list .box-story-item")].find(r => r.querySelector(".range").textContent.trim() === "#1")
            row.scrollIntoView({ block: "center" })
            const r = row.getBoundingClientRect()
            return [fr.left + r.left + 30, fr.top + r.top + r.height / 2].join(",")
            """
                .trimIndent(),
        )
        .toString()
        .trim('"')
        .split(",")
        .map { it.toDouble() }
ui.moveMouse(at[0], at[1], 6)
ui.clickAt(at[0], at[1])
var shown = ""
for (i in 0 until 60) {
    ui.pause(250)
    shown =
        ui.eval(
                """
                const f = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe")
                const d = f.contentDocument
                return "target=" + (d.querySelector("#target-version")?.textContent?.trim() || "") +
                    " active=" + (d.querySelector("#box-story-list .box-story-item.active .range")?.textContent?.trim() || "")
                """
                    .trimIndent(),
            )
            .toString()
    if (shown.contains("target=#1") && shown.contains("active=#1")) break
}
check(shown.contains("target=#1") && shown.contains("active=#1")) {
    "clicking This Box's #1 row did not select version #1: " + shown
}
ui.pause(1200)
```

For one line of code rather than the whole document, put the cursor on it and press
`alt+shift+h`: the editor opens that line's history in place, version by version.

## The document guide

The document guide is a reader for a document's own Markdown files and notes, beside the
canvas. Open it with **Show Document Guide** in the command palette. A box's `notes` property
is listed too; here the guide is reading the notes on `sketch`.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x600" clip-selector="#document-guide" screenshot="assets/tour_interface/document-guide.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface_document.yaml" document="docs-tour-interface-document-guide" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-document-guide" alt="The Document Guide reading the box notes of sketch: the list on the left with sketch under Box notes, and the notes themselves, a heading Ring sketch, a sentence and two bullet points, on the right."}
ui.eval("window.showDocumentGuide(); return true")
ui.waitForSelector("#document-guide #guide-notes button, #document-guide #guide-notes a", 15000.0)
// Open sketch's notes from the guide's own list, the way a reader would.
ui.eval(
    "const b = document.querySelector('#document-guide #guide-notes button, #document-guide #guide-notes a'); b.click(); return true",
)
ui.pause(1200)
val text = ui.eval("return document.querySelector('#document-guide')?.innerText || ''").toString()
check("Ring sketch" in text) { "the guide is not showing sketch's notes: " + text }
```

## The profiling timeline

**Show Profiling Timeline** records what your boxes' coroutines, the CPU and allocations are
doing for a few seconds, and lays it out as a timeline you can zoom into. Below is a
three-second recording of three running boxes, zoomed in, with the lane of the box that
allocates selected. [The profiling timeline](profiling_timeline.md) explains how to read it.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x520" clip-selector="#profile-timeline" screenshot="assets/tour_interface/profiling-timeline.png" transcript="../../dev_docs/agents/transcripts/profiling_timeline.yaml" document="docs-tour-interface-profiling-timeline" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="180" embed=true hidden=true name="tour-interface-profiling-timeline" alt="The profiling timeline after a real three-second recording of three running boxes, brushed and zoomed: the overview of allocated bytes, a lane for each box's coroutine, and the selected Allocate or reuse lane with its CPU time and allocated bytes."}
ui.eval("window.showProfilingTimeline(); return 'requested'")
var ready = false
repeat(40) {
    if (!ready) {
        ui.pause(150)
        ready =
            ui.eval(
                    "return document.getElementById('profile-status')?.textContent.startsWith('Latched') && Number(document.getElementById('profile-timeline')?.dataset.selectedCount) > 30",
                )
                ?.toString() == "true"
    }
}
check(ready) { "The real profiling demo did not produce a latched capture" }
// Frame the actual panel at a readable width; all content and plotted data come from the capture.
ui.eval("const p=document.getElementById('profile-timeline'); return 'framed'")
ui.pause(300)
ui.eval(
    """
    const metric=document.getElementById('profile-metric');
    metric.value='bytes'; metric.dispatchEvent(new Event('change'));
    const overview=document.getElementById('profile-overview');
    for(let i=0;i<4;i++) overview.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true}));
    document.getElementById('profile-zoom').click();
    const lane=[...document.querySelectorAll('.profile-labels button')].find(b=>b.textContent.includes('Allocate or reuse'));
    if(!lane) throw new Error('The vector workload has no lane');
    lane.click();
    const count=Number(document.getElementById('profile-timeline').dataset.selectedCount);
    if(count<=0) throw new Error('The brushed interval has no observed resumes');
    return count;
""",
)
ui.pause(300)
```

## Box output

What a box shows with `display(...)` (and plots, controls, and the value it ends on) appears in
panels under the box, one per channel. `println` is different: it prints in the text editor, beside
or under the line that printed. Each panel has a title bar to open or fold it; drag its corner to
resize it. [Box output](box_output.md) has the details.

Some panels look like the floating ones above but belong to one box. The
[AI chat](aiFeatures.md#chat-about-one-box) (`cmd+4` with a box selected) opens as that box's
`ai-chat` output: it reads the box's code and can propose edits to it. Here is one restored from a
saved conversation, in [a worked example](stackauto_conversation.md):

![The polytope box with its AI4 chat restored: the user's last request in orange, asking for ragged glowing frames at the burst and a whole solid at home, then a sheet of nine frames of the polytope bursting and healing, and the model's reply that the live cycle now matches what was asked for](assets/stackauto_conversation/saved-chat.png)

Others are small tools of their own, such as
the keyframe browser, which lists saved keyframes and closes with its own **×**. Below it reads
four looks saved for a glow pass, `hot`, `mid`, `soft` and `wide`, and plots each look's values
for the pass's three settings; [Keyframer examples](keyframer_examples.md#browsing-saved-keys)
shows the code that saved them.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x700" clip-selector="iframe.field-keyframe-browser" screenshot="assets/tour_interface/keyframe-browser.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface_keyframes.yaml" document="docs-tour-interface-keyframe-browser" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-keyframe-browser" alt="The keyframe browser in the glow box's output panel, reading the glow-looks folder: four saved keys, hot, mid, soft and wide, and one row for each of the three slots, glow@falloff, glow@intensity and glow@radius, with a tick for every key's value along the slot's range."}
ui.waitForSelector("iframe.field-keyframe-browser", 15000.0)
ui.pause(1500)
```

## Publish for the web

**Publish document for the web (.zip)** in the command palette captures every box's output and
packages the document as a folder you can put on any static web host. A panel shows its
progress and, when it is done, a download button; below, it has captured the output of this
document's box.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1100x700" clip-selector="#field-publication" screenshot="assets/tour_interface/publish.png" transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface_document.yaml" document="docs-tour-interface-publish" fresh-document=true hide-toasts=true padding="8" wait="0" timeout="150" embed=true hidden=true name="tour-interface-publish" alt="The Publish document panel after packaging this document: a status line counting the outputs captured, a line on what the folder holds, and the Download web publication (.zip) button."}
ui.eval(
    "const a = (window.lastActions || []).find(a => a.id === 'publish-document-web'); if (!a) throw new Error('no action publish-document-web'); a.run(); return true",
)
// Wait for the publication to finish packaging, so the panel shows its result rather than its progress.
var status = ""
for (i in 0 until 80) {
    ui.pause(250)
    status = ui.eval("return document.querySelector('#field-publication')?.innerText || ''").toString()
    if (Regex("[1-9]\\d*/[1-9]").containsMatchIn(status) && !status.contains("Capturing")) break
}
check(Regex("[1-9]\\d*/[1-9]").containsMatchIn(status)) { "publish captured no outputs: " + status }
ui.pause(800)
```

---

**The Tour:** previous, [What Field Is](funFeatures.md) · next, [Frames and the Animation Loop](animation_time_model.md)
