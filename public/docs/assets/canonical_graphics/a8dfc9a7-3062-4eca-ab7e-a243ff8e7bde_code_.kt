import org.lwjgl.vulkan.VK10.*
import afield.four.targets.*
import afield.four.texture.*

prop<PingPongRenderToTexture>(target) = PingPongRenderToTexture {
    clearFirst = false
    useDepth = false
}

prop<HasImageView>(otherView) = target.otherImageView

renderer.renderList += "target" to target

var module = moduleHere {
    //  imageFormat = VK_FORMAT_R16G16B16A16_SFLOAT
    target.apply()
}
var m = Mesh()

module += "mainMesh" to m
target += "boo" to module

val camera = Camera()
m.p = camera.p
m.m = camera.m
m.finalColor = { vec(Math.random(),Math.random(),Math.random(),1.0) }



// we can set this live and after the fact
target.parameters.clearFirst = true

var kc = KeyCamera(camera=camera, name="kc")
loop {
    m.set(3, 1) {
        val z = Math.random()*0.0
        pos.add(-3,1,-1+z, 1.0,-20.3,z, 3,3,z)
        tri.add(0,1,2)
    }
    true
}
