package afield.four.physics

import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector3fc
import afield.stdlib.linalg.minus
import afield.stdlib.linalg.plus
import afield.stdlib.linalg.times
import afield.stdlib.vec
import afield.stdlib.x
import kotlin.math.*

/**
 * PointDriver -- position in, force out.
 *
 *     val drv = PointDriver(DriverConfig(dt = 1.0 / 120.0, worldScale = 1.0))
 *     drv.grab(target)                     // may be called every frame; target can move
 *     val f = drv.update(pos)              // per frame, fixed dt
 *     drv.releaseGrab()                    // then keep calling update() -- see below
 *
 * Vectors are Field's ordinary Vector3f; every scalar the controller reasons
 * about -- gains, timescales, the identified plant -- stays Double. The file is otherwise standalone.
 *
 * The whole controller state lives in the instance, so a rune-keyed slot holding
 * one PointDriver per particle survives hot-reload with the learned plant model
 * intact. Nothing needs re-priming except omega, which re-converges in ~1s.
 *
 * #### What it does, in four coupled timescales
 *
 *  1. ~dt      model-inverting PD+I on acceleration, output through a
 *     bounded-curvature force limiter (magnitude, magnitude rate, and
 *     angular rate/acceleration on the direction).
 *  2. ~100ms   discrete-time RLS identification of the host's own integrator:
 *       x[k+1] - 2x[k] + x[k-1] = dt^2 (b F[k] + c v[k] + d)
 *     exact under semi-implicit Euler, so b^ absorbs the host's
 *     numerical damping and dispersion, not just the mass. Gains are
 *     synthesised from (b,c,d); the only free knobs are physical.
 *  3. ~seconds omega hunting driven by sin(lagAngle) -- the direction-limiter's
 *     turn-rate deficit. Slow rise, fast knockback: self-organised
 *     criticality that pins omega to the capture/orbit bifurcation.
 *  4. ~10s     winding memory in the limiter's rotation axis. Chirality flips
 *     become rare punctuated events with heavy-tailed dwell times.
 *
 * #### Position-only input, done properly
 *
 *  - Velocity comes from the host's delta-position, which is the average over
 *    the interval and therefore centred at t - dt/2. Corrected by extrapolating
 *    dt/2 forward through the identified model -- pure phase recovery, no noise
 *    amplification. Uncorrected this donates omega*dt/2 of lag straight to the
 *    limiter and lowers the bandwidth ceiling.
 *  - TWO velocity signals, never crossed:
 *        vHat  (model observer)  -> control law only
 *        vPrev (raw, prefiltered) -> identifier only
 *    Feeding the observer estimate to the identifier is circular -- it was built
 *    from the force and the current model, so the RLS would confirm whatever it
 *    already believes. That failure looks stable for minutes, then diverges.
 *  - Jump guard: a single-frame teleport or collision reads as enormous velocity.
 *    Rejected on a running speed estimate; the identifier skips two frames while
 *    the second-difference window refills, and the model is left untouched.
 *  - Identification freezes while the limiter is saturated. If the host solves
 *    springs as constraints (PBD/XPBD, fixed iterations) the effective stiffness
 *    depends on how hard we are pushing, which makes b^ a function of our own
 *    aggression -- a self-referential loop that can actually diverge. Freezing
 *    plus a slow forgetting factor breaks it.
 *  - Release ramps magnitude to zero and FREEZES the aim, and keeps identifying
 *    through the ringdown. Free modal data with no control input to confound it,
 *    and the retained aim couples successive grabs.
 */

// ---------------------------------------------------------------- vec ------
//
// Vector3f is mutable and most of its methods write into the receiver, so the
// rules here are: only the allocating operators from afield.stdlib.linalg are
// used, `v / s` is always written `v * (1.0 / s)` (the `/` operator on a
// concrete Vector3f is the in-place member and would clobber its left operand),
// and anything crossing the API boundary in either direction is copied. Length
// and dot come back as Double so they compose with the scalar maths.

private fun Vector3fc.copy(): Vector3f = Vector3f(this)

private val Vector3fc.len: Double get() = length().toDouble()

private val Vector3fc.len2: Double get() = lengthSquared().toDouble()

private infix fun Vector3fc.dotD(o: Vector3fc): Double = dot(o).toDouble()

private fun Vector3fc.clampLen(m: Double): Vector3f {
    val l = len
    return if (l > m && l > 0.0) this * (m / l) else copy()
}

private fun Vector3fc.unitOr(f: Vector3fc): Vector3f {
    val l = len
    return if (l > 1e-12 && isFinite()) this * (1.0 / l) else f.copy()
}

// ---------------------------------------------------------------- config ---

data class DriverConfig(
    /** MUST be fixed. Substep internally if your frame time varies -- this is what
     *  makes the SOC layer and the RNG stream bit-reproducible. */
    val dt: Double = 1.0 / 120.0,
    /** Characteristic length of your scene. All length-dependent thresholds derive
     *  from this, so it is the only world-scale number you need to set. */
    val worldScale: Double = 1.0,

    // --- inner loop -------------------------------------------------------
    val zeta: Double = 0.85,
    val omega0: Double = 4.0,
    val omegaMin: Double = 0.5,
    val omegaMax: Double = 80.0,
    val integralFrac: Double = 0.08,     // ki = integralFrac * omega^3
    val integralLeak: Double = 0.25,     // 1/s; 0.0 stores more energy for the release

    // --- actuator ---------------------------------------------------------
    val maxForce: Double = 40.0,
    val magRateMax: Double = 2000.0,     // N/s
    /** rad/s. THE dial. This is a scale-free constraint, so unlike maxForce it
     *  never stops binding as the error shrinks. It sets a hard ceiling on
     *  closed-loop bandwidth: omega_d > angRateMax means capture is impossible at
     *  any amplitude and you get marginal spirals instead. */
    val angRateMax: Double = 6.0,
    /** Finite gives the aim its own inertia -- overshoot and precession, a gimbal
     *  with a torque limit. Reads as intention. Also adds the two state
     *  dimensions that push the grab-to-grab map from quasi-periodic to chaotic. */
    val angAccelMax: Double = Double.POSITIVE_INFINITY,
    val axisMemoryTau: Double = 0.25,    // s, winding-sense hysteresis
    val antipodalGuard: Double = 0.02,   // rad
    val releaseTau: Double = 0.03,       // s

    // --- identification ---------------------------------------------------
    val lambda: Double = 0.997,          // keep slow relative to the grab period
    val p0: Double = 1e3,
    val pTraceMax: Double = 1e4,
    val filterRatio: Double = 6.0,       // prefilter cutoff = filterRatio * omega
    val deadzone: Double = 1e-3,
    val bMin: Double = 1e-3, val bMax: Double = 1e4,
    val cMax: Double = 60.0, val dMax: Double = 500.0,
    val freezeIdWhenSaturated: Boolean = true,
    val observerGain: Double = 0.4,      // 0 = pure model, 1 = pure measurement
    val jumpFactor: Double = 8.0,        // teleport/collision rejection

    // --- layer 3: SOC bandwidth hunting -----------------------------------
    /** Chosen distance from the capture/orbit bifurcation, in units of turn-rate
     *  deficit. 0 = strictly convergent servo. >0.3 = permanent orbit. The
     *  interesting band is narrow. Put this on a fader. */
    val lagTarget: Double = 0.10,
    val gammaUp: Double = 0.35,          // 1/s
    val gammaDown: Double = 6.0,         // 1/s -- asymmetry makes the avalanches

    // --- layer 4: frustration (largely superseded by the limiter) ---------
    /** The angle limiter generates orbits on its own, from an actuator constraint
     *  rather than a heuristic. Leave muOrbit at 0 unless angRateMax is high
     *  enough that the limiter never binds. */
    val muOrbit: Double = 0.0,
    val muDamp: Double = -0.35,
    val rhoMax: Double = 1.4,
    val rhoRate: Double = 2.0,
    val stallTol: Double = 0.02,
    val frustrationRise: Double = 0.5,
    val frustrationFall: Double = 1.5,

    // --- exploration ------------------------------------------------------
    /** Radial only. Tangential dither is eaten by the angle limiter, and radial
     *  excitation is exactly what separates b from d. */
    val ditherFrac: Double = 0.03,
    val ditherTau: Double = 0.08,
    val seed: Long = 0x51ED5EEDL,
)

data class Telemetry(
    val omega: Double,          // current closed-loop bandwidth, rad/s
    val lag: Double,            // rad, commanded vs applied force direction
    val chatter: Double,        // EMA of sin(lag); compare against lagTarget
    val massEst: Double, val dragEst: Double, val biasEst: Vector3f,
    val winding: Double,        // accumulated signed aim rotation, rad
    val aimDir: Vector3f, val forceMag: Double,
    val frustration: Double, val rho: Vector3f,
    val uncertainty: Double,    // 0..1, drives radial dither
    val saturated: Boolean, val idFrozen: Boolean, val jumped: Boolean,
    val errMag: Double, val speed: Double,
)

// ---------------------------------------------------------------- driver ---

class PointDriver(val cfg: DriverConfig = DriverConfig()) {

    private val dt = cfg.dt
    private val captureRadius = 0.01 * cfg.worldScale
    private val minSpeed = 1e-4 * cfg.worldScale

    // ---- targets ----
    private var grabbed = false
    private var target = Vector3f()
    private var targetVel = Vector3f()
    private var targetAcc = Vector3f()

    // ---- measurement / observer ----
    private var samples = 0
    private var xPrev = Vector3f()
    private var xPrev2 = Vector3f()
    private var vPrev = Vector3f()    // raw, for the identifier
    private var vHat = Vector3f()     // observer, for the control law
    private var speedEma = 0.0
    private var skipId = 0
    private var jumped = false

    // ---- identification ----
    private val rls = Rls(5, cfg.lambda, cfg.p0).also { it.theta[0] = 1.0 }
    private val phi = DoubleArray(5)
    private var yF = Vector3f()
    private var fFlt = Vector3f()
    private var vFlt = Vector3f()
    private var idFrozen = false

    // ---- slow layers ----
    var omega = cfg.omega0; private set
    private var chatter = 0.0
    private var lag = 0.0
    private var errEma = 0.0
    private var frustration = 0.0
    private var rho = Vector3f()
    private var iErr = Vector3f()
    private var ou = Vector3f()
    private val rng = Rng(cfg.seed)

    // ---- actuator state ----
    private var aim = Vector3f(0f, 1f, 0f)
    private var mag = 0.0
    private var axisMem = Vector3f(0f, 0f, 1f)
    private var angVel = Vector3f()
    var winding = 0.0; private set
    private var fPrev = Vector3f()
    private var saturated = false

    // ---------------------------------------------------------------- api --

    /** Call every frame while holding. The target may move; targetVel/Acc are
     *  feedforward and optional. The three vectors are copied, so the caller
     *  stays free to mutate whatever it passed in. */
    fun grab(target: Vector3f, targetVel: Vector3f = Vector3f(), targetAcc: Vector3f = Vector3f()) {
        grabbed = true
        this.target = target.copy(); this.targetVel = targetVel.copy(); this.targetAcc = targetAcc.copy()
    }

    /** Let go. Keep calling update() -- the magnitude ramps out, the aim freezes,
     *  and identification continues through the ringdown. */
    fun releaseGrab() { grabbed = false }

    val isGrabbed get() = grabbed

    /** Per frame. Position in, force out. The returned force is a fresh vector
     *  the caller owns; [position] is only read. */
    fun update(position: Vector3f): Vector3f {
        if (!position.isFinite()) { reset(keepModel = true); return Vector3f() }

        // ---- 1. velocity from delta position, with jump guard -------------
        jumped = false
        if (samples == 0) {
            xPrev = position.copy(); xPrev2 = position.copy(); aim = Vector3f(0f, 1f, 0f)
            samples = 1; fPrev = Vector3f()
            return Vector3f()
        }
        val dx = position - xPrev
        var vMeas = dx * (1.0 / dt)
        val sp = vMeas.len
        if (sp > cfg.jumpFactor * (speedEma + minSpeed) && samples > 2) {
            // teleport or collision: reject the sample, hold velocity, refill the
            // second-difference window before trusting the identifier again
            jumped = true
            vMeas = vPrev.copy()
            skipId = 2
            xPrev2 = position.copy(); xPrev = position.copy()
        } else {
            speedEma += (sp - speedEma) * (1.0 - exp(-dt * 4.0))
        }
        if (samples < 3) samples++

        // ---- 2. discrete-time identification -----------------------------
        idFrozen = skipId > 0 || (cfg.freezeIdWhenSaturated && saturated)
        if (samples >= 3 && !jumped) {
            // (x[k]-x[k-1]) - (x[k-1]-x[k-2]), not x[k] - 2x[k-1] + x[k-2]:
            // algebraically the same, but it differences neighbours instead of
            // cancelling two large f32 terms against each other.
            val y = (dx - (xPrev - xPrev2)) * (1.0 / (dt * dt))
            val wf = (cfg.filterRatio * omega).coerceIn(1.0, 0.45 / dt)
            val a = 1.0 - exp(-wf * dt)
            // filter both sides identically -- preserves the linear relation
            yF = yF + (y - yF) * a
            fFlt = fFlt + (fPrev - fFlt) * a
            vFlt = vFlt + (vPrev - vFlt) * a
            if (!idFrozen && yF.isFinite() && fFlt.isFinite() && vFlt.isFinite()) {
                for (ax in 0 until 3) {
                    phi[0] = fFlt[ax].toDouble(); phi[1] = vFlt[ax].toDouble()
                    phi[2] = 0.0; phi[3] = 0.0; phi[4] = 0.0
                    phi[2 + ax] = 1.0
                    rls.update(phi, yF[ax].toDouble(), cfg.deadzone)
                }
                rls.boundTrace(cfg.pTraceMax)
            }
        }
        if (skipId > 0) skipId--
        // parameter projection: keeps the model inversion well-posed regardless
        rls.theta[0] = rls.theta[0].guard(1.0).coerceIn(cfg.bMin, cfg.bMax)
        rls.theta[1] = rls.theta[1].guard(0.0).coerceIn(-cfg.cMax, cfg.cMax)
        for (i in 2..4) rls.theta[i] = rls.theta[i].guard(0.0).coerceIn(-cfg.dMax, cfg.dMax)
        val b = rls.theta[0]; val c = rls.theta[1]
        val d = vec(rls.theta[2], rls.theta[3], rls.theta[4])

        // ---- 3. half-step extrapolation + observer ------------------------
        // vMeas is centred at t - dt/2. Push it forward through the model: phase
        // recovery with no noise amplification.
        val aPred = fPrev * b + vMeas * c + d
        val vExtrap = vMeas + aPred * (0.5 * dt)
        vHat = vHat + (fPrev * b + vHat * c + d) * dt
        vHat = vHat + (vExtrap - vHat) * cfg.observerGain.coerceIn(0.0, 1.0)
        if (!vHat.isFinite()) vHat = vExtrap.copy()

        // bookkeeping for the next frame's identifier window
        xPrev2 = xPrev; xPrev = position.copy(); vPrev = vMeas

        // ---- released: ramp out, freeze aim, keep identifying -------------
        if (!grabbed) {
            mag *= exp(-dt / max(cfg.releaseTau, 1e-6))
            if (mag < 1e-9) mag = 0.0
            iErr = Vector3f()
            val f = aim * mag
            fPrev = f.copy()
            return f
        }

        // ---- 4. SOC bandwidth hunting, driven by the turn-rate deficit ----
        val x = chatter / max(cfg.lagTarget, 1e-6)
        val rate = if (x < 1.0) cfg.gammaUp * (1.0 - x) else -cfg.gammaDown * (x - 1.0)
        omega = (omega * exp(rate * dt)).coerceIn(cfg.omegaMin, cfg.omegaMax)

        val e = target - position
        val ed = targetVel - vHat
        val eMag = e.len

        // ---- 5. frustration -> chirality (gated on !saturated) -----------
        val prevErr = errEma
        errEma += (eMag - errEma) * (1.0 - exp(-0.5 * dt))
        val relProgress = ((prevErr - errEma) / dt) / (errEma * omega + 1e-12)
        val stuck = eMag > captureRadius && relProgress < cfg.stallTol && !saturated
        frustration += dt * if (stuck) cfg.frustrationRise * (1.0 - frustration)
        else -cfg.frustrationFall * frustration
        frustration = frustration.coerceIn(0.0, 1.0)
        val s = frustration * frustration * (3.0 - 2.0 * frustration)
        val mu = cfg.muDamp + (cfg.muOrbit - cfg.muDamp) * s
        val omErr = (e x ed) * (1.0 / (e.len2 + 1e-12))
        rho = rho + ((omErr * (mu / omega)).clampLen(cfg.rhoMax) - rho) * (1.0 - exp(-cfg.rhoRate * dt))

        // ---- 6. control law ----------------------------------------------
        val ki = cfg.integralFrac * omega * omega * omega
        iErr = (iErr + e * dt) * exp(-cfg.integralLeak * dt)
        val iLimit = 0.35 * b * cfg.maxForce / max(ki, 1e-9)
        iErr = iErr.clampLen(iLimit)

        var aCmd = (e + (rho x e)) * (omega * omega) +
                ed * (2.0 * cfg.zeta * omega) +
                iErr * ki + targetAcc

        // radial dither, amplitude annealed by identification confidence
        val decay = exp(-dt / cfg.ditherTau)
        ou = ou * decay + vec(rng.gauss(), rng.gauss(), rng.gauss()) * sqrt(max(0.0, 1 - decay * decay))
        val drive = (uncertainty() + 0.5 * s).coerceAtMost(1.5)
        aCmd = aCmd + aCmd.unitOr(Vector3f()) * (aCmd.len * cfg.ditherFrac * drive * ou.x.toDouble())

        // model inversion: cancel identified drag and field, normalise mass
        val want = (aCmd - vHat * c - d) * (1.0 / b)

        // ---- 7. bounded-curvature limiter --------------------------------
        val f = limit(want)

        // ---- 8. anti-windup against the actually-applied force -----------
        if (saturated && ki > 1e-12) {
            val aReal = f * b + vHat * c + d
            iErr = (iErr - (aCmd - aReal) * (dt * omega / ki)).clampLen(iLimit)
        }

        // ---- 9. chatter = EMA of sin(lag) --------------------------------
        chatter += (sin(lag) - chatter) * (1.0 - exp(-dt / (4.0 / omega)))

        fPrev = f.copy()
        return f
    }

    fun telemetry() = Telemetry(
        omega = omega, lag = lag, chatter = chatter,
        massEst = 1.0 / max(rls.theta[0], 1e-9), dragEst = rls.theta[1],
        biasEst = vec(rls.theta[2], rls.theta[3], rls.theta[4]),
        winding = winding, aimDir = aim.copy(), forceMag = mag,
        frustration = frustration, rho = rho.copy(), uncertainty = uncertainty(),
        saturated = saturated, idFrozen = idFrozen, jumped = jumped,
        errMag = errEma, speed = speedEma,
    )

    fun reset(keepModel: Boolean = false) {
        samples = 0; xPrev = Vector3f(); xPrev2 = Vector3f()
        vPrev = Vector3f(); vHat = Vector3f(); speedEma = 0.0; skipId = 0
        yF = Vector3f(); fFlt = Vector3f(); vFlt = Vector3f()
        omega = cfg.omega0; chatter = 0.0; lag = 0.0; errEma = 0.0
        frustration = 0.0; rho = Vector3f(); iErr = Vector3f(); ou = Vector3f()
        aim = Vector3f(0f, 1f, 0f); mag = 0.0; axisMem = Vector3f(0f, 0f, 1f)
        angVel = Vector3f(); winding = 0.0; fPrev = Vector3f(); saturated = false
        if (!keepModel) { rls.resetAll(cfg.p0); rls.theta[0] = 1.0 }
    }

    private fun uncertainty() = sqrt(rls.trace() / (5.0 * cfg.p0)).coerceIn(0.0, 1.0)

    // ------------------------------------------------------------ limiter --

    /**
     * Magnitude, magnitude-rate, and angular-rate (or angular-acceleration)
     * limiting. The direction is a state on S^2 with bounded angular velocity: a
     * Dubins problem on the sphere. Reversal costs pi/angRateMax seconds
     * regardless of magnitude, so the controller must commit to a rotation sense.
     *
     * The near-antipodal case is where the memory lives: u x uDes degenerates and
     * the handedness is undetermined, so we fall back to a low-passed record of
     * the recent rotation axis. Once winding one way, continuing is cheaper than
     * unwinding -- flips become barrier crossings.
     */
    private fun limit(desired: Vector3f): Vector3f {
        val dMag = desired.len
        val uDes = desired.unitOr(aim)

        val ang = acos((aim dotD uDes).coerceIn(-1.0, 1.0))
        var axis = aim x uDes
        val axisLen = axis.len
        axis = if (axisLen > cfg.antipodalGuard) axis * (1.0 / axisLen)
        else (axisMem - aim * (axisMem dotD aim)).unitOr(anyPerp(aim))

        val step: Double
        if (cfg.angAccelMax.isInfinite()) {
            step = min(ang, cfg.angRateMax * dt)
            if (ang > 1e-12) aim = rotate(aim, axis, step)
        } else {
            val wTarget = axis * min(cfg.angRateMax, sqrt(2.0 * cfg.angAccelMax * ang))
            angVel = angVel + (wTarget - angVel).clampLen(cfg.angAccelMax * dt)
            val wMag = angVel.len
            if (wMag > 1e-12) {
                aim = rotate(aim, angVel * (1.0 / wMag), wMag * dt)
                angVel = angVel - aim * (angVel dotD aim)
            }
            step = wMag * dt
        }
        aim = aim.unitOr(uDes)

        winding += step * (if ((axis dotD axisMem) >= 0.0) 1.0 else -1.0)
        axisMem = (axisMem + (axis - axisMem) * (1.0 - exp(-dt / max(cfg.axisMemoryTau, 1e-6)))).unitOr(axisMem)

        val wantMag = min(dMag, cfg.maxForce)
        mag = (mag + (wantMag - mag).coerceIn(-cfg.magRateMax * dt, cfg.magRateMax * dt))
            .coerceIn(0.0, cfg.maxForce)

        lag = acos((aim dotD uDes).coerceIn(-1.0, 1.0))
        saturated = abs(wantMag - mag) > 1e-9 || lag > 1e-6
        return aim * mag
    }

    private fun rotate(v: Vector3f, axis: Vector3f, theta: Double): Vector3f {
        val par = axis * (axis dotD v)
        val perp = v - par
        return par + perp * cos(theta) + (axis x perp) * sin(theta)
    }

    private fun anyPerp(v: Vector3f): Vector3f {
        val a = if (abs(v.x) < 0.9f) Vector3f(1f, 0f, 0f) else Vector3f(0f, 1f, 0f)
        return (a - v * (a dotD v)).unitOr(Vector3f(0f, 0f, 1f))
    }
}

private fun Double.guard(fallback: Double) = if (isFinite()) this else fallback

// ---------------------------------------------------------------- rls ------

private class Rls(val n: Int, val lambda: Double, p0: Double) {
    val theta = DoubleArray(n)
    private val p = DoubleArray(n * n).also { for (i in 0 until n) it[i * n + i] = p0 }
    private val pphi = DoubleArray(n)
    private val kk = DoubleArray(n)

    fun trace(): Double { var s = 0.0; for (i in 0 until n) s += p[i * n + i]; return s }

    fun resetAll(p0: Double) {
        p.fill(0.0); theta.fill(0.0)
        for (i in 0 until n) p[i * n + i] = p0
    }

    fun update(phi: DoubleArray, y: Double, deadzone: Double): Double {
        var nrm = 0.0
        for (i in 0 until n) nrm += phi[i] * phi[i]
        if (nrm < 1e-12 || !y.isFinite()) return 0.0
        var pred = 0.0
        for (i in 0 until n) pred += phi[i] * theta[i]
        val err = y - pred
        if (abs(err) < deadzone * (1.0 + sqrt(nrm))) return err
        for (i in 0 until n) {
            var s = 0.0
            for (j in 0 until n) s += p[i * n + j] * phi[j]
            pphi[i] = s
        }
        var pd = 0.0
        for (i in 0 until n) pd += phi[i] * pphi[i]
        val den = lambda + pd
        if (den < 1e-12 || !den.isFinite()) return err
        for (i in 0 until n) kk[i] = pphi[i] / den
        for (i in 0 until n) theta[i] += kk[i] * err
        for (i in 0 until n) for (j in 0 until n) p[i * n + j] = (p[i * n + j] - kk[i] * pphi[j]) / lambda
        for (i in 0 until n) for (j in i + 1 until n) {
            val m = 0.5 * (p[i * n + j] + p[j * n + i]); p[i * n + j] = m; p[j * n + i] = m
        }
        return err
    }

    fun boundTrace(maxTr: Double) {
        val t = trace()
        if (!t.isFinite()) { resetAll(maxTr / n); return }
        if (t > maxTr) { val s = maxTr / t; for (i in p.indices) p[i] *= s }
    }
}

// ---------------------------------------------------------------- rng ------

private class Rng(seed: Long) {
    private var s = if (seed == 0L) -0x61c8864680b583ebL else seed
    private var spare = Double.NaN
    fun nextDouble(): Double {
        var x = s; x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17); s = x
        return (x ushr 11).toDouble() * (1.0 / 9007199254740992.0)
    }
    fun gauss(): Double {
        if (!spare.isNaN()) { val v = spare; spare = Double.NaN; return v }
        var u: Double; var v: Double; var q: Double
        do { u = 2 * nextDouble() - 1; v = 2 * nextDouble() - 1; q = u * u + v * v } while (q >= 1.0 || q == 0.0)
        val f = sqrt(-2.0 * ln(q) / q); spare = v * f; return u * f
    }
}

/* ---------------------------------------------------------------------------
 * Tuning
 * ---------------------------------------------------------------------------
 * Servo:        angRateMax 30, lagTarget 0.02, gammaUp 0.2, integralLeak 0.5
 * Edge:         angRateMax 6,  lagTarget 0.12, gammaUp 0.5, gammaDown 3.0
 *               -> omega pinned to the capture/orbit bifurcation, heavy-tailed
 *                  knockbacks. Log them and fit a slope.
 * Creature:     angRateMax 4,  angAccelMax 25, lagTarget 0.25, integralLeak 0.0
 *               -> aim has inertia, precesses, orbits, punctuated chirality
 *                  flips. Log `winding` for dwell-time statistics.
 *
 * Signals worth sonifying: omega (slow drifting pitch), chatter (burst envelope),
 * winding rate (a pan or a chirality-keyed timbre). All three are causally locked
 * to the visible motion.
 *
 * On a spring lattice: set angRateMax above the phonon band edge
 * (~2*sqrt(coordination * k_s / m)) to keep the lattice mode-avoidance behaviour,
 * below it and the limiter becomes the binding constraint and the lattice
 * structure drops out of the omega statistics. Multiple driven nodes will
 * spontaneously spread their omega into non-overlapping bands.
 * ------------------------------------------------------------------------- */
