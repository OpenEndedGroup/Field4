import afield.graphics.vulkan.targets.SideBySideStereoTarget

val qualityStereo = fromContext<SideBySideStereoTarget>()
val qualityXr = renderer.openXRHmdTarget
val qualityOrigin = Vec3()
qualityXr?.headPose(qualityOrigin)
println(
    "Eye texture ${qualityStereo.eyeWidth} x ${qualityStereo.eyeHeight}; packed ${qualityStereo.eyeWidth*2} x ${qualityStereo.eyeHeight}",
)
qualityStereo +=
    "quality-chart" to
        {
            val module = moduleHere {
                primitiveType = triangles
                depthTest = true
                depthWrite = true
                blend = false
            }
            val mesh = Mesh(renderer, module)
            module += "chart" to mesh
            mesh.set(4, 2) {
                for ((x, y, u, v) in
                    listOf(
                        floatArrayOf(-0.8f, -0.85f, 0f, 0f),
                        floatArrayOf(0.8f, -0.85f, 1f, 0f),
                        floatArrayOf(0.8f, -0.15f, 1f, 1f),
                        floatArrayOf(-0.8f, -0.15f, 0f, 1f),
                    )) {
                    pos.add(qualityOrigin.x + x, qualityOrigin.y + y, qualityOrigin.z - 1.6f)
                    col.add(u, v, 0f, 0f)
                }
                for (i in listOf(0, 1, 2, 0, 2, 3)) tri.add(i)
            }
            mesh.p = { qualityStereo.currentEyeProjectionMatrix() }
            mesh.m = { qualityStereo.currentEyeViewMatrix() }
            module
        }
println("Resolution chart added below the cubes: solid colour patches and progressively finer antialiased bars")
