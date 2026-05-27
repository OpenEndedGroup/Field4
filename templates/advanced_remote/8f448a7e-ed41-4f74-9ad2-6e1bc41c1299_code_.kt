clearAFrame()

var shader = window.newShaderForBox()

loadGLTF("/local/Desktop/2_2_2025.glb", "panda", (loaded) => {
    // just export a reference to this so
    // everybody can talk about 'loaded'
    window.loaded = loaded

    // let me know when it's actually finished loading
    print("loaded!")

    // or, this works as well
    // a rotation around the up axis (y)
    loaded.scene.rotation.y = 1 // radians

    // or you can also use findByName
    findByName("panda").position.y = 1
}