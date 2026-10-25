import afield.media.stream.ElectronRTTView

// Drawn by Vulkan, streamed into this box's output panel.
val view = ElectronRTTView(640, 360)
val started = AnimationClock.nowSeconds()

view += "content" to moduleHere {
    +view
    +Mesh("square") {
        set(4, 2) {
            pos.add(-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0)
            tc.add(0, 0, 1, 0, 1, 1, 0, 1)
            tri.add(0, 1, 2)
            tri.add(0, 2, 3)
        }
        time = { AnimationClock.nowSeconds() - started }
    }
}

display("main", view.html())
displayOpen("main")
