package afield.ui.slides

import afield.app.protocol.AudienceContent
import afield.app.protocol.AudienceMacro
import afield.document.graph.Box
import afield.runtime.lifecycle.inMain
import kotlinx.html.stream.createHTML
import kotlinx.html.visitAndFinalize
import java.net.URI

/**
 * Giving a talk from a Field document: [Box.presentation] builds the same steps a `slideShow` does,
 * and each step can carry a slide for a separate audience display.
 *
 * A step's audience surface is HTML, a real webpage, a YouTube embed, or another document in the
 * workspace. Write the HTML with [SlideStepBuilder.audience] for kotlinx.html's type-safe builders
 * (see [AudienceBuilder]), with [SlideStepBuilder.audienceHtml] for a fragment you already have, or
 * with [SlideStepBuilder.audienceMarkdown]. [SlideStepBuilder.audienceWebpage] and
 * [SlideStepBuilder.audienceYouTube] put a page or a video in an isolated native Electron view, and
 * [SlideStepBuilder.audienceDocument] opens another workspace document with its own editor, outputs
 * and guide. A step with no audience surface shows its note HTML, templates and all, so a guide
 * written for a reader presents without being rewritten.
 *
 * [SlideShowInstance.present] starts the talk. Each step publishes a `presentation-state` message to
 * the windows showing the host box's document, and Field's Electron app opens the audience and
 * presenter windows from it; in a browser the document and its guides still work, but there is no
 * separate display. Running the host box again rebuilds the talk and keeps the current step where it
 * can. Anything bound for Electron is checked before it gets there: a webpage needs an HTTP or HTTPS
 * URL with a host, a document a plain name in the current workspace, a video the eleven-character
 * YouTube id, and a fractional rect has to sit inside the audience window.
 *
 * [Presenting with Field](../presentation_studio.md) is the guide to writing one.
 */

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
    instance.resumeStepId = previous?.currentStep?.id
    instance.resumeCue = previous?.currentCueIndex ?: 0
    slideShow = instance
    return instance
}

/** Open this guide as a presentation, using note HTML for steps without an explicit audience surface. */
fun SlideShowInstance.present() {
    if (presentationOptions == null) presentationOptions = PresentationOptions(hostBox.name.ifBlank { "Field presentation" })
    inMain(Box.boxContextElement(hostBox)) {
        val index = steps.indexOfFirst { it.id == resumeStepId }.takeIf { it >= 0 }
            ?: resumeStep.takeIf { it >= 0 }?.coerceAtMost(steps.lastIndex) ?: currentStepIndex.coerceAtLeast(0)
        goToStep(index, resumeCue)
    }
}

/** Standard kotlinx.html builders; text is escaped by the library. No JavaScript build is needed. */
fun SlideStepBuilder.audience(block: AudienceBuilder.() -> Unit): AudienceContent {
    val content = audienceFragment(block)
    audienceContent = content
    return content
}

/** Reusable slide content. Keep one copy of the markup and shared image paths across slides. */
fun audienceFragment(block: AudienceBuilder.() -> Unit): AudienceContent {
    val consumer = createHTML()
    return AudienceContent(html = AudienceBuilder(consumer).visitAndFinalize(consumer, block))
}

/** Show a reusable HTML fragment without changing its content. */
fun SlideStepBuilder.audience(content: AudienceContent) { audienceContent = content }

/** Repeat HTML content dimmed behind a new layer; native webpages and documents stay separate surfaces. */
fun SlideStepBuilder.audienceOver(previous: AudienceContent, dimPrevious: Double = 0.25, block: AudienceBuilder.() -> Unit) {
    audienceContent = layerAudience(previous, audienceFragment(block), dimPrevious)
}

/** A cue that retains the preceding HTML view underneath a new layer. */
fun SlideStepBuilder.reveal(title: String, dimPrevious: Double = 0.25, block: AudienceBuilder.() -> Unit) {
    val previousCue = build().cues.lastOrNull()
    val previous = previousCue?.audienceProvider ?: previousCue?.audience?.let { { it } }
        ?: audienceProvider ?: audienceContent?.let { { it } }
        ?: error("A reveal needs an HTML audience view first")
    val addition = audienceFragment(block)
    // Validate the surface now; providers are read again when Refresh reloads the slide.
    layerAudience(previous(), addition, dimPrevious)
    cue(title) { audienceProvider = { layerAudience(previous(), addition, dimPrevious) } }
}

internal fun layerAudience(previous: AudienceContent, addition: AudienceContent, opacity: Double): AudienceContent {
    require(previous.type == "html" && addition.type == "html") { "Build layers need HTML slides; use a normal cue for webpages or Field documents" }
    require(opacity.isFinite() && opacity in 0.0..1.0) { "dimPrevious must be between 0 and 1" }
    require(previous.html.length + addition.html.length <= 2_000_000) { "Layered slide exceeds 2 MB" }
    return AudienceContent(html = """<div class="field-slide-stack"><div class="field-slide-layer field-retained" inert aria-hidden="true" style="opacity:$opacity"><div class="field-slide-content">${previous.html}</div></div><div class="field-slide-layer"><div class="field-slide-content">${addition.html}</div></div></div>""")
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
