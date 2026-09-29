package afield.four.physics.pressure

import org.apache.commons.math4.legacy.linear.EigenDecomposition
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.*

data class MinimumAudit(val energy: Double,val curvature: Double,val curvatureAtDoubleStep: Double,
                        val ritzResidual: Double,val directions: Int,val testedDisplacements: Int,
                        val bestEnergyDrop: Double,val escape: DoubleArray?,val shape: ShapeAudit,
                        val rows: List<String> = emptyList()) {
    val negativeCurvature get()=curvature < -1e-2 && curvatureAtDoubleStep < -1e-2
    val title get()=when {
        shape.intersections.isNotEmpty()->"Intersecting skin — rejected"
        negativeCurvature || bestEnergyDrop>max(1e-4,abs(energy)*1e-10)->"Lower-energy direction remains"
        shape.foldedEdges>0->"Sharp folds remain — unresolved"
        else->"No descent found in sampled directions"
    }
}

/** Finite-difference Hessian/Lanczos audit. A positive Ritz value is NOT a stability certificate. */
object MinimumSearch {
    data class Ritz(val value: Double,val vector: DoubleArray,val residual: Double,val steps: Int)
    fun lowest(n: Int,apply: (DoubleArray)->DoubleArray,steps: Int=96,seed: Long=47): Ritz {
        val random=Random(seed);val basis=ArrayList<DoubleArray>();val alpha=ArrayList<Double>();val beta=ArrayList<Double>()
        var q=DoubleArray(n) { random.nextGaussian() };normalise(q)
        for(k in 0 until min(n,steps)) {
            basis.add(q);val v=apply(q);val a=dot(q,v);alpha.add(a)
            for(i in 0 until n) {v[i]-=a*q[i];if(k>0) v[i]-=beta[k-1]*basis[k-1][i]}
            // Full reorthogonalisation avoids spurious duplicate Ritz values in stiff meshes.
            repeat(2) {for(b in basis) {val projection=dot(b,v);for(i in 0 until n) v[i]-=projection*b[i]}}
            val length=sqrt(dot(v,v));if(length<1e-12 || k+1==min(n,steps)) break
            beta.add(length);q=DoubleArray(n) { v[it]/length }
        }
        val eigen=EigenDecomposition(alpha.toDoubleArray(),beta.toDoubleArray())
        val index=alpha.indices.minBy { eigen.getRealEigenvalue(it) };val coefficients=eigen.getEigenvector(index).toArray()
        val vector=DoubleArray(n) { i->basis.indices.sumOf { coefficients[it]*basis[it][i] } };normalise(vector)
        val value=dot(vector,apply(vector));val av=apply(vector)
        return Ritz(value,vector,sqrt(av.indices.sumOf { (av[it]-value*vector[it]).pow(2) }),basis.size)
    }
    private fun dot(a: DoubleArray,b: DoubleArray)=a.indices.sumOf { a[it]*b[it] }
    private fun normalise(x: DoubleArray) {val n=sqrt(dot(x,x));if(n>0) for(i in x.indices) x[i]/=n}
    internal fun diagonal(m: Envelope): DoubleArray {
        val d=DoubleArray(m.initial.size) { 1.0 }
        m.triangles.forEach { f->for(j in 0..2) for(k in 0..2) d[3*f.nodes()[j]+k]+=f.film.young*f.film.thickness*f.area*(f.du[j].pow(2)+f.dv[j].pow(2)) }
        m.tapes.forEach { t->for(p in listOf(t.startPoint(),t.endPoint())) for(j in p.nodes.indices) for(k in 0..2) d[3*p.nodes[j]+k]+=t.ea/t.rest*p.weights[j].pow(2) }
        m.tethers.forEach { t->for(j in t.attachment.nodes.indices) for(k in 0..2) d[3*t.attachment.nodes[j]+k]+=t.ea/t.rest*t.attachment.weights[j].pow(2) }
        return d
    }
    fun audit(s: Solution,cancelled: ()->Boolean={false},steps: Int=96): MinimumAudit {
        require(s.converged) {"Minimum audit requires full-pressure force balance"}
        val m=s.model;val x=s.positions;val mechanics=Mechanics(m,s.load);val base=mechanics.evaluate(x,details=false)
        val fixed=m.anchors.filter { it.stiffness==null }.map { it.node }.toSet();val free=x.indices.filter { it/3 !in fixed }
        val diag=diagonal(m);val inv=DoubleArray(free.size) { 1/sqrt(diag[free[it]]) }
        fun full(v: DoubleArray)=DoubleArray(x.size).also { out->free.indices.forEach { out[free[it]]=v[it]*inv[it] } }
        fun hv(v: DoubleArray,h: Double): DoubleArray {
            if(cancelled()) throw CancellationException()
            val scale=max(1e-20,v.maxOf { abs(it) });val step=h/scale
            val plus=mechanics.evaluate(DoubleArray(x.size) { x[it]+step*v[it] },details=false).gradient
            val minus=mechanics.evaluate(DoubleArray(x.size) { x[it]-step*v[it] },details=false).gradient
            return DoubleArray(x.size) { (plus[it]-minus[it])/(2*step) }
        }
        val ritz=lowest(free.size,{v->val h=hv(full(v),1e-5);DoubleArray(free.size) { h[free[it]]*inv[it] }},steps)
        val direction=full(ritz.vector);normalise(direction)
        val curvature=dot(direction,hv(direction,1e-5));val curvature2=dot(direction,hv(direction,2e-5))
        val directions=ArrayList<DoubleArray>();directions.add(direction)
        val neighbours=Array(m.nodes) { HashSet<Int>() };m.triangles.forEach { f->for(a in f.nodes()) for(b in f.nodes()) if(a!=b) neighbours[a].add(b) }
        val random=Random(7129)
        repeat(8) {
            var v=DoubleArray(x.size) { random.nextGaussian() }
            repeat(6) { v=DoubleArray(x.size) { i->(v[i]+neighbours[i/3].sumOf { v[3*it+i%3] })/(neighbours[i/3].size+1) } }
            fixed.forEach { put(v,it,P3(0.0,0.0,0.0)) };normalise(v);directions.add(v)
        }
        var best=base.energy;var escape: DoubleArray?=null;var tested=0
        for(v in directions) {val maximum=v.maxOf { abs(it) };if(maximum<1e-20) continue
            for(size in listOf(.0001,.001,.005,.02)) for(sign in listOf(-1.0,1.0)) {
                if(cancelled()) throw CancellationException()
                val trial=DoubleArray(x.size) { x[it]+sign*size*v[it]/maximum }
                val energy=runCatching { mechanics.evaluate(trial,details=false).energy }.getOrDefault(Double.POSITIVE_INFINITY);tested++
                if(energy<best-max(1e-4,abs(base.energy)*1e-10)) {best=energy;escape=trial}
            }
        }
        return MinimumAudit(base.energy,curvature,curvature2,ritz.residual,directions.size,tested,base.energy-best,escape,ShapeAudits.inspect(m,x))
    }
    fun improve(input: Solution,cancelled: ()->Boolean={false},progress: (String)->Unit={}): Solution {
        if(!input.converged) return input
        var best=input;val rows=ArrayList<String>();val start=System.nanoTime()
        var audit=audit(best,cancelled)
        rows.add("initial potential ${best.evaluation.energy} J; ${audit.title}; tested drop ${audit.bestEnergyDrop} J")
        for(round in 0 until 3) {
            val seed=audit.escape ?: break
            progress("Minimum search ${round+1}/3 · testing a descent of ${audit.bestEnergyDrop} J")
            val trial=InflationSolver.solve(best.model,best.load,SolveOptions(pressureSteps=4,iterationsPerStep=48000,relativeTolerance=2e-6,absoluteTolerance=.005),cancelled=cancelled,initialPositions=seed)
            val gain=best.evaluation.energy-trial.evaluation.energy
            rows.add("restart ${round+1}: potential ${trial.evaluation.energy} J; decrease $gain J; force balance ${trial.converged}")
            if(!trial.converged || gain<=max(1e-4,abs(best.evaluation.energy)*1e-10)) break
            best=trial;audit=audit(best,cancelled)
        }
        return best.copy(elapsedMs=input.elapsedMs+(System.nanoTime()-start)/1e6,minimum=audit.copy(rows=rows))
    }
}
