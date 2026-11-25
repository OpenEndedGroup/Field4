// How long the pairwise order takes as the mesh grows: a sphere and a torus at three resolutions.
val rows =
    (3..5).joinToString("") { sub ->
        val ball = icosphereMesh(subdivisions = sub, radius = 100.0)
        val ring = torusMesh(4 shl sub, 2 shl sub, 160.0, 40.0)
        val scene = vectorScene {
            mesh(ball)
            mesh(ring)
        }
        val faces = scene.project(yaw = 0.3, pitch = -0.6)
        val t0 = System.nanoTime()
        val (_, breaks) = VectorScene.pairwiseOrder(faces)
        val ms = (System.nanoTime() - t0) / 1e6
        val (wrong, covered) = scene.wrongOrderShare(0.3, -0.6, DepthOrder.PAIRWISE, 200, 200)
        "<tr><td>${ball.triangleCount + ring.triangleCount}</td><td>${faces.size}</td><td>${ms.f0} ms</td><td>$breaks</td><td>${(100.0 * wrong / covered).f3}%</td></tr>"
    }
display(
    "main",
    """<table style="font:14px sans-serif;border-collapse:collapse" cellpadding="6">
<tr><th>triangles</th><th>facing the camera</th><th>pairwise order</th><th>cycle breaks</th><th>wrong pixels</th></tr>$rows</table>""",
)
