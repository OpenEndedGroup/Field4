package afield.four.physics.pressure

import kotlin.math.*

/** Double precision mechanical coordinates; metres throughout. */
data class P3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(b: P3) = P3(x+b.x,y+b.y,z+b.z)
    operator fun minus(b: P3) = P3(x-b.x,y-b.y,z-b.z)
    operator fun times(s: Double) = P3(x*s,y*s,z*s)
    fun dot(b: P3) = x*b.x+y*b.y+z*b.z
    fun cross(b: P3) = P3(y*b.z-z*b.y,z*b.x-x*b.z,x*b.y-y*b.x)
    fun norm() = sqrt(dot(this))
    fun unit() = this*(1.0/max(norm(),1e-30))
    fun array() = doubleArrayOf(x,y,z)
}

/** Isotropic membrane material: modulus in Pa, thickness in m and density in kg/m³. Supply measured values for the intended material. */
data class Film(val young: Double, val poisson: Double,
                val thickness: Double, val density: Double,
                val provenance: String = "User-supplied isotropic membrane material") {
    init {
        require(listOf(young,poisson,thickness,density).all { it.isFinite() })
        require(young>0 && poisson>=0 && poisson<.5 && thickness>0 && density>0)
    }
}

/** Tension-only axial element. [ea] is rigidity in newtons, [rest] and [width] are metres, [linearDensity] is kg/m. */
data class Tape(val id: String, val a: Int, val b: Int, val rest: Double,
                val ea: Double, val width: Double = 1.0, val linearDensity: Double = 0.0,
                val start: SurfacePoint? = null, val end: SurfacePoint? = null) {
    init { require((a!=b || start!=null && end!=null) && rest>0 && ea>=0 && width>0 && linearDensity>=0)
        require(listOf(rest,ea,width,linearDensity).all { it.isFinite() }) }
}

/** Material attachment: fixed barycentric weights, independent of the deformed embedding. */
data class SurfacePoint(val nodes: List<Int>, val weights: List<Double>) {
    init { require(nodes.isNotEmpty() && nodes.size==weights.size && nodes.distinct().size==nodes.size)
        require(weights.all { it.isFinite() && it>=0 } && abs(weights.sum()-1)<1e-8) }
    fun at(x: DoubleArray): P3 = nodes.indices.fold(P3(0.0,0.0,0.0)) { p,i->p+point(x,nodes[i])*weights[i] }
    fun distribute(g: DoubleArray,f: P3) { nodes.indices.forEach { add(g,nodes[it],f*weights[it]) } }
}
fun Tape.startPoint() = start ?: SurfacePoint(listOf(a),listOf(1.0))
fun Tape.endPoint() = end ?: SurfacePoint(listOf(b),listOf(1.0))
data class Tether(val id: String,val attachment: SurfacePoint,val target: P3,val rest: Double,val ea: Double) {
    init { require(rest>0 && ea>0 && rest.isFinite() && ea.isFinite() && target.array().all { it.isFinite() }) }
    fun force(x: DoubleArray): P3 {
        val d=target-attachment.at(x); val length=d.norm()
        return if(length<=rest) P3(0.0,0.0,0.0) else d*(ea*(length/rest-1)/length)
    }
}

/** Each triangle retains material coordinates independently of its starting spatial shape. */
data class PanelTriangle(val a: Int, val b: Int, val c: Int, val panel: String,
                         val uv: DoubleArray, val film: Film) {
    val area: Double
    val du: DoubleArray
    val dv: DoubleArray
    init {
        require(uv.size==6 && uv.all { it.isFinite() })
        val x1=uv[2]-uv[0]; val y1=uv[3]-uv[1]
        val x2=uv[4]-uv[0]; val y2=uv[5]-uv[1]
        val det=x1*y2-x2*y1
        require(abs(det)>1e-14) { "Degenerate material triangle in $panel" }
        area=abs(det)*.5
        du=doubleArrayOf((y1-y2)/det,y2/det,-y1/det)
        dv=doubleArrayOf((x2-x1)/det,-x2/det,x1/det)
    }
    fun nodes() = intArrayOf(a,b,c)
}

/** stiffness=null is an ideal fixed point; otherwise a 3D spring in N/m. */
data class Anchor(val id: String, val node: Int, val target: P3, val stiffness: Double? = null,
                  val capacity: Double? = null) {
    init { require(target.array().all { it.isFinite() })
        require(stiffness==null || stiffness.isFinite() && stiffness>0)
        require(capacity==null || capacity.isFinite() && capacity>0) }
}

data class Pattern(val id: String, val outline: List<Pair<Double,Double>>)

/** Closed oriented pressure mesh with independent material coordinates and optional bonded tapes, tethers and anchors. No carrier or cutting algorithm is assumed. Arrays are solve inputs: do not mutate them during evaluation. */
data class Envelope(val initial: DoubleArray, val triangles: List<PanelTriangle>,
                    val tapes: List<Tape> = emptyList(), val anchors: List<Anchor> = emptyList(),
                    val patterns: List<Pattern> = emptyList(),
                    val description: String = "", val tapeProvenance: String = "Uncalibrated EA study parameter",
                    val tethers: List<Tether> = emptyList()) {
    val nodes get() = initial.size/3
    val mass: DoubleArray = DoubleArray(initial.size/3)
    init {
        require(initial.size>=12 && initial.size%3==0 && initial.all { it.isFinite() })
        require(triangles.isNotEmpty())
        val edges=HashMap<Pair<Int,Int>,MutableList<Pair<Int,Int>>>()
        for (f in triangles) {
            val ns=f.nodes(); require(ns.all { it in 0 until nodes } && ns.toSet().size==3)
            require((point(initial,f.b)-point(initial,f.a)).cross(point(initial,f.c)-point(initial,f.a)).norm()>1e-12)
            for (i in 0..2) { val a=ns[i]; val b=ns[(i+1)%3]
                edges.getOrPut(min(a,b) to max(a,b)) { mutableListOf() }.add(a to b) }
            for (n in ns) mass[n]+=f.area*f.film.thickness*f.film.density/3
        }
        require(edges.values.all { it.size==2 && it[0].first==it[1].second && it[0].second==it[1].first }) {
            "Pressure envelope must be closed, manifold and consistently oriented" }
        require(volume(initial,triangles)>1e-10) { "Envelope winding must face outward" }
        for (t in tapes) {
            for(p in listOf(t.startPoint(),t.endPoint())) {
                require(p.nodes.all { it in 0 until nodes })
                p.nodes.indices.forEach { mass[p.nodes[it]]+=t.rest*t.linearDensity*.5*p.weights[it] }
            }
        }
        require(tethers.all { t->t.attachment.nodes.all { it in 0 until nodes } })
        require(anchors.all { it.node in 0 until nodes } && anchors.map { it.node }.toSet().size==anchors.size)
    }
}

fun point(x: DoubleArray,n: Int) = P3(x[3*n],x[3*n+1],x[3*n+2])
internal fun add(x: DoubleArray,n: Int,p: P3) { x[3*n]+=p.x; x[3*n+1]+=p.y; x[3*n+2]+=p.z }
internal fun put(x: DoubleArray,n: Int,p: P3) { x[3*n]=p.x; x[3*n+1]=p.y; x[3*n+2]=p.z }
fun volume(x: DoubleArray,fs: List<PanelTriangle>): Double = fs.sumOf {
    point(x,it.a).dot(point(x,it.b).cross(point(x,it.c)))/6 }
