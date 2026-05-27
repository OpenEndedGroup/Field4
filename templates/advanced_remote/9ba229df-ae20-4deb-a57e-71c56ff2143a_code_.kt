clearAFrame()

// let's make a new FLine
var f = new FLine()

// and one for somebody else
var f2 = new FLine()

// let's add some code that's run only when the "mouse" moves on the screen

mouseMove.f = (event) => {
    var s = 0.1

    // let's move 0.1cm away from your finger
    var at = event.ray.origin + event.ray.direction * s
    f.lineTo(at)

    // set this color to be bright white
    f.color = vec(1.0, 1.0, 1.0, 1.0)

    // and add it to the world
    stage.f = f

    // tell people about it
    sendData("at", at)
}

// this function gets called _everywhere else_ when you send
onData.moved = (data) => {
    var what = data.key // will be 'at'
    var whereTo = data.value
    f2.lineTo(whereTo)
    f2.color = vec(1.0, 1.0, 1.0, 1.0)
    stage.f2 = f2
}
