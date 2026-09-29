package afield.four.physics.pressure

import kotlin.math.*

enum class GasMode { REGULATED, SEALED_ISOTHERMAL }
/** Pressure difference in Pa, gravity in m/s², optional horizontal penalty-contact plane in m. Sealed gas uses the mesh initial volume as its reference. */
data class LoadCase(val pressure: Double = 0.0, val gasMode: GasMode = GasMode.REGULATED,
                    val gravity: P3 = P3(0.0,0.0,0.0), val groundY: Double? = null,
                    val groundPenalty: Double = 1e8) {
    init { require(pressure.isFinite() && pressure>=0 && gravity.array().all { it.isFinite() })
        require(groundY==null || groundY.isFinite()); require(groundPenalty.isFinite() && groundPenalty>0) }
}

data class FilmState(val major: Double, val minor: Double, val strain: Double,
                     val direction: P3, val state: String, val area: Double)
data class TapeState(val tension: Double, val strain: Double)
data class Evaluation(val energy: Double, val gradient: DoubleArray, val pressure: Double,
                      val volume: Double, val films: List<FilmState>, val tapes: List<TapeState>,
                      val contact: DoubleArray, val pressureForces: DoubleArray,
                      val filmEnergy: Double, val tapeEnergy: Double)

/**
 * Total-Lagrangian constant-strain triangles; large rotations, elastic Green strain.
 * Isotropic plane-stress St Venant--Kirchhoff energy relaxed in compressive principal
 * directions (taut / uniaxially wrinkled / slack). No bending or wrinkle wavelength.
 * Film force resultants are pushed into the current surface, in N per current metre.
 * This elastic law does not model bending, self-contact, plasticity or material failure.
 */
class Mechanics(val model: Envelope, val load: LoadCase) {
    private val referenceVolume=volume(model.initial,model.triangles)
    private val nodeArea=DoubleArray(model.nodes).also { a -> model.triangles.forEach { f ->
        f.nodes().forEach { a[it]+=f.area/3 } } }

    fun evaluate(x: DoubleArray, fraction: Double = 1.0, details: Boolean = true): Evaluation {
        require(x.size==model.initial.size)
        val g=DoubleArray(x.size); val pf=DoubleArray(x.size); val contact=DoubleArray(x.size)
        val films=ArrayList<FilmState>(); val tapes=ArrayList<TapeState>()
        var filmEnergy=0.0; var tapeEnergy=0.0; var supportEnergy=0.0
        val vol=volume(x,model.triangles)
        require(vol>1e-12 && vol.isFinite()) { "Collapsed or inverted pressure volume" }
        val gasConstant=load.pressure*fraction*referenceVolume
        val pressure=when(load.gasMode) { GasMode.REGULATED->load.pressure*fraction; GasMode.SEALED_ISOTHERMAL->gasConstant/vol }
        val pressureEnergy=when(load.gasMode) { GasMode.REGULATED->-pressure*vol; GasMode.SEALED_ISOTHERMAL->-gasConstant*ln(vol/referenceVolume) }
        for(f in model.triangles) {
            val ids=f.nodes(); val p=ids.map { point(x,it) }
            var fu=P3(0.0,0.0,0.0); var fv=fu
            for(i in 0..2) { fu+=p[i]*f.du[i]; fv+=p[i]*f.dv[i] }
            val jac=fu.cross(fv).norm()
            require(jac>1e-8 && jac.isFinite()) { "Collapsed membrane element" }
            val eu=(fu.dot(fu)-1)*.5; val ev=(fv.dot(fv)-1)*.5; val es=fu.dot(fv)*.5
            val mid=(eu+ev)*.5; val rad=hypot((eu-ev)*.5,es)
            val e1=mid+rad; val e2=mid-rad
            val film=f.film; val nu=film.poisson; val young=film.young
            val s1: Double; val s2: Double; val density: Double; val state: String
            when {
                e1<=0 -> { s1=0.0; s2=0.0; density=0.0; state="slack" }
                e2 < -nu*e1 -> { s1=young*e1; s2=0.0; density=.5*young*e1*e1; state="wrinkled" }
                else -> { val k=young/(1-nu*nu)
                    s1=k*(e1+nu*e2); s2=k*(e2+nu*e1)
                    density=.5*(s1*e1+s2*e2); state="taut" }
            }
            val theta=.5*atan2(2*es,eu-ev); val c=cos(theta); val s=sin(theta)
            val suu=s1*c*c+s2*s*s; val svv=s1*s*s+s2*c*c; val suv=(s1-s2)*c*s
            val pu=(fu*suu+fv*suv)*(f.area*film.thickness)
            val pv=(fu*suv+fv*svv)*(f.area*film.thickness)
            for(i in 0..2) add(g,ids[i],pu*f.du[i]+pv*f.dv[i])
            filmEnergy+=f.area*film.thickness*density
            // Exact derivative of the closed triangulated volume: pressure is a follower load.
            val q=arrayOf(p[1].cross(p[2]),p[2].cross(p[0]),p[0].cross(p[1]))
            for(i in 0..2) { add(g,ids[i],q[i]*(-pressure/6)); add(pf,ids[i],q[i]*(pressure/6)) }
            if(details) films.add(FilmState(film.thickness*(1+2*e1)*s1/jac,
                film.thickness*(1+2*e2)*s2/jac,sqrt(max(0.0,1+2*e1))-1,
                (fu*c+fv*s).unit(),state,jac*f.area))
        }
        for(t in model.tapes) {
            val a=t.startPoint(); val b=t.endPoint()
            val delta=b.at(x)-a.at(x); val len=delta.norm()
            require(len>1e-10) { "Collapsed tape ${t.id}" }
            val strain=len/t.rest-1; val tension=t.ea*max(0.0,strain)
            val force=delta*(tension/len)
            a.distribute(g,force*(-1.0)); b.distribute(g,force)
            tapeEnergy+=.5*t.ea*t.rest*max(0.0,strain).pow(2)
            if(details) tapes.add(TapeState(tension,strain))
        }
        for(t in model.tethers) {
            val length=(t.attachment.at(x)-t.target).norm()
            supportEnergy+=.5*t.ea*t.rest*max(0.0,length/t.rest-1).pow(2)
            t.attachment.distribute(g,t.force(x)*(-1.0))
        }
        for(a in model.anchors) if(a.stiffness!=null) {
            val delta=point(x,a.node)-a.target
            supportEnergy+=.5*a.stiffness*delta.dot(delta)
            add(g,a.node,delta*a.stiffness)
        }
        for(n in 0 until model.nodes) {
            val p=point(x,n)
            supportEnergy-=model.mass[n]*load.gravity.dot(p)
            add(g,n,load.gravity*(-model.mass[n]))
            if(load.groundY!=null && p.y<load.groundY) {
                val penetration=load.groundY-p.y; val k=load.groundPenalty*nodeArea[n]
                supportEnergy+=.5*k*penetration*penetration
                g[n*3+1]-=k*penetration; contact[n*3+1]=k*penetration
            }
        }
        return Evaluation(filmEnergy+tapeEnergy+supportEnergy+pressureEnergy,g,pressure,vol,films,tapes,contact,pf,filmEnergy,tapeEnergy)
    }
}
