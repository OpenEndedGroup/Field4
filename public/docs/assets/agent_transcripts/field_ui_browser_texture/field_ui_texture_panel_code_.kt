import afield.four.browser.FieldUiBrowserTexture
import afield.four.modules.fromContext

val renderer: Renderer = fromContext()
val browser = FieldUiBrowserTexture(
    renderer = renderer,
    url = FieldUiBrowserTexture.fieldDocumentUrl(thisBox),
    width = 1280,
    height = 720,
    readyBoxUid = thisBox.id
)
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
    }
    frame()
}.end {
    renderer.renderList.modules.remove("field-ui-browser-texture")
    renderer.renderList.preTargets.remove("field-ui-browser-upload")
    browser.close()
}
