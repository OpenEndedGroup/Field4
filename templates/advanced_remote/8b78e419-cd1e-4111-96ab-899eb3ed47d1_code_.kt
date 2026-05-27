clearAFrame()

var shader = window.newShaderForBox()

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

if (false) {
    // once that's loaded, we can run this code here
    var map = window.plane.object3D.children[0].material.map

    window.plane.object3D.children[0].material = shader

    shader.uniforms["map"] = {value:map}
}
