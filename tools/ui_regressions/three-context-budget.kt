// More three.js views than Chromium keeps WebGL contexts (16 per page): 20 stacked views, most below the window.
import afield.simulation.optics.*
val sceneMesh = OpticsScene.testScene().mesh()
for (i in 0 until 20) three(channel = "v$i", name = "v$i", height = 300, width = 420) {
    mesh(sceneMesh, wireframe = false)
    background("#d8dde3")
}
