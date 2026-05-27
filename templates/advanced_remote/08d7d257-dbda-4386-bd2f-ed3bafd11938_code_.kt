// advanced stenciling technique
// --- execute this first
clearAFrame()

var box = aCreate("Box", "a-box")

size = 1
box.position = vec(0,0,-1)
box.setAttribute("width", 0.2 * size)
box.setAttribute("height", 0.2  * size)
box.setAttribute("depth", 0.2  * size)
box.setAttribute("opacity", 1.0)
box.setAttribute("color", "red")

var p = aCreate("Plane", "a-plane")
p.position = vec(0,0,0)

p.setAttribute("width", 1 * size)
p.setAttribute("height", 1  * size)
p.setAttribute("opacity", 1.0)

// then execute this after one frame -----------
// add make plane, make box etc

var done= false
_r = function*() {
    yield
    if (!done) {
        done = true
        p.object3D.children[0].onBeforeRender = (renderer) => {
            var gl = renderer.getContext()
            gl.enable( gl.STENCIL_TEST );
            gl.depthMask(false)
            gl.stencilFunc( gl.ALWAYS, 1, 0xff );
            gl.stencilOp( gl.REPLACE, gl.REPLACE, gl.REPLACE );
        }

        p.object3D.children[0].onAfterRender = (renderer) => {
            var gl = renderer.getContext()
            gl.disable( gl.STENCIL_TEST );
            gl.depthMask(true)
            gl.stencilFunc( gl.ALWAYS, 1, 0xff );
            gl.stencilOp( gl.KEEP, gl.KEEP, gl.KEEP );
        }

        box.object3D.children[0].onBeforeRender = (renderer) => {
            var gl = renderer.getContext()
            gl.enable( gl.STENCIL_TEST );
            gl.stencilFunc( gl.EQUAL, 1, 0xff );
            gl.stencilOp( gl.KEEP, gl.KEEP, gl.KEEP );
        }

        box.object3D.children[0].onAfterRender = (renderer) => {
            var gl = renderer.getContext()
            gl.disable( gl.STENCIL_TEST );
            gl.depthMask(true)
            gl.stencilFunc( gl.ALWAYS, 1, 0xff );
            gl.stencilOp( gl.KEEP, gl.KEEP, gl.KEEP );
        }
    }
}