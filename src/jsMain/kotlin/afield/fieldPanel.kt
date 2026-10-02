package afield

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * A floating panel over the canvas: a black shell with a title, a status line, a close button and
 * a scrolling body. The look follows the macro recorder's shell and the history viewer's cards;
 * the CSS is `.field-panel` in field.css. The bookmarks panel is the first user, and other
 * canvas-level panels (layers) are meant to be built on it rather than on a copy.
 *
 * Keys and mouse-downs are kept inside the panel, so typing in one of its fields never reaches a
 * canvas shortcut and a click never deselects boxes. Capture-phase listeners (the bookmark F-keys)
 * still see them.
 */
class FieldPanel(id: String, title: String, private val onHide: () -> Unit = {}) {
    val root: HTMLElement = document.createElement("div") as HTMLElement
    val status: HTMLElement
    val body: HTMLElement

    init {
        root.id = id
        root.className = "field-panel"
        val head = el("div", "field-panel-head")
        val titles = el("div")
        titles.appendChild(el("div", "field-panel-title", title))
        status = el("div", "field-panel-status")
        titles.appendChild(status)
        head.appendChild(titles)
        val close = el("button", "field-panel-icon", "×")
        close.setAttribute("type", "button")
        close.title = "Close"
        close.addEventListener("click", { hide() })
        head.appendChild(close)
        root.appendChild(head)
        body = el("div", "field-panel-body")
        root.appendChild(body)

        root.addEventListener("keydown", { event ->
            event.stopPropagation()
            if (event.asDynamic().key == "Escape") hide()
        })
        root.addEventListener("mousedown", { event -> event.stopPropagation() })
        root.addEventListener("wheel", { event -> event.stopPropagation() })
        document.body!!.appendChild(root)
    }

    val isVisible: Boolean get() = root.classList.contains("is-visible")

    fun show() {
        root.classList.add("is-visible")
    }

    fun hide() {
        if (!isVisible) return
        root.classList.remove("is-visible")
        onHide()
    }

    fun toggle() = if (isVisible) hide() else show()

    companion object {
        /** A new element with an optional class list and text; text is never parsed as HTML. */
        fun el(tag: String, className: String = "", text: String? = null): HTMLElement {
            val e = document.createElement(tag) as HTMLElement
            if (className.isNotEmpty()) e.className = className
            if (text != null) e.textContent = text
            return e
        }
    }
}
