// {{ remoteAR }}
// A lit torus-knot rig, an FLine orbit ring transformed by rotate()/+vec/*scale, and a
// glTF asset loaded + animated through the auto AnimationMixer.
onStart {
    // --- richer materials / geometries / lights (roadmap item 3) ---
    val gold = THREE.MeshStandardMaterial()
    gold.metalness = 0.9
    gold.roughness = 0.25
    gold.color.setRGB(0.85, 0.65, 0.2)

    val knot = THREE.Mesh(THREE.TorusKnotGeometry(0.4, 0.12, 96, 12), gold)
    knot.name = "knot"
    knot.castShadow = true
    knot.position.set(0.0, 1.2, -1.5)
    knot.lookAt(0.0, 1.6, 0.0)

    val rig = THREE.Group()
    rig.name = "rig"
    rig.add(knot)
    SCENE.add(rig)

    val key = THREE.DirectionalLight(0xffffff, 1.4)
    key.position.set(2.0, 4.0, 2.0)
    SCENE.add(key)
    SCENE.add(THREE.AmbientLight(0x404050, 0.7))

    // --- FLine transform operators (roadmap item 1) ---
    var ring = FLine()
    ring.moveTo(0.6, 0.0, 0.0)
    var a = 0.0
    while (a < 6.3) {
        ring.lineTo(0.6 * kotlin.math.cos(a), 0.0, 0.6 * kotlin.math.sin(a))
        a += 0.3
    }
    // rotate the whole ring, lift + push it back, then shrink it: `+ Matrix4`, `+ Vector3`, `* scalar`
    val orbit = ((ring + rotate(0.4)) + vec(0.0, 1.2, -1.5)) * 0.9
    orbit.stroked = true
    orbit.pointed = false
    stage.orbit = orbit

    // --- assets + animation (roadmap item 2): load a glTF and drive the mixer via play() ---
    // A head can only fetch over http, so the asset is a URL and not a path. This one is
    // the CC0 Khronos ToyCar committed at public/docs/assets/fabricate_demo/ToyCar.glb,
    // which Field's server mounts at /sample-assets — so nothing here needs a download or
    // a directory of your own. (`mapDirectory` looks like the way to serve a folder of
    // your own and is not: it sends a message the server does not handle. Copy the model
    // next to ToyCar.glb, or add a static mount beside that one in Server.kt.)
    loadGLTF("/sample-assets/ToyCar.glb", "toycar") { g ->
        g.scene.position.set(0.0, 0.9, -1.2)
        g.scene.scale.set(6.0, 6.0, 6.0)          // the file is in metres and it is a 7cm toy
        log("toy car loaded: meshes " + g.meshes + ", anims " + g.animations)
        // clipAction(clip).play() on every clip, updated by the XR AnimationMixer. The
        // ToyCar ships no clips, so this does nothing you can see — swap in a model that
        // has some (the log line above prints how many arrived) and it animates.
        g.play()
    }

    // gently spin the rig on the head's own render clock
    onFrame { rig.rotation.y += 0.01 }
    log("AR scene + motion ready")
}
