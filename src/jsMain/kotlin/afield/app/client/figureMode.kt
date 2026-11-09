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
 * into a fixed host above the canvas (Field reparents boxes between panes in the same way), its chrome,
 * channel headers, panel borders and resize grips are hidden (`field.css`), and CSS zoom fits its
 * natural size, overflow included (`figureFitZoom`), to the window. Zoom rather than a transform:
 * the figure is laid out again at the larger size, so it stays sharp, and an iframe inside it (a
 * three.js scene) renders at the zoomed pixel ratio once told to resize. Nothing else
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
        // The box is laid out under CSS zoom, so its on-screen size divided by the zoom in force is
        // its natural size. scroll/offset is a ratio, so it scales that to the content an overflowing
        // output (an editable drawing) really draws.
        val applied = host.style.getPropertyValue("--field-figure-zoom").toDoubleOrNull() ?: 1.0
        val rect = area.getBoundingClientRect()
        val width = rect.width / applied
        val height = rect.height / applied
        val overflowX = if (area.offsetWidth > 0) area.scrollWidth.toDouble() / area.offsetWidth else 1.0
        val overflowY = if (area.offsetHeight > 0) area.scrollHeight.toDouble() / area.offsetHeight else 1.0
        val zoom = afield.app.protocol.figureFitZoom(
            width, height, width * overflowX, height * overflowY,
            window.innerWidth.toDouble(), window.innerHeight.toDouble()
        ) ?: return
        if (kotlin.math.abs(applied - zoom) > 0.002 * zoom) {
            host.style.setProperty("--field-figure-zoom", zoom.asDynamic().toFixed(4) as String)
            // A zoomed iframe sees the zoom as a higher devicePixelRatio, but a page sizes its canvas
            // only on resize: tell it, so a three.js figure re-renders sharp at the new size.
            box.querySelectorAll("iframe").asList().forEach { frame ->
                runCatching { (frame as org.w3c.dom.HTMLIFrameElement).contentWindow?.dispatchEvent(org.w3c.dom.events.Event("resize")) }
            }
        }
    }

    // Boxes arrive with the document, outputs a little later, and pane layout may move a box back
    // into the canvas; checking four times a second is cheap and settles all three.
    place()
    window.setInterval({ place() }, 250)
    window.addEventListener("resize", { place() })
}
