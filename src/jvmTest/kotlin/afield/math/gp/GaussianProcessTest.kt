package afield.math.gp

import kotlin.math.*
import kotlin.test.*

class GaussianProcessTest {
    private fun close(a: Double, b: Double, eps: Double = 1e-9) = assertEquals(a, b, eps)
    private fun moments(joint: GpJoint, count: Int = 20000): Pair<DoubleArray, Array<DoubleArray>> {
        val sum = DoubleArray(joint.size); val product = Array(joint.size) { DoubleArray(joint.size) }
        val rng = java.util.Random(437)
        repeat(count) {
            val draw = joint.sample(rng)
            for (i in draw.indices) { sum[i] += draw[i]; for (j in draw.indices) product[i][j] += draw[i] * draw[j] }
        }
        for (i in sum.indices) sum[i] /= count
        for (i in sum.indices) for (j in sum.indices) product[i][j] = product[i][j] / count - sum[i] * sum[j]
        return sum to product
    }
    @Test fun `one observation has analytic mean covariance and evidence`() {
        val gp = gp1D(doubleArrayOf(0.0), doubleArrayOf(2.0), GpKernels.rbf(1.0, 3.0), noiseStd = 0.5)
        val k = 3 * exp(-0.5)
        close(2 * k / 3.25, gp.mean(1.0))
        close(3 - k * k / 3.25, gp.variance(1.0))
        close(-0.5 * (4 / 3.25 + ln(3.25) + ln(2 * PI)), gp.logMarginalLikelihood)
        close(0.0, gp.jitterUsed)
        close(gp.variance(1.0) + 0.49, gp.predictObservation(doubleArrayOf(1.0), 0.7).variance)
    }
    @Test fun `joint samples reproduce off diagonal posterior covariance`() {
        val gp = gp1D(doubleArrayOf(-1.0, 1.0), doubleArrayOf(0.2, -0.5), noiseStd = 0.3)
        val joint = gp.posterior(doubleArrayOf(-0.3, 0.1, 0.5))
        val (m, c) = moments(joint)
        val expected = joint.mean
        for (i in m.indices) { close(expected[i], m[i], 0.02); for (j in m.indices) close(joint.covarianceAt(i,j), c[i][j], 0.015) }
        assertTrue(c[0][1] > 0.01) // independent marginal draws cannot pass
    }
    @Test fun `duplicate queries share draws and pinned queries stay pinned`() {
        val gp = gp1D(doubleArrayOf(0.0), doubleArrayOf(2.0))
        val joint = gp.posterior(doubleArrayOf(0.0, 0.3, 0.3))
        repeat(30) { val s = joint.sample(it.toLong()); close(2.0, s[0]); close(s[1], s[2], 1e-12) }
        assertEquals(1, joint.samplingRank)
        assertTrue(joint.samplingResidualVariance < 1e-10)
    }
    @Test fun `prior variance returns far from data and mean is honoured`() {
        val gp = gp1D(doubleArrayOf(0.0), doubleArrayOf(5.0), GpKernels.rbf(0.5, 2.0), mean = { 3.0 })
        close(5.0, gp.mean(0.0)); close(3.0, gp.mean(100.0)); close(2.0, gp.variance(100.0))
        val prior = GaussianProcess.prior(3, GpKernels.rbf(variance = 4.0), { 7.0 })
        close(7.0, prior.mean(2.0,3.0,4.0)); close(4.0, prior.variance(2.0,3.0,4.0))
    }
    @Test fun `repeated noisy data and heteroscedastic weights have analytic result`() {
        val gp = gp1D(doubleArrayOf(0.0,0.0), doubleArrayOf(1.0,3.0), doubleArrayOf(1.0,2.0))
        close((1.0 + 3.0 / 4) / (1 + 1.0 + 1.0 / 4), gp.mean(0.0))
        close(1 / 2.25, gp.variance(0.0))
        val exact = gp1D(doubleArrayOf(0.0,0.0), doubleArrayOf(2.0,2.0))
        assertEquals(1, exact.trainingSize)
        assertFailsWith<IllegalArgumentException> { gp1D(doubleArrayOf(0.0,0.0), doubleArrayOf(2.0,3.0)) }
    }
    @Test fun `training query and returned arrays do not mutate the model`() {
        val xs = arrayOf(doubleArrayOf(0.0)); val ys = doubleArrayOf(1.0)
        val gp = GaussianProcess.fit(xs,ys)
        xs[0][0] = 90.0; ys[0] = 99.0
        close(1.0,gp.mean(0.0))
        val joint = gp.posterior(doubleArrayOf(0.2,0.7)); val before = joint.sample(7)
        joint.mean[0] = 88.0; joint.covariance[0][0] = 90.0; joint.variance[0] = 0.0
        assertContentEquals(before,joint.sample(7))
    }
    @Test fun `two and three dimensional ARD fits agree with one dimensional restriction`() {
        for (d in 2..3) {
            val p = Array(3) { i -> DoubleArray(d) { if (it == 0) i.toDouble() else 0.0 } }
            val y = doubleArrayOf(1.0,-1.0,0.5)
            val gp = GaussianProcess.fit(p,y,GpKernels.rbf(DoubleArray(d) { if(it==0) 0.7 else 2.0 }),DoubleArray(3){0.1})
            val one = gp1D(doubleArrayOf(0.0,1.0,2.0),y,GpKernels.rbf(0.7),0.1)
            val q = DoubleArray(d) { if(it==0) 0.4 else 0.0 }
            close(one.mean(0.4),gp.mean(*q));close(one.variance(0.4),gp.variance(*q))
            val queries = arrayOf(q,DoubleArray(d) { 0.6 },DoubleArray(d) { -0.8 })
            val j = gp.posterior(queries); val (m,c)=moments(j)
            for(i in m.indices) { close(j.mean[i],m[i],0.025);for(k in m.indices) close(j.covarianceAt(i,k),c[i][k],0.03) }
        }
    }
    @Test fun `invalid inputs and indefinite kernels fail explicitly`() {
        assertFailsWith<IllegalArgumentException> { gp1D(doubleArrayOf(Double.NaN),doubleArrayOf(0.0)) }
        assertFailsWith<IllegalArgumentException> { GpKernels.rbf(0.0) }
        assertFailsWith<IllegalArgumentException> { gp1D(doubleArrayOf(0.0),doubleArrayOf(0.0),noiseStd = -1.0) }
        val bad = GpKernel { a,b -> if(a.contentEquals(b)) 1.0 else 2.0 }
        assertFailsWith<IllegalArgumentException> { gp1D(doubleArrayOf(0.0,1.0),doubleArrayOf(0.0,0.0),bad) }
        assertFailsWith<IllegalArgumentException> { GaussianProcess.prior(kernel=bad).posterior(doubleArrayOf(0.0,1.0)).sample(1) }
        val asymmetric=GpKernel{a,b->a[0]-b[0]+1}
        assertFailsWith<IllegalArgumentException> { gp1D(doubleArrayOf(0.0,1.0),doubleArrayOf(0.0,1.0),asymmetric) }
    }
    @Test fun `zero diagonal nonzero cross covariance is rejected`() {
        val bad = GpKernel { a,b -> if(a.contentEquals(b)) 0.0 else 1.0 }
        assertFailsWith<IllegalArgumentException> { GaussianProcess.prior(kernel=bad).posterior(doubleArrayOf(0.0,1.0)).sample(1) }
    }
    @Test fun `rank deficient smooth prior samples without independent jitter`() {
        val prior = GaussianProcess.prior(kernel = GpKernels.constant(2.0))
        val joint = prior.posterior(DoubleArray(100) { it / 99.0 })
        assertEquals(1, joint.samplingRank)
        for (s in joint.samples(10,6)) assertTrue(s.all { abs(it-s[0]) < 1e-12 })
    }
    @Test fun `posterior marginals and pair covariance agree with joint`() {
        val gp = gp1D(doubleArrayOf(-1.0,0.0,1.0), doubleArrayOf(0.0,2.0,1.0), noiseStd = 0.1)
        val xs = doubleArrayOf(-0.6,0.2,0.7); val j=gp.posterior(xs)
        val m=gp.marginals(xs)
        for(i in xs.indices) { close(m[i].mean,j.mean[i]); close(m[i].variance,j.variance[i]); for(k in xs.indices) close(gp.covariance(doubleArrayOf(xs[i]),doubleArrayOf(xs[k])),j.covarianceAt(i,k)) }
    }
    @Test fun `leave one out matches explicit refits`() {
        val x=doubleArrayOf(-0.7,0.0,0.9);val y=doubleArrayOf(1.0,0.0,-1.0);val n=doubleArrayOf(0.1,0.2,0.4)
        val loo=gp1D(x,y,n).leaveOneOut()
        for(i in x.indices){val keep=x.indices.filter{it!=i};val gp=gp1D(keep.map{x[it]}.toDoubleArray(),keep.map{y[it]}.toDoubleArray(),keep.map{n[it]}.toDoubleArray())
            val p=gp.predictObservation(doubleArrayOf(x[i]),n[i]);close(p.mean,loo.mean[i]);close(p.variance,loo.variance[i])}
    }
    @Test fun `linear transforms retain cross covariance in derivative uncertainty`() {
        val j=GaussianProcess.prior().posterior(doubleArrayOf(0.0,0.2))
        val derivative=j.linearTransform(arrayOf(doubleArrayOf(-5.0,5.0)))
        close(50*(1-exp(-0.02)),derivative.variance[0])
        val (_,c)=moments(derivative);close(derivative.variance[0],c[0][0],0.03)
    }
    @Test fun `conditioning incrementally equals a batch fit`() {
        val a=gp1D(doubleArrayOf(0.0),doubleArrayOf(1.0),noiseStd=0.2)
        val b=a.condition(arrayOf(doubleArrayOf(1.0)),doubleArrayOf(-1.0),doubleArrayOf(0.3))
        val c=gp1D(doubleArrayOf(0.0,1.0),doubleArrayOf(1.0,-1.0),doubleArrayOf(0.2,0.3))
        close(c.mean(0.4),b.mean(0.4));close(c.variance(0.4),b.variance(0.4))
    }
    @Test fun `kernel families compose and preserve periodicity`() {
        val a=doubleArrayOf(0.0);val b=doubleArrayOf(1.0)
        close(exp(-1.0),GpKernels.matern(0.5).covariance(a,b))
        close((1+sqrt(3.0))*exp(-sqrt(3.0)),GpKernels.matern(1.5).covariance(a,b))
        close((1+sqrt(5.0)+5.0/3)*exp(-sqrt(5.0)),GpKernels.matern(2.5).covariance(a,b))
        close(2.0/3,GpKernels.rationalQuadratic().covariance(a,b))
        val p=GpKernels.periodic(period=1.0)
        close(1.0,p.covariance(a,b))
        val k=(GpKernels.matern()+GpKernels.constant(0.2))*p
        val j=GaussianProcess.prior(kernel=k).posterior(DoubleArray(50){it*0.06})
        assertTrue(j.sample(3).all{it.isFinite()})
        val warped=GpKernels.warp(GpKernels.rbf()){doubleArrayOf(2*it[0])}
        close(exp(-2.0),warped.covariance(a,b))
    }
    @Test fun `bounded likelihood fitting improves an intentionally poor model`() {
        val xs=DoubleArray(12){it/11.0};val ys=DoubleArray(12){sin(xs[it]*2*PI)}
        val result=fitGpHyperparameters(listOf(GpParameter("length",4.0,0.05,5.0),GpParameter("noise",0.5,0.01,1.0)),maxIterations=80){v->gp1D(xs,ys,GpKernels.rbf(v[0]),v[1])}
        assertTrue(result.process.logMarginalLikelihood > result.initialLogMarginalLikelihood+5)
        assertTrue(result.parameters.getValue("length") in 0.05..5.0)
        assertTrue(result.evaluations>1)
    }
    @Test fun `exact callable sample is persistent and jointly distributed`() {
        val gp=GaussianProcess.prior()
        var sum=0.0;var product=0.0;val count=12000
        repeat(count){val p=gp.samplePath(it.toLong()+998);val a=p(0.0);val b=p(0.5);assertEquals(a,p(0.0));sum+=a;product+=a*b}
        close(0.0,sum/count,0.03);close(exp(-0.125),product/count,0.04)
        val constant=GaussianProcess.prior(kernel=GpKernels.constant()).samplePath(4)
        close(constant(0.0),constant(1.0))
    }
    @Test fun `feature path samples reproduce their model posterior moments`() {
        val features=GpRbfFeatures(doubleArrayOf(0.7,1.2),128,seed=11)
        val model=features.fit(arrayOf(doubleArrayOf(0.0,0.0),doubleArrayOf(1.0,1.0)),doubleArrayOf(1.0,-1.0),doubleArrayOf(0.1,0.2))
        val a=doubleArrayOf(0.3,0.2);val b=doubleArrayOf(0.5,0.4)
        val expected=model.process.posterior(arrayOf(a,b));val count=12000
        var sa=0.0;var sb=0.0;var saa=0.0;var sab=0.0
        repeat(count){val path=model.samplePath(it.toLong()+65432);val x=path(*a);val y=path(*b);assertEquals(x,path(*a));sa+=x;sb+=y;saa+=x*x;sab+=x*y}
        close(model.mean(*a),expected.mean[0]);close(sa/count,expected.mean[0],0.025)
        close(expected.variance[0],saa/count-(sa/count).pow(2),0.025)
        close(expected.covarianceAt(0,1),sab/count-sa*sb/(count.toDouble()*count),0.025)
    }
    @Test fun `feature model approximates RBF and exact constraints hold`() {
        val f=GpRbfFeatures(doubleArrayOf(0.5),4096,seed=90)
        val p=f.prior().process
        close(1.0,p.variance(0.2));close(exp(-0.5),p.covariance(doubleArrayOf(0.0),doubleArrayOf(0.5)),0.035)
        val model=f.fit(arrayOf(doubleArrayOf(0.0)),doubleArrayOf(2.0))
        repeat(10){close(2.0,model.samplePath(it.toLong())(0.0),1e-9)}
    }
    @Test fun `vector outputs retain chosen spatial and cross-coordinate covariance`() {
        val b=arrayOf(doubleArrayOf(2.0,0.6),doubleArrayOf(0.6,1.0))
        val gp=GpVectorProcess.fit(emptyArray(),emptyArray(),outputCovariance=b)
        val joint=gp.posterior(arrayOf(doubleArrayOf(0.0),doubleArrayOf(0.5)))
        close(0.6,joint.covarianceAt(0)[0][1]);close(2*exp(-0.125),joint.covarianceAt(0,1)[0][0])
        close(4.2,joint.projectedVariance(0,doubleArrayOf(1.0,1.0)))
        val (_,c)=moments(joint.joint)
        close(0.6,c[0][1],0.04);close(2*exp(-0.125),c[0][2],0.05)
    }
    @Test fun `independent curve coordinates are not tied to one Gaussian jitter`() {
        val model=GpVectorProcess.fit(arrayOf(doubleArrayOf(0.0)),arrayOf(doubleArrayOf(0.0,0.0,0.0)))
        val joint=model.posterior(arrayOf(doubleArrayOf(0.5)))
        val (_,c)=moments(joint.joint)
        close(0.0,c[0][1],0.015);close(joint.variance[0][0],c[0][0],0.015)
        assertTrue(joint.sample(17)[0].distinct().size==3)
    }
    @Test fun `rank deficient training reports jitter and large joint guard is actionable`() {
        val gp=gp1D(doubleArrayOf(0.0,1.0),doubleArrayOf(1.0,1.0),GpKernels.constant())
        assertTrue(gp.jitterUsed>0)
        val small=GaussianProcess.prior(numerics=GpNumerics(maxJointSize=2))
        assertFailsWith<IllegalArgumentException>{small.posterior(doubleArrayOf(0.0,1.0,2.0))}
        assertEquals(3,small.marginals(doubleArrayOf(0.0,1.0,2.0)).size)
    }
    @Test fun `empty joint and tiny output scales are valid`() {
        assertContentEquals(doubleArrayOf(),GaussianProcess.prior().posterior(doubleArrayOf()).sample(4))
        val joint=GaussianProcess.prior(kernel=GpKernels.rbf(variance=1e-20)).posterior(doubleArrayOf(0.0,1.0))
        assertEquals(2,joint.samplingRank);assertTrue(joint.sample(4).any{it!=0.0})
    }
    @Test fun `numerical jitter does not excuse incompatible exact low rank data`() {
        assertFailsWith<IllegalArgumentException> {
            gp1D(doubleArrayOf(0.0,1.0),doubleArrayOf(0.0,1.0),GpKernels.constant())
        }
        val compatible=gp1D(doubleArrayOf(0.0,1.0),doubleArrayOf(1.0,1.0),GpKernels.constant())
        close(1.0,compatible.mean(0.5),1e-9)
    }

}
