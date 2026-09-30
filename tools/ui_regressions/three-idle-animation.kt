import afield.graphics.*
import afield.stdlib.*
import kotlin.math.sin
val initial = fline { moveTo(-1,0,0); lineTo(1,0,0) }
val view = threeLines(listOf(initial), name="idle-animation", channel="main", height=360)
var count=0
loop {
    val x=sin(count*0.12).toFloat()
    val shape=ThreeGeometryData(mapOf(
        "position" to ThreeAttribute(3,floatArrayOf(-.8f+x,-.7f,0f,.8f+x,-.7f,0f,x,.8f,0f)),
        "color" to ThreeAttribute(3,floatArrayOf(.8f,.8f,.8f,.8f,.8f,.8f,.8f,.8f,.8f))
    ),ThreeIndex("Uint16Array",intArrayOf(0,1,2)))
    view.update(ThreeGeometryPayload(uid=view.uid, fills=shape,
        bounds=ThreeBounds(floatArrayOf(-2f,-1f,-1f),floatArrayOf(2f,1f,1f)),helpers=false,
        meta=ThreeMeta(triangles=1)))
    count++
}
