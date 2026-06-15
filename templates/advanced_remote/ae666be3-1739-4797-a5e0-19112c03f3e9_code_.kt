clearAFrame()
print("hello Sydney")

// we haven't loaded it yet
var snail = null

loadGLTF("/local/Desktop/ar/snail.glb", "snail", (loaded) => {
    window.loaded = loaded

    // move it up
    snail = findByName("snail") // once marc cuts a field.alpha013
    // snail = findByName("Sketchfab_Scene") // for now
    snail.position.y=0
    snail.position.x=4

    sc = 0.01
    snail.scale.x=sc
    snail.scale.y=sc
    snail.scale.z=sc
})

_r = function*() {
    while(true)
    {
        var at = getCurrentCamera().position

        if (snail) // if we've actually loaded the snail
        {
            var delta = vec(at.x - snail.position.x,at.z - snail.position.z)
            delta.normalize()

            speed = 0.01 // meters per animation frame?
            snail.position.x += delta.x*speed
            snail.position.z += delta.y*speed

            // question: what should snail.rotation.y be to not
            // have a moonwalking snail?
            snail.rotation.y = Math.atan2(delta.x, delta.y)
        }

        // question

        yield
    }
}
