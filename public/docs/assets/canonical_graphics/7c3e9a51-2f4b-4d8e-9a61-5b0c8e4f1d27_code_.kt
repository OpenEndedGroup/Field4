/*
Run this in a box beneath the renderer (`window`) box.

Stigmergy: two Physarum species sharing one TrailField. The trail has four channels; each
species deposits into its own and senses all four through its row of `attraction` (+1 its
own, -1 the other's), so the two divide the field into amber and cyan territories.
"12b. trail followers", beneath this box, adds particles that steer by the same field.
published as the `physarum` property so boxes beneath this one can reach the system
*/
prop<ShaderPhysarumSystem>(physarum) = ShaderPhysarumSystem() {
    width = 512
    height = 512
    agentCount = 250_000
    speciesCount = 2
    exposure = 0.06f
    attraction = listOf(
        vec(1f, -1f, 0f, 0f),
        vec(-1f, 1f, 0f, 0f),
    )
}
// steps once a frame and draws the trail; the agent rule lands in this box's vertex tab
physarum.installInto()
