import afield.graphics.*
import afield.stdlib.*

three(name="features",width=760,height=500) {
    camera(yaw=0.0,pitch=0.0,distance=2.4)
    helpers(false)
    mesh(TriMesh(floatArrayOf(-70f,-40f,0f,-10f,-40f,0f,-10f,40f,0f,-70f,40f,0f),intArrayOf(0,1,2,0,2,3)), id="quad",wireframe=false)
    mesh(TriMesh(floatArrayOf(-70f,-40f,-10f,-10f,-40f,-10f,-10f,40f,-10f,-70f,40f,-10f),intArrayOf(0,1,2,0,2,3)), id="hidden-quad",wireframe=false)
    lines(listOf(
        fline { moveTo(20.0,-30.0,0.0);lineTo(70.0,30.0,0.0);stroked=true },
        fline { moveTo(60.0,-40.0,0.0);pointed=true;stroked=false;pointSize=8.0 }
    ))
}
