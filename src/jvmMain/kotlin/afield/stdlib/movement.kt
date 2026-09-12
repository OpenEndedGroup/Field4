@file:afield.CompletionCategory("Simulation")

package afield.stdlib

import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector2fc
import afield.stdlib.linalg.minus
import afield.stdlib.linalg.plus
import afield.stdlib.linalg.times
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Critically damped springs and dampers — the pieces [MovementState] is built from, and worth having
 * on their own for anything that should arrive at a value without a hard edge.
 *
 * Half-life is the parameter throughout: the time the spring takes to close half the distance to its
 * goal. It is the one spring parameter that describes something you can see, and the reason to reach
 * for these rather than tuning a stiffness against a damping.
 *
 * Every step here is *exact* — the analytic solution of the spring's differential equation rather
 * than a numerical integration — so one step of `dt` and ten steps of `dt / 10` land in the same
 * place, and behaviour does not drift with frame rate. Holden's `fast_negexp` polynomial is the one
 * thing not carried across: a real [exp] is what makes that exactness true, and nothing here is
 * counting cycles.
 *
 * After Daniel Holden, ["Spring-It-On: The Game Developer's Spring-Roll-Call"
 * ](https://theorangeduck.com/page/spring-roll-call). The names there are `damper_exact`,
 * `simple_spring_damper_exact`, `decay_spring_damper_exact` and `double_spring_damper_exact`.
 */
object Springs {

    /** Natural log of two: what turns a half-life into a decay rate. */
    const val LN2: Float = 0.69314718056f

    /** [x] and `v` after a spring step — position and velocity, or whatever pair is being sprung. */
    data class Step(val x: Float, val v: Float)

    /** Two-dimensional [Step]: each component is sprung on its own. */
    data class Step2(val x: Vector2f, val v: Vector2f)

    /** A [Step] for a double spring, which carries an intermediate spring along with the visible one. */
    data class DoubleStep(val x: Float, val v: Float, val intermediateX: Float, val intermediateV: Float)

    /** The damping coefficient that gives a critically damped spring this [halflife]. */
    fun halflifeToDamping(halflife: Float, eps: Float = 1e-5f): Float = (4f * LN2) / (halflife + eps)

    /** The half-life of a critically damped spring with this [damping] coefficient. */
    fun dampingToHalflife(damping: Float, eps: Float = 1e-5f): Float = (4f * LN2) / (damping + eps)

    /**
     * How far behind a constant-speed goal a damper of this [halflife] ends up trailing, in seconds.
     *
     * A damper chasing something that keeps moving never catches it, and settles into a fixed lag
     * instead. This is that lag, which is what lets it be cancelled: aim at where the goal *will* be
     * in `halflifeToLag(halflife)` seconds and the output lands on the goal rather than behind it.
     * [MovementState]'s `accelerationCompensation` is exactly this trick.
     */
    fun halflifeToLag(halflife: Float): Float = halflife / LN2

    /** The half-life whose trailing lag is [lag] seconds — the inverse of [halflifeToLag]. */
    fun lagToHalflife(lag: Float): Float = lag * LN2

    /**
     * Moves [x] a fraction of the way to [goal], closing half the remaining distance every [halflife].
     *
     * The plain exponential damper, with no velocity of its own: it leaves at full speed and slows
     * as it arrives, so it eases out but not in. Use [spring] when the start should be smooth too.
     */
    fun damper(x: Float, goal: Float, halflife: Float, dt: Float, eps: Float = 1e-5f): Float =
        x + (goal - x) * (1f - exp(-(LN2 * dt) / (halflife + eps)))

    /** [damper] on both components of a vector. */
    fun damper(x: Vector2fc, goal: Vector2fc, halflife: Float, dt: Float, eps: Float = 1e-5f): Vector2f {
        val blend = 1f - exp(-(LN2 * dt) / (halflife + eps))
        return Vector2f(
            x.x() + (goal.x() - x.x()) * blend,
            x.y() + (goal.y() - x.y()) * blend,
        )
    }

    /**
     * Steps a critically damped spring at [x] moving at [v] toward [goal].
     *
     * Critically damped means it never overshoots and never oscillates: the fastest approach that
     * still arrives cleanly. Because it carries [v], the motion is smooth through a change of goal
     * rather than restarting, which is what makes it the usual choice for following something.
     */
    fun spring(x: Float, v: Float, goal: Float, halflife: Float, dt: Float): Step {
        val y = halflifeToDamping(halflife) / 2f
        val j0 = x - goal
        val j1 = v + j0 * y
        val eydt = exp(-y * dt)
        return Step(eydt * (j0 + j1 * dt) + goal, eydt * (v - j1 * y * dt))
    }

    /** [spring] on both components of a vector, each on its own curve. */
    fun spring(x: Vector2fc, v: Vector2fc, goal: Vector2fc, halflife: Float, dt: Float): Step2 {
        val sx = spring(x.x(), v.x(), goal.x(), halflife, dt)
        val sy = spring(x.y(), v.y(), goal.y(), halflife, dt)
        return Step2(Vector2f(sx.x, sy.x), Vector2f(sx.v, sy.v))
    }

    /**
     * [spring] on an angle in radians, taking the short way round.
     *
     * The only difference from [spring] is that the distance to the goal is measured as a wrapped
     * angle, so a spring at 3 radians chasing -3 turns the short way, onward through π, rather than
     * sweeping back across zero.
     *
     * The result is always expressed near [goal], which means the *number* can jump by 2π on the
     * step where the short way crosses ±π even though the angle does not. Feed it to [cos]/[sin] or
     * a rotation and that is invisible; compare it against a previous value and take the difference
     * through [wrapAngle].
     */
    fun springAngle(x: Float, v: Float, goal: Float, halflife: Float, dt: Float): Step {
        val y = halflifeToDamping(halflife) / 2f
        val j0 = wrapAngle(x - goal)
        val j1 = v + j0 * y
        val eydt = exp(-y * dt)
        return Step(eydt * (j0 + j1 * dt) + goal, eydt * (v - j1 * y * dt))
    }

    /**
     * Two [springAngle]s in series, each on half of [halflife] — the visible one chasing an
     * intermediate one that chases [goal].
     *
     * A single spring leaves at once when the goal jumps, which reads as a flick. Chaining two costs
     * a little more lag and removes the flick, because the visible spring's goal is itself something
     * that accelerates. Worth it on anything a viewer watches directly, like a character's facing.
     */
    fun doubleSpringAngle(
        x: Float,
        v: Float,
        intermediateX: Float,
        intermediateV: Float,
        goal: Float,
        halflife: Float,
        dt: Float
    ): DoubleStep {
        val intermediate = springAngle(intermediateX, intermediateV, goal, 0.5f * halflife, dt)
        val visible = springAngle(x, v, intermediate.x, 0.5f * halflife, dt)
        return DoubleStep(visible.x, visible.v, intermediate.x, intermediate.v)
    }

    /**
     * Decays [x] and [v] toward zero with the shape of a critically damped spring.
     *
     * The offset half of an inertialization: hold the difference between where something was and
     * where it has jumped to, decay it with this, and add it back to hide the jump.
     */
    fun decay(x: Float, v: Float, halflife: Float, dt: Float): Step {
        val y = halflifeToDamping(halflife) / 2f
        val j1 = v + x * y
        val eydt = exp(-y * dt)
        return Step(eydt * (x + j1 * dt), eydt * (v - j1 * y * dt))
    }

    /** [a] wrapped into `(-π, π]`. */
    fun wrapAngle(a: Float): Float {
        val twoPi = (2.0 * PI).toFloat()
        var r = a % twoPi
        if (r > PI) r -= twoPi
        if (r <= -PI) r += twoPi
        return r
    }
}

/**
 * How a character's movement responds to input — the dials on [MovementState.update].
 *
 * The defaults are the ones Holden's demo settles on, in pixels and seconds. The four that matter
 * most, in the order worth touching them:
 *
 * - [acceleration] and [deceleration] cap how hard the character can change speed. Deceleration
 *   higher than acceleration is what makes a stop feel deliberate rather than floaty.
 * - [accelerationHalflife] and [decelerationHalflife] are how much *smoothing* sits on top of that
 *   cap. Zero gives you the raw, responsive, slightly robotic version; larger values round the
 *   corners off at the cost of responsiveness.
 * - [accelerationCompensation] buys back the lag that smoothing costs, by aiming the smoothing at
 *   where the movement is heading rather than where it is. At `1` the cancellation is exact for as
 *   long as the movement is changing at a steady rate — the smoothing keeps its shape and stops
 *   costing anything (see [Springs.halflifeToLag]). Above `1` it anticipates, which reads as keenness.
 * - [turnStrength] is how hard the direction of travel is rotated toward the requested direction, as
 *   a damper of half-life `1 / turnStrength`. It buys *sharpness*: at `0` the direction changes only
 *   as a side effect of accelerating at the velocity error, which is slow, and at high
 *   [directionalAcceleration] barely turns at all. It costs a little speed through the turn, since
 *   rotating a velocity by damping it cuts the chord rather than following the arc. A reversal is not
 *   a turn and still stops: there is no short way round 180°.
 *
 * [directionalAcceleration] is the other side of that trade. At `0` all acceleration goes at the
 * velocity error, which turns readily but loses speed doing it — Holden's quad-copter. At `1` it can
 * only push along the requested direction, so it holds its speed through a turn and drifts, an
 * airboat, and it leans on [turnStrength] for any turn at all. In between, with turn strength up, is
 * a character that turns sharply *and* keeps its speed.
 *
 * [rotationHalflife] and [rotationDoubleSpring] control the facing only, and have no effect on where
 * the character goes.
 */
data class MovementParams(
    val acceleration: Float = 800f,
    val deceleration: Float = 1400f,
    val directionalAcceleration: Float = 0f,
    val turnStrength: Float = 1f,
    val accelerationHalflife: Float = 0.1f,
    val decelerationHalflife: Float = 0.05f,
    val accelerationCompensation: Float = 1f,
    val decelerationCompensation: Float = 0f,
    val rotationHalflife: Float = 0.15f,
    val rotationDoubleSpring: Boolean = true,
) {
    companion object {
        /** The default dials, so that [MovementState.update] can default without allocating. */
        val DEFAULT = MovementParams()
    }
}

/**
 * A character's position, velocity and facing, moved by a desired velocity — a game movement model
 * that stays responsive while still looking like it has mass.
 *
 * Feed it where the player is asking to go, as a velocity, and it produces motion that neither
 * snaps to the input nor lags behind it:
 *
 * ```kotlin
 * val character = MovementState()
 * val params = MovementParams(turnStrength = 2f)
 *
 * // every frame
 * character.update(stick * 400f, dt, params)
 * drawAt(character.position, character.rotation)
 * ```
 *
 * The shape of it, in the order [update] does the work: the requested direction pulls the current
 * direction of travel round, so a turn is sharper than acceleration alone would make it;
 * acceleration toward the requested velocity is clamped to a rate, which is where the weight comes
 * from; that clamped velocity is smoothed by a spring; and the spring is aimed slightly into the
 * future to cancel the lag the smoothing would otherwise cost. Facing is a separate spring on
 * [rotation] that does not affect the path.
 *
 * [velocity] is what to trust for animation, and [position] integrates it. [immediateVelocity] is
 * the unsmoothed velocity the spring is chasing, exposed because it is occasionally the more useful
 * thing to compare against. Every field is a plain `var`: set [position] to teleport, zero
 * [velocity] and [acceleration] to stop dead.
 *
 * After Daniel Holden, ["A New Movement Model"](https://theorangeduck.com/page/new-movement-model).
 */
class MovementState {

    /** Where the character is, integrated from [velocity]. */
    var position: Vector2f = Vector2f()

    /** The smoothed velocity — the one to animate from. */
    var velocity: Vector2f = Vector2f()

    /** [velocity]'s own rate of change, carried by the smoothing spring. */
    var acceleration: Vector2f = Vector2f()

    /** The clamped but unsmoothed velocity that [velocity] is chasing. */
    var immediateVelocity: Vector2f = Vector2f()

    /** Facing, in radians. Sprung separately, and not what decides where the character goes. */
    var rotation: Float = 0f

    /** [rotation]'s rate of change, carried by the facing spring. */
    var angularVelocity: Float = 0f

    /** The intermediate facing, used only when `rotationDoubleSpring` is on. */
    var immediateRotation: Float = 0f

    /** The intermediate facing's rate of change, used only when `rotationDoubleSpring` is on. */
    var immediateAngularVelocity: Float = 0f

    /** How fast the character is actually moving. */
    val speed: Float get() = velocity.length()

    /** The unit vector [rotation] is pointing along. */
    val heading: Vector2f get() = Vector2f(cos(rotation), sin(rotation))

    /**
     * Advances the character by [dt] seconds toward [desiredVelocity].
     *
     * [desiredVelocity] is an instruction, not a value to be reached exactly: it carries both a
     * direction and a speed, so a half-deflected stick should be a shorter vector and not a slower
     * approach to a long one. A zero vector is a request to stop.
     *
     * [desiredRotation] defaults to facing along [desiredVelocity], holding the current facing while
     * stopped, which is what a top-down character usually wants. Pass it explicitly for anything that
     * aims independently of where it is going.
     */
    fun update(
        desiredVelocity: Vector2fc,
        dt: Float,
        params: MovementParams = MovementParams.DEFAULT,
        desiredRotation: Float = if (desiredVelocity.lengthSquared() > 1e-16f)
            atan2(desiredVelocity.y(), desiredVelocity.x()) else rotation,
    ) {
        val desiredSpeed = desiredVelocity.length()

        // Rotate the current direction of travel toward the requested one. This is what makes a turn
        // sharp: without it the direction changes only as a by-product of accelerating at the error.
        if (params.turnStrength > 1e-8f && desiredSpeed > 1e-8f) {
            immediateVelocity = Springs.damper(
                immediateVelocity,
                direction(desiredVelocity) * immediateVelocity.length(),
                1f / params.turnStrength,
                dt,
            )
        }

        val velocityError = desiredVelocity - immediateVelocity
        val errorMagnitude = velocityError.length()

        // Speeding up and slowing down get their own rates: a stop can be much more abrupt than a
        // start without looking wrong.
        val isAccelerating = desiredSpeed + 1e-4f > immediateVelocity.length()

        val lateralRate =
            if (isAccelerating) (1f - params.directionalAcceleration) * params.acceleration
            else params.deceleration
        val directionalRate =
            if (isAccelerating) params.directionalAcceleration * params.acceleration else 0f

        // Close the remaining error exactly if a full step would overshoot it, otherwise move at the
        // allowed rate. This is the clamp that gives the model its weight. (`* (1 / dt)` rather than
        // `/ dt`: Vector2f's div operator is the in-place one, and would scale the error vector that
        // the rest of this function still needs.)
        val lateral =
            if (errorMagnitude < lateralRate * dt) velocityError * (1f / dt)
            else direction(velocityError) * lateralRate
        val directional = direction(desiredVelocity) * directionalRate
        val rate = lateral + directional

        val smoothingHalflife =
            if (isAccelerating) params.accelerationHalflife else params.decelerationHalflife
        val compensation =
            if (isAccelerating) params.accelerationCompensation else params.decelerationCompensation

        // Aim the smoothing spring at where the unsmoothed velocity will be once the spring's own lag
        // has passed, so the smoothing costs shape without costing responsiveness.
        val lookahead = dt + compensation * Springs.halflifeToLag(smoothingHalflife)

        // Extrapolating past the desired velocity would be worse than arriving at it, so stop there.
        val errorSquared = velocityError.dot(velocityError)
        var next =
            if (velocityError.dot(rate * dt) < errorSquared) immediateVelocity + rate * dt
            else Vector2f(desiredVelocity)
        var tracked =
            if (velocityError.dot(rate * lookahead) < errorSquared) immediateVelocity + rate * lookahead
            else Vector2f(desiredVelocity)

        // Neither the step nor the lookahead may invent speed the character was not already asking
        // for: without this the directional and lateral terms together can exceed the input.
        val speedCap = max(immediateVelocity.length(), desiredSpeed)
        if (next.length() > speedCap) next = direction(next) * speedCap
        if (tracked.length() > speedCap) tracked = direction(tracked) * speedCap

        val sprung = Springs.spring(velocity, acceleration, tracked, smoothingHalflife, dt)
        velocity = sprung.x
        acceleration = sprung.v

        immediateVelocity = next
        position = position + velocity * dt

        if (params.rotationDoubleSpring) {
            val turned = Springs.doubleSpringAngle(
                rotation,
                angularVelocity,
                immediateRotation,
                immediateAngularVelocity,
                desiredRotation,
                params.rotationHalflife,
                dt,
            )
            rotation = turned.x
            angularVelocity = turned.v
            immediateRotation = turned.intermediateX
            immediateAngularVelocity = turned.intermediateV
        } else {
            val turned =
                Springs.springAngle(rotation, angularVelocity, desiredRotation, params.rotationHalflife, dt)
            rotation = turned.x
            angularVelocity = turned.v
        }
    }

    /** Teleports the character, leaving it at rest and facing [facing]. */
    fun placeAt(position: Vector2fc, facing: Float = rotation) {
        this.position = Vector2f(position)
        velocity = Vector2f()
        acceleration = Vector2f()
        immediateVelocity = Vector2f()
        rotation = facing
        angularVelocity = 0f
        immediateRotation = facing
        immediateAngularVelocity = 0f
    }

    /** The unit vector along [v], or zero when there is no direction to speak of. */
    private fun direction(v: Vector2fc): Vector2f {
        val length = v.length()
        return if (length > 1e-8f) Vector2f(v.x() / length, v.y() / length) else Vector2f()
    }
}
