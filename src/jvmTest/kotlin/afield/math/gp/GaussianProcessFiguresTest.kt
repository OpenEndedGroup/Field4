package afield.math.gp

import java.io.File
import kotlin.math.*
import kotlin.test.*

/** Numerical figures use the production Kotlin GP, never a Python reconstruction of the algorithm. */
class GaussianProcessFiguresTest {
    private val directory = System.getenv("FIELD_GP_FIGURE_DATA")?.let { File(it).also(File::mkdirs) }
    private fun write(name: String, header: String, rows: List<DoubleArray>) {
        assertTrue(rows.all { row -> row.all { it.isFinite() } }, "Non-finite $name figure data")
        directory?.resolve("$name.csv")?.writeText(header + "\n" + rows.joinToString("\n") { it.joinToString(",") } + "\n")
    }
    @Test fun `produce tutorial distributions from the numerical implementation`() {
        val x = doubleArrayOf(-1.2,-0.35,0.1,0.95)
        val y = doubleArrayOf(-0.7,0.8,0.35,-0.4)
        val noise = doubleArrayOf(0.06,0.06,0.22,0.06)
        val q = DoubleArray(181) { -1.8 + 3.6 * it / 180 }
        val kernel = GpKernels.rbf(0.48)
        val prior = GaussianProcess.prior(kernel=kernel).posterior(q)
        val gp = gp1D(x,y,noise,kernel)
        val posterior = gp.posterior(q)
        val ps = prior.samples(5,401);val draws = posterior.samples(5,402)
        val mu=posterior.mean;val variance=posterior.variance
        write("conditioning", "x,mean,std,prior0,prior1,prior2,prior3,prior4,sample0,sample1,sample2,sample3,sample4",
            q.indices.map{i->doubleArrayOf(q[i],mu[i],sqrt(variance[i]))+DoubleArray(5){ps[it][i]}+DoubleArray(5){draws[it][i]}})
        write("observations","x,y,noise",x.indices.map{doubleArrayOf(x[it],y[it],noise[it])})
        val kernels=listOf(GpKernels.rbf(0.45),GpKernels.matern(2.5,0.45),GpKernels.matern(1.5,0.45),GpKernels.matern(0.5,0.45),GpKernels.periodic(1.2,0.7))
        val kr=ArrayList<DoubleArray>()
        for((index,k) in kernels.withIndex()){
            val d=GaussianProcess.prior(kernel=k).posterior(q).samples(3,900+index.toLong())
            for(i in q.indices) kr.add(doubleArrayOf(index.toDouble(),q[i],d[0][i],d[1][i],d[2][i]))
        }
        write("kernels","kernel,x,sample0,sample1,sample2",kr)
        val p2=arrayOf(doubleArrayOf(-0.8,-0.5),doubleArrayOf(0.55,-0.5),doubleArrayOf(-0.3,0.65),doubleArrayOf(0.65,0.55))
        val v2=doubleArrayOf(0.9,-0.7,-0.8,0.65)
        val field=GaussianProcess.fit(p2,v2,GpKernels.matern(1.5,doubleArrayOf(0.55,0.8)),DoubleArray(4){0.04})
        val side=25
        val grid=Array(side*side){i->doubleArrayOf(-1.2+2.4*(i%side)/(side-1),-1.2+2.4*(i/side)/(side-1))}
        val joint=field.posterior(grid);val samples=joint.samples(2,57);val m2=joint.mean;val var2=joint.variance
        write("field2d","x,y,mean,std,sample0,sample1",grid.indices.map{i->grid[i]+doubleArrayOf(m2[i],sqrt(var2[i]),samples[0][i],samples[1][i])})
        write("observations2d","x,y,value",p2.indices.map{p2[it]+doubleArrayOf(v2[it])})
        val p3=arrayOf(doubleArrayOf(-0.7,-0.4,-0.5),doubleArrayOf(0.6,0.4,0.5),doubleArrayOf(-0.5,0.6,0.0),doubleArrayOf(0.6,-0.6,0.0))
        val field3=GpRbfFeatures(doubleArrayOf(0.7,0.7,0.9),512,seed=77).fit(p3,doubleArrayOf(0.8,-0.8,-0.5,0.6),DoubleArray(4){0.04})
        val volume=field3.samplePath(88)
        val rows3=ArrayList<DoubleArray>()
        for(z in doubleArrayOf(-0.6,0.0,0.6))for(iy in 0 until 25)for(ix in 0 until 25){
            val xx=-1.2+2.4*ix/24;val yy=-1.2+2.4*iy/24
            val pred=field3.predict(xx,yy,z)
            rows3.add(doubleArrayOf(z,xx,yy,pred.mean,sqrt(pred.variance),volume(xx,yy,z)))
        }
        write("field3d","z,x,y,mean,std,sample",rows3)
        val t=doubleArrayOf(0.0,0.2,0.65,1.0)
        val xy=arrayOf(doubleArrayOf(-1.0,-0.6),doubleArrayOf(-0.65,0.65),doubleArrayOf(0.6,0.5),doubleArrayOf(0.9,-0.5))
        val curve=GpVectorProcess.fit(Array(t.size){doubleArrayOf(t[it])},xy,GpKernels.matern(2.5,0.28,0.3),Array(t.size){doubleArrayOf(0.015,0.015)},arrayOf(doubleArrayOf(1.0,0.35),doubleArrayOf(0.35,0.7)))
        val tq=DoubleArray(121){it/120.0}
        val cj=curve.posterior(Array(tq.size){doubleArrayOf(tq[it])});val cm=cj.mean;val cv=cj.variance
        val cs=Array(5){cj.sample(800+it.toLong())}
        write("curve","t,x,y,varx,vary,covxy,s0x,s0y,s1x,s1y,s2x,s2y,s3x,s3y,s4x,s4y",tq.indices.map{i->
            doubleArrayOf(tq[i],cm[i][0],cm[i][1],cv[i][0],cv[i][1],cj.covarianceAt(i)[0][1])+DoubleArray(10){cs[it/2][i][it%2]}
        })
        write("curve_observations","t,x,y",t.indices.map{doubleArrayOf(t[it])+xy[it]})
        assertTrue(gp.variance(-1.2)<gp.variance(-1.8))
        assertTrue(joint.samplingRank>1)
        assertTrue(cj.covarianceAt(50)[0][1]>0)
    }
}
