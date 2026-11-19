import afield.graphics.render.SplatRaster
import afield.graphics.render.SplatTiles
import afield.math.linalg.Vector3f

// A dense core inside a sparse halo, in the renderer's 16-float layout.
val count = 40_000
val cloud = SplatTiles.clusteredCloud(count)
val view = SplatRaster.View.lookAt(
    eye = Vector3f(0f, 0f, 4.2f),
    target = Vector3f(0f, 0f, 0f),
    width = 480,
    height = 320,
)

val reference = SplatRaster.rasterize(cloud, count, view).image.toArgb()
val binning = SplatTiles.bin(cloud, count, view)
val tiled = SplatTiles.render(binning).image.toArgb()

var worst = 0
for (i in reference.indices) for (shift in listOf(16, 8, 0)) {
    worst = max(worst, abs(((reference[i] shr shift) and 255) - ((tiled[i] shr shift) and 255)))
}
println(binning)
println("largest difference from SplatRaster: $worst of 255")
showImage(tiled, view.width, view.height)
