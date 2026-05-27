clearAFrame()

// we have a bug right now where shaders with uniforms need to be reloaded once. Press f1 and 'reload shader'

var shader = window.newShaderForBox({"foo":vec(0,0,0)})

loadPointCloudPLY("/local/Desktop/IMG_6962.MOV.dir/dense/0/fused.ply", "something", (loaded) => {
    window.loaded = loaded
    print("loaded!")

    loaded.mesh.material.size = 0.01
    loaded.mesh.scale.y = -1
    loaded.mesh.material = shader
})

window.theShader = shader