import afield.four.browser.FieldUiBrowserTexture
import afield.four.browser.PuppetBrowser
import afield.four.browser.PuppetBrowserUvInput
import afield.four.browser.PuppetBrowserWindowInput
import afield.four.modules.fromContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CompletableFuture

val renderer: Renderer = fromContext()
prop<afield.four.browser.PuppetBrowser>(browserControl)
val browser = FieldUiBrowserTexture(
    renderer = renderer,
    url = FieldUiBrowserTexture.fieldDocumentUrl(thisBox),
    width = 1280,
    height = 720,
    readyBoxUid = thisBox.id,
    // Linux: Chromium's shared-texture export never fills the buffer (electron#53271).
    cpuBitmapFallback = FieldUiBrowserTexture.cpuBitmapFallbackRequired
)
browserControl = browser
val interaction = PuppetBrowserWindowInput(browser, renderer.window)
val scriptedInput = PuppetBrowserUvInput(browser)
val removeReadyBridge = browser.onPageRequest("field-ready") { payload ->
    println("Field UI page called Field: $payload")
    CompletableFuture.completedFuture<JsonElement>(buildJsonObject {
        put("accepted", true)
        put("echo", payload)
    })
}
renderer.renderList.preTargets["field-ui-browser-upload"] =
    afield.four.Target { command, _ -> browser.prepareSampledImagesFor(command) }

val material = moduleHere {
    textures("fieldUi" to browser)
}
val panel = Mesh()
material += "field-ui-panel" to panel
renderer += "field-ui-browser-texture" to material

panel.set(4, 2) {
    pos.add(
        -0.96, -0.96, 0.0,
         0.96, -0.96, 0.0,
         0.96,  0.96, 0.0,
        -0.96,  0.96, 0.0
    )
    tc.add(0, 0, 1, 0, 1, 1, 0, 1)
    tri.add(0, 1, 2, 0, 2, 3)
}

var announced = false
loop {
    if (!announced && browser.pageReady) {
        announced = true
        println("Field UI browser texture is live: ${browser.presentedFrame}")
        browser.evaluate("""
            new Promise((resolve, reject) => {
                const deadline = Date.now() + 10000
                const prepare = () => {
                    if (typeof window.fieldBackdoorPrepareWindowScreenshot !== "function") {
                        if (Date.now() < deadline) return setTimeout(prepare, 100)
                        return reject(new Error("Field screenshot preparation API did not appear"))
                    }
                    const result = JSON.parse(window.fieldBackdoorPrepareWindowScreenshot({
                        uid: "field_ui_texture_panel",
                        property: "code",
                        showEditor: true,
                        panIntoView: true,
                        openOutputs: false,
                        padding: 18
                    }))
                    if (result.success) resolve(result)
                    else if (Date.now() < deadline) setTimeout(prepare, 100)
                    else reject(new Error(result.message || "could not open the selected box editor"))
                }
                prepare()
            })
        """).thenCompose {
            browser.evaluate("""
            (() => {
                const probe = document.createElement("input")
                probe.id = "field-puppet-input-probe"
                probe.style.cssText = "position:fixed;left:8px;top:8px;width:280px;height:40px;z-index:2147483647;opacity:.01"
                document.body.appendChild(probe)
                return true
            })()
            """)
        }.thenCompose {
            scriptedInput.move(40.0 / scriptedInput.cssWidth, 28.0 / scriptedInput.cssHeight)
            scriptedInput.down()
            scriptedInput.up()
            browser.insertText("always available in VR")
            browser.flushInput()
        }.thenCompose {
            browser.evaluate("""
                (() => {
                    const probe = document.getElementById("field-puppet-input-probe")
                    const input = probe.value
                    probe.remove()
                    return window.fieldPuppet.request("field-ready", {
                        title: document.title,
                        location: location.href,
                        input
                    })
                })()
            """)
        }.whenComplete { result, error ->
            if (error == null) println("Puppet page/Field/JavaScript round trip: ${result.value}")
            else error.printStackTrace()
        }
    }
    frame()
}.end {
    renderer.renderList.modules.remove("field-ui-browser-texture")
    renderer.renderList.preTargets.remove("field-ui-browser-upload")
    removeReadyBridge()
    interaction.close()
    browser.close()
}
