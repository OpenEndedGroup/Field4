// {{ visionmetal uniforms=phase,amount,tint }}
// Open Live Metal in Field4Vision. Launch again after editing to hot-reload.
// The shader lives in this box's `msl` property — edit it and press alt-r to
// recompile on the headset without restarting any of this.
onStart {
    // Geometry is built here, in the headset, and handed to Metal in one call.
    geometry {
        color(1.0, 0.35, 0.15)
        cube(0.18, cx = 0.45, cz = -1.2, tintFaces = true)
    }

    uniform("amount", 0.025)
    uniform("tint", 0.1, 0.8, 1.0, 0.35)

    onFrame { frame ->
        uniform("phase", frame.time * 3.0)
    }

    onStop {
        println("visionOS starter stopped")
    }

    println("visionOS starter running")
}
