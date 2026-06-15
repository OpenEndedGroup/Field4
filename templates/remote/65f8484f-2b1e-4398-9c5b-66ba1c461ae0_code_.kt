clearAFrame()

// let's make a new FLine
var f = new FLine()

// let's add some code that's run only when the "mouse" moves on the screen

mouseMove.f = (event) => {
    var s = 0.1

    // let's move 0.1cm away from your finger
    f.lineTo(event.ray.origin + event.ray.direction * s)

    // set this color to be bright white
    f.color = vec(1.0, 1.0, 1.0, 1.0)

    // and add it to the world
    stage.f = f

}

// and we're done