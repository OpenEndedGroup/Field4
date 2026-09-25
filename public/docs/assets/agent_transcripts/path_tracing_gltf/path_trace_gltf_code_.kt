import afield.four.fabricated.*
import afield.four.util.Camera
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
val scene = PathTraceScene()
val model = scene.importGltf("public/docs/assets/fabricate_demo/ToyCar.glb", Matrix4f().scale(20f))
val center = Vector3f(model.boundsMin).add(model.boundsMax).mul(0.5f)
val radius = model.boundsMin.distance(model.boundsMax)*0.5f
val floor = scene.material(PathTraceMaterial.diffuse(0.35f,0.35f,0.35f))
val y=model.boundsMin.y-radius*0.003f
val d=radius*4f
scene.quad(Vector3f(center.x-d,y,center.z-d),Vector3f(center.x-d,y,center.z+d),Vector3f(center.x+d,y,center.z+d),Vector3f(center.x+d,y,center.z-d),floor)
val camera = Camera(renderer).also {
 it.target.set(center)
 it.at.set(center).add(radius*1.7f,radius*1.05f,radius*2f)
 it.p.fov=0.55
}
val tracer = PathTracingRenderer(renderer,scene,
 PathTraceEnvironment.fromFile("public/docs/assets/fabricate_demo/venice_sunset_1k.exr"),
 width=1280,height=720,camera=camera).install()
tracer.maxBounces=8
tracer.samplesPerFrame=4
tracer.rayEpsilon=(radius*0.00001f).coerceAtLeast(0.0000001f)
tracer.showEnvironment=false
tracer.indirectClamp=10f
tracer.exposure=1.5f
tracer.blitToScreen()
frames {
 while(tracer.samples<1024) frame()
 println("glTF study converged: ${tracer.samples} samples; ${model.triangleCount} imported triangles")
}
