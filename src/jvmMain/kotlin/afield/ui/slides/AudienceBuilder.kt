package afield.ui.slides

import afield.ui.output.FieldMarkdown
import kotlinx.html.*

/**
 * The HTML an audience slide is written in: kotlinx.html's builders, with scoped Markdown,
 * stylesheets and two picture layouts added.
 *
 * [SlideStepBuilder.audience] runs its block against an [AudienceBuilder], which is a kotlinx.html
 * `DIV` carrying the slide's class, so every standard tag works inside it, along with Kotlin's own
 * loops, functions and completion. Text added with `+` is escaped by the library; `"# Heading".md`
 * renders Markdown instead, and the unary plus on the [AudienceMarkdown] it returns inserts the
 * generated tags unescaped. That pair is how one slide mixes written prose with built HTML.
 *
 * Use [AudienceBuilder.div] rather than kotlinx.html's own `div` for a nested container, since only
 * this one keeps the Markdown syntax available inside it. [AudienceBuilder.stylesheet] links a CSS
 * file from the document's files directory; the audience fetches it past the browser cache each time
 * a slide is drawn, so an edit to it appears on Refresh slide.
 * [AudienceBuilder.fullScreenImage] and [AudienceBuilder.imageBeside] are the picture layouts. Both
 * want alt text and take an optional visible credit, and [AudienceImageFit] chooses between
 * containing the whole picture and cropping it to fill.
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
