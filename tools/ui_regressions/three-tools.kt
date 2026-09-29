import afield.graphics.*
import afield.stdlib.*

fun sheet(z: Float, half: Float, rgb: FloatArray) = TriMesh(
    floatArrayOf(-half,-40f,z, half,-40f,z, half,40f,z, -half,40f,z),
    intArrayOf(0,1,2,0,2,3), colors=FloatArray(12) { rgb[it % 3] })
three(name="xray",width=760,height=500) {
    camera(yaw=0.0,pitch=0.0,distance=3.2)
    helpers(false)
    tools("/threejs_tools_demo.js")
    mesh(sheet(10f,65f,floatArrayOf(.6f,.6f,.6f)),id="front",wireframe=false)
    mesh(sheet(0f,30f,floatArrayOf(1f,.05f,.02f)),id="rear",wireframe=false)
    lines(listOf(
        fline { moveTo(-50.0,50.0,12.0);lineTo(50.0,50.0,12.0);color=vec(1.0,1.0,1.0,.35);stroked=true },
        fline { moveTo(-50.0,-20.0,5.0);lineTo(50.0,-20.0,5.0);color=vec(0.0,1.0,1.0,1.0);stroked=true },
        fline { moveTo(45.0,20.0,5.0);pointed=true;stroked=false;pointSize=12.0;color=vec(0.0,1.0,1.0,1.0) }
    ))
    caption("X-ray reveals the red rear sheet and cyan stroke and point. Picks still stop at the grey front sheet.")
}
