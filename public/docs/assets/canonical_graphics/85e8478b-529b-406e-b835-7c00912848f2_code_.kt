// an example of storing the camera transforms inside a uniform buffer as opposed to using push constants. Usually we'd _not_ do this. We're all in on buffer
var m = Mesh()
var module = moduleHere()

module += "mainMesh" to m
renderer += module

val camera = Camera()
camera.at = vec(-10,2,0)

var tick = 0.0
loop {
    module.camera.m = camera.m()
    module.camera.p = camera.p()
    if (tick<3) {
        camera.at = vec(-10*Math.sin(tick),0,10*Math.cos(tick))
        for(n in 1 .. 100) {
            camera.at = vec(-10*Math.sin(tick),0,10*Math.cos(tick))
            tick += 0.01
            m.set(3*n, 1*n) {
                n {
                    val z = 0.0 + it
                    pos.add(0,0,-1+z, 1.0,0.1,z, 1,1,z)
                    3 {
                        col.add(10-it*0,it/3.0,1-it/3.0,1)
                    }
                    tri.add(0+3*it,1+3*it,2+3*it)
                }
            }
            frame()
        }
    }
    else {
        tick += 0.01
//        camera.at = vec(-10*Math.sin(tick),0,10*Math.cos(tick))
        _print(tick)
        frame()
    }
    true
}
