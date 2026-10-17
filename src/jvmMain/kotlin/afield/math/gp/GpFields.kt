@file:afield.runtime.completion.CompletionCategory("Math")
package afield.math.gp

import afield.math.linalg.Vec2
import afield.math.linalg.Vec3

/** Scalar values over the plane. Kernel length scales are measured in Vec2 coordinate units. */
fun gp2D(points: List<Vec2>, values: DoubleArray, kernel: GpKernel = GpKernels.rbf(), noiseStd: Double = 0.0,
         mean: (DoubleArray) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics()): GaussianProcess =
    gp2D(points, values, DoubleArray(values.size) { noiseStd }, kernel, mean, numerics)
fun gp2D(points: List<Vec2>, values: DoubleArray, noiseStd: DoubleArray, kernel: GpKernel = GpKernels.rbf(),
         mean: (DoubleArray) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics()): GaussianProcess =
    GaussianProcess.fit(Array(points.size) { doubleArrayOf(points[it].x.toDouble(), points[it].y.toDouble()) }, values, kernel, noiseStd, mean, numerics, 2)
/** Scalar values over space, distinct from a vector-valued parametric curve. */
fun gp3D(points: List<Vec3>, values: DoubleArray, kernel: GpKernel = GpKernels.rbf(), noiseStd: Double = 0.0,
         mean: (DoubleArray) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics()): GaussianProcess =
    gp3D(points, values, DoubleArray(values.size) { noiseStd }, kernel, mean, numerics)
fun gp3D(points: List<Vec3>, values: DoubleArray, noiseStd: DoubleArray, kernel: GpKernel = GpKernels.rbf(),
         mean: (DoubleArray) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics()): GaussianProcess =
    GaussianProcess.fit(Array(points.size) { doubleArrayOf(points[it].x.toDouble(), points[it].y.toDouble(), points[it].z.toDouble()) }, values, kernel, noiseStd, mean, numerics, 3)
fun GaussianProcess.predict(point: Vec2): GpPrediction = predict(point.x.toDouble(), point.y.toDouble())
fun GaussianProcess.predict(point: Vec3): GpPrediction = predict(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())
