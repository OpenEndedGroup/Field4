clearAFrame()

window.loaded = null

loadGLTF("/local/Desktop/2_2_2025.glb", "stick", (loaded) => {
    window.loaded = loaded
    print("loaded!")

    // move it up
    stick = findByName("stick")
    let roomsize = 1;
    // stick.position.x = (Math.random() * 2 - 1) * roomsize;
    stick.position.z = (Math.random() * 2 - 1) * roomsize;

    stick.position.y=1
    stick.scale.x = 10
    stick.scale.y = 10
    stick.scale.z = 10
})

_r = function*() {

    while(true)
    {
        if (window.loaded) {
             game_active = true;

            var at = getCurrentCamera().position

            var delta = vec(at.x - stick.position.x, at.z - stick.position.z)
            print(delta.length())
            if (delta.length()<0.1)
            {
                print("Done!")
                game_active = false;
                stick.scale = 5;

            }

        }

        yield // wait for a frame to pass.
        // without that yield, all you have
        // is an infinte loop in the scene draw
    }
}
