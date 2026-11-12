import afield.graphics.volume.*
import java.io.File
import java.util.Base64

val view = CloudScene.Camera(doubleArrayOf(4.6, 0.9, 3.6), doubleArrayOf(0.0, 2.05, 0.0), 40.0)
val w = 200
val h = 125
val none = CloudScene.InScatter { _, _, _, _, into -> into.fill(0.0) }

val reference = cloudScene.render(view, w, h, cloudScene.reference(48))
val single = cloudScene.render(view, w, h, none)
val ambient = cloudScene.render(view, w, h, cloudAmbient)
val neural = cloudScene.render(view, w, h, cloudNet.inScatter)

// Pixels the cloud touches: where an absurd in-scatter changes the picture.
val mask = cloudScene.render(view, w, h, CloudScene.InScatter { _, _, _, _, into -> into.fill(1e3) })

val file = File.createTempFile("neural_clouds", ".png")
writeCloudComparison(file, reference, [single, ambient, neural])
val png = Base64.getEncoder().encodeToString(file.readBytes())
val rows =
    ["single scatter" to single, "fitted ambient" to ambient, "network" to neural].joinToString("") { (name, image) ->
        val e = cloudError(image, reference, mask)
        "<tr><td>$name</td><td>${e.relativeMeanAbs.f3}</td><td>${e.rmse.f3}</td></tr>"
    }
display(
    "compare",
    "<img style='width:100%' src='data:image/png;base64,$png'>" +
        "<div>reference | single scatter | fitted ambient | network; below, |error| x4</div>" +
        "<table><tr><th></th><th>relative MAE</th><th>RMSE</th></tr>$rows</table>",
)
