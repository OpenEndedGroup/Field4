package afield.stdlib

import kotlin.math.*
import kotlin.test.*
import smile.math.kernel.GaussianKernel
import smile.regression.GaussianProcessRegression

class GaussianProcessSmileTest {
    @Test fun `Smile kernel uses identical length scale convention`() {
        val kernel = GaussianKernel(0.7).asGpKernel()
        val a=doubleArrayOf(0.0,1.0);val b=doubleArrayOf(0.3,-0.7)
        assertEquals(GpKernels.rbf(0.7).covariance(a,b),kernel.covariance(a,b),1e-12)
    }
    @Test fun `exact posterior agrees with Smile at fixed hyperparameters`() {
        val x=Array(4){doubleArrayOf(it/3.0)};val y=doubleArrayOf(0.0,0.9,-0.2,-0.7)
        val smile=GaussianProcessRegression.fit(x,y,GaussianKernel(0.4),GaussianProcessRegression.Options(0.01,false,1e-6,0))
        val ours=GaussianProcess.fit(x,y,GaussianKernel(0.4).asGpKernel(),DoubleArray(4){0.1})
        val q=arrayOf(doubleArrayOf(0.1),doubleArrayOf(0.5),doubleArrayOf(0.8))
        val s=smile.query(q);val p=ours.posterior(q)
        for(i in q.indices){assertEquals(s.mu[i],p.mean[i],1e-9);for(j in q.indices)assertEquals(s.cov.get(i,j),p.covarianceAt(i,j),1e-9)}
    }
    @Test fun `Smile wrapper exposes uncertainty and constant normalized data remains finite`() {
        val gp=smileGaussianProcess(doubleArrayOf(0.0,0.4,0.8),doubleArrayOf(2.0,2.0,2.0),SmileGpOptions(maxIter=0))
        assertTrue(gp.predictWithVariance(0.5).variance>=0)
        val joint=gp.posterior(doubleArrayOf(0.2,0.2,0.5));val s=joint.sample(2)
        assertEquals(s[0],s[1],1e-9);assertTrue(s.all{it.isFinite()})
    }
    @Test fun `Smile optimizer improves the same explicit Field likelihood`() {
        val x=DoubleArray(10){it/9.0};val y=DoubleArray(10){sin(x[it]*2*PI)}
        fun model(v:DoubleArray)=gp1D(x,y,GpKernels.rbf(v[0]),v[1])
        val initial=model(doubleArrayOf(2.0,0.4)).logMarginalLikelihood
        val result=fitGpHyperparametersWithSmile(listOf(GpParameter("length",2.0,0.05,3.0),GpParameter("noise",0.4,0.01,1.0)),maxIterations=60,build=::model)
        assertTrue(result.process.logMarginalLikelihood>initial+5)
        assertTrue(result.projectedGradientMax.isFinite())
    }
}
