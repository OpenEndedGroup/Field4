/*
A second particle system that only reads the field. Each particle climbs the gradient
of dot(trail, weights): up species 0's channel (amber) and down species 1's (cyan), so
the magenta dots gather on amber roads and slide off cyan ones.
*/
var followers = TrailFollowers(physarum.trail) {
    count = 20_000
    weights = vec(1f, -1f, 0f, 0f)
    steering = 0.4f
    pointSize = 3f
}
// draws after the trail's preview, so the points sit on top of it
followers.installInto()
println("${followers.settings.count} followers on the trail")
