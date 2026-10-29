package afield.ui.slides

import afield.app.protocol.AudienceContent
import java.io.StringReader
import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.HTML
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.parser.ParserDelegator

/**
 * Reduces a slide to the plain text a presenter can search, by reading the audience HTML the talk
 * would actually show.
 *
 * The slideshow calls `text` for every step when a client asks for search text, and caches the
 * result for the built talk. It parses the HTML with the JDK's own parser, drops `<style>` and
 * `<script>` subtrees, keeps image `alt` text, and adds the one identifying string of the other
 * audience types: a document name, or a webpage or video URL. A step with no audience content at
 * all falls back to its presenter note. The result is capped at 16 384 characters.
 *
 * Indexing stays on authored content. The presenter-note fallback is taken as written rather than
 * with its template functions evaluated, because those can run user code, and a step whose audience
 * provider throws (a slide file not written yet) is skipped so the rest of the talk still opens.
 */

/** Authored audience text only: indexing never starts a guest, invokes callbacks or fetches a URL. */
internal object PresentationSearch {
    const val MAX_TEXT = 16_384

    fun text(step: SlideStep): String {
        val parts = linkedSetOf<String>()
        fun add(value: String) {
            val text = value.replace(Regex("\\s+"), " ").trim()
            if (text.isNotEmpty()) parts += text
        }
        fun content(content: AudienceContent) {
            when (content.type) {
                "html" -> {
                    var suppressed = 0
                    val text = StringBuilder()
                    ParserDelegator().parse(StringReader(content.html), object : HTMLEditorKit.ParserCallback() {
                        override fun handleStartTag(tag: HTML.Tag, attrs: MutableAttributeSet, pos: Int) {
                            if (tag == HTML.Tag.STYLE || tag == HTML.Tag.SCRIPT) suppressed++
                            if (suppressed == 0 && tag.isBlock) text.append(' ')
                        }
                        override fun handleEndTag(tag: HTML.Tag, pos: Int) {
                            if (tag == HTML.Tag.STYLE || tag == HTML.Tag.SCRIPT) suppressed = (suppressed - 1).coerceAtLeast(0)
                            if (suppressed == 0 && tag.isBlock) text.append(' ')
                        }
                        override fun handleText(data: CharArray, pos: Int) { if (suppressed == 0) text.append(data) }
                        override fun handleSimpleTag(tag: HTML.Tag, attrs: MutableAttributeSet, pos: Int) {
                            if (suppressed == 0) {
                                if (tag == HTML.Tag.BR || tag == HTML.Tag.HR) text.append(' ')
                                if (tag == HTML.Tag.IMG) attrs.getAttribute(HTML.Attribute.ALT)?.let { text.append(' ').append(it).append(' ') }
                            }
                        }
                    }, true)
                    add(text.toString())
                }
                "field-document" -> add(content.document)
                "webpage", "video" -> add(content.url)
            }
        }
        fun audience(view: SlideStep, noteFallback: Boolean) {
            // A missing future file must not prevent the presenter opening the rest of the talk.
            val value = runCatching { view.audienceProvider?.invoke() ?: view.audience }.getOrNull()
            if (value != null) content(value)
            // Custom template functions may execute user code; index the authored note, not its evaluation.
            else if (noteFallback && view.audienceProvider == null && view.audience == null) view.noteHtml?.let { content(AudienceContent(html = it)) }
        }
        audience(step, true)
        step.cues.forEach { add(it.title); audience(it, step.audience == null && step.audienceProvider == null) }
        return parts.joinToString(" ").take(MAX_TEXT)
    }
}
