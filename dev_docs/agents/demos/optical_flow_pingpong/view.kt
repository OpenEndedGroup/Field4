// The parent publishes these three props: previousImage, currentImage, motion.
// moduleHere() resolves the samplers by those names, just like any other texture.
val view = moduleHere { depthTest=false; depthWrite=false; blend=false }
val triangle = Mesh(renderer,view)
triangle.resize(3,0,view)
view += "display" to triangle
renderer.renderList.modules["pingpong flow texture"]=view
val owner=renderer
frames { while(!owner.isClosed)frame() }.end {
    owner.renderList.modules.remove("pingpong flow texture",view)
    if(!owner.isClosed)owner.cleanup+=Runnable { view.current.cleanup() }
}
