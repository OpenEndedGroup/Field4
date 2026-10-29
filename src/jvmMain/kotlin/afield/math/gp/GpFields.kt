@file:afield.runtime.completion.CompletionCategory("Math")
package afield.math.gp

/**
 * Gaussian-process regression of a scalar field sampled at [Vec2] or [Vec3] points.
 *
 * `gp2D` and `gp3D` turn a list of points and their values into a [GaussianProcess], with either one
 * noise level for every observation or one per observation; the `predict` overloads read the fitted
 * field back at a point as a [GpPrediction] (mean and variance).
 */

import afield.math.linalg.Vec2
import afield.math.linalg.Vec3

/** Scalar values over the plane. Kernel length scales are measured in Vec2 coordinate units. */
fun gp2D(points: List<Vec2>, values: DoubleArray, kernel: GpKernel = GpKernels.rbf(), noiseStd: Double = 0.0,
         mean: (DoubleArray) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics()): GaussianProcess =
    gp2D(points, values, DoubleArray(values.size) { noiseStd }, kernel, mean, numerics)
/** One noise standard deviation per point, for a survey whose samples are not equally reliable. */
fun gp2D(points: List<Vec2>, values: DoubleArray, noiseStd: DoubleArray, kernel: GpKernel = GpKernels.rbf(),
         mean: (DoubleArray) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics()): GaussianProcess =
    GaussianProcess.fit(Array(points.size) { doubleArrayOf(points[it].x.toDouble(), points[it].y.toDouble()) }, values, kernel, noiseStd, mean, numerics, 2)
/** Scalar values over space, distinct from a vector-valued parametric curve. */
fun gp3D(points: List<Vec3>, values: DoubleArray, kernel: GpKernel = GpKernels.rbf(), noiseStd: Double = 0.0,
         mean: (DoubleArray) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics()): GaussianProcess =
    gp3D(points, values, DoubleArray(values.size) { noiseStd }, kernel, mean, numerics)
/** One noise standard deviation per point, for a volume sampled at differing quality. */
fun gp3D(points: List<Vec3>, values: DoubleArray, noiseStd: DoubleArray, kernel: GpKernel = GpKernels.rbf(),
         mean: (DoubleArray) -> Double = { 0.0 }, numerics: GpNumerics = GpNumerics()): GaussianProcess =
    GaussianProcess.fit(Array(points.size) { doubleArrayOf(points[it].x.toDouble(), points[it].y.toDouble(), points[it].z.toDouble()) }, values, kernel, noiseStd, mean, numerics, 3)
/** Read the fitted field back at a point, without unpacking its coordinates first. */
fun GaussianProcess.predict(point: Vec2): GpPrediction = predict(point.x.toDouble(), point.y.toDouble())
/** Read the fitted field back at a point in space, without unpacking its coordinates first. */
fun GaussianProcess.predict(point: Vec3): GpPrediction = predict(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())
