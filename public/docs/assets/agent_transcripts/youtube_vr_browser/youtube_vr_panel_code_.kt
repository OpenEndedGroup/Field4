import afield.graphics.vulkan.Target
import afield.media.browser.FieldUiBrowserTexture
import afield.media.browser.PuppetBrowser
import afield.media.browser.PuppetBrowserVrPanel
import afield.media.browser.PuppetBrowserWindowInput

// Publish browser controls to connected boxes.
prop<PuppetBrowser>(browserControl)

// The producer is a full Chromium page, not an embed: consent, account state, player,
// navigation and audio all behave as they do in an ordinary browser.
val browser = FieldUiBrowserTexture(
    renderer = renderer,
    url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
    width = 1280,
    height = 720,
    // Linux: Chromium's shared-texture export never fills the buffer (electron#53271).
    cpuBitmapFallback = FieldUiBrowserTexture.cpuBitmapFallbackRequired,
)
browserControl = browser

// Import the newest cross-process frame before either eye samples the resident texture.
renderer.renderList.preTargets["youtube-browser-upload"] = Target { command, _ ->
    browser.prepareSampledImagesFor(command)
}

val stereo = MultiviewStereoTarget(renderer, openXR = renderer.openXRHmdTarget)
val panel = PuppetBrowserVrPanel(
    renderer = renderer,
    stereo = stereo,
    browser = browser,
    widthMeters = 1.6f,
    distanceMeters = 1.3f,
    name = "youtube-browser-panel",
)
panel.build()
panel.enableDesktopInteraction()

// The controller owns pointer input. Once the panel has focus, physical keyboard/IME
// events from Field's Window remain available for search boxes and other text fields.
val keyboard = PuppetBrowserWindowInput(browser, renderer.window) { _, _ -> null }
keyboard.takeFocus()

// Desktop mirror of the same stereo texture that can be handed to OpenXR eye swapchains.
stereo.mirrorToScreen(name = "youtube-stereo-screen", stereoPair = { true })

var announced = false
loop {
    if (!announced && browser.pageReady) {
        announced = true
        println(
            "YouTube is live on a ${browser.spec.width}x${browser.spec.height} " +
                "texture; trigger accepts consent and controls playback",
        )
    }
    frame()
}
    .end {
        renderer.renderList.preTargets.remove("youtube-browser-upload")
        keyboard.close()
        browser.close()
    }
