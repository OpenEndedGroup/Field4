// the most complicated one of all

// clear the scene
clearAFrame()

// are we touching the screen or not?
// let's keep track of it
weAreDown = false

// and a list of all the lines we're responsible for
allLines = []

// let's add some code that starts a new line when we click
mouseDown.f = (event) => {
    weAreDown = true
    f = new FLine()
    stage.add(f)

    // maintain the list of all lines
    allLines.push(f)
}

// let's add some code that starts a new line when we click
mouseUp.f = (event) => {
    weAreDown = false
}

// let's add some code that's run only when the "mouse" moves on the screen
mouseMove.f = (event) => {
    // if we aren't touching the screen, we don't need to do anything
    if (!weAreDown) return

    var s = 0.1

    // let's get the last line we added to allLines
    // the one we're currently drawing
    var f = allLines[allLines.length-1]

    // let's move 0.1cm away from your finger
    f.lineTo(event.ray.origin + event.ray.direction * s)

    // set this color to be bright white
    f.color = vec(1.0, 1.0, 1.0, 1.0)

}

// this code says "do this function every animation frame"
_r = function*() {

    while(true)
    {
        nextLines = []

        // for all of the lines we have
        allLines.forEach( (f) => {

            // remove it
            stage.remove(f)

            // make a new one based on the old one
            f = f + vec(0,-0.001, 0)
            f.color = vec(1.0, 0,0, 1.0)

            // add it back in
            stage.add(f)
            nextLines.push(f)
        })

        // next time throgh we'll have this set of lines
        allLines = nextLines

        // this waits for a frame to happen
        yield
    }

}