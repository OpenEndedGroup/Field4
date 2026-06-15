import afield.four.examples.VirtualTexturingDemo

val camera = Camera()
camera.at = vec(-10,2,0)
KeyCamera(camera=camera)

var vt = VirtualTexturingDemo(camera=camera)

vt.vt.setDebugFlags(true, false, false)