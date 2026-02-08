package afield.stdlib

import smile.interpolation.CubicSplineInterpolation1D
import smile.math.kernel.GaussianKernel
import smile.regression.GaussianProcessRegression

data class SmileGpOptions(
    val lengthScale: Double = 0.15,
    val noise: Double = 1e-3,
    val normalize: Boolean = true,
    val tol: Double = 1e-4,
    val maxIter: Int = 500
)

class SmileCubicSpline1D internal constructor(
    val xs: DoubleArray,
    val ys: DoubleArray,
    private val model: CubicSplineInterpolation1D
) {
    fun predict(x: Double): Double = model.interpolate(x)

    fun predict(xs: DoubleArray): DoubleArray =
        DoubleArray(xs.size) { i -> predict(xs[i]) }

    operator fun invoke(x: Double): Double = predict(x)
}

class SmileGaussianProcess1D internal constructor(
    val xs: DoubleArray,
    val ys: DoubleArray,
    val options: SmileGpOptions,
    private val model: GaussianProcessRegression<DoubleArray>
) {
    fun predict(x: Double): Double = model.predict(doubleArrayOf(x))

    fun predict(xs: DoubleArray): DoubleArray =
        DoubleArray(xs.size) { i -> predict(xs[i]) }

    operator fun invoke(x: Double): Double = predict(x)
}

fun smileCubicSpline(xs: DoubleArray, ys: DoubleArray): SmileCubicSpline1D {
    validateTrainingSeries(xs, ys, "smileCubicSpline")
    val xCopy = xs.copyOf()
    val yCopy = ys.copyOf()
    val spline = CubicSplineInterpolation1D(xCopy, yCopy)
    return SmileCubicSpline1D(xCopy, yCopy, spline)
}

fun smileGaussianProcess(
    xs: DoubleArray,
    ys: DoubleArray,
    options: SmileGpOptions = SmileGpOptions()
): SmileGaussianProcess1D {
    validateTrainingSeries(xs, ys, "smileGaussianProcess")
    require(options.lengthScale.isFinite() && options.lengthScale > 0.0) {
        "smileGaussianProcess: lengthScale must be > 0, found ${options.lengthScale}"
    }
    require(options.noise.isFinite() && options.noise >= 0.0) {
        "smileGaussianProcess: noise must be >= 0, found ${options.noise}"
    }
    require(options.tol.isFinite() && options.tol > 0.0) {
        "smileGaussianProcess: tol must be > 0, found ${options.tol}"
    }
    require(options.maxIter > 0) {
        "smileGaussianProcess: maxIter must be > 0, found ${options.maxIter}"
    }

    val xCopy = xs.copyOf()
    val yCopy = ys.copyOf()
    val xTrain = Array(xCopy.size) { i -> doubleArrayOf(xCopy[i]) }
    val kernel = GaussianKernel(options.lengthScale)
    val gpOptions = GaussianProcessRegression.Options(options.noise, options.normalize, options.tol, options.maxIter)
    val model = GaussianProcessRegression.fit(xTrain, yCopy, kernel, gpOptions)
    return SmileGaussianProcess1D(xCopy, yCopy, options, model)
}

private fun validateTrainingSeries(xs: DoubleArray, ys: DoubleArray, fn: String) {
    require(xs.size == ys.size) {
        "$fn: xs.size (${xs.size}) must match ys.size (${ys.size})"
    }
    require(xs.size >= 2) {
        "$fn: at least 2 samples required, got ${xs.size}"
    }

    for (i in xs.indices) {
        require(xs[i].isFinite()) { "$fn: xs[$i] must be finite, found ${xs[i]}" }
        require(ys[i].isFinite()) { "$fn: ys[$i] must be finite, found ${ys[i]}" }
    }

    for (i in 1 until xs.size) {
        require(xs[i] > xs[i - 1]) {
            "$fn: xs must be strictly increasing; xs[$i]=${xs[i]} <= xs[${i - 1}]=${xs[i - 1]}"
        }
    }
}
