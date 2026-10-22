package afield.ui.slides

import afield.app.protocol.AudienceContent
import afield.app.protocol.AudienceMacro
import afield.document.graph.Box
import afield.runtime.lifecycle.inMain
import kotlinx.html.DIV
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import java.net.URI

/** Options for showing a guide on a separate audience display. */
data class PresentationOptions(val title: String, val transitionMs: Int = 450) {
    init { require(transitionMs in 0..2000) { "transitionMs must be between 0 and 2000" } }
}

/**
 * Build a presentation using the existing guide steps, callbacks, notes and annotations.
 * Call [SlideShowInstance.present] to open its audience display in Field's Electron app.
 * Running the same box again preserves the current step when possible.
 */
fun Box.presentation(
    title: String = name.ifBlank { "Field presentation" },
    transitionMs: Int = 450,
    init: SlideShowBuilder.() -> Unit
): SlideShowInstance {
    val previous = slideShow
    val builder = SlideShowBuilder(this, PresentationOptions(title, transitionMs))
    builder.slideshowId("presentation_$id")
    builder.init()
    val instance = builder.build()
    require(instance.steps.isNotEmpty()) { "A presentation needs at least one step" }
    instance.resumeStep = previous?.takeIf { it.slideshowId == instance.slideshowId }?.currentStepIndex ?: -1
    slideShow = instance
    return instance
}

/** Open this guide as a presentation, using note HTML for steps without an explicit audience surface. */
fun SlideShowInstance.present() {
    if (presentationOptions == null) presentationOptions = PresentationOptions(hostBox.name.ifBlank { "Field presentation" })
    inMain(Box.boxContextElement(hostBox)) {
        goToStep(resumeStep.takeIf { it >= 0 }?.coerceAtMost(steps.lastIndex) ?: currentStepIndex.coerceAtLeast(0))
    }
}

/** Standard kotlinx.html builders; text is escaped by the library. No JavaScript build is needed. */
fun SlideStepBuilder.audience(block: DIV.() -> Unit) {
    audienceHtml(createHTML().div(classes = "field-slide", block = block))
}

/** Set an HTML fragment for the audience. Use [audience] for type-safe, escaped HTML construction. */
fun SlideStepBuilder.audienceHtml(html: String) {
    audienceContent = AudienceContent(html = html)
}

/** Render Markdown on the audience display using the guide's existing Markdown renderer. */
fun SlideStepBuilder.audienceMarkdown(markdown: String) = audienceHtml(SlideMarkdown.render(markdown))

/** Show a real browser page in an isolated native Electron view, optionally within a fractional rect. */
fun SlideStepBuilder.audienceWebpage(url: String, rect: List<Double>? = null) {
    require(URI(url).let { it.scheme in listOf("http", "https") && !it.host.isNullOrBlank() }) { "A webpage needs an HTTP(S) URL" }
    audienceContent = AudienceContent(type = "webpage", url = url, rect = checkedAudienceRect(rect))
}

/** Show a YouTube embed; the video must permit embedding. */
fun SlideStepBuilder.audienceYouTube(videoId: String, rect: List<Double>? = null) {
    require(Regex("[A-Za-z0-9_-]{11}").matches(videoId)) { "Use the 11-character YouTube video ID" }
    audienceContent = AudienceContent(type = "youtube", videoId = videoId, rect = checkedAudienceRect(rect))
}

/**
 * Show an existing document in the current workspace, with its normal Field editor and outputs.
 * A named saved macro and the document's interactive guide are explicit presenter controls.
 */
fun SlideStepBuilder.audienceDocument(
    document: String,
    macro: String? = null,
    interactiveGuide: Boolean = true,
    rect: List<Double>? = null
) {
    require(document.isNotBlank() && document == document.trim() && document !in listOf(".", "..") &&
        document.none { it == '/' || it == '\\' || it.code < 32 }) { "Use a document name in the current workspace" }
    require(macro == null || (macro.isNotBlank() && macro.none { it == '/' || it == '\\' || it.code < 32 })) { "Use a saved macro name" }
    audienceContent = AudienceContent(type = "field-document", document = document,
        macro = macro?.let { AudienceMacro(it) }, interactiveGuide = interactiveGuide, rect = checkedAudienceRect(rect))
}

private fun checkedAudienceRect(rect: List<Double>?): List<Double>? {
    if (rect == null) return null
    require(rect.size == 4 && rect.all { it.isFinite() } && rect[0] >= 0 && rect[1] >= 0 &&
        rect[2] > 0 && rect[3] > 0 && rect[0] + rect[2] <= 1 && rect[1] + rect[3] <= 1) {
        "rect must be [x, y, width, height] as fractions within the audience window"
    }
    return rect.toList()
}
