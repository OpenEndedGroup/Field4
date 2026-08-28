import afield.four.Target
import afield.four.browser.FieldUiBrowserTexture
import afield.four.browser.PuppetBrowserWindowPanel
import afield.four.modules.fromContext

val renderer: Renderer = fromContext()
prop<afield.four.browser.PuppetBrowser>(browserControl)

val browser = FieldUiBrowserTexture(
    renderer = renderer,
    url = "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
    width = 1280,
    height = 720
)
browserControl = browser

renderer.renderList.preTargets["youtube-desktop-browser-upload"] =
    Target { command, _ -> browser.prepareSampledImagesFor(command) }

// This is direct window input, not a synthetic controller ray. The panel letterboxes if
// the window is resized and uses that same fitted rectangle for its mouse hit test.
val screen = PuppetBrowserWindowPanel(renderer, browser, "youtube-desktop-browser")
screen.build(takeFocus = true)

var announced = false
loop {
    if (!announced && browser.pageReady) {
        announced = true
        println("YouTube is live: click, scroll and type directly in the Chromium page")
    }
    frame()
}.end {
    renderer.renderList.preTargets.remove("youtube-desktop-browser-upload")
    screen.close()
    browser.close()
}
