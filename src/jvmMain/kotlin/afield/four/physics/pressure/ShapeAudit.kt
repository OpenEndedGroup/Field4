package afield.four.physics.pressure

import kotlin.math.*

data class ShapeAudit(val intersections: List<Pair<Int,Int>>,val foldedEdges: Int,val maximumFoldDegrees: Double)

/** Diagnostics on computed triangles, independent of shading and viewer explosion. */
object ShapeAudits {
    fun junctionAngles(model: Envelope): Map<Int,Double> {
        val angles=HashMap<Int,Double>();val patches=HashMap<Int,MutableSet<String>>()
        model.triangles.forEach { f->val ns=f.nodes();val uv=f.uv
            for(i in 0..2) {
                val j=(i+1)%3;val k=(i+2)%3
                val ax=uv[2*j]-uv[2*i];val ay=uv[2*j+1]-uv[2*i+1];val bx=uv[2*k]-uv[2*i];val by=uv[2*k+1]-uv[2*i+1]
                angles[ns[i]]=(angles[ns[i]] ?: 0.0)+atan2(abs(ax*by-ay*bx),ax*bx+ay*by)*180/PI
                patches.getOrPut(ns[i]) { HashSet() }.add(f.panel)
            }
        }
        return angles.filterKeys {patches.getValue(it).size>=3}.toSortedMap()
    }
    fun seams(model: Envelope): List<Pair<Int,Int>> {
        val edges=HashMap<Pair<Int,Int>,MutableList<String>>()
        model.triangles.forEach { f->val n=f.nodes();for(i in 0..2) edges.getOrPut(min(n[i],n[(i+1)%3]) to max(n[i],n[(i+1)%3])) { mutableListOf() }.add(f.panel) }
        return edges.filterValues { it.distinct().size>1 }.keys.toList()
    }
    private fun intersect(a: List<P3>,b: List<P3>): Boolean {
        val na=(a[1]-a[0]).cross(a[2]-a[0]);val nb=(b[1]-b[0]).cross(b[2]-b[0])
        fun segment(p: P3,q: P3,t: List<P3>,n: P3): Boolean {
            val u=n.dot(p-t[0]);val v=n.dot(q-t[0]);if(u*v>0 || abs(u-v)<1e-15) return false
            val s=u/(u-v);if(s<0 || s>1) return false
            val hit=p+(q-p)*s
            return (0..2).all { i->(t[(i+1)%3]-t[i]).cross(hit-t[i]).dot(n)>=-1e-12*n.norm() }
        }
        if((0..2).any { segment(a[it],a[(it+1)%3],b,nb) || segment(b[it],b[(it+1)%3],a,na) }) return true
        // Coplanar overlap: separating axes within the common plane, including containment.
        if(na.cross(nb).norm()>1e-9*na.norm()*nb.norm() || abs(na.dot(b[0]-a[0]))>1e-9*na.norm()) return false
        val axes=(0..2).flatMap { listOf(na.cross(a[(it+1)%3]-a[it]),na.cross(b[(it+1)%3]-b[it])) }
        return axes.all { axis->val aa=a.map { it.dot(axis) };val bb=b.map { it.dot(axis) };max(aa.min(),bb.min())<=min(aa.max(),bb.max())+1e-12 }
    }
    fun inspect(model: Envelope,x: DoubleArray): ShapeAudit {
        val triangles=model.triangles.map { f->f.nodes().map { point(x,it) } }
        val normals=triangles.map { (it[1]-it[0]).cross(it[2]-it[0]).unit() }
        val edges=HashMap<Pair<Int,Int>,MutableList<Int>>()
        model.triangles.forEachIndexed { index,f->val n=f.nodes();for(i in 0..2) edges.getOrPut(min(n[i],n[(i+1)%3]) to max(n[i],n[(i+1)%3])) { mutableListOf() }.add(index) }
        val angles=edges.values.map { acos(normals[it[0]].dot(normals[it[1]]).coerceIn(-1.0,1.0))*180/PI }
        val bounds=triangles.map { t->DoubleArray(6) { k->if(k<3) t.minOf { it.array()[k] } else t.maxOf { it.array()[k-3] } } }
        val sorted=triangles.indices.sortedBy { bounds[it][0] };val hits=ArrayList<Pair<Int,Int>>()
        for(ii in sorted.indices) {val i=sorted[ii];val a=bounds[i]
            for(jj in ii+1 until sorted.size) {val j=sorted[jj];val b=bounds[j];if(b[0]>a[3]+1e-10) break
                if((1..2).any { a[it]>b[it+3]+1e-10 || b[it]>a[it+3]+1e-10 }) continue
                if(model.triangles[i].nodes().any { it in model.triangles[j].nodes() }) continue
                if(intersect(triangles[i],triangles[j])) hits.add(i to j)
            }
        }
        return ShapeAudit(hits,angles.count { it>150.0 },angles.max())
    }
}
