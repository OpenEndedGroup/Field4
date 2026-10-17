import afield.ui.output.controlGroup
import afield.ui.output.displayOpen
import afield.graphics.render.neural.*

val owner = renderer
prop<OpenDlssNrStudy>(lab)
(thisBox.getPropertyRaw("lab") as? OpenDlssNrStudy)?.close()
// Leave null for the editable fragment shader; try the bundled drawing or your own image.
val imagePath: String? = null
// val imagePath: String? = "public/docs/assets/opendlssnr/ink-input.png"
lab = OpenDlssNrStudy(owner, size=768, imagePath=imagePath,
    vertex=thisBox.getPropertyRaw("vertex") as String,
    fragment=thisBox.getPropertyRaw("fragment") as String)
println(lab.availability.reason)
var style=1
var tone=0f
var structure=10f
var intensity=1f
var animate=false
var reload=false
var red=.65f
var green=1.1f
var blue=1.4f
controlGroup("neural ink") {
    if(lab.availability.ready) {
        slider("Network style",min=0.0,max=2.0,step=1.0,initialValue=1.0) {style=it.toInt()}
        slider("Tone",min=-10.0,max=30.0,initialValue=0.0) {tone=it.toFloat()}
        slider("Structure",min=-10.0,max=30.0,initialValue=10.0) {structure=it.toFloat()}
        slider("NR intensity (0 = source)",min=0.0,max=1.0,initialValue=1.0) {intensity=it.toFloat()}
    }
    checkbox("Animate source",initialValue=false) {animate=it}
    slider("Grade red",min=0.0,max=2.0,initialValue=.65) {red=it.toFloat()}
    slider("Grade green",min=0.0,max=2.0,initialValue=1.1) {green=it.toFloat()}
    slider("Grade blue",min=0.0,max=2.0,initialValue=1.4) {blue=it.toFloat()}
    button("Reload edited shaders") {if(it)reload=true}
}
displayOpen("neural ink")
println("SOURCE | NR | GRADE · 768 × 768 per panel. Edit fragment, then reload. Run record clip for six seconds of GPU frames.")
val owned=lab
frames {
    try {
        while(!owner.isClosed) {
            owned.controls=OpenDlssNrControls(style=style,tone=tone,structure=structure,intensity=intensity)
            owned.grade=Vector4f(red,green,blue,1f)
            owned.animate=animate && !owned.recording
            if(reload) {
                owned.reload(thisBox.getPropertyRaw("vertex") as String,
                    thisBox.getPropertyRaw("fragment") as String)
                    .onFailure {println("Keeping working shaders: ${it.message}")}
                reload=false
            }
            frame()
        }
    } finally {owned.close()}
}
