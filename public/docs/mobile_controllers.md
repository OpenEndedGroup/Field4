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

Below are Pad, Group, Outputs and Edit at iPhone size, each showing a box that publishes a small `controlGroup(...)` and one output channel. Chat and Select have their own sections further down.

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
    vec2Control("orbit", initialValue = afield.stdlib.linalg.Vector2f(0.35f, -0.2f)) { }
    checkbox("armed", initialValue = true) { }
    stringInput("name", initialValue = "Field") { }
    colorPicker("accent", initialValue = afield.stdlib.linalg.Vector4f(0.95f, 0.45f, 0.15f, 0.85f)) { }
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
    menu("wave", listOf("sine", "saw", "square", "noise"), initialIndex = 1) { _, name ->
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

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-mobile-controller-edit" screenshot="assets/mobile_controllers/edit.png" alt="The Edit tab of the Field mobile controller with Monaco open on a box's code at iPhone size" padding="0" wait="0" timeout="120" embed=true hidden=true name="mobile-controller-edit"}
// A box with a few live controls, open in the phone's editor.
controlGroup("controls") {
    slider("gain", min = 0.0, max = 1.0, step = 0.01, initialValue = 0.35) { }
    checkbox("armed", initialValue = true) { }
}
ui.eval("""
    document.getElementById("docs-mobile-controller-edit")?.remove()
    const driveBox = [...document.querySelectorAll(".box")].find(box => box.uid === "__docs_drive__" || box.name === "__docs_drive__")
    if (driveBox && typeof window.selectBox === "function") {
        ;(window.selectedList || []).slice().forEach(box => window.deselectBox(box))
        window.selectBox(driveBox)
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
