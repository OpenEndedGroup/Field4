package afield.four.physics.pressure

import kotlin.math.*
import afield.stdlib.DenseDoubleOps

/** Truncated preconditioned CG in a trust region. A final force correction, not a stability certificate. */
object NewtonPolish {
    fun improve(model:Envelope,initial:DoubleArray,fraction:Double,options:SolveOptions,
                evaluate:(DoubleArray)->Evaluation,cancelled:()->Boolean,trace:SolveTrace,
                progress:(SolveProgress)->Unit):DoubleArray {
        val fixed=model.anchors.filter {it.stiffness==null}.map {it.node}.toSet()
        val free=initial.indices.filter {it/3 !in fixed}.toIntArray()
        val d=MinimumSearch.diagonal(model)
        val inv=DoubleArray(free.size) {1/sqrt(d[free[it]])}
        fun dot(a:DoubleArray,b:DoubleArray)=DenseDoubleOps.dot(a,b)
        fun norm(a:DoubleArray)=sqrt(dot(a,a))
        fun residual(e:Evaluation)=sqrt(free.sumOf {e.gradient[it]*e.gradient[it]})
        fun relative(e:Evaluation)=residual(e)/max(1.0,sqrt(free.sumOf {e.pressureForces[it]*e.pressureForces[it]}))
        fun boundary(p:DoubleArray,v:DoubleArray,radius:Double):Double {
            val pv=dot(p,v);val vv=dot(v,v)
            return max(0.0,(-pv+sqrt(max(0.0,pv*pv+vv*(radius*radius-dot(p,p)))))/vv)
        }
        var x=initial.copyOf();var e=evaluate(x)
        var radius=.01*sqrt(free.sumOf {d[it]}/free.size)
        repeat(16) { outer->
            if(cancelled()) throw java.util.concurrent.CancellationException()
            val before=residual(e)
            if(before<=options.absoluteTolerance || relative(e)<=options.relativeTolerance) return x
            fun hv(v:DoubleArray):DoubleArray {
                val maxMove=v.indices.maxOf {abs(v[it]*inv[it])}
                if(maxMove==0.0) return DoubleArray(v.size)
                val h=1e-6/maxMove
                val plus=x.copyOf();val minus=x.copyOf()
                for(i in free.indices) {val step=h*v[i]*inv[i];plus[free[i]]+=step;minus[free[i]]-=step}
                val gp=evaluate(plus).gradient;val gm=evaluate(minus).gradient
                return DoubleArray(v.size) {(gp[free[it]]-gm[free[it]])*inv[it]/(2*h)}
            }
            val g=DoubleArray(free.size) {e.gradient[free[it]]*inv[it]}
            val p=DoubleArray(free.size);var r=DoubleArray(free.size) {-g[it]};var v=r.copyOf()
            val target=.05*norm(r);var rr=dot(r,r);var cg=0;var boundaryHit=false
            for(k in 0 until 100) {
                if(cancelled()) throw java.util.concurrent.CancellationException()
                val h=hv(v);val curvature=dot(v,h);cg=k+1
                if(curvature<=0 || !curvature.isFinite()) {
                    val tau=boundary(p,v,radius);for(i in p.indices)p[i]+=tau*v[i];boundaryHit=true;break
                }
                val alpha=rr/curvature
                val candidate=DoubleArray(p.size).also {DenseDoubleOps.combine(it,p,alpha,v)}
                if(norm(candidate)>=radius) {
                    val tau=boundary(p,v,radius);for(i in p.indices)p[i]+=tau*v[i];boundaryHit=true;break
                }
                for(i in p.indices)p[i]=candidate[i]
                val next=DoubleArray(r.size).also {DenseDoubleOps.combine(it,r,-alpha,h)};val nextRR=dot(next,next)
                if(sqrt(nextRR)<=target)break
                val beta=nextRR/rr;v=DoubleArray(v.size).also {DenseDoubleOps.combine(it,next,beta,v)};r=next;rr=nextRR
            }
            val predicted=-dot(g,p)-.5*dot(p,hv(p))
            val trial=x.copyOf();for(i in free.indices)trial[free[i]]+=p[i]*inv[i]
            val next=try {evaluate(trial)} catch (_:IllegalArgumentException) {null}
            val gain=if(next==null) Double.NEGATIVE_INFINITY else e.energy-next.energy
            // At machine precision use a force decrease only inside a narrow energy-roundoff band.
            val noise=64*Math.ulp(max(1.0,abs(e.energy)+abs(e.filmEnergy)+abs(e.tapeEnergy)))
            val roundoff=next!=null && predicted>0 && abs(gain)<=noise && residual(next)<before*.9
            val rho=if(predicted>0) gain/predicted else Double.NEGATIVE_INFINITY
            val accepted=next!=null && (rho>.1 && gain>0 || roundoff)
            trace.newtonIterations++
            trace.rows.add("newton,$fraction,$outer,$cg,$before,$predicted,$gain,$rho,$radius,$accepted,$roundoff")
            if(accepted) {x=trial;e=next}
            if(rho<.25 && !roundoff)radius*=.25 else if(rho>.75 && boundaryHit)radius*=2
            progress(SolveProgress(outer+1,fraction,residual(e),relative(e),trace.evaluations,"Newton-CG"))
            if(radius<1e-10)return x
        }
        return x
    }
}
