import afield.graphics.*
import afield.stdlib.*

three(name="parts",channel="parts",width=900,height=560) {
    camera(yaw=20.0,pitch=25.0,distance=3.0)
    helpers(false)
    // Two drawable buffers belonging to one logical part.
    mesh(boxMesh(120,60,40),id="body",group=0,wireframe=false)
    mesh(boxMesh(100,55,45),id="body",group=0,wireframe=false)
    mesh(boxMesh(35,100,30),id="axle",group=1,wireframe=false)
    mesh(boxMesh(20,20,90),id="wheel",group=2,wireframe=false)
    lines(fline { moveTo(-100.0,-50.0,0.0);lineTo(100.0,-50.0,0.0);color=vec(0.0,1.0,1.0,1.0) })
    inspect(groups=listOf("body","axle","wheel"), partPaths=mapOf(
        "body" to listOf("Vehicle","Bodywork"),
        "axle" to listOf("Vehicle","Running gear"),
        "wheel" to listOf("Vehicle","Running gear")
    ))
    caption("Parts tree: companion buffers, folders, mixed visibility and reversible solo.")
}
three(name="ordinary",channel="ordinary",width=700,height=360) {
    mesh(boxMesh(80,30,30),id="plain",wireframe=false)
}
