var m = Mesh()
var module = moduleHere()

module += "mainMesh" to m
renderer += "boo" to module

val camera = Camera()
camera.at = vec(-10,2,0)


var tick = 0.0
loop {
    module.camera.m = camera.m()
    module.camera.p = camera.p()
    module.wiggle.offset = vec(1,Math.random(),0)

    camera.at = vec(-10*Math.sin(tick),0,10*Math.cos(tick))
    if (tick<3) {
        for(n in 1 .. 100) {
            camera.at = vec(-10*Math.sin(tick),0,10*Math.cos(tick))
            tick += 0.01
            m.set(3*n, 1*n) {
                n {
                    val z = 0.0 + it
                    pos.put(0,0,-1+z, 1.0,0.1,z, 1,1,z)
                    3 {
                        col.put(10-it*0,it/3.0,1-it/3.0,1)
                    }
                    tri.put(0+3*it,1+3*it,2+3*it)
                }
            }
            frame()
        }
    }
    else {
        tick += 0.01
        frame()
    }
    true
}
