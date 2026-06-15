clearAFrame()

var shader = window.newShaderForBox()

loadGLTF("/local/Desktop/rubber_duck.glb", "duck", (loaded) => {
    console.log("loaded! and customized")
    loaded.meshes.forEach( (mesh) => {
        mesh.material = shader
        console.log("customizing ", mesh)
    })
})
