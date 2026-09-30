import afield.graphics.*
import afield.stdlib.*

three(name="idle-rendering", width=760, height=500) {
    camera(yaw=15.0, pitch=20.0, distance=3.0)
    helpers(false)
    mesh(TriMesh(floatArrayOf(-80f,-40f,0f,-10f,-40f,0f,-10f,40f,0f,-80f,40f,0f),
        intArrayOf(0,1,2,0,2,3)), id="left", group=0, wireframe=false)
    mesh(TriMesh(floatArrayOf(10f,-40f,0f,80f,-40f,0f,80f,40f,0f,10f,40f,0f),
        intArrayOf(0,1,2,0,2,3)), id="right", group=1, wireframe=false)
    inspect(groups=listOf("left","right"), assembly=listOf(
        ThreeAssemblyMotion("left", doubleArrayOf(-100.0,0.0,0.0)),
        ThreeAssemblyMotion("right", doubleArrayOf(100.0,0.0,0.0))
    ))
    caption("Static geometry sleeps; visibility, light, section, insertion, camera and resize wake it.")
}
