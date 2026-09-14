package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLBox
import afield.four.audio.steamaudio.raw.IPLCoordinateSpace3
import afield.four.audio.steamaudio.raw.IPLMatrix4x4
import afield.four.audio.steamaudio.raw.IPLSphere
import afield.four.audio.steamaudio.raw.IPLVector3
import java.lang.foreign.MemorySegment
import java.lang.foreign.SegmentAllocator
import java.lang.foreign.ValueLayout

/**
 * A point or direction in 3D space.
 *
 * Steam Audio uses a right-handed coordinate system: +x right, +y up, -z forward.
 *
 * This is an ordinary class rather than a native view, so it allocates. On the audio thread,
 * prefer the `(x, y, z)` overloads on the parameter types (for example
 * [BinauralEffectParams.setDirection]) which write straight through to native memory.
 */
public data class Vector3(
    public val x: Float,
    public val y: Float,
    public val z: Float,
) {
    public operator fun plus(other: Vector3): Vector3 = Vector3(x + other.x, y + other.y, z + other.z)

    public operator fun minus(other: Vector3): Vector3 = Vector3(x - other.x, y - other.y, z - other.z)

    public operator fun times(scalar: Float): Vector3 = Vector3(x * scalar, y * scalar, z * scalar)

    public operator fun unaryMinus(): Vector3 = Vector3(-x, -y, -z)

    /** Euclidean length. */
    public val length: Float get() = kotlin.math.sqrt(x * x + y * y + z * z)

    /** This vector scaled to unit length, or [ZERO] if it has no length. */
    public fun normalized(): Vector3 {
        val len = length
        return if (len == 0f) ZERO else Vector3(x / len, y / len, z / len)
    }

    public infix fun dot(other: Vector3): Float = x * other.x + y * other.y + z * other.z

    public infix fun cross(other: Vector3): Vector3 = Vector3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    /** Writes this vector into an `IPLVector3` at [segment]. */
    internal fun writeTo(segment: MemorySegment) {
        IPLVector3.x(segment, x)
        IPLVector3.y(segment, y)
        IPLVector3.z(segment, z)
    }

    /** Allocates a new `IPLVector3` holding this vector. */
    internal fun allocate(allocator: SegmentAllocator): MemorySegment =
        IPLVector3.allocate(allocator).also(::writeTo)

    public companion object {
        public val ZERO: Vector3 = Vector3(0f, 0f, 0f)
        public val RIGHT: Vector3 = Vector3(1f, 0f, 0f)
        public val UP: Vector3 = Vector3(0f, 1f, 0f)

        /** Steam Audio's forward direction, which is -z. */
        public val FORWARD: Vector3 = Vector3(0f, 0f, -1f)

        internal fun readFrom(segment: MemorySegment): Vector3 = Vector3(
            IPLVector3.x(segment),
            IPLVector3.y(segment),
            IPLVector3.z(segment),
        )
    }
}

/**
 * A 4x4 transform matrix, stored **row-major** to match `IPLMatrix4x4`.
 *
 * [elements] is 16 floats in row-major order; `elements[row * 4 + column]`.
 */
public class Matrix4x4(elements: FloatArray) {

    public val elements: FloatArray = elements.copyOf().also {
        require(it.size == 16) { "a 4x4 matrix needs 16 elements, got ${it.size}" }
    }

    public operator fun get(row: Int, column: Int): Float = elements[row * 4 + column]

    /**
     * Matrix product: `this * other`.
     *
     * Applied to a column vector, [other] acts first. So
     * `translation(...) * rotationY(...)` rotates and then translates, which is the usual
     * order for placing an object.
     */
    public operator fun times(other: Matrix4x4): Matrix4x4 {
        val result = FloatArray(16)
        for (row in 0 until 4) {
            for (column in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) {
                    sum += elements[row * 4 + k] * other.elements[k * 4 + column]
                }
                result[row * 4 + column] = sum
            }
        }
        return Matrix4x4(result)
    }

    /** Transforms a point, applying the translation column. */
    public fun transformPoint(point: Vector3): Vector3 = Vector3(
        get(0, 0) * point.x + get(0, 1) * point.y + get(0, 2) * point.z + get(0, 3),
        get(1, 0) * point.x + get(1, 1) * point.y + get(1, 2) * point.z + get(1, 3),
        get(2, 0) * point.x + get(2, 1) * point.y + get(2, 2) * point.z + get(2, 3),
    )

    internal fun writeTo(segment: MemorySegment) {
        MemorySegment.copy(
            elements, 0,
            IPLMatrix4x4.elements(segment), ValueLayout.JAVA_FLOAT,
            0L, 16,
        )
    }

    internal fun allocate(allocator: SegmentAllocator): MemorySegment =
        IPLMatrix4x4.allocate(allocator).also(::writeTo)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Matrix4x4 && elements.contentEquals(other.elements))

    override fun hashCode(): Int = elements.contentHashCode()

    override fun toString(): String =
        (0 until 4).joinToString(prefix = "Matrix4x4[", postfix = "]", separator = "; ") { row ->
            (0 until 4).joinToString(" ") { column -> get(row, column).toString() }
        }

    public companion object {
        public val IDENTITY: Matrix4x4 = Matrix4x4(
            floatArrayOf(
                1f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            ),
        )

        /** A translation matrix. */
        public fun translation(x: Float, y: Float, z: Float): Matrix4x4 = Matrix4x4(
            floatArrayOf(
                1f, 0f, 0f, x,
                0f, 1f, 0f, y,
                0f, 0f, 1f, z,
                0f, 0f, 0f, 1f,
            ),
        )

        /** A uniform scale matrix. */
        public fun scale(factor: Float): Matrix4x4 = scale(factor, factor, factor)

        /** A non-uniform scale matrix. */
        public fun scale(x: Float, y: Float, z: Float): Matrix4x4 = Matrix4x4(
            floatArrayOf(
                x, 0f, 0f, 0f,
                0f, y, 0f, 0f,
                0f, 0f, z, 0f,
                0f, 0f, 0f, 1f,
            ),
        )

        /** A rotation about the y axis, in radians. */
        public fun rotationY(radians: Float): Matrix4x4 {
            val c = kotlin.math.cos(radians)
            val s = kotlin.math.sin(radians)
            return Matrix4x4(
                floatArrayOf(
                    c, 0f, s, 0f,
                    0f, 1f, 0f, 0f,
                    -s, 0f, c, 0f,
                    0f, 0f, 0f, 1f,
                ),
            )
        }

        /** A rotation about the x axis, in radians. */
        public fun rotationX(radians: Float): Matrix4x4 {
            val c = kotlin.math.cos(radians)
            val s = kotlin.math.sin(radians)
            return Matrix4x4(
                floatArrayOf(
                    1f, 0f, 0f, 0f,
                    0f, c, -s, 0f,
                    0f, s, c, 0f,
                    0f, 0f, 0f, 1f,
                ),
            )
        }

        /** A rotation about the z axis, in radians. */
        public fun rotationZ(radians: Float): Matrix4x4 {
            val c = kotlin.math.cos(radians)
            val s = kotlin.math.sin(radians)
            return Matrix4x4(
                floatArrayOf(
                    c, -s, 0f, 0f,
                    s, c, 0f, 0f,
                    0f, 0f, 1f, 0f,
                    0f, 0f, 0f, 1f,
                ),
            )
        }

        internal fun readFrom(segment: MemorySegment): Matrix4x4 {
            val out = FloatArray(16)
            MemorySegment.copy(
                IPLMatrix4x4.elements(segment), ValueLayout.JAVA_FLOAT, 0L,
                out, 0, 16,
            )
            return Matrix4x4(out)
        }
    }
}

/** An axis-aligned bounding box. */
public data class Box(
    public val min: Vector3,
    public val max: Vector3,
) {
    public val center: Vector3 get() = (min + max) * 0.5f

    internal fun writeTo(segment: MemorySegment) {
        min.writeTo(IPLBox.minCoordinates(segment))
        max.writeTo(IPLBox.maxCoordinates(segment))
    }

    internal fun allocate(allocator: SegmentAllocator): MemorySegment =
        IPLBox.allocate(allocator).also(::writeTo)

    internal companion object {
        fun readFrom(segment: MemorySegment): Box = Box(
            Vector3.readFrom(IPLBox.minCoordinates(segment)),
            Vector3.readFrom(IPLBox.maxCoordinates(segment)),
        )
    }
}

/** A sphere, used both for probes and for baked-data influence regions. */
public data class Sphere(
    public val center: Vector3,
    public val radius: Float,
) {
    internal fun writeTo(segment: MemorySegment) {
        center.writeTo(IPLSphere.center(segment))
        IPLSphere.radius(segment, radius)
    }

    internal fun allocate(allocator: SegmentAllocator): MemorySegment =
        IPLSphere.allocate(allocator).also(::writeTo)

    internal companion object {
        fun readFrom(segment: MemorySegment): Sphere = Sphere(
            Vector3.readFrom(IPLSphere.center(segment)),
            IPLSphere.radius(segment),
        )
    }
}

/**
 * A position and orientation: three orthonormal axes plus an origin.
 *
 * Used for listeners, directional sources, and Ambisonics rotation.
 */
public data class CoordinateSpace3(
    public val right: Vector3,
    public val up: Vector3,
    public val ahead: Vector3,
    public val origin: Vector3,
) {
    internal fun writeTo(segment: MemorySegment) {
        right.writeTo(IPLCoordinateSpace3.right(segment))
        up.writeTo(IPLCoordinateSpace3.up(segment))
        ahead.writeTo(IPLCoordinateSpace3.ahead(segment))
        origin.writeTo(IPLCoordinateSpace3.origin(segment))
    }

    internal fun allocate(allocator: SegmentAllocator): MemorySegment =
        IPLCoordinateSpace3.allocate(allocator).also(::writeTo)

    public companion object {
        /** The identity frame: axes aligned to the world, origin at zero. */
        public val IDENTITY: CoordinateSpace3 = CoordinateSpace3(
            right = Vector3.RIGHT,
            up = Vector3.UP,
            ahead = Vector3.FORWARD,
            origin = Vector3.ZERO,
        )

        /**
         * Builds a right-handed frame from an [ahead] direction and an [up] hint, both of
         * which are normalised. [up] is re-orthogonalised against [ahead].
         */
        public fun lookingAt(
            origin: Vector3,
            ahead: Vector3,
            up: Vector3 = Vector3.UP,
        ): CoordinateSpace3 {
            val forward = ahead.normalized()
            // Steam Audio's own frame is +x right, +y up, -z ahead, and that is right-handed as
            // `ahead cross up` -- which is what iplCalculateRelativeDirection derives internally
            // from the ahead/up it is given. Building `right` the other way round produced a
            // self-consistent but mirrored frame: left and right swapped for anything that passes
            // a whole CoordinateSpace3 (source and listener spaces in the simulation path), while
            // the direct binaural path stayed correct because it never reads this field.
            val right = (forward cross up).normalized()
            val trueUp = (right cross forward).normalized()
            return CoordinateSpace3(right = right, up = trueUp, ahead = forward, origin = origin)
        }

        internal fun readFrom(segment: MemorySegment): CoordinateSpace3 = CoordinateSpace3(
            Vector3.readFrom(IPLCoordinateSpace3.right(segment)),
            Vector3.readFrom(IPLCoordinateSpace3.up(segment)),
            Vector3.readFrom(IPLCoordinateSpace3.ahead(segment)),
            Vector3.readFrom(IPLCoordinateSpace3.origin(segment)),
        )
    }
}

/** A ray: an [origin] and a [direction]. */
public data class Ray(
    public val origin: Vector3,
    public val direction: Vector3,
)

/** An acoustic material: per-band absorption and transmission, plus a scattering coefficient. */
public data class Material(
    /** Fraction of energy absorbed per frequency band. One entry per [SteamAudio.NUM_BANDS]. */
    public val absorption: FloatArray,
    /** Fraction of energy scattered (rather than reflected specularly) on reflection. */
    public val scattering: Float,
    /** Fraction of energy transmitted through the surface, per frequency band. */
    public val transmission: FloatArray,
) {
    init {
        require(absorption.size == SteamAudio.NUM_BANDS) {
            "absorption needs ${SteamAudio.NUM_BANDS} bands, got ${absorption.size}"
        }
        require(transmission.size == SteamAudio.NUM_BANDS) {
            "transmission needs ${SteamAudio.NUM_BANDS} bands, got ${transmission.size}"
        }
    }

    override fun equals(other: Any?): Boolean = this === other || (
        other is Material &&
            absorption.contentEquals(other.absorption) &&
            scattering == other.scattering &&
            transmission.contentEquals(other.transmission)
        )

    override fun hashCode(): Int {
        var result = absorption.contentHashCode()
        result = 31 * result + scattering.hashCode()
        result = 31 * result + transmission.contentHashCode()
        return result
    }

    override fun toString(): String =
        "Material(absorption=${absorption.toList()}, scattering=$scattering, " +
            "transmission=${transmission.toList()})"

    public companion object {
        /**
         * Builds a material from per-band values, padding or truncating to
         * [SteamAudio.NUM_BANDS].
         *
         * The presets below are quoted for the 3-band configuration the shipped binaries use
         * (low, mid, high). This adapts them if the native library was built with octave bands.
         */
        private fun of(
            absorption: FloatArray,
            scattering: Float,
            transmission: FloatArray,
        ): Material {
            fun fit(values: FloatArray) = FloatArray(SteamAudio.NUM_BANDS) { band ->
                values[band.coerceAtMost(values.lastIndex)]
            }
            return Material(fit(absorption), scattering, fit(transmission))
        }

        /** A generic moderately absorbent material, handy as a placeholder. */
        public val GENERIC: Material = of(
            absorption = floatArrayOf(0.10f, 0.20f, 0.30f),
            scattering = 0.05f,
            transmission = floatArrayOf(0.100f, 0.050f, 0.030f),
        )

        /**
         * Rock. Absorbs little, so it rings -- this is the surface that makes a cave a cave.
         */
        public val ROCK: Material = of(
            absorption = floatArrayOf(0.13f, 0.20f, 0.24f),
            scattering = 0.05f,
            transmission = floatArrayOf(0.015f, 0.002f, 0.001f),
        )

        /** Brick. Slightly more absorbent than rock, still a live surface. */
        public val BRICK: Material = of(
            absorption = floatArrayOf(0.03f, 0.04f, 0.07f),
            scattering = 0.05f,
            transmission = floatArrayOf(0.015f, 0.015f, 0.015f),
        )

        /** Concrete. Very reflective, especially when smooth. */
        public val CONCRETE: Material = of(
            absorption = floatArrayOf(0.05f, 0.07f, 0.08f),
            scattering = 0.05f,
            transmission = floatArrayOf(0.015f, 0.002f, 0.001f),
        )

        /** Metal. Bright and highly reflective. */
        public val METAL: Material = of(
            absorption = floatArrayOf(0.20f, 0.07f, 0.06f),
            scattering = 0.05f,
            transmission = floatArrayOf(0.200f, 0.025f, 0.010f),
        )

        /** Wood. Absorbs the low end noticeably. */
        public val WOOD: Material = of(
            absorption = floatArrayOf(0.11f, 0.07f, 0.06f),
            scattering = 0.05f,
            transmission = floatArrayOf(0.070f, 0.014f, 0.005f),
        )

        /** Glass. Reflective and almost acoustically transparent at low frequencies. */
        public val GLASS: Material = of(
            absorption = floatArrayOf(0.06f, 0.03f, 0.02f),
            scattering = 0.05f,
            transmission = floatArrayOf(0.060f, 0.044f, 0.011f),
        )

        /** Carpet. Absorbs the top end hard; use it to deaden a space. */
        public val CARPET: Material = of(
            absorption = floatArrayOf(0.24f, 0.69f, 0.73f),
            scattering = 0.05f,
            transmission = floatArrayOf(0.020f, 0.005f, 0.003f),
        )

        /**
         * Heavy curtain. Strongly absorbent across the band -- a good "acoustic shadow" plane
         * for swinging into a space to kill its reverb.
         */
        public val CURTAIN: Material = of(
            absorption = floatArrayOf(0.14f, 0.35f, 0.55f),
            scattering = 0.05f,
            transmission = floatArrayOf(0.120f, 0.100f, 0.100f),
        )

        /** Fully absorbing, for testing: whatever hits this surface does not come back. */
        public val ANECHOIC: Material = of(
            absorption = floatArrayOf(1.0f, 1.0f, 1.0f),
            scattering = 0.0f,
            transmission = floatArrayOf(0.0f, 0.0f, 0.0f),
        )
    }
}
