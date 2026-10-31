// {{ remoteAR }}
// Open /extensions/remote_ar/index.html?debug=1 on the browser or headset.
// Edit this box and Launch it again to replace the running scene.
onStart {
    val cube = addBox("starter-cube", 0.3).mesh
    cube.position.set(0.0, 1.2, -1.5)

    onFrame { frame ->
        cube.rotation.x = frame.time * 0.35
        cube.rotation.y = frame.time
    }

    onStop {
        SCENE.remove(cube)
        println("RemoteAR starter stopped")
    }

    println("RemoteAR starter running")
}
