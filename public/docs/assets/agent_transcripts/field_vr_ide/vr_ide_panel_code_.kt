import afield.media.browser.FieldUiBrowserTexture
import afield.media.browser.FieldVrWorkspace
import afield.media.browser.PuppetBrowser

prop<PuppetBrowser>(browserControl)
val ownedRenderer = renderer
val vr = FieldVrWorkspace(
    renderer = ownedRenderer,
    url = FieldUiBrowserTexture.fieldDocumentUrl(thisBox),
    width = 2560,
    height = 1600,
    readyBoxUid = thisBox.id,
    widthMeters = 2.0f,
    distanceMeters = 1.35f,
)
browserControl = vr.browser
println("Live IDE: ${vr.url}; ${vr.stereo.eyeWidth} × ${vr.stereo.eyeHeight} per eye")
// Capture the renderer before entering the asynchronous scope.
loop {
    ownedRenderer.window.pollEvents()
    ownedRenderer.render()
    frame()
}
    .end { vr.close() }
