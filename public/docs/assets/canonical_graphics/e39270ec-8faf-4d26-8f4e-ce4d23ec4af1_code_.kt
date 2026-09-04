import afield.stdlib.*
var dla = ShaderDlaSystem() {
    width = window.width
    height = window.height
    particleCount = 1_000_000
}

// this installs editable shaders
dla.installInto()