

import afield.ui.output.button

val reviewView = three(name="mixed-review",width=760,height=500) { helpers(false) }
val panelPositions = floatArrayOf(-80f,-50f,0f,-20f,-50f,0f,-20f,50f,0f,-80f,50f,0f)
val triangles = intArrayOf(0,1,2,0,2,3)
val panelTopology = ThreePartTopology(panelPositions.map(Float::toDouble).toDoubleArray(), triangles,
    listOf(listOf(0,1),listOf(1,2),listOf(2,3),listOf(0,3)), intArrayOf(0,0))
var targetHeight = 0f
val publishMixed = {
    val targetPositions = floatArrayOf(20f,targetHeight-10f,0f,60f,targetHeight-10f,0f,40f,targetHeight+10f,0f)
    reviewView.update(ThreeGeometryPayload(uid=reviewView.uid,helpers=false,meta=ThreeMeta(triangles=3),
        bounds=ThreeBounds(floatArrayOf(-80f,-50f,0f),floatArrayOf(80f,50f,0f)),
        inspector=ThreeInspector(artifactDigest="fixed-panel-boundary",groups=listOf("panel","target"),parts=mapOf("panel" to panelTopology)),
        drawables=listOf(
            ThreeDrawable(ThreeGeometryData(mapOf("position" to ThreeAttribute(3,panelPositions)),ThreeIndex(type="Uint32Array",array=triangles)),ThreeDrawStyle("surface"),id="panel",group=0),
            ThreeDrawable(ThreeGeometryData(mapOf("position" to ThreeAttribute(3,targetPositions))),ThreeDrawStyle("surface"),id="target",group=1))))
}
publishMixed()
button("Move review target") { down -> if(down) { targetHeight += 20f; publishMixed() } }
