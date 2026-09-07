# Mobile Controllers

Field's `/controllers` page is a pocket-sized remote surface for a running Field document. Open it on a phone to adjust live controls, inspect outputs, talk to AI4, choose a box, and edit that box in Monaco without carrying the full canvas UI onto a small screen.

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
| **Group** | Phone-sized sliders, toggles, text inputs, vector pads, and color controls published by the selected box's `controlGroup(...)`. |
| **Outputs** | The selected box's output areas, reformatted into a narrow scrolling column. Native controls remain interactive. |
| **Chat** | The real AI4 conversation for the selected box and property, including attachments, code, and expandable tool-use summaries. File edits are called out by filename. |
| **Select** | Field's **Open Anything** panel, adapted for touch. Search boxes, properties, commands, and document text. Choosing a result updates every other phone tab. |
| **Edit** | A full embedded Monaco editor on the box and property chosen in **Select**. The same editor instance moves between Select and Edit, preserving its model and undo history. |

</div>

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

Open **Select** whenever you want to move elsewhere in the document. The search field's font is 16 pixels, which is the size at which mobile Safari stops zooming the page as you focus an input; rows have touch-sized targets, and the desktop preview column is omitted so results have room beside the on-screen keyboard.

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
2. Reach the Field machine through a trusted VPN or authenticated HTTPS tunnel.
3. Open `/controllers?file=...` on the phone and add it to the Home Screen if you use it often.
4. Use **Select** to find the box you care about.
5. Adjust live parameters in **Group**, check the result in **Outputs**, or use **Chat** for a larger change.
6. Use **Edit** for a small direct correction, then **Pad** to launch or stop the selected box.

Landscape works, but portrait is the primary layout. The `controllers-mobile-workspace` UI regression in `tools/run_ui_regressions.py` drives the shell at 390×844 and 430×932, and then at 390×430, which approximates the space left when the software keyboard is up.
