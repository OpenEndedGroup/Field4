import afield.dmx.*

// The page's measurements, on this machine: nine frames sent and received back on a free port the
// system picks (so this can run beside boxes 1 and 2), and a thin line swept across the rig,
// sampled two ways.
val trip = SceneOnARig.roundTrip()
println(trip)

val (point, area) = SceneOnARig.aliasing()
println("point samples: dark in ${point.darkFrames} of ${point.total.size} frames, light on the rig ${"%.2f".format(point.min)}..${"%.2f".format(point.max)}")
println("area average:  dark in ${area.darkFrames} frames, light on the rig ${"%.3f".format(area.min)}..${"%.3f".format(area.max)} (the line is worth ${SceneOnARig.LINE_TRUTH})")
