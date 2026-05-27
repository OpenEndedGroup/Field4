
clearAFrame()
var plane = aCreate("plane", "a-plane")
var size = 1.0

// put the plane where we clicked
plane.position = vec(0,1,-1.5)
plane.setAttribute("width", 1 * size)
plane.setAttribute("height", 3/4  * size)
plane.setAttribute("opacity", 1.0)


// let's make a new FLine
var f = new FLine()

// let's add some code that's run only when the "mouse" moves on the screen

mouseDown.f = (event) => {
    if (event.intersects.length>0)
    {
        print(event.intersects[0].point)
    }
    else {
        print("didn't hit any AFrame / Three.js geometry")
    }
}

// and we're done