import afield.four.neural.LearnedShader
import afield.four.modules.fromContextWithNameOrNull

// A separate scene: stop the original learned-shader box before launching this one.
// Edit this box's fragment property while it runs; the network stays allocated.
prop<AutoCloseable>(materialSession)
fromContextWithNameOrNull<AutoCloseable>("materialSession")?.close()
val owner=renderer
val passName="editable learned material"
val learned=LearnedShader(owner,name=passName,attach=false)
learned.view=1
learned.setParameter(.25f)
prop<RenderToTexture>(learnedImage)=learned.output
val material=moduleHere { depthTest=false; depthWrite=false; blend=false }
val triangle=Mesh(owner,material)
triangle.resize(3,0,material)
material += "fullscreen" to triangle

// Compute and resolve run before this ordinary fragment shader samples their RTT.
owner.renderList.preTargets[passName]=learned
owner.renderList.modules[passName]=material
var closed=false
materialSession=AutoCloseable {
    if(!closed) {
        closed=true
        owner.renderList.modules.remove(passName,material)
        if(!owner.isClosed) {
            learned.close()
            owner.cleanup+=Runnable { material.current.cleanup() }
        }
    }
}
val ownedSession=materialSession
println("Edit fragment: the learnedImage texture comes from the unchanged network. Stop the original comparison box to avoid rendering it too.")
frames { while(!closed && !owner.isClosed)frame() }.end { ownedSession.close() }
