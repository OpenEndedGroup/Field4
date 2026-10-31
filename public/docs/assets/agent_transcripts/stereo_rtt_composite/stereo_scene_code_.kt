import afield.graphics.vulkan.targets.SideBySideStereoTarget

val xr = renderer.openXRHmdTarget
check(xr == null || xr.hasEyeViews) { "Connect and wear the headset, then launch box 1 first" }
val eye = xr?.let { renderer.runOnQueueThread { it.sideBySideRTTParameters() } }
val origin = Vec3()
xr?.headPose(origin)
val stereo = SideBySideStereoTarget(
    renderer,
    eyeWidth = eye?.width?.div(2) ?: 1200,
    eyeHeight = eye?.height ?: 1200,
    openXR = xr,
)
stereo.stereoCamera.base.lookAt(Vec3(), Vec3(0f, 0f, -2f))
stereo.stereoCamera.base.setAspect(stereo.eyeWidth.toFloat() / stereo.eyeHeight)
stereo +=
    "room" to
        {
            val module = moduleHere {
                primitiveType = lines
                depthTest = true
                depthWrite = true
                blend = false
            }
            fun wire(name: String, points: List<Vec3>, color: Vec4) {
                val mesh = Mesh(renderer, module)
                module += name to mesh
                mesh.set(points.size, points.size / 2) {
                    for ((i, v) in points.withIndex()) {
                        pos.add(v.x, v.y, v.z)
                        col.add(color.x, color.y, color.z, color.w)
                        tri.add(i)
                    }
                }
                mesh.p = { stereo.currentEyeProjectionMatrix() }
                mesh.m = { stereo.currentEyeViewMatrix() }
            }
            val grid = mutableListOf<Vec3>()
            for (i in -6..6) {
                val a = i * 0.5f
                grid.add(Vec3(origin.x + a, origin.y - 1.5f, origin.z - 3f))
                grid.add(Vec3(origin.x + a, origin.y - 1.5f, origin.z + 3f))
                grid.add(Vec3(origin.x - 3f, origin.y - 1.5f, origin.z + a))
                grid.add(Vec3(origin.x + 3f, origin.y - 1.5f, origin.z + a))
            }
            wire("floor", grid, vec(0.2, 0.35, 0.45, 1))
            for (n in -1..1) {
                val corners =
                    (0..7).map { i ->
                        Vec3(
                            origin.x + n * 0.8f + (if (i and 1 == 0) -0.25f else 0.25f),
                            origin.y + (if (i and 2 == 0) -0.25f else 0.25f),
                            origin.z - 2f + (if (i and 4 == 0) -0.25f else 0.25f),
                        )
                    }
                val edges = mutableListOf<Vec3>()
                for (i in 0..7) for (bit in listOf(1, 2, 4)) if (i and bit == 0) {
                    edges.add(corners[i])
                    edges.add(corners[i or bit])
                }
                wire(
                    "cube $n",
                    edges,
                    if (n == -1) vec(1, 0.3, 0.2, 1) else if (n == 0) vec(0.3, 1, 0.5, 1) else vec(0.3, 0.5, 1, 1),
                )
            }
            module
        }
