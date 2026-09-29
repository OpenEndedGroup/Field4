package afield.four.physics.pressure

import kotlin.math.*
import kotlin.test.*
import java.util.concurrent.CancellationException

/** Independent mesh fixtures: no architectural carrier, gore generator, saved document or personal sources. */
class PressureTest {
    private val material=Film(1e6,.3,.1,100.0,"test material")
    private fun mesh(points:List<P3>,indices:List<List<Int>>):Envelope {
        val faces=indices.map {ids->
            val a=points[ids[0]];val e=points[ids[1]]-a;val f=points[ids[2]]-a
            PanelTriangle(ids[0],ids[1],ids[2],"skin",doubleArrayOf(0.0,0.0,e.norm(),0.0,f.dot(e.unit()),f.cross(e.unit()).norm()),material)
        }
        return Envelope(points.flatMap {it.array().toList()}.toDoubleArray(),faces)
    }
    private fun octahedron():Envelope {
        val points=listOf(P3(1.0,0.0,0.0),P3(-1.0,0.0,0.0),P3(0.0,1.0,0.0),P3(0.0,-1.0,0.0),P3(0.0,0.0,1.0),P3(0.0,0.0,-1.0))
        val faces=listOf(0,1).flatMap {a->listOf(2,3).flatMap {b->listOf(4,5).map {c->
            if((points[b]-points[a]).cross(points[c]-points[a]).dot(points[a])>0) listOf(a,b,c) else listOf(a,c,b)
        }}}
        return mesh(points,faces)
    }
    private fun sphere(rings:Int):Envelope {
        val cols=2*rings;val points=ArrayList<P3>();points.add(P3(0.0,1.0,0.0))
        for(i in 1 until rings) for(j in 0 until cols) {val t=PI*i/rings;val p=2*PI*j/cols;points.add(P3(sin(t)*cos(p),cos(t),sin(t)*sin(p)))}
        val bottom=points.size;points.add(P3(0.0,-1.0,0.0))
        fun id(i:Int,j:Int)=when(i){0->0;rings->bottom;else->1+(i-1)*cols+j%cols}
        val faces=ArrayList<List<Int>>()
        fun add(a:Int,b:Int,c:Int) {faces.add(if((points[b]-points[a]).cross(points[c]-points[a]).dot(points[a])>0) listOf(a,b,c) else listOf(a,c,b))}
        for(j in 0 until cols) {
            add(0,id(1,j),id(1,j+1))
            for(i in 1 until rings-1) {add(id(i,j),id(i+1,j),id(i+1,j+1));add(id(i,j),id(i+1,j+1),id(i,j+1))}
            add(id(rings-1,j),bottom,id(rings-1,j+1))
        }
        return mesh(points,faces)
    }
    @Test fun materialBranchesForcesAndPackedKernelAgree() {
        val base=octahedron()
        val model=base.copy(tapes=listOf(Tape("reinforcement",0,2,1.3,2000.0,width=.02,linearDensity=.1)),
            anchors=listOf(Anchor("spring",1,P3(-1.1,.1,0.0),3000.0)),
            tethers=listOf(Tether("cable",SurfacePoint(listOf(0,2,4),listOf(.2,.3,.5)),P3(2.0,-2.0,1.0),1.0,2000.0)))
        val states=HashSet<String>()
        for(gas in GasMode.entries) for(scale in listOf(P3(.9,.9,.9),P3(1.05,.95,1.0),P3(1.05,1.05,1.05))) {
            val x=DoubleArray(base.initial.size) {i->base.initial[i]*scale.array()[i%3]}
            val load=LoadCase(1000.0,gas,P3(.1,-1.0,.2),groundY=-.85)
            val ref=Mechanics(model,load).evaluate(x,.7);val fast=FastMechanics(model,load).evaluate(x,.7)
            states.addAll(ref.films.map {it.state})
            assertEquals(ref.energy,fast.energy,1e-7)
            ref.gradient.indices.forEach {assertEquals(ref.gradient[it],fast.gradient[it],1e-7)}
            val v=DoubleArray(x.size) {sin(it*1.73)};val h=1e-6
            val numeric=(Mechanics(model,load).evaluate(DoubleArray(x.size){x[it]+h*v[it]},.7).energy-Mechanics(model,load).evaluate(DoubleArray(x.size){x[it]-h*v[it]},.7).energy)/(2*h)
            val analytic=x.indices.sumOf {ref.gradient[it]*v[it]}
            assertTrue(abs(numeric-analytic)<1e-3+abs(analytic)*1e-6,"energy derivative: $numeric / $analytic")
        }
        assertEquals(setOf("slack","wrinkled","taut"),states)
    }
    @Test fun pressureResultantAndSealedGasAreIndependentOfTranslation() {
        val m=octahedron();val x=DoubleArray(m.initial.size){m.initial[it]*1.04}
        val load=LoadCase(1000.0,GasMode.SEALED_ISOTHERMAL)
        val a=Mechanics(m,load).evaluate(x)
        assertEquals(1000.0*volume(m.initial,m.triangles),a.pressure*a.volume,1e-8)
        assertTrue((0 until m.nodes).fold(P3(0.0,0.0,0.0)){sum,n->sum+point(a.pressureForces,n)}.norm()<1e-8)
        val shifted=DoubleArray(x.size) {x[it]+listOf(3.0,-2.0,4.0)[it%3]}
        val b=Mechanics(m,load).evaluate(shifted)
        assertEquals(a.energy,b.energy,1e-7)
        a.gradient.indices.forEach {assertEquals(a.gradient[it],b.gradient[it],1e-7)}
    }
    @Test fun meshAndOptionValidationRejectsBrokenInputs() {
        val m=octahedron()
        assertFailsWith<IllegalArgumentException>{m.copy(triangles=m.triangles.dropLast(1))}
        assertFailsWith<IllegalArgumentException>{m.copy(triangles=m.triangles.map {it.copy(b=it.c,c=it.b)})}
        assertFailsWith<IllegalArgumentException>{SolveOptions(relativeTolerance=Double.POSITIVE_INFINITY)}
        assertFailsWith<IllegalArgumentException>{SolveOptions(minimumStep=2.0)}
        assertEquals(0.0,LoadCase().pressure)
    }
    @Test fun sphereApproachesLaplaceLawUnderRefinement() {
        val errors=listOf(8,16).map {rings->
            val m=sphere(rings);val s=InflationSolver.solve(m,LoadCase(1000.0),SolveOptions(pressureSteps=4,iterationsPerStep=3000))
            assertTrue(s.converged,s.reason)
            val radius=(0 until m.nodes).map {point(s.positions,it).norm()}.average()
            val k=1000.0*(1-material.poisson)/(material.young*material.thickness)
            assertEquals((k+sqrt(k*k+4))*.5,radius,.015)
            val area=s.evaluation.films.sumOf {it.area}
            val force=s.evaluation.films.sumOf {(it.major+it.minor)*.5*it.area}/area
            val error=abs(force-1000.0*radius/2)/(1000.0*radius/2)
            assertTrue(error<.065,"Laplace error $error")
            assertTrue(s.balanceN<1e-6)
            error
        }
        assertTrue(errors[1]<errors[0])
    }
    @Test fun stoppingCancellationAndAuditRemainHonest() {
        val m=octahedron()
        val stopped=InflationSolver.solve(m,LoadCase(1000.0),SolveOptions(iterationsPerStep=1,newtonPolish=false))
        assertFalse(stopped.converged)
        assertFailsWith<CancellationException>{InflationSolver.solve(m,LoadCase(1000.0),cancelled={true})}
        val r=MinimumSearch.lowest(4,{v->doubleArrayOf(-2*v[0],v[1],2*v[2],3*v[3])},steps=4)
        assertEquals(-2.0,r.value,1e-9)
        assertTrue(r.residual<1e-8)
        assertTrue(ShapeAudits.inspect(m,m.initial).intersections.isEmpty())
    }
}
