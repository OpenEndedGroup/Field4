import afield.ui.output.controlGroup
import afield.ui.output.displayOpen
import afield.graphics.render.neural.*
import java.nio.file.Path

// Run as a child of the standard renderer box. Edit this path to use your own image.
val image = Path.of("public/docs/assets/opendlssnr/ink-input.png").toAbsolutePath()
val owner = renderer
prop<OpenDlssNrImage>(study)
fromContextWithNameOrNull<OpenDlssNrImage>("study")?.close()
val available = OpenDlssNrSupport.availability(owner)
println(available.reason)
if(available.ready) {
    study = OpenDlssNrImage(owner,image.toString()).install()
    var style=1
    var tone=0f
    var structure=10f
    var skin=-1f
    var autoMask=false
    var intensity=1f
    var red=1f
    var green=1f
    var blue=1f
    controlGroup("neural image") {
        slider("Network style",min=0.0,max=2.0,step=1.0,initialValue=1.0) {style=it.toInt()}
        slider("Tone",min=-10.0,max=30.0,initialValue=0.0) {tone=it.toFloat()}
        slider("Structure",min=-10.0,max=30.0,initialValue=10.0) {structure=it.toFloat()}
        checkbox("Automatic skin mask",initialValue=false) {autoMask=it}
        slider("Skin (active with mask; -1 follows structure)",min=-1.0,max=30.0,initialValue=-1.0) {skin=it.toFloat()}
        slider("NR intensity",min=0.0,max=1.0,initialValue=1.0) {intensity=it.toFloat()}
        slider("Grade red",min=0.0,max=2.0,initialValue=red.toDouble()) {red=it.toFloat()}
        slider("Grade green",min=0.0,max=2.0,initialValue=green.toDouble()) {green=it.toFloat()}
        slider("Grade blue",min=0.0,max=2.0,initialValue=blue.toDouble()) {blue=it.toFloat()}
    }
    displayOpen("neural image")
    frames {
        try {
            while(true) {
                study.controls=OpenDlssNrControls(style=style,tone=tone,structure=structure,skin=skin,autoMask=autoMask,intensity=intensity)
                study.exposure=Vector4f(red,green,blue,1f)
                frame()
            }
        } finally {study.close()}
    }
} else frames { println("Image study unavailable: ${available.reason}") }
