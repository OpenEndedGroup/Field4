package afield.stdlib

import afield.stdlib.linalg.Vector2f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MovementTest {

    private val dt = 1f / 60f

    @Test
    fun aSpringStepIsExactWhateverTheStepSize() {
        val inOneGo = Springs.spring(0f, 0f, 1f, 0.2f, 0.5f)

        var x = 0f
        var v = 0f
        repeat(50) {
            val stepped = Springs.spring(x, v, 1f, 0.2f, 0.01f)
            x = stepped.x
            v = stepped.v
        }

        assertEquals(inOneGo.x, x, 1e-4f, "fifty small steps must land where one big one does")
        assertEquals(inOneGo.v, v, 1e-3f)
    }

    @Test
    fun aDamperClosesHalfTheDistanceInAHalfLife() {
        assertEquals(0.5f, Springs.damper(0f, 1f, 0.25f, 0.25f), 1e-3f)
        assertEquals(0.75f, Springs.damper(0f, 1f, 0.25f, 0.5f), 1e-3f)
        assertEquals(0.875f, Springs.damper(0f, 1f, 0.25f, 0.75f), 1e-3f)
    }

    @Test
    fun aCriticallyDampedSpringNeverOvershoots() {
        var x = 0f
        var v = 0f
        repeat(240) {
            val stepped = Springs.spring(x, v, 1f, 0.1f, dt)
            x = stepped.x
            v = stepped.v
            assertTrue(x <= 1f + 1e-6f, "overshot to $x")
        }
        assertEquals(1f, x, 1e-4f, "and still arrives")
    }

    @Test
    fun anAngleSpringTakesTheShortWayRound() {
        // 3 radians to -3 is 0.283 radians onward through π, not 6 radians back through zero.
        var x = 3f
        var v = 0f
        var turned = 0f
        repeat(60) {
            val stepped = Springs.springAngle(x, v, -3f, 0.1f, dt)
            val increment = Springs.wrapAngle(stepped.x - x)
            assertTrue(increment >= -1e-6f, "turned back on itself by $increment")
            turned += increment
            x = stepped.x
            v = stepped.v
        }

        val shortWay = Springs.wrapAngle(-3f - 3f)
        assertEquals(shortWay, turned, 1e-3f, "should have turned the short way and no further")
        assertEquals(-3f, Springs.wrapAngle(x), 1e-4f)
    }

    @Test
    fun wrappingAnAngleLandsInTheHalfOpenTurn() {
        val twoPi = (2.0 * PI).toFloat()
        assertEquals(0f, Springs.wrapAngle(twoPi), 1e-5f)
        assertEquals(0.5f, Springs.wrapAngle(0.5f + twoPi), 1e-5f)
        assertEquals(-0.5f, Springs.wrapAngle(-0.5f - twoPi), 1e-5f)
        assertTrue(Springs.wrapAngle(PI.toFloat()) > 0f, "π wraps to itself, not to -π")
    }

    @Test
    fun aDoubleSpringStartsMoreGentlyThanASingleOne() {
        val single = Springs.springAngle(0f, 0f, 1f, 0.2f, dt)
        val double = Springs.doubleSpringAngle(0f, 0f, 0f, 0f, 1f, 0.2f, dt)

        assertTrue(
            abs(double.v) < abs(single.v),
            "a double spring should not flick off the mark: ${double.v} vs ${single.v}"
        )
    }

    @Test
    fun aimingALagAheadOfAMovingGoalCancelsTheLagExactly() {
        val halflife = 0.2f
        val goalSpeed = 3f

        fun trailingAfterTenSeconds(compensation: Float): Float {
            var x = 0f
            var v = 0f
            var goal = 0f
            repeat(600) {
                goal += goalSpeed * dt
                val aimed = goal + compensation * goalSpeed * Springs.halflifeToLag(halflife)
                val stepped = Springs.spring(x, v, aimed, halflife, dt)
                x = stepped.x
                v = stepped.v
            }
            return goal - x
        }

        // Uncompensated, a spring settles exactly halflifeToLag seconds' worth of travel behind.
        assertEquals(
            goalSpeed * Springs.halflifeToLag(halflife),
            trailingAfterTenSeconds(0f),
            goalSpeed * dt,
            "the steady-state lag should be the one halflifeToLag predicts"
        )
        assertEquals(0f, trailingAfterTenSeconds(1f), goalSpeed * dt, "and compensation should cancel it")
    }

    @Test
    fun aCharacterAcceleratesToTheVelocityItIsAskedFor() {
        val character = MovementState()
        val desired = Vector2f(400f, 0f)

        repeat(120) { character.update(desired, dt) }

        assertEquals(400f, character.velocity.x, 1f)
        assertEquals(0f, character.velocity.y, 1e-3f)
        assertTrue(character.position.x > 0f, "and has covered ground")
    }

    @Test
    fun aCharacterNeverMovesFasterThanItWasAskedTo() {
        val character = MovementState()
        val desired = Vector2f(400f, 0f)

        repeat(240) {
            character.update(desired, dt)
            assertTrue(character.speed < 400f * 1.001f, "ran away to ${character.speed}")
        }
    }

    @Test
    fun aCharacterStopsWhenAskedTo() {
        val character = MovementState()
        repeat(120) { character.update(Vector2f(400f, 0f), dt) }

        repeat(120) { character.update(Vector2f(), dt) }
        val restingAt = Vector2f(character.position)

        assertTrue(character.speed < 1f, "still drifting at ${character.speed}")
        repeat(60) { character.update(Vector2f(), dt) }
        assertEquals(restingAt.x, character.position.x, 0.5f, "and stays where it stopped")
    }

    @Test
    fun accelerationIsCappedAtTheRateItIsGiven() {
        val params = MovementParams(acceleration = 800f, turnStrength = 0f)
        val character = MovementState()
        val desired = Vector2f(400f, 0f)

        var previous = 0f
        repeat(60) {
            character.update(desired, dt, params)
            val speed = character.immediateVelocity.length()
            assertTrue(
                speed - previous < params.acceleration * dt * 1.001f,
                "gained ${speed - previous} in one step, cap is ${params.acceleration * dt}"
            )
            previous = speed
        }
    }

    @Test
    fun compensationBuysBackTheLagThatSmoothingCosts() {
        fun framesToReachFullSpeed(compensation: Float): Int {
            val params = MovementParams(accelerationCompensation = compensation)
            val character = MovementState()
            val desired = Vector2f(400f, 0f)
            for (frame in 1..600) {
                character.update(desired, dt, params)
                if (character.speed > 0.9f * 400f) return frame
            }
            return Int.MAX_VALUE
        }

        val compensated = framesToReachFullSpeed(1f)
        val uncompensated = framesToReachFullSpeed(0f)

        assertTrue(
            compensated < uncompensated,
            "compensation should arrive sooner: $compensated vs $uncompensated frames"
        )
    }

    /** How far round a right-angle turn the character gets in a third of a second, and its low speed. */
    private class Turn(val degrees: Float, val slowestSpeed: Float)

    private fun quarterTurn(directionalAcceleration: Float, turnStrength: Float): Turn {
        val params = MovementParams(
            directionalAcceleration = directionalAcceleration,
            turnStrength = turnStrength,
        )
        val character = MovementState()
        repeat(120) { character.update(Vector2f(400f, 0f), dt, params) }

        var slowest = character.speed
        repeat(20) {
            character.update(Vector2f(0f, 400f), dt, params)
            slowest = minOf(slowest, character.speed)
        }
        val degrees = Math.toDegrees(atan2(character.velocity.y, character.velocity.x).toDouble())
        return Turn(degrees.toFloat(), slowest)
    }

    @Test
    fun turnStrengthMakesTheTurnSharper() {
        val byStrength = listOf(0f, 1f, 4f, 16f).map { quarterTurn(0f, it).degrees }

        assertEquals(
            byStrength.sorted(),
            byStrength,
            "more turn strength should get further round the corner: $byStrength"
        )
        assertTrue(
            byStrength.last() - byStrength.first() > 20f,
            "and the difference should be worth having: $byStrength"
        )

        // It is the only thing that turns a character whose acceleration only points along the input.
        val directional = listOf(0f, 4f, 16f).map { quarterTurn(1f, it).degrees }
        assertEquals(directional.sorted(), directional, "and it is what turns an airboat: $directional")
        assertTrue(directional.last() - directional.first() > 30f, "$directional")
    }

    @Test
    fun directionalAccelerationIsWhatKeepsSpeedThroughATurn() {
        val drifting = quarterTurn(directionalAcceleration = 1f, turnStrength = 1f).slowestSpeed
        val braking = quarterTurn(directionalAcceleration = 0f, turnStrength = 1f).slowestSpeed

        assertTrue(
            drifting > braking + 50f,
            "the airboat should hold its speed better than the quad-copter: $drifting vs $braking"
        )
    }

    @Test
    fun facingFollowsTheDirectionOfTravelByDefault() {
        val character = MovementState()

        repeat(120) { character.update(Vector2f(0f, 300f), dt) }

        assertEquals((PI / 2).toFloat(), Springs.wrapAngle(character.rotation), 1e-2f)

        // Stopping holds the heading rather than snapping it back to zero.
        repeat(120) { character.update(Vector2f(), dt) }
        assertEquals((PI / 2).toFloat(), Springs.wrapAngle(character.rotation), 1e-2f)
    }

    @Test
    fun placeAtTeleportsAndLeavesTheCharacterAtRest() {
        val character = MovementState()
        repeat(60) { character.update(Vector2f(400f, 0f), dt) }

        character.placeAt(Vector2f(10f, 20f), facing = 1f)

        assertEquals(10f, character.position.x, 0f)
        assertEquals(20f, character.position.y, 0f)
        assertEquals(0f, character.speed, 0f)
        assertEquals(1f, character.rotation, 0f)
        assertEquals(0f, character.angularVelocity, 0f)
    }
}
