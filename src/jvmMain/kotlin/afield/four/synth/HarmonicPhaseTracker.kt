@file:afield.CompletionCategory("Synthesis")
package afield.four.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

private const val TWO_PI = 2.0 * PI


/**
 * Track ONE latent periodicity from MANY harmonics of a spectrally complex monophonic input.
 *
 * This is the analysis front-end to use when a raw waveform is too rich for a single sinusoidal
 * PLL: saws, bowed strings, voice, reeds, distorted basses, signals with a weak or entirely absent
 * fundamental, etc.
 *
 * The tracker has two cooperating parts.
 *
 * FINE TRACKER — adaptive complex harmonic demodulators
 * -----------------------------------------------------
 *
 * For harmonic k we mix the real input against exp(-j k φ), where φ is the current master phase,
 * and low-pass the result:
 *
 *      z_k ~= A_k/2 * exp(j (θ_k - kφ))
 *
 * The ABSOLUTE phase of z_k contains the source's arbitrary timbral phase ψ_k, so this code does
 * NOT average atan2(z_k)/k. That would incorrectly assume all harmonics have a common zero phase.
 *
 * Instead we look at the phase ADVANCE of each baseband channel:
 *
 *      δ_k = arg(z_k[n] conj(z_k[n-1]))
 *
 * and every partial votes for a residual fundamental phase velocity
 *
 *      r_k = δ_k / k.
 *
 * A weighted median followed by a robust weighted mean fuses those votes. Constant harmonic phase
 * offsets disappear under differentiation, while true common pitch movement survives division by k.
 *
 * Each channel uses a two-pole low-pass whose cutoff grows with harmonic number. Thus each partial
 * has roughly the same capture range when expressed back in fundamental-Hz units. Channel weights
 * come from baseband energy with a mild preference for lower partials; large residual outliers are
 * rejected before fusion.
 *
 * COARSE TRACKER — periodicity reacquisition
 * ------------------------------------------
 *
 * A harmonic bank alone has octave traps: if the current f0 estimate is 2x the truth, channels at
 * 1*f0, 2*f0 ... may still line up beautifully with even harmonics and report high confidence.
 *
 * A decimated ring buffer supplies a snapshot for normalized autocorrelation. One lag is evaluated
 * per audio sample, bounding each step to [coarseWindow] products rather than a whole search.
 * Among peaks close to the best one it chooses the shortest lag (highest fundamental). For an ordinary harmonic waveform the first high-quality
 * repeated period is the fundamental period; later peaks at 2T, 3T ... are deliberately ignored.
 *
 * This also gives useful missing-fundamental acquisition: a signal containing only 2f0, 3f0 and
 * 5f0 still repeats every 1/f0, so it can seed the fine tracker at f0 without energy at f0.
 *
 * No algorithm can infer an intentionally "missing" fundamental from an exactly even-only spectrum
 * such as {2f,4f,6f}: that signal is literally periodic at 2f. In that case octave choice requires
 * history, a prior, or a higher-level musical model.
 *
 * PHASE OUTPUT
 * ------------
 *
 * [state.omega] is the stable fused pitch estimate.
 * [state.phaseAdvance] is the more agile phase trajectory:
 *
 *      phaseAdvance = omega + phaseFollow * fusedResidual.
 *
 * That distinction is useful downstream. A TrackedPhaseBank voice with lock=0 follows stable pitch;
 * lock=1 inherits this cycle-level timing; intermediate values continuously interpolate.
 *
 * [state.harmonicity] estimates how much recent broadband power is explained by the harmonic
 * demodulators. [state.confidence] combines harmonicity with agreement among their fundamental-
 * velocity votes. [state.activePartials] says how many channels actually voted.
 *
 * This is still a MONOPHONIC model: it tries to explain a spectrum with one latent phase φ.
 * Material with two independent pitches wants multiple HarmonicPhaseTrackers (or a future
 * MultiPhaseTracker), not a wider value of [harmonics].
 */
class HarmonicPhaseTracker(
    override val sampleRate: Double = 48_000.0,
    val minHz: Double = 35.0,
    val maxHz: Double = 350.0,
    val initialHz: Double = 100.0,

    /** Number of harmonic demodulators. Channels above Nyquist automatically stop voting. */
    val harmonics: Int = 12,

    /**
     * Fine-track capture bandwidth measured in FUNDAMENTAL Hz.
     * Harmonic k receives a baseband LP cutoff of approximately k * this value.
     */
    val captureHz: Double = 4.0,

    /** Time constant with which the stable omega estimate absorbs the fused residual. */
    val frequencyTrackingTimeSeconds: Double = 0.030,

    /**
     * How much of the instantaneous fused residual appears in phaseAdvance.
     * 0 = only stable omega; 1 = strongly cycle-following. Values >1 are allowed experimentally.
     */
    var phaseFollow: Double = 1.0,

    /** Fundamental-Hz distance from the robust median after which a partial is rejected. */
    val residualOutlierHz: Double = 7.0,

    /** Mildly prefer low partials: weight is energy / k^harmonicWeightExponent. */
    val harmonicWeightExponent: Double = 0.5,

    /** Input RMS-ish level below which the tracker is considered gated. */
    val gateLevel: Double = 0.01,

    /** Baseband partial amplitude needed to cast a vote, relative to [gateLevel]. */
    val partialGateScale: Double = 0.35,

    // Broadband power follower for harmonicity / gate measurement.
    broadbandPowerTimeSeconds: Double = 0.020,

    // Confidence smoothing, mostly to prevent lock chatter.
    confidenceTimeSeconds: Double = 0.025,

    /** Confidence above which [TrackedPhaseState.locked] is true. */
    val lockThreshold: Double = 0.65,

    // ---- coarse periodicity reacquisition ----

    /** Raw samples averaged into one coarse sample. 8 -> 6 kHz at a 48 kHz audio rate. */
    val coarseDecimation: Int = 8,

    /** Number of downsampled samples in each normalized-correlation comparison. */
    val coarseWindow: Int = 384,

    /** Re-run coarse correlation this many downsampled samples. */
    val coarseHop: Int = 96,

    /** Absolute normalized-correlation floor for accepting a periodicity candidate. */
    val coarseMinCorrelation: Double = 0.60,

    /**
     * Accept peaks this close to the best correlation and choose the shortest-lag one.
     * This avoids selecting the equally-good 2T / 3T autocorrelation peaks.
     */
    val coarseRelativePeak: Double = 0.93,

    /** Ignore coarse/fine disagreement smaller than this. Fine tracking has better precision. */
    val coarseDeadbandHz: Double = 1.5,

    /** Coarse pull while fine confidence is weak. */
    val coarseUnlockedPull: Double = 0.65,

    /** Coarse pull when the fine bank claims to be confident (principally octave-trap correction). */
    val coarseLockedPull: Double = 0.12,
) : PhaseTracker {

    override val state = TrackedPhaseState(sampleRate)

    init {
        require(minHz.isFinite() && maxHz.isFinite() && minHz > 0 && maxHz > minHz && maxHz < sampleRate / 2)
        require(initialHz.isFinite() && initialHz in minHz..maxHz)
        require(harmonics in 1..64)
        require(captureHz.isFinite() && captureHz > 0 && residualOutlierHz.isFinite() && residualOutlierHz > 0)
        require(coarseDecimation > 0 && sampleRate / coarseDecimation > 2 * maxHz)
        require(coarseWindow in 16..8192 && coarseHop > 0)
        require(sampleRate / coarseDecimation / minHz < 16384)
        require(gateLevel.isFinite() && gateLevel > 0 && partialGateScale.isFinite() && partialGateScale > 0)
        require(harmonicWeightExponent.isFinite() && phaseFollow.isFinite())
        require(lockThreshold in 0.0..1.0 && coarseRelativePeak in 0.0..1.0 && coarseMinCorrelation in 0.0..1.0)
        require(coarseUnlockedPull in 0.0..1.0 && coarseLockedPull in 0.0..1.0)
        require(coarseDeadbandHz.isFinite() && coarseDeadbandHz >= 0)
        for (seconds in doubleArrayOf(frequencyTrackingTimeSeconds, broadbandPowerTimeSeconds, confidenceTimeSeconds))
            require(seconds.isFinite() && seconds >= 0)
    }

    private val harmonicPreferences = DoubleArray(harmonics) { 1.0 / Math.pow(it + 1.0, harmonicWeightExponent) }
    private val minOmega = TWO_PI * minHz / sampleRate
    private val maxOmega = TWO_PI * maxHz / sampleRate

    private var omega = 0.0
    private var phase = 0.0

    private val frequencyAlpha =
        if (frequencyTrackingTimeSeconds <= 0.0) 1.0
        else 1.0 - exp(-1.0 / (frequencyTrackingTimeSeconds * sampleRate))

    private val broadbandPowerKeep =
        if (broadbandPowerTimeSeconds <= 0.0) 0.0
        else exp(-1.0 / (broadbandPowerTimeSeconds * sampleRate))

    private val confidenceKeep =
        if (confidenceTimeSeconds <= 0.0) 0.0
        else exp(-1.0 / (confidenceTimeSeconds * sampleRate))

    // Two cascaded complex one-poles per harmonic.
    private val lp1Re = DoubleArray(harmonics)
    private val lp1Im = DoubleArray(harmonics)
    private val lp2Re = DoubleArray(harmonics)
    private val lp2Im = DoubleArray(harmonics)

    private val previousRe = DoubleArray(harmonics)
    private val previousIm = DoubleArray(harmonics)
    private val previousValid = BooleanArray(harmonics)

    // 1-a coefficients for each harmonic's low-pass:
    // fc_k ~= k*captureHz, bounded so the one-pole remains sensible near Nyquist.
    private val lpAdd = DoubleArray(harmonics) { index ->
        val k = index + 1
        val fc = (captureHz * k).coerceAtMost(sampleRate * 0.20)
        1.0 - exp(-TWO_PI * fc / sampleRate)
    }

    // Preallocated robust-fusion scratch. Filled densely from [0, voteCount).
    private val residualScratch = DoubleArray(harmonics)
    private val weightScratch = DoubleArray(harmonics)

    private var broadbandPower = 0.0
    private var smoothedConfidence = 0.0

    // ---- coarse ring / downsampler ----
    private val coarseRate = sampleRate / coarseDecimation
    private val coarseMinLag = (coarseRate / maxHz).toInt().coerceAtLeast(2)
    private val coarseMaxLag = kotlin.math.ceil(coarseRate / minHz).toInt() + 1
    private val coarseRingSize =
        nextPowerOfTwo(coarseWindow + coarseMaxLag + 8)
    private val coarseMask = coarseRingSize - 1
    private val coarseRing = DoubleArray(coarseRingSize)
    private val coarseScores = DoubleArray(coarseMaxLag + 2)

    // Copy one decimated window, then compute one lag per audio sample. No full
    // autocorrelation burst sits on a single sample or requires a worker thread.
    private val coarseSnapshot = DoubleArray(coarseWindow + coarseMaxLag)
    private var analysisLag = 0

    private var coarseWrite = 0
    private var coarseCount = 0
    private var coarseAccumulator = 0.0
    private var coarseAccumulatorCount = 0
    private var coarseSinceAnalysis = 0

    /** Last accepted coarse-periodicity estimate, useful for diagnostics/UI. */
    var coarseHz: Double = 0.0
        private set

    /** Normalized autocorrelation score of [coarseHz]. */
    var coarseCorrelation: Double = 0.0
        private set

    init { reset() }

    override fun process(input: Float): TrackedPhaseState {
        val x = if (input.isFinite()) input.toDouble() else 0.0

        // Broadband reference power. For a sum of sinusoids this lets us estimate what fraction
        // the harmonic channels explain.
        broadbandPower =
            broadbandPowerKeep * broadbandPower +
                (1.0 - broadbandPowerKeep) * (x * x)

        // The coarse path runs independently of the fine demodulators and may pull omega if it
        // discovers a convincing different periodicity.
        pushCoarse(x)
        advanceCoarse()

        // Generate sin(k phi), cos(k phi) recursively: only one sin/cos pair per audio sample
        // regardless of harmonic count.
        val c1 = cos(phase)
        val s1 = sin(phase)
        var ck = c1
        var sk = s1

        var voteCount = 0
        var explainedPower = 0.0
        var activePartials = 0

        // amplitude of a real sinus is ~2*|complex baseband| after demodulation.
        val partialAmplitudeFloor = gateLevel * partialGateScale
        val partialMagnitudeSquaredFloor =
            0.25 * partialAmplitudeFloor * partialAmplitudeFloor

        val nyquist = sampleRate * 0.5

        for (index in 0 until harmonics) {
            val k = index + 1
            val harmonicHz = omega * sampleRate / TWO_PI * k

            // Recurrence values ck/sk are still advanced even for channels above Nyquist so the
            // loop remains branch-free and simple.
            if (harmonicHz < nyquist * 0.98) {
                // x * exp(-j k phi)
                val mixRe = x * ck
                val mixIm = -x * sk

                val add = lpAdd[index]

                lp1Re[index] += add * (mixRe - lp1Re[index])
                lp1Im[index] += add * (mixIm - lp1Im[index])
                lp2Re[index] += add * (lp1Re[index] - lp2Re[index])
                lp2Im[index] += add * (lp1Im[index] - lp2Im[index])

                val re = lp2Re[index]
                val im = lp2Im[index]
                val magnitudeSquared = re * re + im * im

                // For x=A cos(...), |baseband|~=A/2 and sinusoidal average power=A²/2,
                // therefore explained power is approximately 2|z|².
                explainedPower += 2.0 * magnitudeSquared

                if (magnitudeSquared >= partialMagnitudeSquaredFloor) {
                    activePartials++

                    if (previousValid[index]) {
                        val pr = previousRe[index]
                        val pi = previousIm[index]

                        val cross = im * pr - re * pi
                        val dot = re * pr + im * pi
                        val harmonicResidual = atan2(cross, dot)

                        // Convert harmonic residual to FUNDAMENTAL radians/sample.
                        val residual = harmonicResidual / k

                        // Reject absurd jumps before they can affect the robust estimator.
                        val hardLimit =
                            TWO_PI * (captureHz * 2.5) / sampleRate

                        if (abs(residual) <= hardLimit) {
                            residualScratch[voteCount] = residual

                            val harmonicPreference = harmonicPreferences[index]

                            weightScratch[voteCount] =
                                magnitudeSquared * harmonicPreference

                            voteCount++
                        }
                    }

                    previousRe[index] = re
                    previousIm[index] = im
                    previousValid[index] = true
                } else {
                    // Do not measure phase advance across a long interval where this partial was
                    // below the usable floor.
                    previousValid[index] = false
                }
            } else {
                previousValid[index] = false
            }

            // harmonic-angle recurrence:
            // exp(j(k+1)phi) = exp(jkphi) exp(jphi)
            val nextC = ck * c1 - sk * s1
            val nextS = sk * c1 + ck * s1
            ck = nextC
            sk = nextS
        }

        val gateOpen =
            sqrt(broadbandPower.coerceAtLeast(0.0)) >= gateLevel

        val broadband = broadbandPower.coerceAtLeast(1e-20)
        val harmonicity =
            if (gateOpen)
                (explainedPower / broadband).coerceIn(0.0, 1.0)
            else
                0.0

        var fusedResidual = 0.0
        var agreement = 0.0

        if (gateOpen && voteCount > 0) {
            // Sort only the dense scratch prefix. N is normally 8..16, so insertion sort is both
            // allocation-free and cheaper than bringing a general collection sort into audio code.
            insertionSortPairs(residualScratch, weightScratch, voteCount)

            val median = weightedMedian(
                residualScratch,
                weightScratch,
                voteCount
            )

            val outlierRadius =
                TWO_PI * residualOutlierHz / sampleRate

            var weightedResidual = 0.0
            var acceptedWeight = 0.0
            var totalWeight = 0.0

            for (n in 0 until voteCount) {
                val r = residualScratch[n]
                val w = weightScratch[n]
                totalWeight += w

                if (abs(r - median) <= outlierRadius) {
                    weightedResidual += w * r
                    acceptedWeight += w
                }
            }

            if (acceptedWeight > 0.0) {
                fusedResidual = weightedResidual / acceptedWeight

                // Agreement is the fraction of spectral voting weight surviving robust rejection.
                agreement =
                    (acceptedWeight / totalWeight.coerceAtLeast(1e-30))
                        .coerceIn(0.0, 1.0)

                omega =
                    (omega + frequencyAlpha * fusedResidual)
                        .coerceIn(minOmega, maxOmega)
            }
        }

        val instantaneousConfidence =
            if (gateOpen && voteCount > 0)
                (harmonicity * agreement).coerceIn(0.0, 1.0)
            else
                0.0

        smoothedConfidence =
            confidenceKeep * smoothedConfidence +
                (1.0 - confidenceKeep) * instantaneousConfidence

        // The agile coordinate used by lock=1 bank voices.
        val actualAdvance =
            omega + (if (phaseFollow.isFinite()) phaseFollow else 0.0) * fusedResidual

        phase = wrapTrackedPhase(phase + actualAdvance)

        state.phase = phase
        state.phaseAdvance = actualAdvance
        state.omega = omega
        state.level = sqrt(broadbandPower.coerceAtLeast(0.0))
        state.confidence = if (gateOpen) smoothedConfidence else 0.0
        state.gateOpen = gateOpen
        state.locked = gateOpen && smoothedConfidence >= lockThreshold
        state.harmonicity = harmonicity
        state.activePartials = activePartials

        return state
    }

    override fun reset() {
        phase = 0.0
        omega =
            (TWO_PI * initialHz / sampleRate)
                .coerceIn(minOmega, maxOmega)

        broadbandPower = 0.0
        smoothedConfidence = 0.0

        lp1Re.fill(0.0)
        lp1Im.fill(0.0)
        lp2Re.fill(0.0)
        lp2Im.fill(0.0)
        previousRe.fill(0.0)
        previousIm.fill(0.0)
        previousValid.fill(false)

        coarseRing.fill(0.0)
        coarseScores.fill(0.0)
        analysisLag = 0
        coarseWrite = 0
        coarseCount = 0
        coarseAccumulator = 0.0
        coarseAccumulatorCount = 0
        coarseSinceAnalysis = 0
        coarseHz = 0.0
        coarseCorrelation = 0.0

        state.phase = 0.0
        state.phaseAdvance = omega
        state.omega = omega
        state.level = 0.0
        state.confidence = 0.0
        state.gateOpen = false
        state.locked = false
        state.harmonicity = 0.0
        state.activePartials = 0
    }

    /**
     * Feed the cheap coarse periodicity path.
     *
     * A box average precedes decimation. It is not intended to be a beautiful resampler; its only
     * job is to make normalized autocorrelation cheap enough to run as an acquisition safety net.
     */
    private fun pushCoarse(x: Double) {
        coarseAccumulator += x
        coarseAccumulatorCount++

        if (coarseAccumulatorCount < coarseDecimation) return

        val coarseSample = coarseAccumulator / coarseDecimation
        coarseAccumulator = 0.0
        coarseAccumulatorCount = 0

        coarseRing[coarseWrite] = coarseSample
        coarseWrite = (coarseWrite + 1) and coarseMask
        coarseCount = (coarseCount + 1).coerceAtMost(coarseRingSize)

        coarseSinceAnalysis++

        if (
            coarseCount < coarseWindow + coarseMaxLag + 2 ||
            coarseSinceAnalysis < coarseHop
        ) {
            return
        }

        if (analysisLag != 0) return
        coarseSinceAnalysis = 0
        for (j in coarseSnapshot.indices)
            coarseSnapshot[j] = coarseRing[(coarseWrite - 1 - j) and coarseMask]
        analysisLag = coarseMinLag
    }

    private fun advanceCoarse() {
        val lag = analysisLag
        if (lag == 0) return
        var cross = 0.0
        var e0 = 0.0
        var e1 = 0.0
        var sum0 = 0.0
        var sum1 = 0.0
        for (j in 0 until coarseWindow) {
            val a = coarseSnapshot[j]
            val b = coarseSnapshot[j + lag]
            cross += a * b; e0 += a * a; e1 += b * b
            sum0 += a; sum1 += b
        }
        // Center both windows so DC cannot masquerade as perfect periodicity.
        cross -= sum0 * sum1 / coarseWindow
        val denominator = e0 + e1 - (sum0 * sum0 + sum1 * sum1) / coarseWindow
        coarseScores[lag] = if (denominator > 1e-20) (2.0 * cross / denominator).coerceIn(-1.0, 1.0) else 0.0
        if (lag < coarseMaxLag) { analysisLag++; return }
        analysisLag = 0
        val estimate = estimateCoarsePeriodicity()
        if (estimate <= 0.0 || broadbandPower < gateLevel * gateLevel) return

        val candidateHz = estimate
        val differenceHz =
            abs(candidateHz - omega * sampleRate / TWO_PI)

        if (differenceHz <= coarseDeadbandHz) return

        val targetOmega =
            (TWO_PI * candidateHz / sampleRate)
                .coerceIn(minOmega, maxOmega)

        val pull =
            if (differenceHz > maxOf(2.0 * captureHz, candidateHz * 0.20)) 0.85
            else if (smoothedConfidence < lockThreshold)
                coarseUnlockedPull
            else
                coarseLockedPull

        val before = omega
        omega += pull * (targetOmega - omega)
        omega = omega.coerceIn(minOmega, maxOmega)

        // A large retune invalidates the fine filters' old baseband phase history.
        if (abs(omega - before) > before * 0.10) {
            clearFineHistory()
        }
    }

    /**
     * Returns Hz or <=0 when no convincing periodicity exists.
     */
    private fun estimateCoarsePeriodicity(): Double {
        var best = -1.0

        for (lag in coarseMinLag..coarseMaxLag) best = maxOf(best, coarseScores[lag])

        if (best < coarseMinCorrelation) {
            coarseHz = 0.0
            coarseCorrelation = best
            return 0.0
        }

        val threshold =
            maxOf(
                coarseMinCorrelation,
                best * coarseRelativePeak
            )

        var chosenLag = -1

        // Smallest lag == highest frequency. The first peak almost as good as the global peak is
        // preferred over equally strong 2T / 3T repetitions.
        for (lag in (coarseMinLag + 1) until coarseMaxLag) {
            val y = coarseScores[lag]
            if (
                y >= threshold &&
                y >= coarseScores[lag - 1] &&
                y > coarseScores[lag + 1]
            ) {
                chosenLag = lag
                break
            }
        }

        if (chosenLag < 0) {
            // Boundary / pathological fallback: choose the global maximum.
            var maxScore = Double.NEGATIVE_INFINITY
            for (lag in coarseMinLag..coarseMaxLag) {
                if (coarseScores[lag] > maxScore) {
                    maxScore = coarseScores[lag]
                    chosenLag = lag
                }
            }
        }

        var refinedLag = chosenLag.toDouble()

        if (chosenLag > coarseMinLag && chosenLag < coarseMaxLag) {
            val ym = coarseScores[chosenLag - 1]
            val y0 = coarseScores[chosenLag]
            val yp = coarseScores[chosenLag + 1]

            val curvature = ym - 2.0 * y0 + yp
            if (abs(curvature) > 1e-12) {
                refinedLag +=
                    (0.5 * (ym - yp) / curvature)
                        .coerceIn(-0.5, 0.5)
            }
        }

        val hz =
            (coarseRate / refinedLag)
                .coerceIn(minHz, maxHz)

        coarseHz = hz
        coarseCorrelation = coarseScores[chosenLag]

        return hz
    }

    private fun clearFineHistory() {
        lp1Re.fill(0.0)
        lp1Im.fill(0.0)
        lp2Re.fill(0.0)
        lp2Im.fill(0.0)
        previousRe.fill(0.0)
        previousIm.fill(0.0)
        previousValid.fill(false)
        smoothedConfidence *= 0.5
    }
}


private fun insertionSortPairs(
    values: DoubleArray,
    weights: DoubleArray,
    count: Int,
) {
    for (i in 1 until count) {
        val v = values[i]
        val w = weights[i]
        var j = i - 1

        while (j >= 0 && values[j] > v) {
            values[j + 1] = values[j]
            weights[j + 1] = weights[j]
            j--
        }

        values[j + 1] = v
        weights[j + 1] = w
    }
}


private fun weightedMedian(
    values: DoubleArray,
    weights: DoubleArray,
    count: Int,
): Double {
    var total = 0.0
    for (n in 0 until count) total += weights[n]

    val halfway = total * 0.5
    var accumulated = 0.0

    for (n in 0 until count) {
        accumulated += weights[n]
        if (accumulated >= halfway) return values[n]
    }

    return values[count - 1]
}


private fun nextPowerOfTwo(value: Int): Int {
    var v = 1
    while (v < value) v = v shl 1
    return v
}
