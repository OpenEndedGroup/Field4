/*
Run this in a box beneath the renderer (`window`) box.

A forest fire: a fixed scatter of trees, and four kinds of fire (red, green, blue,
magenta) that compete for them. Burnt trees recover, so a fire front can be
followed by another. Nothing burns until something is lit; "11b. fire starter",
beneath this box, lights it.
The forest is 768 trees high and as wide as the window's shape needs, so it fills
the window without being stretched.
published as the `fire` property so boxes beneath this one can reach the system
*/
val treesHigh = 768
prop<ShaderForestFireSystem>(fire) = ShaderForestFireSystem() {
    height = treesHigh
    width = treesHigh * window.width / window.height
    treeDensity = 0.6f
}

// this installs the editable spreading rule in this box's fragment tab
fire.installInto()
