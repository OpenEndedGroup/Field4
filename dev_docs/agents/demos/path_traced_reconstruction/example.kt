import afield.controlGroup
import afield.displayOpen
import afield.four.fabricated.*
import afield.four.reconstruction.*
import afield.four.modules.fromContextWithNameOrNull
import afield.stdlib.linalg.Vector3f

prop<PathTracedReconstruction>(lab)
fromContextWithNameOrNull<PathTracedReconstruction>("lab")?.close()
NgxSupport.rayReconstructionAvailability(renderer.device).getOrThrow()
val scene=PathTraceScene().apply {
    val floor=material(PathTraceMaterial.diffuse(.45f,.5f,.55f))
    val red=material(PathTraceMaterial.plastic(.65f,.055f,.025f,.22f))
    val gold=material(PathTraceMaterial.metal(.95f,.7f,.3f,.12f))
    val blue=material(PathTraceMaterial.metal(.3f,.5f,.8f,.5f))
    val light=material(PathTraceMaterial.emissive(12f,8f,5f))
    quad(Vector3f(-15f,0f,-15f),Vector3f(-15f,0f,15f),Vector3f(15f,0f,15f),Vector3f(15f,0f,-15f),floor)
    sphere(Vector3f(-1.7f,.8f,0f),.8f,red,48,24)
    sphere(Vector3f(0f,.8f,0f),.8f,gold,48,24)
    sphere(Vector3f(1.7f,.8f,0f),.8f,blue,48,24)
    quad(Vector3f(-2f,4f,-1f),Vector3f(0f,4f,-1f),Vector3f(0f,4f,1f),Vector3f(-2f,4f,1f),light)
}
val camera=Camera(renderer).also {
    it.at.set(3.5f,2.4f,7.5f);it.target.set(0f,.65f,0f);it.p.fov=.62
}
// Keep the tracer uninstalled: lab records trace -> raw guides -> NGX in one ordered pass.
val tracer=PathTracingRenderer(renderer,scene,PathTraceEnvironment.constant(.08f,.1f,.15f),1920,1080,camera)
lab=PathTracedReconstruction(tracer).install()
var view=2
var move=false
var cut=false
var samples=1
controlGroup("path-traced reconstruction") {
    slider("0: Raw · 1: Progressive · 2: RR",min=0.0,max=2.0,step=1.0,initialValue=2.0) { view=it.toInt() }
    slider("Samples per frame",min=1.0,max=8.0,step=1.0,initialValue=1.0) { samples=it.toInt() }
    checkbox("Move camera",initialValue=false) { move=it }
    button("Cut camera and reset") { if(it)cut=true }
}
displayOpen("path-traced reconstruction")
println("1920 × 1080. RR receives each frame's raw multi-bounce lighting and measured surface guides. Opaque surfaces; pinhole camera.")
frames {
    var phase=0.0
    while(true) {
        lab.view=PathTracedReconstruction.View.entries[view];tracer.samplesPerFrame=samples
        if(move) { phase+=.015;camera.at.x=(3.5+kotlin.math.sin(phase)*1.2).toFloat() }
        if(cut) { camera.at.z=if(camera.at.z>7)6f else 7.5f;lab.reset("camera cut");cut=false }
        frame()
    }
}.end { lab.close() }
