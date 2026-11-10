import org.lwjgl.vulkan.VK10.*

// Pipeline linkage. StereoViews supplies this frame's predicted view/projection for each eye.
val trackingModule = moduleHere {
    stereo.configure(this)
    +stereo.layered
    depthTest = false
    depthWrite = false
    blend = true
    srcColor = VK_BLEND_FACTOR_SRC_ALPHA
    dstColor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
}

// Small CPU-driven scene: push constants carry every marker/segment, no particle runtime.
prop<MutableList<Array<Vector4f>>>(trackingMarks) = MutableList(384) {
    arrayOf(vec(0f, 0f, 0f, .005f), vec(0f, 0f, 0f, 1f), vec(1f, 1f, 1f, 0f))
}

// Marker ABI: [0] start/centre + radius, [1] end + shape, [2] linear RGBA.
// This shared property is looked up by the tracking box on each access.
for ((index, mark) in trackingMarks.withIndex()) {
    val geometry = Mesh()
    trackingModule += "tracking mark $index" to geometry
    geometry.resize(6, 0)
    geometry.push.bind("a") { mark[0] } // world start/centre, width in metres
    geometry.push.bind("b") { mark[1] } // world end, 1 = circle / 0 = strip
    geometry.push.bind("tint") { mark[2] } // linear RGBA; zero alpha hides the mark
}

// Attach explicitly: configuring a target alone does not add a draw module.
stereo.layered += "body-tracking" to trackingModule
trackingModule.current.enabled = true

// Retire the attachment with this box.
loop { frame() }
    .end {
        trackingModule.disable()
        stereo.layered.modules.remove("body-tracking", trackingModule)
    }
