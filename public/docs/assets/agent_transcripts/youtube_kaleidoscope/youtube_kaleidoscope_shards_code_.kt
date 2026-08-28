import afield.four.Target
import afield.four.browser.FieldUiBrowserTexture
import afield.four.browser.PuppetBrowserKaleidoscope
import afield.four.modules.fromContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

val renderer: Renderer = fromContext()
prop<afield.four.browser.PuppetBrowser>(browserControl)

val browser = FieldUiBrowserTexture(
    renderer = renderer,
    url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
    width = 1280,
    height = 720,
    // Linux: Chromium's shared-texture export never fills the buffer (electron#53271).
    cpuBitmapFallback = FieldUiBrowserTexture.cpuBitmapFallbackRequired
)
browserControl = browser

renderer.renderList.preTargets["youtube-kaleidoscope-upload"] =
    Target { command, _ -> browser.prepareSampledImagesFor(command) }

val shards = PuppetBrowserKaleidoscope(
    renderer = renderer,
    browser = browser,
    name = "youtube-kaleidoscope",
    columns = 14,
    rows = 8
)
shards.motion = 1.0f
shards.facets = 5.0f
shards.chroma = 1.0f
shards.build()

val requestInFlight = AtomicBoolean(false)
val playbackConfirmed = AtomicBoolean(false)
val nextAttempt = AtomicLong(0L)
var announced = false
var audibleAnnounced = false
var frameNumber = 0L

loop {
    if (!announced && browser.pageReady) {
        announced = true
        println("Kaleidoscope online: ${shards.triangleCount} independently animated Vulkan triangles")
    }

    // A userGesture JavaScript evaluation first accepts Google's EU consent page, then
    // retries until the real HTMLVideoElement is advancing. Navigation during consent
    // deliberately looks like a failed attempt; the next one runs in the new document.
    if (browser.pageReady && !playbackConfirmed.get() &&
        frameNumber >= nextAttempt.get() && requestInFlight.compareAndSet(false, true)) {
        browser.evaluate(
            """
            (async () => {
                const buttons = Array.from(document.querySelectorAll('button'))
                const consent = buttons.find(button =>
                    /^(accept all|i agree)$/i.test((button.innerText || button.textContent || '').trim()))
                if (consent) {
                    consent.click()
                    return { state: 'consent-clicked', label: consent.innerText }
                }
                const video = document.querySelector('video')
                if (!video) return { state: 'waiting-for-video', title: document.title }
                video.muted = false
                try {
                    await video.play()
                } catch (error) {
                    return { state: 'play-rejected', reason: String(error), readyState: video.readyState }
                }
                await new Promise(resolve => setTimeout(resolve, 350))
                return {
                    state: video.paused ? 'paused' : 'playing',
                    currentTime: video.currentTime,
                    readyState: video.readyState,
                    title: document.title
                }
            })()
            """.trimIndent(),
            userGesture = true,
            timeoutMillis = 15_000
        ).whenComplete { result, error ->
            requestInFlight.set(false)
            val report = if (error == null) result.value.toString() else error.message.orEmpty()
            if (error == null && "playing" in report) {
                playbackConfirmed.set(true)
                println("YouTube playback confirmed by Chromium: $report")
            } else {
                nextAttempt.set(frameNumber + 120L)
            }
        }
    }
    if (playbackConfirmed.get() && browser.state.audible && !audibleAnnounced) {
        audibleAnnounced = true
        println("Chromium reports the offscreen YouTube tab is audible")
    }
    frameNumber++
    frame()
}.end {
    renderer.renderList.preTargets.remove("youtube-kaleidoscope-upload")
    shards.close()
    browser.close()
}
