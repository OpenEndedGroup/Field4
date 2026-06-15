clearAFrame()

// let's make a new FLine
var f = null
var positions = []
var down = false

// let's add some code that's run only when the "mouse" moves on the screen

mouseDown.f = (event) => {
    f = new FLine()
    positions = []
    down = true
}

mouseMove.f = (event) => {
    if (!down) return

    var s = 0.3

    var at = event.ray.origin + event.ray.direction * s
    // let's move 0.1cm away from your finger
    f.lineTo(at)

    // set this color to be bright white
    f.color = vec(1.0, 1.0, 1.0, 1.0)

    // and add it to the world
    stage.f = f

    positions.push(at)
}

mouseUp.f = (event) => {

    // create an AFrame sphere
    var plane = aCreate("plane", "a-sphere")
    plane.setAttribute("radius", 0.02)

    // positioned at the last position we were drawing
    plane.position = positions[positions.length-1]

    // do something with positions that result in plane.position changing
    // and stage.f = a new FLine with the 'rope'
}
