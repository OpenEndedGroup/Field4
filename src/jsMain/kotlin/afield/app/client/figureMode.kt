package afield.app.client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.url.URLSearchParams

/**
 * `index.html?file=<document>&figure=<box uid>` shows one box's output area and nothing else, scaled to
 * fill the window: the live figure a presentation slide places beside its prose
 * (`afield/ui/slides/PresentationFigures.kt`). `&channels=a,b` keeps only those output channels,
 * and `&bg=%23rrggbb` sets the colour around the figure to match the slide.
 *
 * The page is an ordinary Field client of the document, so the output is the real one: its scripts
 * run, its sliders send their updates, and output replay fills it on attach. The box element is moved
 * into a fixed host above the canvas (Field reparents boxes between panes in the same way), its chrome
 * and channel headers are hidden, and a CSS scale fits its natural size to the window, overflow included
 * (`figureFitZoom`). Nothing else
 * of the document is visible or reachable, and this client never sends its viewport, so a figure
 * cannot change what `Box.viewport` reports to box code.
 */

internal var figureModeActive = false
    private set

internal fun installFigureMode(params: URLSearchParams) {
    val uid = params.get("figure")?.takeIf { it.isNotBlank() } ?: return
    figureModeActive = true
    val channels = params.get("channels").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val body = document.body ?: return
    body.classList.add("mode-figure")
    val host = document.createElement("div") as HTMLElement
    host.id = "field-figure-host"
    params.get("bg")?.takeIf { Regex("#[0-9A-Fa-f]{3,8}|rgba?\\([0-9., ]+\\)").matches(it) }?.let {
        host.style.setProperty("--field-figure-bg", it)
    }
    body.appendChild(host)

    fun place() {
        val box = document.querySelectorAll(".box").asList().firstOrNull { (it as Element).asDynamic().uid == uid } as? HTMLElement
            ?: return
        if (box.parentElement !== host) host.appendChild(box)
        box.classList.add("field-figure-box")
        val group = box.querySelector(".outputArea .disclosure-group") ?: return
        val disclosures = window.asDynamic().disclosureInterface
        val inter = if (disclosures != null) disclosures(group) else null
        group.querySelectorAll(".disclosure").asList().forEach { node ->
            val disclosure = node as HTMLElement
            val id = disclosure.asDynamic().__disclosureId as? String ?: return@forEach
            val shown = channels.isEmpty() || id in channels
            disclosure.classList.toggle("field-figure-hidden", !shown)
            if (shown && inter != null && inter.isOpen(id) != true) inter.open(id)
        }
        val area = box.querySelector(".outputArea") as? HTMLElement ?: return
        // Content wider than the area (an editable drawing) overflows to the right; widening the box
        // to it keeps the whole figure centred. Measured afresh each time, so it can shrink again.
        box.style.removeProperty("min-width")
        if (area.scrollWidth > area.offsetWidth) box.style.setProperty("min-width", "${area.scrollWidth}px", "important")
        val zoom = afield.app.protocol.figureFitZoom(
            area.offsetWidth.toDouble(), area.offsetHeight.toDouble(),
            area.scrollWidth.toDouble(), area.scrollHeight.toDouble(),
            window.innerWidth.toDouble(), window.innerHeight.toDouble()
        ) ?: return
        val current = host.style.getPropertyValue("--field-figure-zoom").toDoubleOrNull()
        if (current == null || kotlin.math.abs(current - zoom) > 0.002) {
            host.style.setProperty("--field-figure-zoom", zoom.asDynamic().toFixed(4) as String)
        }
    }

    // Boxes arrive with the document, outputs a little later, and pane layout may move a box back
    // into the canvas; checking four times a second is cheap and settles all three.
    place()
    window.setInterval({ place() }, 250)
    window.addEventListener("resize", { place() })
}
