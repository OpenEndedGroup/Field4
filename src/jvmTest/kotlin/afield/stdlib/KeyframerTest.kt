package afield.stdlib

import afield.four.buffers.Structure
import afield.four.util.CameraPose
import afield.stdlib.linalg.DualQuaternionf
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector4f
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first tests for `keyframer.kt` — snapshotting, blending and persisting the live state of a
 * sketch.
 *
 * Written alongside `public/docs/keyframer_examples.md`, on the model of [SpatialGeometryTest]:
 * every number and every universally-quantified claim the page makes is asserted here, so the page
 * cannot rot silently. Where a claim is about interpolation it is checked at **at least three
 * points** — both endpoints and a midpoint — and, for the rotations, against an *independently
 * derived* oracle rather than against a second call to the function under test.
 *
 * The file needs no GPU. `afield.four.buffers.Structure` is constructible bare
 * (`Structure.kt:112`) and its `bind`/`bindings` (`Structure.kt:176`, `:136`) are plain map
 * operations, so the whole `target` → `capture()` → apply loop runs headlessly. The one part that
 * genuinely cannot be reached from a unit test is [KeyframeScope.camera], because
 * `Camera(val renderer: Renderer = fromContext())` (`Camera.kt:16`) requires a live renderer — so
 * the camera tests here exercise `CameraPose` itself, which is the whole of the maths.
 *
 * Two tests pin down behaviour that the KDoc did not mention and that the page now warns about:
 *
 *  - [captureSeesBoundPropertiesAndNotSetValues] — `capture()` reads a `Structure`'s *bindings*
 *    only. A property assigned by value (`structure["radius"] = 3f`) is invisible to it, and
 *    silently captures as null.
 *  - [updateCodeEscapesDollarInASourceBlock] — a regression for a defect this page's research
 *    turned up: `kotlinRawStringLiteral` emitted a Kotlin *raw* string for a source block, guarding
 *    against `"""` and a trailing quote but not against `$`, which a raw string still interpolates.
 */
class KeyframerTest {

    private fun tempDir(): Path = Files.createTempDirectory("keyframer-test")

    // ==========================================================================================
    // Targets, capture and apply
    // ==========================================================================================

    @Test
    fun captureThenApplyRoundTripsAStructureBinding() {
        val scope = KeyframeScope()
        val structure = Structure()
        structure.bind("radius") { 3.0f }
        scope.target("radius", structure)

        val captured = scope.capture()
        assertEquals(3.0f, captured["radius"], "capture reads the bound value")

        // Move the live structure somewhere else, then put the snapshot back.
        structure.bind("radius") { 99.0f }
        assertEquals(99.0f, structure.bindings["radius"]?.invoke())

        captured()
        assertEquals(3.0f, structure.bindings["radius"]?.invoke(), "invoking a Key writes it back")
    }

    @Test
    fun anAtQualifiedNameDrivesThePropertyAfterTheLastAt() {
        // "glow@radius" and "blur@radius" both drive a `radius` uniform, on different structures.
        val scope = KeyframeScope()
        val glow = Structure()
        val blur = Structure()
        glow.bind("radius") { 1.0f }
        blur.bind("radius") { 2.0f }
        scope.target("glow@radius", glow)
        scope.target("blur@radius", blur)

        val key = scope.capture()
        assertEquals(1.0f, key["glow@radius"])
        assertEquals(2.0f, key["blur@radius"])

        key["glow@radius"] = 10.0f
        key["blur@radius"] = 20.0f
        key()

        // The `@`-prefix is stripped: each structure holds a plain `radius`, not the qualified name.
        assertEquals(10.0f, glow.bindings["radius"]?.invoke())
        assertEquals(20.0f, blur.bindings["radius"]?.invoke())
        assertNull(glow.bindings["glow@radius"], "the qualified name is not what lands on the structure")
    }

    @Test
    fun captureSeesBoundPropertiesAndNotSetValues() {
        // Undocumented until this page: capture() reads Structure.bindings, never Structure.values.
        // A property written by value rather than bound is invisible to a snapshot.
        val scope = KeyframeScope()
        val structure = Structure()
        structure.bind("bound") { 1.0f }
        structure["set"] = 2.0f
        scope.target("bound", structure)
        scope.target("set", structure)

        val key = scope.capture()
        assertEquals(1.0f, key["bound"], "a bound property is captured")
        assertNull(key["set"], "a set-by-value property captures as null, with no warning")
        assertTrue("set" in key, "…but the name is still present in the key")
    }

    @Test
    fun aLazyTargetThatResolvesNullCapturesNullRatherThanFailing() {
        val scope = KeyframeScope()
        var live: Structure? = null
        scope.target("gain") { live }

        assertNull(scope.capture()["gain"], "an unresolvable target contributes null")

        live = Structure().also { it.bind("gain") { 0.5f } }
        assertEquals(0.5f, scope.capture()["gain"], "…and starts working once it resolves")
    }

    @Test
    fun registeringANameTwiceReplacesTheEarlierTargetEitherWay() {
        val scope = KeyframeScope()
        val first = Structure().also { it.bind("gain") { 1.0f } }
        val second = Structure().also { it.bind("gain") { 2.0f } }

        scope.target("gain", first)
        assertEquals(1.0f, scope.capture()["gain"])

        // static -> dynamic
        scope.target("gain") { second }
        assertEquals(2.0f, scope.capture()["gain"])
        assertTrue("gain" !in scope.targets, "the static registration is removed")

        // dynamic -> static
        scope.target("gain", first)
        assertEquals(1.0f, scope.capture()["gain"])
    }

    @Test
    fun applyingAKeySkipsNamesWithNoTarget() {
        // What makes an older key file safe to apply to a scope that has been rearranged.
        val scope = KeyframeScope()
        val structure = Structure().also { it.bind("kept") { 0f } }
        scope.target("kept", structure)

        val key = scope.Key(mutableMapOf("kept" to 5.0f, "goneAway" to 7.0f))
        key() // must not throw

        assertEquals(5.0f, structure.bindings["kept"]?.invoke())
        assertNull(structure.bindings["goneAway"])
    }

    @Test
    fun findByNameFindsStructureTargetsOnly() {
        val scope = KeyframeScope()
        scope.target("gain", Structure())
        assertTrue(scope.findByName("gain") != null, "a structure target has a bind function")
        assertNull(scope.findByName("nope"), "an unregistered name has none")
    }

    // ==========================================================================================
    // Key algebra
    // ==========================================================================================

    @Test
    fun keyGetSetContainsPutAndRemoveBehaveAsDocumented() {
        val scope = KeyframeScope()
        val key = scope.Key(mutableMapOf("a" to 1))

        assertEquals(1, key["a"])
        assertTrue("a" in key)
        assertTrue("b" !in key)

        key["b"] = 2
        assertEquals(2, key["b"])

        assertTrue(key.put("c", 3) === key, "put is chainable and returns the same key")
        assertTrue(key.putAll("d" to 4, "e" to 5) === key)
        assertEquals(setOf("a", "b", "c", "d", "e"), key.values.keys)

        assertEquals(3, key.remove("c"))
        assertTrue("c" !in key)

        // A name mapped to null still counts as present.
        key["f"] = null
        assertTrue("f" in key)
        assertNull(key["f"])
    }

    @Test
    fun copyIsAShallowCopyOfTheMap() {
        val scope = KeyframeScope()
        val vector = Vector3f(1f, 2f, 3f)
        val original = scope.Key(mutableMapOf("pos" to vector, "gain" to 1.0))
        val duplicate = original.copy()

        duplicate["gain"] = 2.0
        assertEquals(1.0, original["gain"], "the maps are independent")

        // …but shallow: both go on referencing the same Vector3f.
        (duplicate["pos"] as Vector3f).set(9f, 9f, 9f)
        assertEquals(9f, (original["pos"] as Vector3f).x, "mutating a value in place is visible through both")
    }

    @Test
    fun assigningAStringOverACodeValueKeepsTheWrapper() {
        val scope = KeyframeScope()
        val key = scope.Key(
            mutableMapOf(
                "voice" to stackSLString("saw 110 *"),
                "block" to SourceBackedBlock<Any?>("box-1", "1 + 1")
            )
        )

        key["voice"] = "sin 220 *"
        val voice = key["voice"]
        assertTrue(voice is StackSLString, "a String over a StackSLString re-wraps rather than degrading")
        assertEquals("sin 220 *", voice.value)

        key["block"] = "2 + 2"
        val block = key["block"]
        assertTrue(block is SourceBackedBlock<*>, "a String over a source block re-wraps too")
        assertEquals("2 + 2", block.source)
        assertEquals("box-1", block.boxId, "…keeping the original box")

        // No other coercion happens: a Double over an Int really does change the type.
        key["n"] = 1
        key["n"] = 1.0
        assertTrue(key["n"] is Double)
    }

    @Test
    fun updateEditsInPlaceAndUpdateAndApplyAlsoWritesBack() {
        val scope = KeyframeScope()
        val structure = Structure().also { it.bind("gain") { 0f } }
        scope.target("gain", structure)
        val key = scope.capture()

        val same = key.update { this["gain"] = 1.0f }
        assertTrue(same === key, "update returns the same key, edited")
        assertEquals(0f, structure.bindings["gain"]?.invoke(), "update alone touches nothing live")

        key.updateAndApply { this["gain"] = 2.0f }
        assertEquals(2.0f, structure.bindings["gain"]?.invoke(), "updateAndApply writes back")
    }

    // ==========================================================================================
    // interpolate — numbers, vectors, code
    // ==========================================================================================

    @Test
    fun numbersLerpAtBothEndpointsAndTheMidpoint() {
        val scope = KeyframeScope()
        val a = scope.Key(mutableMapOf("gain" to 0.0))
        val b = scope.Key(mutableMapOf("gain" to 10.0))

        assertEquals(0.0, scope.interpolate(a, b, 0.0)["gain"] as Double, 1e-12)
        assertEquals(2.5, scope.interpolate(a, b, 0.25)["gain"] as Double, 1e-12)
        assertEquals(5.0, scope.interpolate(a, b, 0.5)["gain"] as Double, 1e-12)
        assertEquals(7.5, scope.interpolate(a, b, 0.75)["gain"] as Double, 1e-12)
        assertEquals(10.0, scope.interpolate(a, b, 1.0)["gain"] as Double, 1e-12)
    }

    @Test
    fun tIsNotClampedSoInterpolationExtrapolates() {
        val scope = KeyframeScope()
        val a = scope.Key(mutableMapOf("gain" to 0.0))
        val b = scope.Key(mutableMapOf("gain" to 10.0))

        assertEquals(-10.0, scope.interpolate(a, b, -1.0)["gain"] as Double, 1e-12)
        assertEquals(20.0, scope.interpolate(a, b, 2.0)["gain"] as Double, 1e-12)
    }

    @Test
    fun aFloatPairStaysFloatAndAnyOtherNumericPairBecomesDouble() {
        val scope = KeyframeScope()

        val floats = scope.interpolate(
            scope.Key(mutableMapOf("v" to 0.0f)),
            scope.Key(mutableMapOf("v" to 1.0f)),
            0.5
        )["v"]
        assertTrue(floats is Float, "Float + Float stays Float")
        assertEquals(0.5f, floats)

        // Two Int keyframes yield a Double — which an Int uniform will not accept.
        val ints = scope.interpolate(
            scope.Key(mutableMapOf("v" to 0)),
            scope.Key(mutableMapOf("v" to 10)),
            0.5
        )["v"]
        assertTrue(ints is Double, "Int + Int becomes Double, not Int")
        assertEquals(5.0, ints)

        // Mixed Float/Double is Double too.
        val mixed = scope.interpolate(
            scope.Key(mutableMapOf("v" to 0.0f)),
            scope.Key(mutableMapOf("v" to 1.0)),
            0.5
        )["v"]
        assertTrue(mixed is Double, "Float + Double becomes Double")
    }

    @Test
    fun vectorsLerpComponentwiseAtThreePoints() {
        val scope = KeyframeScope()
        val a = scope.Key(
            mutableMapOf(
                "v2" to Vector2f(0f, 0f),
                "v3" to Vector3f(0f, 0f, 0f),
                "v4" to Vector4f(0f, 0f, 0f, 0f)
            )
        )
        val b = scope.Key(
            mutableMapOf(
                "v2" to Vector2f(2f, 4f),
                "v3" to Vector3f(2f, 4f, 6f),
                "v4" to Vector4f(2f, 4f, 6f, 8f)
            )
        )

        for ((t, scale) in listOf(0.0 to 0f, 0.5 to 0.5f, 1.0 to 1f)) {
            val out = scope.interpolate(a, b, t)
            val v2 = out["v2"] as Vector2f
            val v3 = out["v3"] as Vector3f
            val v4 = out["v4"] as Vector4f
            assertEquals(2f * scale, v2.x, 1e-6f, "v2.x at t=$t")
            assertEquals(4f * scale, v2.y, 1e-6f, "v2.y at t=$t")
            assertEquals(6f * scale, v3.z, 1e-6f, "v3.z at t=$t")
            assertEquals(8f * scale, v4.w, 1e-6f, "v4.w at t=$t")
        }

        // The inputs are not mutated — interpolate returns a fresh key of fresh vectors.
        assertEquals(0f, (a["v3"] as Vector3f).x)
        assertEquals(2f, (b["v3"] as Vector3f).x)
    }

    @Test
    fun codeValuesStepOnceAtTOneAndNeverBlend() {
        val scope = KeyframeScope()
        val a = scope.Key(
            mutableMapOf(
                "voice" to stackSLString("saw 110 *"),
                "label" to "before",
                "block" to SourceBackedBlock<Any?>("box-1", "1")
            )
        )
        val b = scope.Key(
            mutableMapOf(
                "voice" to stackSLString("sin 220 *"),
                "label" to "after",
                "block" to SourceBackedBlock<Any?>("box-1", "2")
            )
        )

        // Everywhere below 1 it is still the left value — checked at several points, not just one.
        for (t in listOf(0.0, 0.25, 0.5, 0.75, 0.999)) {
            val out = scope.interpolate(a, b, t)
            assertEquals(a["voice"], out["voice"], "StackSLString still left at t=$t")
            assertEquals("before", out["label"], "String still left at t=$t")
            assertTrue(out["block"] === a["block"], "source block still left at t=$t")
        }
        for (t in listOf(1.0, 1.5)) {
            val out = scope.interpolate(a, b, t)
            assertEquals(b["voice"], out["voice"], "StackSLString stepped at t=$t")
            assertEquals("after", out["label"], "String stepped at t=$t")
            assertTrue(out["block"] === b["block"], "source block stepped at t=$t")
        }
    }

    @Test
    fun unblendableEntriesAreSimplyAbsentFromTheResult() {
        val scope = KeyframeScope()
        val a = scope.Key(mutableMapOf("onlyA" to 1.0, "nullA" to null, "both" to 1.0, "mixed" to 1.0))
        val b = scope.Key(mutableMapOf("onlyB" to 1.0, "nullA" to 1.0, "both" to 2.0, "mixed" to "text"))

        val out = scope.interpolate(a, b, 0.5)
        assertEquals(setOf("both"), out.values.keys, "only the one blendable pair survives")
        assertEquals(1.5, out["both"])
    }

    @Test
    fun remapRetimesOneKeyAndLeavesTheRestLinear() {
        val scope = KeyframeScope()
        val a = scope.Key(mutableMapOf("eased" to 0.0, "linear" to 0.0))
        val b = scope.Key(mutableMapOf("eased" to 1.0, "linear" to 1.0))
        scope.remap["eased"] = { t -> t * t * (3.0 - 2.0 * t) } // smoothstep

        // Endpoints agree, the middle does not — smoothstep(0)=0, (0.5)=0.5, (1)=1, (0.25)=0.15625.
        assertEquals(0.0, scope.interpolate(a, b, 0.0)["eased"] as Double, 1e-12)
        assertEquals(0.15625, scope.interpolate(a, b, 0.25)["eased"] as Double, 1e-12)
        assertEquals(0.5, scope.interpolate(a, b, 0.5)["eased"] as Double, 1e-12)
        assertEquals(0.84375, scope.interpolate(a, b, 0.75)["eased"] as Double, 1e-12)
        assertEquals(1.0, scope.interpolate(a, b, 1.0)["eased"] as Double, 1e-12)

        assertEquals(0.25, scope.interpolate(a, b, 0.25)["linear"] as Double, 1e-12)
        assertEquals(0.75, scope.interpolate(a, b, 0.75)["linear"] as Double, 1e-12)
    }

    @Test
    fun remapCanMakeACodeValueSwitchBeforeTheEnd() {
        // Reaching t >= 1 early is precisely how a code value is made to switch mid-transition.
        val scope = KeyframeScope()
        val a = scope.Key(mutableMapOf("voice" to stackSLString("a")))
        val b = scope.Key(mutableMapOf("voice" to stackSLString("b")))
        scope.remap["voice"] = { t -> t * 2.0 } // hits 1.0 at t = 0.5

        assertEquals(a["voice"], scope.interpolate(a, b, 0.49)["voice"])
        assertEquals(b["voice"], scope.interpolate(a, b, 0.50)["voice"])
    }

    // ==========================================================================================
    // Rotations — checked against independently derived oracles
    // ==========================================================================================

    /** The rotation angle between two unit quaternions, computed from the dot product alone. */
    private fun angleBetween(a: Quaternionf, b: Quaternionf): Double {
        val d = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w).toDouble()
        return 2.0 * acos(d.coerceIn(-1.0, 1.0))
    }

    @Test
    fun quaternionsSlerpAtConstantAngularRate() {
        val scope = KeyframeScope()
        // 20 degrees and 100 degrees about the same (non-axis-aligned) axis.
        val axis = Vector3f(1f, 2f, 3f)
        fun about(degrees: Double) =
            Quaternionf().setAngleAxis(Math.toRadians(degrees).toFloat(), axis.x, axis.y, axis.z)

        val qa = about(20.0)
        val qb = about(100.0)
        val a = scope.Key(mutableMapOf("q" to qa))
        val b = scope.Key(mutableMapOf("q" to qb))

        // Oracle 1: rotation about a shared axis is linear in the angle, so slerp(t) must equal the
        // quaternion built directly at the interpolated angle. Built with setAngleAxis, not slerp.
        for ((t, degrees) in listOf(0.0 to 20.0, 0.25 to 40.0, 0.5 to 60.0, 0.75 to 80.0, 1.0 to 100.0)) {
            val got = scope.interpolate(a, b, t)["q"] as Quaternionf
            val want = about(degrees)
            assertEquals(want.x, got.x, 1e-5f, "x at t=$t")
            assertEquals(want.y, got.y, 1e-5f, "y at t=$t")
            assertEquals(want.z, got.z, 1e-5f, "z at t=$t")
            assertEquals(want.w, got.w, 1e-5f, "w at t=$t")
        }

        // Oracle 2, independent of the axis trick: the angle from the start grows exactly linearly.
        val total = angleBetween(qa, qb)
        assertEquals(Math.toRadians(80.0), total, 1e-5, "the pair is 80 degrees apart")
        for (t in listOf(0.25, 0.5, 0.75)) {
            val got = scope.interpolate(a, b, t)["q"] as Quaternionf
            assertEquals(total * t, angleBetween(qa, got), 1e-5, "angular rate is constant at t=$t")
        }
    }

    @Test
    fun slerpKeepsTheQuaternionNormalised() {
        val scope = KeyframeScope()
        val qa = Quaternionf().setAngleAxis(0.2f, 1f, 0f, 0f)
        val qb = Quaternionf().setAngleAxis(2.7f, 0f, 1f, 0.3f)
        val a = scope.Key(mutableMapOf("q" to qa))
        val b = scope.Key(mutableMapOf("q" to qb))

        for (t in listOf(0.0, 0.2, 0.5, 0.8, 1.0)) {
            val q = scope.interpolate(a, b, t)["q"] as Quaternionf
            val n = sqrt((q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w).toDouble())
            assertEquals(1.0, n, 1e-5, "unit length at t=$t")
        }
    }

    @Test
    fun aDualQuaternionSclerpIsAScrewMotionNotAStraightLine() {
        // The claim the docs page's camera figure rests on: sclerp between two rigid transforms
        // sweeps a helical (screw) path, so its midpoint is NOT the midpoint of the straight line
        // between the two positions.
        val scope = KeyframeScope()
        val start = DualQuaternionf(Quaternionf(), Vector3f(6f, 0f, 0f))
        val end = DualQuaternionf(
            Quaternionf().setAngleAxis(Math.toRadians(90.0).toFloat(), 0f, 1f, 0f),
            Vector3f(0f, 0f, 6f)
        )
        val a = scope.Key(mutableMapOf("t" to start))
        val b = scope.Key(mutableMapOf("t" to end))

        // Endpoints are exact.
        val at0 = (scope.interpolate(a, b, 0.0)["t"] as DualQuaternionf).getTranslation()
        val at1 = (scope.interpolate(a, b, 1.0)["t"] as DualQuaternionf).getTranslation()
        assertEquals(6f, at0.x, 1e-4f)
        assertEquals(0f, at0.z, 1e-4f)
        assertEquals(0f, at1.x, 1e-4f)
        assertEquals(6f, at1.z, 1e-4f)

        // The midpoint bulges away from the chord. The straight-line midpoint is (3, 0, 3).
        val mid = (scope.interpolate(a, b, 0.5)["t"] as DualQuaternionf).getTranslation()
        val chordMidX = 3f
        val chordMidZ = 3f
        val deviation = sqrt(
            ((mid.x - chordMidX) * (mid.x - chordMidX) + (mid.z - chordMidZ) * (mid.z - chordMidZ)).toDouble()
        )
        assertTrue(
            deviation > 0.5,
            "the sclerp midpoint must leave the chord; deviation was $deviation at $mid"
        )

        // …and the rotation halves cleanly: 90 degrees at the end, 45 at the middle.
        val midRot = (scope.interpolate(a, b, 0.5)["t"] as DualQuaternionf).getRotation()
        assertEquals(Math.toRadians(45.0), angleBetween(Quaternionf(), midRot), 1e-4)
        val endRot = (scope.interpolate(a, b, 1.0)["t"] as DualQuaternionf).getRotation()
        assertEquals(Math.toRadians(90.0), angleBetween(Quaternionf(), endRot), 1e-4)
    }

    @Test
    fun aCameraPoseSclerpsItsTransformAndLerpsItsFocusDistance() {
        val scope = KeyframeScope()
        val a = scope.Key(
            mutableMapOf(
                "camera" to CameraPose.lookAt(Vector3f(8f, 2f, 0f), Vector3f(0f, 0f, 0f))
            )
        )
        val b = scope.Key(
            mutableMapOf(
                "camera" to CameraPose.lookAt(Vector3f(0f, 2f, 8f), Vector3f(0f, 0f, 0f))
            )
        )
        val fa = (a["camera"] as CameraPose).focusDistance
        val fb = (b["camera"] as CameraPose).focusDistance

        for (t in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val pose = scope.interpolate(a, b, t)["camera"] as CameraPose
            assertEquals(
                (fa + (fb - fa) * t).toFloat(), pose.focusDistance, 1e-4f,
                "focus distance is a plain lerp at t=$t"
            )
        }

        // Endpoints reproduce the input positions.
        val p0 = (scope.interpolate(a, b, 0.0)["camera"] as CameraPose).position
        assertEquals(8f, p0.x, 1e-3f)
        assertEquals(0f, p0.z, 1e-3f)
        val p1 = (scope.interpolate(a, b, 1.0)["camera"] as CameraPose).position
        assertEquals(0f, p1.x, 1e-3f)
        assertEquals(8f, p1.z, 1e-3f)

        // Both endpoints look at the origin, and so does the midpoint — the screw motion keeps the
        // subject framed, which is the reason to use it for a camera move.
        for (t in listOf(0.0, 0.5, 1.0)) {
            val pose = scope.interpolate(a, b, t)["camera"] as CameraPose
            val target = pose.target
            assertTrue(
                target.length() < 0.6f,
                "the camera still points near the origin at t=$t (target was $target)"
            )
        }
    }

    // ==========================================================================================
    // KValue — the serial union
    // ==========================================================================================

    @Test
    fun everyNumericKindComesBackAsItsOwnType() {
        val samples: List<Any> = listOf(1.toByte(), 2.toShort(), 3, 4L, 5.0f, 6.0)
        val kinds = listOf(
            NumKind.BYTE, NumKind.SHORT, NumKind.INT, NumKind.LONG, NumKind.FLOAT, NumKind.DOUBLE
        )
        // Every one of the six NumKind values is covered — this is the universally-quantified claim.
        assertEquals(NumKind.entries.size, kinds.size, "all six NumKinds are exercised")

        samples.zip(kinds).forEach { (value, kind) ->
            val kv = KValue.fromAny(value)
            assertTrue(kv is KValue.Num, "$value is a Num")
            assertEquals(kind, kv.kind)
            val back = kv.toAny()
            assertEquals(value::class, back::class, "$value round-trips to its own type")
            assertEquals(value, back)
        }
    }

    @Test
    fun aLongBeyondTwoToTheFiftyThreeDoesNotSurviveTheRoundTrip() {
        // Every number is stored on the wire as a Double, so the mantissa runs out.
        val safe = (1L shl 53) - 1L
        assertEquals(safe, KValue.fromAny(safe)!!.toAny(), "just under 2^53 is exact")

        val lossy = (1L shl 53) + 1L
        assertNotEquals(lossy, KValue.fromAny(lossy)!!.toAny(), "just over 2^53 is not")
    }

    @Test
    fun fromAnyReturnsNullForNullAndForAnythingOutsideTheUnion() {
        assertNull(KValue.fromAny(null))
        assertNull(KValue.fromAny(true), "a Boolean is outside the union")
        assertNull(KValue.fromAny(listOf(1, 2, 3)), "so is a list")
        assertNull(KValue.fromAny(mapOf("a" to 1)))
        assertNull(KValue.fromAny(Any()))

        // A Number outside the six primitive kinds falls back to DOUBLE.
        val big = KValue.fromAny(java.math.BigDecimal("1.5"))
        assertTrue(big is KValue.Num)
        assertEquals(NumKind.DOUBLE, big.kind)
        assertEquals(1.5, big.value)
    }

    @Test
    fun aDualQuaternionIsRenormalisedOnLoadButAPlainQuaternionIsNot() {
        // A drifted rigid transform is a broken transform, so it is repaired; a plain quaternion may
        // be standing in for something that is not a rotation, so it is left exactly as saved.
        val drifted = Quaternionf(0f, 0f, 0f, 2f)
        val quatBack = KValue.fromAny(drifted)!!.toAny() as Quaternionf
        assertEquals(2f, quatBack.w, 1e-6f, "a plain quaternion keeps its length")

        val driftedDual = DualQuaternionf(Quaternionf(0f, 0f, 0f, 2f), Quaternionf(0f, 0f, 0f, 0f))
        val dualBack = KValue.fromAny(driftedDual)!!.toAny() as DualQuaternionf
        assertEquals(1f, dualBack.real.w, 1e-6f, "a dual quaternion is renormalised")

        val driftedPose = CameraPose(driftedDual, 5f)
        val poseBack = KValue.fromAny(driftedPose)!!.toAny() as CameraPose
        assertEquals(1f, poseBack.transform.real.w, 1e-6f, "so is a camera pose's transform")
        assertEquals(5f, poseBack.focusDistance, 1e-6f, "…and its focus distance is untouched")
    }

    @Test
    fun stackSlSourceReloadsAsCodeAndAPlainStringAsText() {
        val code = KValue.fromAny(stackSLString("saw 110 *"))
        assertTrue(code is KValue.StackSL)
        assertTrue(code.toAny() is StackSLString, "StackSL reloads as a StackSLString, not a String")

        val text = KValue.fromAny("saw 110 *")
        assertTrue(text is KValue.Str)
        assertTrue(text.toAny() is String)
    }

    // ==========================================================================================
    // Persistence
    // ==========================================================================================

    @Test
    fun savingAndLoadingIsByteIdenticalOnASecondSave() {
        val dir = tempDir()
        val scope = KeyframeScope()
        val key = scope.Key(
            mutableMapOf(
                "gain" to 0.5f,
                "count" to 7,
                "big" to 9L,
                "pos2" to Vector2f(1f, 2f),
                "pos3" to Vector3f(1f, 2f, 3f),
                "rgba" to Vector4f(0.1f, 0.2f, 0.3f, 1f),
                "spin" to Quaternionf().setAngleAxis(0.7f, 0f, 1f, 0f),
                "rigid" to DualQuaternionf(
                    Quaternionf().setAngleAxis(0.3f, 1f, 0f, 0f), Vector3f(1f, 2f, 3f)
                ),
                "camera" to CameraPose.lookAt(Vector3f(4f, 1f, 0f), Vector3f(0f, 0f, 0f)),
                "voice" to stackSLString("saw 110 *"),
                "label" to "a look",
                "block" to SourceBackedBlock<Any?>("box-7", "1 + 1")
            )
        )

        val first = dir.resolve("look.json")
        scope.saveKey(first, key)
        val firstText = first.readText()

        // serialize -> deserialize -> serialize must be byte-identical.
        val loaded = scope.loadKey(first)
        val second = dir.resolve("again.json")
        scope.saveKey(second, loaded)
        assertEquals(firstText, second.readText(), "a load/save round trip is byte-identical")

        // …and all twelve values survived.
        assertEquals(12, loaded.values.size, "every supported type made it back")
        assertEquals(0.5f, loaded["gain"])
        assertEquals(7, loaded["count"])
        assertEquals(9L, loaded["big"])
        assertTrue(loaded["voice"] is StackSLString)
        assertEquals("a look", loaded["label"])
        assertEquals("box-7", (loaded["block"] as SourceBackedBlock<*>).boxId)
    }

    @Test
    fun unsupportedValuesAndNullsAreDroppedFromASavedKeyInSilence() {
        val dir = tempDir()
        val scope = KeyframeScope()
        val key = scope.Key(
            mutableMapOf(
                "kept" to 1.0,
                "aNull" to null,
                "aBoolean" to true,
                "aList" to listOf(1, 2, 3)
            )
        )

        val path = dir.resolve("look.json")
        scope.saveKey(path, key)
        val loaded = scope.loadKey(path)

        assertEquals(setOf("kept"), loaded.values.keys, "three of the four entries were dropped")
        assertEquals(4, key.values.size, "…and nothing warned; the in-memory key is untouched")
    }

    @Test
    fun everySaveAppendsANumberedVersionAndLoadLatestFollowsIt() {
        val dir = tempDir()
        val scope = KeyframeScope()
        val path = dir.resolve("look.json")

        assertEquals(emptyList(), scope.keyVersions(path), "no history before the first save")

        scope.saveKey(path, scope.Key(mutableMapOf("gain" to 1.0)), note = "first")
        scope.saveKey(path, scope.Key(mutableMapOf("gain" to 2.0)), note = "second")
        scope.saveKey(path, scope.Key(mutableMapOf("gain" to 3.0)))

        val versions = scope.keyVersions(path)
        assertEquals(3, versions.size)
        assertEquals(listOf(0, 1, 2), versions.map { it.index }, "numbered from 0, oldest first")
        assertEquals(listOf("first", "second", null), versions.map { it.note })
        assertEquals(listOf("000000.json", "000001.json", "000002.json"), versions.map { it.file })
        assertTrue(versions.all { it.timestamp.isNotBlank() })

        assertEquals(1.0, scope.loadKeyVersion(path, 0)["gain"])
        assertEquals(2.0, scope.loadKeyVersion(path, 1)["gain"])
        assertEquals(3.0, scope.loadKeyVersion(path, 2)["gain"])
        assertEquals(3.0, scope.loadKey(path)["gain"], "the main file is the most recent save")
        assertEquals(3.0, scope.loadLatestKey(path)["gain"])

        assertFailsWith<IllegalArgumentException> { scope.loadKeyVersion(path, 99) }
    }

    @Test
    fun loadLatestPrefersTheHistoryOverAHandEditedMainFile() {
        val dir = tempDir()
        val scope = KeyframeScope()
        val path = dir.resolve("look.json")
        scope.saveKey(path, scope.Key(mutableMapOf("gain" to 1.0)))

        // Someone edits the main file behind the history's back.
        path.writeText("""{"schemaVersion":0,"values":{"gain":{"type":"num","kind":"DOUBLE","value":42.0}}}""")

        assertEquals(42.0, scope.loadKey(path)["gain"], "loadKey reads the file as it now stands")
        assertEquals(1.0, scope.loadLatestKey(path)["gain"], "loadLatestKey prefers what Field last wrote")
    }

    @Test
    fun loadLatestFallsBackToTheFileWhenThereIsNoHistory() {
        val dir = tempDir()
        val scope = KeyframeScope()
        val path = dir.resolve("hand-written.json")
        path.writeText("""{"schemaVersion":0,"values":{"gain":{"type":"num","kind":"DOUBLE","value":5.0}}}""")

        assertEquals(5.0, scope.loadLatestKey(path)["gain"])
    }

    @Test
    fun aCorruptManifestSilentlyRestartsNumberingAndOverwritesVersionZero() {
        // The trap the KDoc names: readKeyVersions swallows a parse failure, and the next save
        // numbers itself from an empty manifest.
        val dir = tempDir()
        val scope = KeyframeScope()
        val path = dir.resolve("look.json")

        scope.saveKey(path, scope.Key(mutableMapOf("gain" to 1.0)), note = "original")
        val versionZero = dir.resolve("look.json.versions").resolve("000000.json")
        assertTrue(versionZero.readText().contains("1.0"))

        dir.resolve("look.json.versions").resolve("manifest.json").writeText("{ not json at all")
        assertEquals(emptyList(), scope.keyVersions(path), "the corrupt manifest reads as no history")

        scope.saveKey(path, scope.Key(mutableMapOf("gain" to 2.0)), note = "after corruption")

        val versions = scope.keyVersions(path)
        assertEquals(1, versions.size, "numbering restarted")
        assertEquals(0, versions[0].index)
        assertTrue(versionZero.readText().contains("2.0"), "and the original 000000.json was overwritten")
    }

    @Test
    fun missingKeyDefaultsFillInAbsentNamesAndAFilePresentNameAlwaysWins() {
        val dir = tempDir()
        val writer = KeyframeScope()
        val path = dir.resolve("old.json")
        writer.saveKey(path, writer.Key(mutableMapOf("gain" to 1.0)))

        val reader = KeyframeScope()
        reader.missingKey("gain", 99.0)      // present in the file — must lose
        reader.missingKey("newParam", 7.0)   // absent from the file — must be filled
        reader.missingKey("computed") { 8.0 }

        val loaded = reader.loadKey(path)
        assertEquals(1.0, loaded["gain"], "a name present in the file wins over a default")
        assertEquals(7.0, loaded["newParam"])
        assertEquals(8.0, loaded["computed"], "a lambda default is evaluated at load time")
    }

    @Test
    fun aSchemaSpecificDefaultBeatsAVersionAgnosticOneForAMatchingFile() {
        val dir = tempDir()
        // Write a file stamped schemaVersion = 2.
        val writer = KeyframeScope(schemaVersion = 2)
        val v2 = dir.resolve("v2.json")
        writer.saveKey(v2, writer.Key(mutableMapOf("gain" to 1.0)))
        // …and one stamped 0.
        val writer0 = KeyframeScope(schemaVersion = 0)
        val v0 = dir.resolve("v0.json")
        writer0.saveKey(v0, writer0.Key(mutableMapOf("gain" to 1.0)))

        val reader = KeyframeScope()
        reader.missingKey("mode", "generic")
        reader.missingKey(2, "mode", "for-schema-2")

        assertEquals("for-schema-2", reader.loadKey(v2)["mode"], "the matching schema default wins")
        assertEquals("generic", reader.loadKey(v0)["mode"], "a non-matching file gets the generic one")
    }

    @Test
    fun theSavedFileCarriesTheScopesSchemaVersionAndNothingRejectsAMismatch() {
        val dir = tempDir()
        val writer = KeyframeScope(schemaVersion = 3)
        val path = dir.resolve("look.json")
        writer.saveKey(path, writer.Key(mutableMapOf("gain" to 1.0)))
        assertTrue(path.readText().contains("\"schemaVersion\": 3"))

        // A scope on a different version loads it without complaint.
        assertEquals(1.0, KeyframeScope(schemaVersion = 9).loadKey(path)["gain"])
    }

    @Test
    fun keySaveIsChainableAndWritesTheSameFileAsSaveKey() {
        val dir = tempDir()
        val scope = KeyframeScope()
        val key = scope.Key(mutableMapOf("gain" to 1.0))
        val path = dir.resolve("look.json")

        assertTrue(key.save(path, "via Key.save") === key, "Key.save is chainable")
        assertEquals(listOf("via Key.save"), scope.keyVersions(path).map { it.note })
        assertEquals(1.0, scope.loadKey(path)["gain"])
    }

    // ==========================================================================================
    // diff
    // ==========================================================================================

    @Test
    fun diffReportsOnlyWhatMovedAndInTheDocumentedDirection() {
        val scope = KeyframeScope()
        val left = scope.Key(mutableMapOf("same" to 1.0, "changed" to 1.0, "removed" to 1.0))
        val right = scope.Key(mutableMapOf("same" to 1.0, "changed" to 2.0, "added" to 1.0))

        val diff = left.diff(right, "before", "after")
        assertEquals(3, diff.entries.size, "the unchanged name is absent entirely")
        assertEquals(listOf("added", "changed", "removed"), diff.entries.map { it.name }, "sorted by name")

        val byName = diff.entries.associateBy { it.name }
        // ADDED means the RIGHT key has it and the left does not.
        assertEquals(KeyDiffKind.ADDED, byName["added"]!!.kind)
        assertNull(byName["added"]!!.left)
        assertEquals(1.0, byName["added"]!!.right)

        assertEquals(KeyDiffKind.REMOVED, byName["removed"]!!.kind)
        assertEquals(1.0, byName["removed"]!!.left)
        assertNull(byName["removed"]!!.right)

        assertEquals(KeyDiffKind.CHANGED, byName["changed"]!!.kind)
        assertEquals(1.0, byName["changed"]!!.left)
        assertEquals(2.0, byName["changed"]!!.right)

        // There is no "same" kind at all.
        assertEquals(3, KeyDiffKind.entries.size)
    }

    @Test
    fun anEmptyDiffSaysSo() {
        val scope = KeyframeScope()
        val key = scope.Key(mutableMapOf("gain" to 1.0))
        val diff = key.diff(key.copy())
        assertTrue(diff.entries.isEmpty())
        assertEquals("No key differences.", diff.toString())
        assertTrue(diff.toHTML().contains("No key differences."))
    }

    @Test
    fun diffComparesVectorsAndPosesComponentwiseWithNoTolerance() {
        val scope = KeyframeScope()
        val a = scope.Key(mutableMapOf("v" to Vector3f(1f, 2f, 3f)))
        val b = scope.Key(mutableMapOf("v" to Vector3f(1f, 2f, 3f)))
        assertTrue(a.diff(b).entries.isEmpty(), "equal components compare equal even as distinct objects")

        val c = scope.Key(mutableMapOf("v" to Vector3f(1f, 2f, 3.0000001f)))
        // Float rounds 3.0000001f back to 3f, so pick a difference a Float can actually hold.
        val d = scope.Key(mutableMapOf("v" to Vector3f(1f, 2f, 3.00001f)))
        assertTrue(c.diff(d).entries.isNotEmpty(), "no float tolerance: a tiny difference still shows")

        // A source block compares on box AND source, not by identity.
        val e = scope.Key(mutableMapOf("b" to SourceBackedBlock<Any?>("box-1", "1")))
        val f = scope.Key(mutableMapOf("b" to SourceBackedBlock<Any?>("box-1", "1")))
        assertTrue(e.diff(f).entries.isEmpty(), "same box and same source are equal")
        val g = scope.Key(mutableMapOf("b" to SourceBackedBlock<Any?>("box-2", "1")))
        assertEquals(1, e.diff(g).entries.size, "a different box is a change")
    }

    @Test
    fun diffTextUsesPlusMinusTilde() {
        val scope = KeyframeScope()
        val left = scope.Key(mutableMapOf("changed" to 1.0, "removed" to 1.0))
        val right = scope.Key(mutableMapOf("changed" to 2.0, "added" to 1.0))
        val text = left.diffText(right)
        val lines = text.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("+ added"), "added is '+': $text")
        assertTrue(lines[1].startsWith("~ changed"), "changed is '~': $text")
        assertTrue(lines[2].startsWith("- removed"), "removed is '-': $text")
    }

    @Test
    fun diffHtmlCountsItsChanges() {
        val scope = KeyframeScope()
        val left = scope.Key(mutableMapOf("a" to 1.0, "b" to 1.0))
        val right = scope.Key(mutableMapOf("a" to 2.0, "b" to 3.0))
        assertTrue(left.diffHtml(right).contains("Key diff(2 changes)"))
    }

    @Test
    fun aKeyRendersAsATableCountingItsValues() {
        val scope = KeyframeScope()
        val key = scope.Key(mutableMapOf("a" to 1.0, "b" to 2.0, "c" to 3.0))
        val html = key.toHTML()
        assertTrue(html.contains("Key(3 values)"))
        assertEquals(html, key.h, "the `h` extension is the same HTML")
        // Names are escaped.
        val nasty = scope.Key(mutableMapOf("<script>" to 1.0))
        assertTrue(nasty.toHTML().contains("&lt;script&gt;"))
        assertTrue(!nasty.toHTML().contains("<script>"))
    }

    // ==========================================================================================
    // updateCode — a captured look becomes pasteable source
    // ==========================================================================================

    @Test
    fun updateCodeEmitsSortedAssignmentsInTheRequestedForm() {
        val scope = KeyframeScope()
        val key = scope.Key(mutableMapOf("zebra" to 1, "alpha" to 2))

        val applying = key.updateCode()
        assertTrue(applying.startsWith("key.updateAndApply {"), applying)
        assertTrue(applying.trimEnd().endsWith("}"))
        assertTrue(
            applying.indexOf("\"alpha\"") < applying.indexOf("\"zebra\""),
            "assignments are sorted by name: $applying"
        )

        assertTrue(key.updateCode("look", apply = false).startsWith("look.update {"))
    }

    @Test
    fun updateCodeKeepsEveryNumericTypeThroughAPasteRoundTrip() {
        val scope = KeyframeScope()
        val key = scope.Key(
            mutableMapOf(
                "b" to 1.toByte(),
                "s" to 2.toShort(),
                "i" to 3,
                "l" to 4L,
                "f" to 5.5f,
                "d" to 6.5
            )
        )
        val code = key.updateCode()
        // The `f` suffix is the load-bearing part: without it a captured Float pastes back a Double.
        assertTrue(code.contains("= 5.5f"), "Float keeps its suffix: $code")
        assertTrue(code.contains("= 4L"), "Long keeps its suffix: $code")
        assertTrue(code.contains("= 1.toByte()"), code)
        assertTrue(code.contains("= 2.toShort()"), code)
        assertTrue(code.contains("= 3\n") || code.contains("= 3"), code)
        assertTrue(code.contains("= 6.5\n"), "Double has no suffix: $code")
    }

    @Test
    fun updateCodeEmitsTheNonFiniteFloatsByName() {
        val scope = KeyframeScope()
        val code = scope.Key(
            mutableMapOf(
                "a" to Float.NaN,
                "b" to Float.POSITIVE_INFINITY,
                "c" to Float.NEGATIVE_INFINITY,
                "d" to Double.NaN,
                "e" to Double.POSITIVE_INFINITY,
                "f" to Double.NEGATIVE_INFINITY
            )
        ).updateCode()
        listOf(
            "Float.NaN", "Float.POSITIVE_INFINITY", "Float.NEGATIVE_INFINITY",
            "Double.NaN", "Double.POSITIVE_INFINITY", "Double.NEGATIVE_INFINITY"
        ).forEach { assertTrue(code.contains(it), "$it missing from: $code") }
    }

    @Test
    fun updateCodeEmitsVectorsQuaternionsAndPosesAsConstructorCalls() {
        val scope = KeyframeScope()
        val code = scope.Key(
            mutableMapOf(
                "v2" to Vector2f(1f, 2f),
                "v3" to Vector3f(1f, 2f, 3f),
                "v4" to Vector4f(1f, 2f, 3f, 4f),
                "q" to Quaternionf(0f, 0f, 0f, 1f),
                "pose" to CameraPose(DualQuaternionf(), 5f)
            )
        ).updateCode()

        assertTrue(code.contains("vec(1.0f, 2.0f)"), code)
        assertTrue(code.contains("vec(1.0f, 2.0f, 3.0f)"), code)
        assertTrue(code.contains("vec(1.0f, 2.0f, 3.0f, 4.0f)"), code)
        assertTrue(code.contains("afield.stdlib.linalg.Quaternionf("), code)
        assertTrue(code.contains("afield.four.util.CameraPose("), code)
    }

    @Test
    fun anUnsupportedValueBecomesNullBehindABlockComment() {
        val scope = KeyframeScope()
        val code = scope.Key(mutableMapOf("weird" to listOf(1, 2, 3))).updateCode()
        assertTrue(code.contains("/* unsupported"), code)
        assertTrue(code.contains("*/ null"), "the code still compiles, quietly nulling the value: $code")
    }

    @Test
    fun updateCodeEscapesAStringLiteralIncludingItsDollars() {
        val scope = KeyframeScope()
        val code = scope.Key(
            mutableMapOf(
                "s" to "a \"quote\", a \\ backslash, a \$dollar and a\nnewline",
                "voice" to stackSLString("gain \$x *")
            )
        ).updateCode()

        assertTrue(code.contains("\\\""), "quotes escaped: $code")
        assertTrue(code.contains("\\\\"), "backslash escaped: $code")
        assertTrue(code.contains("\\n"), "newline escaped: $code")
        assertTrue(code.contains("\\$"), "dollar escaped: $code")
        assertTrue(code.contains("stackSLString("), "StackSL keeps its wrapper: $code")
        // No un-escaped dollar survives anywhere in the emitted source.
        assertTrue(
            Regex("""(?<!\\)\$""").findAll(code).none(),
            "no bare dollar remains: $code"
        )
    }

    @Test
    fun updateCodeEscapesDollarInASourceBlock() {
        // Regression. kotlinRawStringLiteral guarded a raw-string literal against `"""` and a
        // trailing quote, but not against `$` — and a Kotlin raw string still interpolates `$`. So a
        // source block whose source mentioned `$name` or `${...}` emitted code that either failed to
        // compile or silently interpolated something else.
        //
        // NOTE the shape of the sample: it must contain a `$` and must NOT end with a quote. The
        // first version of this test ended with `"` and so tripped the pre-existing trailing-quote
        // guard, taking the escaped path and passing green without ever reaching the defect.
        val scope = KeyframeScope()
        val source = "val total = 2\nprintln(\"sum: \$total\")"
        assertTrue(!source.endsWith("\"") && !source.contains("\"\"\""), "the sample takes the raw-string path")

        val code = scope.Key(
            mutableMapOf("block" to SourceBackedBlock<Any?>("box-1", source))
        ).updateCode()

        assertTrue(
            Regex("""(?<!\\)\$""").findAll(code).none(),
            "a source block's dollars must not reach the emitted source unescaped: $code"
        )
    }

    @Test
    fun aPlainSourceBlockStillEmitsAsARawString() {
        // The raw form is nicer to read, so it must survive for the ordinary case.
        val scope = KeyframeScope()
        val code = scope.Key(
            mutableMapOf("block" to SourceBackedBlock<Any?>("box-1", "val n = 2\nn * 3"))
        ).updateCode()
        assertTrue(code.contains("\"\"\""), "no dollars, so still a raw string: $code")
    }

    @Test
    fun aSourceBlockContainingTripleQuotesFallsBackToAnEscapedLiteral() {
        val scope = KeyframeScope()
        val code = scope.Key(
            mutableMapOf("block" to SourceBackedBlock<Any?>("box-1", "\"\"\"nested\"\"\""))
        ).updateCode()
        assertTrue(code.contains("\\\""), "escaped rather than raw: $code")
    }
}
