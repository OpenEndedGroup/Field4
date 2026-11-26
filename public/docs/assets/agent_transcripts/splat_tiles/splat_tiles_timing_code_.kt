val count = 200_000
val cloud = SplatTiles.clusteredCloud(count)
// A small image is 36 tiles for 10 workers, and the core's tiles hold most of the work:
// the case where whoever gets a heavy tile last holds up the frame.
val view = SplatRaster.View.lookAt(
    eye = Vector3f(0f, 0.3f, 2.6f),
    target = Vector3f(0f, 0f, 0f),
    width = 96,
    height = 96,
)
val binning = SplatTiles.bin(cloud, count, view)
println(binning)
for (cutoff in [1e-4f, 0f]) {
    println("transmittance cutoff $cutoff")
    for (row in SplatTiles.compare(binning, repeats = 9, transmittanceCutoff = cutoff)) println("  $row")
}
