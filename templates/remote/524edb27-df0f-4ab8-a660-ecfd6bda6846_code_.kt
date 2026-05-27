clearAFrame()
var plane = aCreate("plane", "a-plane")
var size = 0.1

// put the plane where we clicked
plane.position = vec(0,1,-0.5)
plane.setAttribute("width", 1 * size)
plane.setAttribute("height", 3/4  * size)
plane.setAttribute("opacity", 1.0)
plane.setAttribute("src", "/local/Desktop/ophelia.jpg")
