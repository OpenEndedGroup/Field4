/*
Run this in a box beneath "12. stigmergy".

Particles that only read the field. Each climbs the gradient of dot(trail, weights), here up
species 0's channel (amber) and down species 1's (cyan), so the magenta dots ride the amber
roads. The rule is this box's vertex tab.
*/
var followers = TrailFollowers(physarum.trail) {
    count = 20_000
    weights = vec(1f, -1f, 0f, 0f)
    steering = 0.4f
    pointSize = 3f
}
// drawn after the trail's preview, so the points sit on top of it
followers.installInto()
