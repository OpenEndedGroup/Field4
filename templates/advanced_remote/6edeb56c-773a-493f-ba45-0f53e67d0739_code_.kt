clearAFrame()

// let's make a new FLine
var f = new FLine()

// and add it to the world
stage.f = f

// let's add some code that's run only when the "mouse" moves on the screen

mouseMove.f = (event) => {
    var s = 0.1

    // let's move 0.1cm away from your finger
    f.lineTo(event.ray.origin + event.ray.direction * s)

    // set this color to be bright white
    f.color = vec(1.0, 1.0, 1.0, 1.0)


}

// this code says "do this function every animation frame"
_r = function*() {

    while(true)
    {
        f = f + vec(0,-0.001, 0)
        f.color = vec(Math.random(),Math.random(),Math.random(), 1.0)
        stage.f = f

        // this waits for a frame to happen
        yield
    }

}