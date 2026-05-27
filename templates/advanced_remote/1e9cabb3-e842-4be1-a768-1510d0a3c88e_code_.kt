clearAFrame()

// when we click on the screen run this code
mouseDown.down = (event) => {
	var middle = event.ray.origin + event.ray.direction*0.2

    var plane = aCreate("plane", "a-plane")
    var size = 1

    window.plane = plane

    // put the plane where we clicked
    plane.position = middle
    plane.setAttribute("width", 1 * size)
    plane.setAttribute("height", 3/4  * size)
    plane.setAttribute("opacity", 1.0)

    // change this to read /local/<NAME>/<FILENAME>
    // where <NAME> is what you wrote in remote_js
    plane.setAttribute("src", "/local/Desktop/foo.mp4")
}

// once we have a plane, we can issue these kinds of commands
if (window.plane) {
    let video = window.plane.object3D.children[0].material.map.source.data

    video.pause()

    video.play()

    print(video.currentTime)

    video.currentTime = 0
}