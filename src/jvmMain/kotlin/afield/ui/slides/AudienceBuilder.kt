package afield.ui.slides

import afield.ui.output.FieldMarkdown
import kotlinx.html.*

/** A rendered Markdown fragment, inserted with unary plus inside [AudienceBuilder]. */
class AudienceMarkdown internal constructor(internal val html: String)

/** How an audience image fits its available space. */
enum class AudienceImageFit(internal val css: String) { Contain("contain"), Cover("cover") }

/** Standard HTML slide content, with scoped Markdown, stylesheets and image layouts. */
class AudienceBuilder internal constructor(
    consumer: TagConsumer<*>, classes: String = "field-slide"
) : DIV(attributesMapOf("class", classes), consumer) {
    /** Render an indented Markdown block; ordinary `+String` still escapes text as HTML. */
    val String.md: AudienceMarkdown get() = AudienceMarkdown(FieldMarkdown.render(this))

    /** Insert a rendered Markdown fragment without escaping its generated tags. */
    operator fun AudienceMarkdown.unaryPlus() { unsafe { +html } }

    /** A nested HTML container that keeps the slide's scoped Markdown syntax available. */
    fun div(classes: String? = null, block: AudienceBuilder.() -> Unit = {}) {
        AudienceBuilder(consumer, classes.orEmpty()).visit(block)
    }

    /** Load a stylesheet relative to this document's files/ directory; Refresh slide reloads it. */
    fun stylesheet(source: String) {
        require(source.isNotBlank()) { "A stylesheet needs a filename or URL" }
        LINK(attributesMapOf("rel", "stylesheet", "href", source), consumer).visit {
            attributes["data-field-presentation-style"] = ""
        }
    }

    /** Fade this text, image or group in on forward entry; Back shows its completed state. */
    fun fadeIn(durationMs: Int = 350, delayMs: Int = 0, block: AudienceBuilder.() -> Unit) {
        require(durationMs in 0..10_000 && delayMs in 0..60_000) { "Use a fade of 0–10000 ms and a delay of 0–60000 ms" }
        div(classes = "field-fade-in") {
            style = "--field-fade-duration:${durationMs}ms;--field-fade-delay:${delayMs}ms"
            block()
        }
    }

    /** Fill the audience display with an image and an optional visible credit. */
    fun fullScreenImage(
        source: String, alt: String, citation: String = "",
        fit: AudienceImageFit = AudienceImageFit.Contain
    ) {
        figure(classes = "field-image-full") {
            picture(source, alt, citation, fit)
        }
    }

    /** Put an image beside ordinary HTML or Markdown content, with its own credit. */
    fun imageBeside(
        source: String, alt: String, citation: String = "", imageOnLeft: Boolean = true,
        fit: AudienceImageFit = AudienceImageFit.Contain,
        block: AudienceBuilder.() -> Unit
    ) {
        div(classes = "field-image-beside") {
            fun pictureColumn() { figure { picture(source, alt, citation, fit) } }
            fun textColumn() { AudienceBuilder(consumer, "field-image-text").visit(block) }
            if (imageOnLeft) { pictureColumn(); textColumn() }
            else { textColumn(); pictureColumn() }
        }
    }

}

private fun FIGURE.picture(source: String, description: String, citation: String, fit: AudienceImageFit) {
    require(source.isNotBlank()) { "An image needs a filename or URL" }
    img(src = source, alt = description) { style = "object-fit:${fit.css}" }
    if (citation.isNotBlank()) figcaption { +citation }
}
