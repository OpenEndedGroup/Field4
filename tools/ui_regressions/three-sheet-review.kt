// A contact sheet whose two panels draw different quads at the same place: a pick has to use the
// panel under the pointer, its camera and its own geometry.
three(name="sheet_review",width=760,height=360) {
    columns(2)
    helpers(false)
    val quad = TriMesh(floatArrayOf(-30f,-30f,0f,30f,-30f,0f,30f,30f,0f,-30f,30f,0f),intArrayOf(0,1,2,0,2,3))
    val bounds = quad.toThreeBounds()
    mesh(quad, id="left-quad", panel=0, wireframe=false)
    mesh(quad, id="right-quad", panel=1, wireframe=false)
    panel(title="Left", bounds=bounds)
    panel(title="Right", bounds=bounds)
}
