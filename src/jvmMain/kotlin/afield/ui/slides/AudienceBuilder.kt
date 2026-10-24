package afield.ui.slides

import afield.ui.output.FieldMarkdown
import kotlinx.html.*

/**
 * The receiver of a presentation step's `audience { }` block: standard kotlinx.html builders, with
 * Markdown, a stylesheet and two image layouts added.
 *
 * [AudienceBuilder] is a `DIV` of class `field-slide`, so every ordinary HTML tag is in scope and
 * `+"text"` is escaped by the library. `.md` renders a string through Field's Markdown renderer
 * ([FieldMarkdown]) into an [AudienceMarkdown], which unary plus inserts with its generated tags
 * intact, and the nested `div` here keeps both available inside it.
 * [AudienceBuilder.stylesheet] loads a CSS file from the document's `files/` directory and marks it
 * so Refresh slide can fetch the saved file again without recompiling the talk.
 *
 * [AudienceBuilder.fullScreenImage] fills the window with a picture and its credit, and
 * [AudienceBuilder.imageBeside] sets that pair beside HTML or Markdown content on either side. Both
 * take an [AudienceImageFit] and emit the `field-image-full`, `field-image-beside` and
 * `field-image-text` classes a stylesheet can override. The audience page styles the fragment and
 * runs no script in it; a slide that needs scripts uses a webpage surface instead.
 */

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
