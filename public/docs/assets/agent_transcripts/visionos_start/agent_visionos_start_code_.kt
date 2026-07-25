// {{ visionmetal uniforms=phase,amount,tint }}
// Open Live Metal in Field4Vision. Launch again after editing to hot-reload.
onStart {
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
