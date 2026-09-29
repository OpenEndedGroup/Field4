package afield.four.physics.pressure

import afield.stdlib.ADGradient
import afield.stdlib.OptimizeMethod
import afield.stdlib.OptimizeOptions
import afield.stdlib.minimizeWithGradient
import java.util.concurrent.CancellationException
import kotlin.math.*

data class SolveOptions(val pressureSteps: Int = 8, val iterationsPerStep: Int = 6000,
                        val relativeTolerance: Double = 2e-5, val absoluteTolerance: Double = .02,
                        val minimumStep: Double = 1.0/128, val precondition: Boolean = false,
                        val physicalStopping: Boolean = true, val newtonPolish: Boolean = true, val fastKernel: Boolean = true) {
    init { require(pressureSteps>0 && iterationsPerStep>0 && relativeTolerance>0 && absoluteTolerance>0 && minimumStep>0 && minimumStep<=1.0 && listOf(relativeTolerance,absoluteTolerance,minimumStep).all {it.isFinite()}) }
}
data class InflationStep(val fraction: Double,val pressure: Double,val volume: Double,
                         val residualN: Double,val relativeResidual: Double,val iterations: Int,
                         val accepted: Boolean,val reason: String,val positions: DoubleArray)
data class AnchorReaction(val anchor: Anchor,val force: P3)
data class Solution(val model: Envelope,val load: LoadCase,val positions: DoubleArray,
                    val evaluation: Evaluation,val history: List<InflationStep>,
                    val converged: Boolean,val reason: String,val elapsedMs: Double,
                    val reactions: List<AnchorReaction>,val balanceN: Double,val momentNm: Double,
                    val residualN: Double,val relativeResidual: Double,val minimum: MinimumAudit?=null,
                    val trace: SolveTrace = SolveTrace()) {
    val maximumTapeForce get() = evaluation.tapes.maxOfOrNull { it.tension } ?: 0.0
    val maximumFilmForce get() = evaluation.films.maxOf { it.major }
    val maximumStrain get() = evaluation.films.maxOf { it.strain }
}

/** Uses Field's existing L-BFGS; only physical residuals can declare an equilibrium accepted. */
data class SolveProgress(val iteration:Int,val fraction:Double,val residualN:Double,val relativeResidual:Double,val evaluations:Int,val method:String)
class SolveTrace {
    var evaluations=0;var iterations=0;var backtracks=0;var skippedCurvature=0;var newtonIterations=0
    val rows=ArrayList<String>()
    override fun toString()="evaluations=$evaluations iterations=$iterations backtracks=$backtracks skippedCurvature=$skippedCurvature newton=$newtonIterations"
}
object InflationSolver {
    /** Relax a supplied closed membrane by pressure continuation. [initialPositions] enables a full-load warm start.
     * Acceptance uses free-force residuals in N, not optimizer step size. Failed continuation returns an
     * unconverged result with its actual load fraction in [Solution.history]. Cancellation propagates.
     * A converged force balance is not proof of a minimum; [MinimumSearch] offers a sampled diagnostic.
     */
    fun solve(model: Envelope, load: LoadCase = LoadCase(), options: SolveOptions = SolveOptions(),
              cancelled: () -> Boolean = { false }, initialPositions: DoubleArray? = null, onProgress:(SolveProgress)->Unit={}, onStep: (InflationStep) -> Unit = {}): Solution {
        require(load.gravity.norm()==0.0 || model.anchors.isNotEmpty() || model.tethers.isNotEmpty() || load.groundY!=null) {
            "Gravity needs supports/contact; an unsupported body has no static equilibrium" }
        val start=System.nanoTime(); val mechanics=Mechanics(model,load)
        val trace=SolveTrace()
        val fast=if(options.fastKernel) FastMechanics(model,load) else null
        fun evaluate(x:DoubleArray,fraction:Double):Evaluation {
            if(cancelled()) throw CancellationException("Inflation cancelled")
            trace.evaluations++
            return fast?.evaluate(x,fraction) ?: mechanics.evaluate(x,fraction,false)
        }
        val fixed=model.anchors.filter { it.stiffness==null }.associateBy { it.node }
        val free=model.initial.indices.filter { it/3 !in fixed }.toIntArray()
        require(free.isNotEmpty())
        // Change optimization coordinates only; residual acceptance remains in newtons.
        val diagonal=if(options.precondition) MinimumSearch.diagonal(model) else DoubleArray(model.initial.size) {1.0}
        val typical=free.map {diagonal[it]}.sorted()[free.size/2]
        val coordinateScale=DoubleArray(free.size) {sqrt(typical/diagonal[free[it]])}
        fun full(q: DoubleArray): DoubleArray = model.initial.copyOf().also { x ->
            free.forEachIndexed { i,k -> x[k]=q[i]*coordinateScale[i] }; fixed.values.forEach { put(x,it.node,it.target) } }
        require(initialPositions==null || initialPositions.size==model.initial.size && initialPositions.all { it.isFinite() })
        val initial=(initialPositions ?: model.initial).copyOf().also { x->fixed.values.forEach { put(x,it.node,it.target) } }
        var q=DoubleArray(free.size) { initial[free[it]]/coordinateScale[it] }
        val scale=max(1.0,model.triangles.sumOf { it.area*it.film.young*it.film.thickness })
        val history=ArrayList<InflationStep>()
        var fraction=0.0; var increment=if(initialPositions==null) 1.0/options.pressureSteps else 1.0
        var target=if(load.pressure==0.0) 1.0 else increment
        var lastPositions=initial; var reason="not started"; var converged=false
        var lastResidual=Double.POSITIVE_INFINITY; var lastRelative=Double.POSITIVE_INFINITY
        while(true) {
            if(cancelled()) throw CancellationException("Inflation cancelled")
            var latest:Evaluation?=null
            val run=minimizeWithGradient(q,OptimizeOptions(method=OptimizeMethod.LBFGS,
                iterations=options.iterationsPerStep,memory=16,maxLineSearchSteps=48,
                gradientTolerance=options.absoluteTolerance/(scale*sqrt(free.size.toDouble())),stepTolerance=1e-15,showProgress=false,
                recordTrajectory=false), observer={ p ->
                val e=latest!!
                val r=sqrt(free.sumOf { e.gradient[it]*e.gradient[it] })
                val relative=r/max(1.0,sqrt(free.sumOf { e.pressureForces[it]*e.pressureForces[it] }))
                trace.backtracks+=p.lineSearchReductions
                if(p.curvatureAccepted==false) trace.skippedCurvature++
                if(p.iteration%25==0) {
                    trace.rows.add("lbfgs,$target,${p.iteration},${p.evaluations},$r,$relative,${p.value},${p.stepNorm},${p.lineSearchReductions},${p.curvatureAccepted}")
                    onProgress(SolveProgress(p.iteration,target,r,relative,trace.evaluations,"L-BFGS"))
                }
                options.physicalStopping && (r<=options.absoluteTolerance || target>0 && relative<=options.relativeTolerance)
            }) { u ->
                if(cancelled()) throw CancellationException("Inflation cancelled")
                try {
                    val e=evaluate(full(u),target);latest=e
                    ADGradient(e.energy/scale,DoubleArray(free.size) { e.gradient[free[it]]*coordinateScale[it]/scale })
                } catch(e: IllegalArgumentException) { ADGradient(1e100,DoubleArray(free.size)) }
            }
            trace.iterations+=run.iterations
            trace.rows.add("stop,$target,${run.iterations},${run.evaluations},${run.reason}")
            var positions=full(run.x)
            var evaluation=evaluate(positions,target)
            val before=sqrt(free.sumOf {evaluation.gradient[it].pow(2)})
            val gate=max(options.absoluteTolerance,options.relativeTolerance*sqrt(free.sumOf {evaluation.pressureForces[it].pow(2)}))
            if(options.newtonPolish && before>gate && before<max(100.0,gate*100)) {
                positions=NewtonPolish.improve(model,positions,target,options,{evaluate(it,target)},cancelled,trace,onProgress)
                evaluation=evaluate(positions,target)
            }
            if(fast!=null) evaluation=mechanics.evaluate(positions,target,false)
            val residual=sqrt(free.sumOf { evaluation.gradient[it].pow(2) })
            val pressureNorm=sqrt(free.sumOf { evaluation.pressureForces[it].pow(2) })
            val relative=residual/max(1.0,pressureNorm)
            val accepted=residual<=options.absoluteTolerance || target>0 && relative<=options.relativeTolerance
            val step=InflationStep(target,evaluation.pressure,evaluation.volume,residual,relative,
                run.iterations,accepted,run.reason,positions.copyOf())
            history.add(step); onStep(step)
            if(accepted) {
                q=DoubleArray(free.size) {positions[free[it]]/coordinateScale[it]}; lastPositions=positions; fraction=target
                lastResidual=residual; lastRelative=relative
                if(target>=1.0) { converged=true; reason="force residual satisfied at full pressure"; break }
                target=min(1.0,fraction+increment)
            } else if(initialPositions!=null || target==0.0 || increment<=options.minimumStep) {
                lastPositions=positions; lastResidual=residual; lastRelative=relative
                reason="Unconverged at inflation fraction $target (${run.reason})"; break
            } else {
                increment*=.5; target=min(1.0,fraction+increment)
            }
        }
        val evaluation=mechanics.evaluate(lastPositions,target,true)
        val reactions=model.anchors.map { a -> AnchorReaction(a,if(a.stiffness==null) point(evaluation.gradient,a.node)
            else (a.target-point(lastPositions,a.node))*a.stiffness) }
        var force=P3(0.0,0.0,0.0); var moment=force
        // Resultant of pressure + gravity + ground + anchor forces, about model centroid.
        val origin=(0 until model.nodes).fold(P3(0.0,0.0,0.0)) { s,n->s+point(lastPositions,n) }*(1.0/model.nodes)
        for(n in 0 until model.nodes) {
            val f=point(evaluation.pressureForces,n)+load.gravity*model.mass[n]+point(evaluation.contact,n)
            force+=f; moment+=(point(lastPositions,n)-origin).cross(f)
        }
        reactions.forEach { force+=it.force; moment+=(point(lastPositions,it.anchor.node)-origin).cross(it.force) }
        model.tethers.forEach { val f=it.force(lastPositions); force+=f; moment+=(it.attachment.at(lastPositions)-origin).cross(f) }
        return Solution(model,load,lastPositions,evaluation,history,converged,reason,
            (System.nanoTime()-start)/1e6,reactions,force.norm(),moment.norm(),lastResidual,lastRelative,trace=trace)
    }
}
