


val shape = arrayOf(doubleArrayOf(0.0,0.0,0.0),doubleArrayOf(1000.0,0.0,0.0),
    doubleArrayOf(0.0,1000.0,0.0),doubleArrayOf(0.0,0.0,1000.0))
val triangles = intArrayOf(0,2,1,0,1,3,0,3,2,1,2,3)
for (index in 0..2) {
    val unit = if(index==1)1000.0 else 1.0
    val origin = if(index==1)doubleArrayOf(-2.0,1.0,.5) else doubleArrayOf(3000.0,-1000.0,2000.0)
    val zUp=index!=1
    val positions=shape.flatMap { p ->
        val v=p.mapIndexed{k,n->n/unit-origin[k]}
        if(zUp)listOf(v[0],v[2],-v[1]) else v
    }.map(Double::toFloat).toFloatArray()
    three(name="camera-link-$index",channel="camera-$index",width=620,height=430) {
        mesh(TriMesh(positions,triangles),id="shape",label="Shared shape",pickable=true,
            cullBackFaces=false,flatShading=true)
        inspect(units=if(index==1)"m" else "mm",renderOrigin=origin,
            renderUpAxis=if(zUp)ThreeUpAxis.Z else ThreeUpAxis.Y)
        helpers(false);caption(if(index==2)"Independent camera" else "Linked comparison · different render origins and units")
    }
}
