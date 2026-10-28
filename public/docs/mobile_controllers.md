# Field from your phone

Open Field's `/controllers` page in a phone browser and the phone becomes a remote for a running Field document: adjust live controls, read outputs, talk to AI4, pick a box, and edit that box in Monaco, none of which asks you to squeeze the canvas UI onto a phone.

This page needs nothing on the phone but a browser. A phone that already runs an OSC control surface, such as TouchOSC, can instead talk to a box directly: see [OSC and TUIO](osc.md).

The phone follows Field's current box selection, and a selection made on the phone becomes the document's. There is one selection behind all six tabs: **Select** sets it, and **Pad**, **Group**, **Outputs**, **Chat** and **Edit** act on whatever it currently is.

## Open it on your phone

Start Field and open the document you want to control. From a device that can reach the Field machine, open:

```diffstatic {data-lang=text}
https://FIELD-HOST:9092/controllers?file=YOUR-DOCUMENT
```

Replace `FIELD-HOST` with the machine's LAN name or address and `YOUR-DOCUMENT` with the document name. A bare `/controllers` can follow the active Field document, but an explicit `?file=` is more predictable when several documents are open.

!!! warning "Do not expose this to the public internet"
    This page can run boxes, stop them, edit code, and ask AI4 to use tools. Use it on a trusted local network or through a private, authenticated tunnel. Do not expose Field's port directly to the public internet. On first use, mobile Safari may also ask you to accept Field's development HTTPS certificate.

## The six tabs

<div class="field-data-table" markdown>

| Tab | What it controls |
| --- | --- |
| **Pad** | A large touch surface, sending Y, X or XY in absolute, delta or both modes, with shortcuts for save, restore, lock, unlock, launch, and stop. |
| **Group** | Phone-sized sliders, toggles, text inputs, vector pads, color controls, multisliders, matrices, range sliders and menus published by the selected box's `controlGroup(...)`, with their playheads. |
| **Outputs** | The selected box's output areas, reformatted into a narrow scrolling column. Native controls remain interactive. |
| **Chat** | The real AI4 conversation for the selected box and property, including attachments, code, and expandable tool-use summaries. File edits are called out by filename. |
| **Select** | Field's **Open Anything** panel, adapted for touch. Search boxes, properties, commands, and document text. Choosing a result updates every other phone tab. |
| **Edit** | A full embedded Monaco editor on the box and property chosen in **Select**. The same editor instance moves between Select and Edit, preserving its model and undo history. |

</div>

Below are Pad, Group and Outputs at iPhone size, showing a box that publishes a small `controlGroup(...)` and one output channel. Chat and Select have their own sections further down.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-mobile-controller-pad" screenshot="assets/mobile_controllers/pad.png" alt="The Pad tab of the Field mobile controller at iPhone size" padding="0" wait="0" timeout="120" embed=true hidden=true name="mobile-controller-pad"}
ui.eval("""
    document.getElementById("docs-mobile-controller-pad")?.remove()
    const driveBox = [...document.querySelectorAll(".box")].find(box => box.uid === "__docs_drive__" || box.name === "__docs_drive__")
    if (driveBox && typeof window.selectBox === "function") {
        ;(window.selectedList || []).slice().forEach(box => window.deselectBox(box))
        window.selectBox(driveBox)
        window.selectionContextChanged?.()
    }
    const params = new URLSearchParams(window.location.search)
    params.set("page", "pad")
    const frame = document.createElement("iframe")
    frame.id = "docs-mobile-controller-pad"
    frame.setAttribute("title", "Field mobile controller pad")
    frame.setAttribute("src", "/controllers?" + params.toString())
    frame.setAttribute("style", "position:fixed;left:32px;top:32px;width:390px;height:844px;border:0;outline:1px solid rgba(72,201,255,.75);background:#050505;z-index:2147483000")
    document.body.appendChild(frame)
    return frame.getAttribute("src")
""".trimIndent())
ui.waitForSelector("#docs-mobile-controller-pad", 8000.0)
ui.pause(6000)
```

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-mobile-controller-group" screenshot="assets/mobile_controllers/group.png" alt="The Group tab of the Field mobile controller showing a box's controlGroup at iPhone size" padding="0" wait="0" timeout="120" embed=true hidden=true name="mobile-controller-group"}
controlGroup("controls") {
    slider("gain", min = 0.0, max = 1.0, step = 0.01, initialValue = 0.35) { }
    vec2Control("orbit", initialValue = afield.math.linalg.Vector2f(0.35f, -0.2f)) { }
    checkbox("armed", initialValue = true) { }
    stringInput("name", initialValue = "Field") { }
    colorPicker("accent", initialValue = afield.math.linalg.Vector4f(0.95f, 0.45f, 0.15f, 0.85f)) { }
}
ui.eval("""
    document.getElementById("docs-mobile-controller-group")?.remove()
    const driveBox = [...document.querySelectorAll(".box")].find(box => box.uid === "__docs_drive__" || box.name === "__docs_drive__")
    if (driveBox && typeof window.selectBox === "function") {
        ;(window.selectedList || []).slice().forEach(box => window.deselectBox(box))
        window.selectBox(driveBox)
        window.selectionContextChanged?.()
    }
    const params = new URLSearchParams(window.location.search)
    params.set("page", "controls")
    const frame = document.createElement("iframe")
    frame.id = "docs-mobile-controller-group"
    frame.setAttribute("title", "Field mobile controller group")
    frame.setAttribute("src", "/controllers?" + params.toString())
    frame.setAttribute("style", "position:fixed;left:32px;top:32px;width:390px;height:844px;border:0;outline:1px solid rgba(72,201,255,.75);background:#050505;z-index:2147483000")
    document.body.appendChild(frame)
    return frame.getAttribute("src")
""".trimIndent())
ui.waitForSelector("#docs-mobile-controller-group", 8000.0)
ui.pause(6000)
```

The Group tab also carries multisliders, matrices, range sliders and menus, with their playheads.
Here is a small step sequencer, first in the desktop app beside its code, then on the phone:

```diffstatic_runner {data-lang=kotlin capture="window" with-editor="true" viewport="1440x900" execution="launch" open-outputs="true" wait-output="true" wait-output-kind="display" screenshot="assets/mobile_controllers/desktop-sequencer.png" alt="The Field desktop app: on the canvas, a box named sequencer with its output panel open, showing a sixteen-bar velocity multislider, a four-row pattern grid with cells in red, green and blue, a band range slider and a wave menu, with an orange playhead on one column; beside it the code editor open on the box's Kotlin, the controlGroup declaring those four controls and the loop that drives the playheads" wait="1.5" timeout="180" w="220" h="80" embed=true hidden=true name="sequencer"}
var velocity = 16 { i -> 0.5 + 0.4 * Math.sin(i * 0.6) }
var pattern = 4 { r -> 16 { c -> if ((c + r * 3) % 8 == 0) 1 + (c / 4) % 3 else 0 } }
var band = 200.0..900.0
var wave = "saw"

lateinit var steps: ArrayControlHandle<List<Double>>
lateinit var grid: ArrayControlHandle<List<List<Int>>>
lateinit var range: ArrayControlHandle<ClosedFloatingPointRange<Double>>

controlGroup("sequencer") {
    steps = multiSlider("velocity", count = 16, initialValues = velocity) { velocity = it }
    grid = matrix("pattern", rows = 4, columns = 16, states = 4, initialValue = pattern) {
        pattern = it
        print(it.h)
    }
    range = rangeSlider("band", min = 20.0, max = 2000.0, initialValue = band) {
        band = it
        print(it.h)
    }
    menu("wave", ["sine", "saw", "square", "noise"], initialIndex = 1) { _, name ->
        wave = name
    }
}

// Eight steps a second, read off the clock every frame.
val start = System.nanoTime()
loop {
    val step = ((System.nanoTime() - start) / 125_000_000L).toInt()
    steps.playhead(step % 16)
    grid.playhead(step % 16)
    range.playhead(20.0 + (step % 64) / 63.0 * 1980.0)
}
```


```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-mobile-controller-sequencer" transcript="../../dev_docs/agents/transcripts/docs_capture/phone_sequencer.yaml" screenshot="assets/mobile_controllers/group-sequencer.png" alt="The Group tab of the Field mobile controller at iPhone size showing the box named sequencer: a velocity multislider of sixteen bars, a four-row pattern grid with cells in red, green and blue and an orange playhead on one column, a band range slider and a wave menu reading saw, with the six tabs along the bottom" padding="0" wait="0" timeout="180" embed=true hidden=true name="mobile-controller-sequencer"}
ui.eval("""
    document.getElementById("docs-mobile-controller-sequencer")?.remove()
    const seq = [...document.querySelectorAll(".box")].find(box => box.uid === "seq_box" || box.name === "sequencer")
    if (seq && typeof window.selectBox === "function") {
        ;(window.selectedList || []).slice().forEach(box => window.deselectBox(box))
        window.selectBox(seq)
        window.selectionContextChanged?.()
    }
    const params = new URLSearchParams(window.location.search)
    params.set("page", "controls")
    const frame = document.createElement("iframe")
    frame.id = "docs-mobile-controller-sequencer"
    frame.setAttribute("title", "Field mobile controller step sequencer")
    frame.setAttribute("src", "/controllers?" + params.toString())
    frame.setAttribute("style", "position:fixed;left:32px;top:32px;width:390px;height:844px;border:0;outline:1px solid rgba(72,201,255,.75);background:#050505;z-index:2147483000")
    document.body.appendChild(frame)
    return frame.getAttribute("src")
""".trimIndent())
ui.waitForSelector("#docs-mobile-controller-sequencer", 8000.0)
ui.pause(6000)
```


```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-mobile-controller-outputs" screenshot="assets/mobile_controllers/outputs.png" alt="The Outputs tab of the Field mobile controller showing a box's output areas at iPhone size" padding="0" wait="0" timeout="120" embed=true hidden=true name="mobile-controller-outputs"}
controlGroup("controls") {
    slider("gain", min = 0.0, max = 1.0, step = 0.01, initialValue = 0.35) { }
    checkbox("armed", initialValue = true) { }
}
display("main", "<div style='padding:12px;font:15px system-ui'><b>gain</b> 0.35 · <b>armed</b> yes</div>")
ui.eval("""
    document.getElementById("docs-mobile-controller-outputs")?.remove()
    const driveBox = [...document.querySelectorAll(".box")].find(box => box.uid === "__docs_drive__" || box.name === "__docs_drive__")
    if (driveBox && typeof window.selectBox === "function") {
        ;(window.selectedList || []).slice().forEach(box => window.deselectBox(box))
        window.selectBox(driveBox)
        window.selectionContextChanged?.()
    }
    const params = new URLSearchParams(window.location.search)
    params.set("page", "outputs")
    const frame = document.createElement("iframe")
    frame.id = "docs-mobile-controller-outputs"
    frame.setAttribute("title", "Field mobile controller outputs")
    frame.setAttribute("src", "/controllers?" + params.toString())
    frame.setAttribute("style", "position:fixed;left:32px;top:32px;width:390px;height:844px;border:0;outline:1px solid rgba(72,201,255,.75);background:#050505;z-index:2147483000")
    document.body.appendChild(frame)
    return frame.getAttribute("src")
""".trimIndent())
ui.waitForSelector("#docs-mobile-controller-outputs", 8000.0)
ui.pause(6000)
```

The **Edit** tab, open on the sequencer's code:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-mobile-controller-edit" transcript="../../dev_docs/agents/transcripts/docs_capture/phone_sequencer.yaml" screenshot="assets/mobile_controllers/edit.png" alt="The Edit tab of the Field mobile controller at iPhone size, with Monaco open on the Kotlin of the box named sequencer" padding="0" wait="0" timeout="180" embed=true hidden=true name="mobile-controller-edit"}
ui.eval("""
    document.getElementById("docs-mobile-controller-edit")?.remove()
    const seq = [...document.querySelectorAll(".box")].find(box => box.uid === "seq_box" || box.name === "sequencer")
    if (seq && typeof window.selectBox === "function") {
        ;(window.selectedList || []).slice().forEach(box => window.deselectBox(box))
        window.selectBox(seq)
        window.selectionContextChanged?.()
    }
    const params = new URLSearchParams(window.location.search)
    params.set("page", "editor")
    const frame = document.createElement("iframe")
    frame.id = "docs-mobile-controller-edit"
    frame.setAttribute("title", "Field mobile controller editor")
    frame.setAttribute("src", "/controllers?" + params.toString())
    frame.setAttribute("style", "position:fixed;left:32px;top:32px;width:390px;height:844px;border:0;outline:1px solid rgba(72,201,255,.75);background:#050505;z-index:2147483000")
    document.body.appendChild(frame)
    return frame.getAttribute("src")
""".trimIndent())
ui.waitForSelector("#docs-mobile-controller-edit", 8000.0)
ui.pause(10000)
```

## The Pad scrubs a number in your code

The Pad does not send values to a box. It edits code, the way Field's [OSC editor](osc.md#fields-own-osc-editor) does: dragging replaces the number under the editor's cursor with the pad's value and, after a short pause, runs the box again. Put the cursor on a constant, either in the desktop editor or in the phone's **Edit** tab, then drag.

- **Value.** In absolute mode the number becomes the finger's position, from 0 at the bottom (or left) edge to 1 at the top (or right). In delta mode the drag's movement is added to it. *Both* does each in turn.
- **XY.** With the XY axis, a cursor on either number of a pair such as `Vector2f(0.2f, 0.7f)` sets both numbers at once.
- **Save** and **Restore** remember the number under the cursor and put it back. **Lock** (⌖) pins the pad to that number, so it keeps scrubbing it after the cursor moves on; **Unlock** releases it.
- **Launch** and **Stop** launch and stop the box being edited.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="1320x900" hide-toasts=true transcript="../../dev_docs/agents/transcripts/docs_capture/pad_scrub.yaml" video="assets/mobile_controllers/pad_scrub.mp4" fps="20" padding="0" wait="0" timeout="180" embed=true hidden=true name="mobile-controller-pad-scrub" alt="On the left, the Field mobile controller's Pad at phone size; on the right, the desktop editor open on a box named brightness whose first line is val brightness = 0.50, with the cursor on the number. A finger drags up the Pad and back down: each time the drag pauses, the number in the editor changes to the pad's height, between 0 and 1, and the box runs again, its output panel on the canvas showing the new value in a colour that brightens and darkens with it."}
ui.openEditor("pad_box")
ui.pause(800)
ui.eval("""
    const c = document.querySelector(".monaco-editor-container")
    if (c) for (const k of ["width", "height", "top", "right", "left", "bottom", "transform"]) c.style[k] = ""
    window.globalEditor?.layout?.()
    const box = [...document.querySelectorAll(".box")].find(b => b.uid === "pad_box" || b.name === "brightness")
    if (box && typeof window.selectBox === "function") {
        ;(window.selectedList || []).slice().forEach(b => window.deselectBox(b))
        window.selectBox(box)
        window.selectionContextChanged?.()
    }
    const ed = window.globalEditor
    const line = ed.getModel().getLineContent(1)
    const col = line.indexOf("0.50") + 2
    ed.setPosition({ lineNumber: 1, column: col })
    ed.focus()
    const params = new URLSearchParams(window.location.search)
    params.set("page", "pad")
    const frame = document.createElement("iframe")
    frame.id = "docs-mobile-controller-pad-scrub"
    frame.setAttribute("title", "Field mobile controller pad")
    frame.setAttribute("src", "/controllers?" + params.toString())
    frame.setAttribute("style", "position:fixed;left:24px;top:24px;width:360px;height:780px;border:0;outline:1px solid rgba(72,201,255,.75);background:#050505;z-index:2147483000")
    document.body.appendChild(frame)
    return col
""".trimIndent())
ui.waitForSelector("#docs-mobile-controller-pad-scrub", 8000.0)
ui.pause(4000)
val area = ui.eval("""
    const frame = document.getElementById("docs-mobile-controller-pad-scrub")
    const r = frame.getBoundingClientRect()
    const a = frame.contentDocument.getElementById("touch-area").getBoundingClientRect()
    return [r.left + a.left, r.top + a.top, a.width, a.height].join(",")
""".trimIndent()).toString().trim('"').split(",").map { it.toDouble() }
val x = area[0] + area[2] / 2
val top = area[1]
val h = area[3]
fun yAt(v: Double) = top + h * (1.0 - v)
ui.moveMouse(x, yAt(0.5), 8)
ui.pause(400)
ui.mouseDown()
for (v in listOf(0.6, 0.7, 0.8, 0.9, 0.7, 0.5, 0.3, 0.15, 0.4)) {
    ui.moveMouse(x, yAt(v), 14)
    ui.pause(900)
}
ui.mouseUp()
ui.pause(1500)
```

The Pad works without the OSC editor being switched on in the welcome page; it talks to Field over the controller's own connection.

## Link straight to a tab

The page reads its settings from the address, so a Home Screen bookmark can open exactly the surface you want:

<div class="field-data-table" markdown>

| Parameter | Values | Default |
| --- | --- | --- |
| `file` (or `document`) | The document's name | The active document |
| `page` | `pad`, `controls` (Group), `outputs`, `chat`, `select`, `editor` (Edit) | `pad` |
| `axis` | `y`, `x`, `xy` | `y` |
| `send` | `absolute`, `delta`, `both` | `absolute` |
| `debounceMs` | How long the Pad waits after the last change before running the box, 0 to 5000 | 200 |
| `maxExecutionMs` | During a continuous drag, the longest the Pad waits before running the box anyway, 0 to 60000 (0 waits for the drag to pause) | 750 |

</div>

For example, `/controllers?file=YOUR-DOCUMENT&page=controls` opens on the Group tab.

## Chat without the desktop

The Chat tab is the same AI4 system used in a box output area. It knows the selected box, its current property, and the document around it. Press **Send** or use the return key to submit. While AI4 works, tool activity appears as compact expandable cards; proposed file edits remain open and put the target filename in the card heading.

The input stays pinned above the tab bar. When the iPhone keyboard appears, the conversation becomes the flexible part of the layout: the input, Send button, and navigation remain reachable.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-mobile-controller-chat" screenshot="assets/mobile_controllers/chat.png" alt="AI4 Chat in the Field mobile controller at iPhone size" padding="0" wait="0" timeout="120" embed=true hidden=true name="mobile-controller-chat"}
ui.eval("""
    document.getElementById("docs-mobile-controller-chat")?.remove()
    const driveBox = [...document.querySelectorAll(".box")].find(box => box.name === "__docs_drive__")
    if (driveBox && typeof window.selectBox === "function") {
        ;(window.selectedList || []).slice().forEach(box => window.deselectBox(box))
        window.selectBox(driveBox)
        window.selectionContextChanged?.()
    }
    const params = new URLSearchParams(window.location.search)
    params.set("page", "chat")
    const frame = document.createElement("iframe")
    frame.id = "docs-mobile-controller-chat"
    frame.setAttribute("title", "Field mobile controller chat")
    frame.setAttribute("src", "/controllers?" + params.toString())
    frame.setAttribute("style", "position:fixed;left:32px;top:32px;width:390px;height:844px;border:0;outline:1px solid rgba(72,201,255,.75);background:#050505;z-index:2147483000")
    document.body.appendChild(frame)
    return frame.getAttribute("src")
""".trimIndent())
ui.waitForSelector("#docs-mobile-controller-chat", 8000.0)
ui.pause(10000)
ui.eval("""
    const controller = document.getElementById("docs-mobile-controller-chat")
    const chatFrame = controller?.contentDocument?.querySelector("#chat-frame-host .remote-frame")
    const field = chatFrame?.contentWindow
    const target = [...(field?.document?.querySelectorAll(".box") || [])].find(box => box.name !== "root")
    if (target && typeof field?.navigateToBoxProperty === "function") {
        field.navigateToBoxProperty(target.uid, "code")
    }
    return target?.uid || ""
""".trimIndent())
ui.pause(1800)
```

AI4 still needs the same provider credentials and configuration it uses on the desktop. The phone does not create a second AI session or a second copy of the box; it is another view of the running Field document.

## Select, then edit

Open **Select** whenever you want to move elsewhere in the document. It searches the same things as the desktop panel, with larger rows and no preview column, so the results fit above the on-screen keyboard.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-mobile-controller-select" screenshot="assets/mobile_controllers/select.png" alt="Open Anything in the Field mobile controller at iPhone size" padding="0" wait="0" timeout="120" embed=true hidden=true name="mobile-controller-select"}
ui.eval("""
    document.getElementById("docs-mobile-controller-select")?.remove()
    const params = new URLSearchParams(window.location.search)
    params.set("page", "select")
    const frame = document.createElement("iframe")
    frame.id = "docs-mobile-controller-select"
    frame.setAttribute("title", "Field mobile controller Open Anything")
    frame.setAttribute("src", "/controllers?" + params.toString())
    frame.setAttribute("style", "position:fixed;left:32px;top:32px;width:390px;height:844px;border:0;outline:1px solid rgba(72,201,255,.75);background:#050505;z-index:2147483000")
    document.body.appendChild(frame)
    return frame.getAttribute("src")
""".trimIndent())
ui.waitForSelector("#docs-mobile-controller-select", 8000.0)
ui.pause(10000)
ui.eval("""
    const controller = document.getElementById("docs-mobile-controller-select")
    controller?.contentDocument?.querySelector('[data-page="select"]')?.click()
    return controller?.contentDocument?.querySelector('[data-page="select"]')?.classList.contains("active") || false
""".trimIndent())
ui.pause(120)
```

Tap a result to select its box and property. The controller switches to **Edit**, where Monaco fills the available area. Return to **Select** to jump again; switching does not discard the editor model.

## On the road

1. Leave Field running with the working document open.
2. Reach the Field machine over your VPN or tunnel.
3. Open `/controllers?file=...` on the phone and add it to the Home Screen if you use it often.
4. Use **Select** to find the box you care about.
5. Adjust live parameters in **Group**, check the result in **Outputs**, or use **Chat** for a larger change.
6. Use **Edit** for a small direct correction, then **Pad** to launch or stop the selected box.

Landscape works, but the controller is laid out for portrait, and it stays usable with the keyboard raised.
