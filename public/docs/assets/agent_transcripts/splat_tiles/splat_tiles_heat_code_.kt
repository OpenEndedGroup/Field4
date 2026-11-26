val count = 40_000
val cloud = SplatTiles.clusteredCloud(count)
val view = SplatRaster.View.lookAt(
    eye = Vector3f(0f, 0f, 4.2f),
    target = Vector3f(0f, 0f, 0f),
    width = 480,
    height = 320,
)
val binning = SplatTiles.bin(cloud, count, view)

// One cell per 16x16 tile, row 0 at the top like the image. Log scale: the core is
// hundreds of times heavier than the halo.
showHeatmap(binning.countRows(), colormap = "inferno", width = 360, logScale = true, smooth = false, flipY = false)
println(
    "heaviest tile ${binning.maxCount} splats, 99th percentile ${binning.quantile(0.99)}, median ${binning.quantile(0.5)}",
)
