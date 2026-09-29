package afield.four.physics.pressure

import kotlin.math.*

/** Per-solve prepacked element data; numeric force path avoids per-element vectors and lists. */
class FastMechanics(private val model:Envelope,private val load:LoadCase) {
    private val fs=model.triangles
    private val ids=IntArray(fs.size*3) {i->val f=fs[i/3];3*when(i%3){0->f.a;1->f.b;else->f.c}}
    private val du=DoubleArray(ids.size) {fs[it/3].du[it%3]}
    private val dv=DoubleArray(ids.size) {fs[it/3].dv[it%3]}
    private val wt=DoubleArray(fs.size) {fs[it].area*fs[it].film.thickness}
    private val young=DoubleArray(fs.size) {fs[it].film.young}
    private val nu=DoubleArray(fs.size) {fs[it].film.poisson}
    private val tapes=model.tapes.map {it.startPoint() to it.endPoint()}
    private val area=DoubleArray(model.nodes).also {a->fs.forEach {f->a[f.a]+=f.area/3;a[f.b]+=f.area/3;a[f.c]+=f.area/3}}
    private val ox=model.initial.filterIndexed {i,_->i%3==0}.average()
    private val oy=model.initial.filterIndexed {i,_->i%3==1}.average()
    private val oz=model.initial.filterIndexed {i,_->i%3==2}.average()
    private val referenceVolume=volume(model.initial)
    private fun volume(x:DoubleArray):Double {
        val sum=AccurateSum()
        for(j in fs.indices) {
            val a=ids[3*j];val b=ids[3*j+1];val c=ids[3*j+2]
            val ax=x[a]-ox;val ay=x[a+1]-oy;val az=x[a+2]-oz
            val bx=x[b]-ox;val by=x[b+1]-oy;val bz=x[b+2]-oz
            val cx=x[c]-ox;val cy=x[c+1]-oy;val cz=x[c+2]-oz
            sum.add((ax*(by*cz-bz*cy)+ay*(bz*cx-bx*cz)+az*(bx*cy-by*cx))/6)
        }
        return sum.value
    }
    fun evaluate(x:DoubleArray,fraction:Double=1.0):Evaluation {
        require(x.size==model.initial.size)
        val g=DoubleArray(x.size);val pf=DoubleArray(x.size);val contact=DoubleArray(x.size)
        val filmEnergy=AccurateSum();val tapeEnergy=AccurateSum();val supportEnergy=AccurateSum()
        val vol=volume(x);require(vol>1e-12 && vol.isFinite()) {"Collapsed or inverted pressure volume"}
        val gas=load.pressure*fraction*referenceVolume
        val pressure=if(load.gasMode==GasMode.REGULATED)load.pressure*fraction else gas/vol
        val pe=if(load.gasMode==GasMode.REGULATED)-pressure*vol else -gas*ln(vol/referenceVolume)
        val kPressure=pressure/6
        for(j in fs.indices) {
            val off=3*j;val a=ids[off];val b=ids[off+1];val c=ids[off+2]
            val ax=x[a]-ox;val ay=x[a+1]-oy;val az=x[a+2]-oz
            val bx=x[b]-ox;val by=x[b+1]-oy;val bz=x[b+2]-oz
            val cx=x[c]-ox;val cy=x[c+1]-oy;val cz=x[c+2]-oz
            val ux=(bx-ax)*du[off+1]+(cx-ax)*du[off+2]
            val uy=(by-ay)*du[off+1]+(cy-ay)*du[off+2]
            val uz=(bz-az)*du[off+1]+(cz-az)*du[off+2]
            val vx=(bx-ax)*dv[off+1]+(cx-ax)*dv[off+2]
            val vy=(by-ay)*dv[off+1]+(cy-ay)*dv[off+2]
            val vz=(bz-az)*dv[off+1]+(cz-az)*dv[off+2]
            val nx=uy*vz-uz*vy;val ny=uz*vx-ux*vz;val nz=ux*vy-uy*vx
            val jac=sqrt(nx*nx+ny*ny+nz*nz);require(jac>1e-8 && jac.isFinite()) {"Collapsed membrane element"}
            val eu=(ux*ux+uy*uy+uz*uz-1)*.5;val ev=(vx*vx+vy*vy+vz*vz-1)*.5
            val es=(ux*vx+uy*vy+uz*vz)*.5;val mid=(eu+ev)*.5;val rad=hypot((eu-ev)*.5,es)
            val e1=mid+rad;val e2=mid-rad;val s1:Double;val s2:Double;val density:Double
            when {
                e1<=0->{s1=0.0;s2=0.0;density=0.0}
                e2 < -nu[j]*e1->{s1=young[j]*e1;s2=0.0;density=.5*s1*e1}
                else->{val k=young[j]/(1-nu[j]*nu[j]);s1=k*(e1+nu[j]*e2);s2=k*(e2+nu[j]*e1);density=.5*(s1*e1+s2*e2)}
            }
            val c2=if(rad>0).5*(1+(eu-ev)/(2*rad)) else .5
            val cs=if(rad>0)es/(2*rad) else 0.0
            val suu=s1*c2+s2*(1-c2);val svv=s1*(1-c2)+s2*c2;val suv=(s1-s2)*cs
            val pux=(ux*suu+vx*suv)*wt[j];val puy=(uy*suu+vy*suv)*wt[j];val puz=(uz*suu+vz*suv)*wt[j]
            val pvx=(ux*suv+vx*svv)*wt[j];val pvy=(uy*suv+vy*svv)*wt[j];val pvz=(uz*suv+vz*svv)*wt[j]
            for(i in 0..2) {
                val node=ids[off+i];val u=du[off+i];val v=dv[off+i]
                g[node]+=pux*u+pvx*v;g[node+1]+=puy*u+pvy*v;g[node+2]+=puz*u+pvz*v
            }
            val pax=(by*cz-bz*cy)*kPressure;val pay=(bz*cx-bx*cz)*kPressure;val paz=(bx*cy-by*cx)*kPressure
            val pbx=(cy*az-cz*ay)*kPressure;val pby=(cz*ax-cx*az)*kPressure;val pbz=(cx*ay-cy*ax)*kPressure
            val pcx=(ay*bz-az*by)*kPressure;val pcy=(az*bx-ax*bz)*kPressure;val pcz=(ax*by-ay*bx)*kPressure
            pf[a]+=pax;pf[a+1]+=pay;pf[a+2]+=paz;g[a]-=pax;g[a+1]-=pay;g[a+2]-=paz
            pf[b]+=pbx;pf[b+1]+=pby;pf[b+2]+=pbz;g[b]-=pbx;g[b+1]-=pby;g[b+2]-=pbz
            pf[c]+=pcx;pf[c+1]+=pcy;pf[c+2]+=pcz;g[c]-=pcx;g[c+1]-=pcy;g[c+2]-=pcz
            filmEnergy.add(wt[j]*density)
        }
        for(j in model.tapes.indices) {
            val t=model.tapes[j];val (a,b)=tapes[j];val delta=b.at(x)-a.at(x);val len=delta.norm()
            require(len>1e-10) {"Collapsed tape ${t.id}"}
            val strain=max(0.0,len/t.rest-1);val force=delta*(t.ea*strain/len)
            a.distribute(g,force*(-1.0));b.distribute(g,force);tapeEnergy.add(.5*t.ea*t.rest*strain*strain)
        }
        for(t in model.tethers) {
            val len=(t.attachment.at(x)-t.target).norm();val strain=max(0.0,len/t.rest-1)
            supportEnergy.add(.5*t.ea*t.rest*strain*strain);t.attachment.distribute(g,t.force(x)*(-1.0))
        }
        for(a in model.anchors) if(a.stiffness!=null) {
            val delta=point(x,a.node)-a.target;supportEnergy.add(.5*a.stiffness*delta.dot(delta));add(g,a.node,delta*a.stiffness)
        }
        for(n in 0 until model.nodes) {
            val k=3*n;val mass=model.mass[n]
            supportEnergy.add(-mass*(load.gravity.x*x[k]+load.gravity.y*x[k+1]+load.gravity.z*x[k+2]))
            g[k]-=mass*load.gravity.x;g[k+1]-=mass*load.gravity.y;g[k+2]-=mass*load.gravity.z
            if(load.groundY!=null && x[k+1]<load.groundY) {
                val delta=load.groundY-x[k+1];val stiff=load.groundPenalty*area[n]
                supportEnergy.add(.5*stiff*delta*delta);g[k+1]-=stiff*delta;contact[k+1]=stiff*delta
            }
        }
        return Evaluation(filmEnergy.value+tapeEnergy.value+supportEnergy.value+pe,g,pressure,vol,emptyList(),emptyList(),contact,pf,filmEnergy.value,tapeEnergy.value)
    }
}

internal class AccurateSum {
    var value=0.0;private set
    private var error=0.0
    fun add(term:Double) {val y=term-error;val t=value+y;error=(t-value)-y;value=t}
}
