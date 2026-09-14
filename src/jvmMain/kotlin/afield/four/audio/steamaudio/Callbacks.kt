package afield.four.audio.steamaudio

import afield.four.audio.steamaudio.raw.IPLAnyHitCallback
import afield.four.audio.steamaudio.raw.IPLBatchedAnyHitCallback
import afield.four.audio.steamaudio.raw.IPLBatchedClosestHitCallback
import afield.four.audio.steamaudio.raw.IPLClosestHitCallback
import afield.four.audio.steamaudio.raw.IPLHit
import afield.four.audio.steamaudio.raw.IPLProgressCallback
import afield.four.audio.steamaudio.raw.IPLRay
import afield.four.audio.steamaudio.raw.IPLSceneSettings
import afield.four.audio.steamaudio.raw.IPLVector3
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** Reports progress of a long-running operation as a fraction in `0..1`. */
public fun interface ProgressCallback {
    public fun onProgress(progress: Float)
}

internal object ProgressCallbacks {
    /**
     * Builds an upcall stub for [callback], valid for as long as [arena].
     *
     * Exceptions must never cross back into native code, so they are swallowed.
     */
    fun stub(callback: ProgressCallback, arena: Arena): MemorySegment =
        IPLProgressCallback.allocate(
            { progress, _ ->
                try {
                    callback.onProgress(progress)
                } catch (_: Throwable) {
                    // Never propagate across the FFM boundary.
                }
            },
            arena,
        )
}

/**
 * What a ray hit, for a custom scene.
 *
 * Fill this in from [CustomSceneCallbacks.closestHit]. A [distance] of a negative value, or
 * leaving the hit untouched, signals that the ray hit nothing.
 */
public class HitResult internal constructor(private val segment: MemorySegment) {

    /** Distance along the ray to the hit point. Set this negative to report a miss. */
    public var distance: Float
        get() = IPLHit.distance(segment)
        set(value) = IPLHit.distance(segment, value)

    /** Index of the triangle that was hit, or `-1` if not meaningful. */
    public var triangleIndex: Int
        get() = IPLHit.triangleIndex(segment)
        set(value) = IPLHit.triangleIndex(segment, value)

    /** Index of the object that was hit, or `-1` if not meaningful. */
    public var objectIndex: Int
        get() = IPLHit.objectIndex(segment)
        set(value) = IPLHit.objectIndex(segment, value)

    /** Index of the material at the hit point, or `-1` if not meaningful. */
    public var materialIndex: Int
        get() = IPLHit.materialIndex(segment)
        set(value) = IPLHit.materialIndex(segment, value)

    /** Surface normal at the hit point. Should be unit length. */
    public fun setNormal(x: Float, y: Float, z: Float) {
        val normal = IPLHit.normal(segment)
        IPLVector3.x(normal, x)
        IPLVector3.y(normal, y)
        IPLVector3.z(normal, z)
    }

    /**
     * The acoustic material at the hit point.
     *
     * Steam Audio reads this as a pointer, so the segment must remain valid after the callback
     * returns -- allocate materials once, up front, in a long-lived arena, rather than per hit.
     */
    public fun setMaterial(material: MemorySegment) {
        IPLHit.material(segment, material)
    }

    /** Records a miss. */
    public fun setMiss() {
        distance = -1f
    }
}

/**
 * Ray-cast callbacks for a [SceneType.CUSTOM] scene, letting Steam Audio query geometry that
 * lives in your own acceleration structure rather than in a [Scene].
 *
 * ### Cost
 *
 * These are the hottest callbacks in the API. Steam Audio calls them from its own
 * ray-tracing worker threads, potentially hundreds of thousands of times per simulation run,
 * and each call is an FFM upcall into the JVM. Consequences worth knowing before you choose
 * this over a default scene:
 *
 *  - **Native threads must attach to the JVM** the first time they upcall. That cost is paid
 *    once per thread, but it is not free, and those threads then participate in safepoints.
 *  - **Allocation in these callbacks is fatal to performance.** Do not allocate, do not box,
 *    do not touch a concurrent collection, and do not log.
 *  - **Implement the batched variants** ([batchedClosestHit] and [batchedAnyHit]) if you use
 *    this at all. They amortise the boundary crossing over many rays instead of paying it per
 *    ray, which is the difference between viable and not.
 *  - **Never throw.** Exceptions are swallowed at the boundary and the ray is reported as a
 *    miss, which will quietly corrupt the simulation rather than fail loudly.
 *
 * For a space made of planes, a default [Scene] with [InstancedMesh] is almost always the
 * better choice: Steam Audio's own BVH is fast, and moving an instance is cheap.
 */
public interface CustomSceneCallbacks {

    /**
     * Finds the closest hit along a ray, between [minDistance] and [maxDistance].
     *
     * Write the result into [hit], or call [HitResult.setMiss].
     */
    public fun closestHit(
        originX: Float, originY: Float, originZ: Float,
        directionX: Float, directionY: Float, directionZ: Float,
        minDistance: Float, maxDistance: Float,
        hit: HitResult,
    )

    /**
     * Reports whether a ray hits anything between [minDistance] and [maxDistance].
     *
     * Cheaper than [closestHit] because it can stop at the first hit; used for occlusion.
     */
    public fun anyHit(
        originX: Float, originY: Float, originZ: Float,
        directionX: Float, directionY: Float, directionZ: Float,
        minDistance: Float, maxDistance: Float,
    ): Boolean

    /**
     * Batched form of [closestHit]. Defaults to looping over the single-ray version.
     *
     * Override this. The default exists so the interface is easy to implement, not because
     * looping is a good idea -- see the cost notes on this interface.
     */
    public fun batchedClosestHit(
        rayCount: Int,
        rays: MemorySegment,
        minDistances: MemorySegment,
        maxDistances: MemorySegment,
        hits: MemorySegment,
    ) {
        val rayLayout = IPLRay.layout()
        val hitLayout = IPLHit.layout()
        for (index in 0 until rayCount) {
            val ray = rays.asSlice(index * rayLayout.byteSize(), rayLayout.byteSize())
            val origin = IPLRay.origin(ray)
            val direction = IPLRay.direction(ray)
            closestHit(
                IPLVector3.x(origin), IPLVector3.y(origin), IPLVector3.z(origin),
                IPLVector3.x(direction), IPLVector3.y(direction), IPLVector3.z(direction),
                minDistances.getAtIndex(ValueLayout.JAVA_FLOAT, index.toLong()),
                maxDistances.getAtIndex(ValueLayout.JAVA_FLOAT, index.toLong()),
                HitResult(hits.asSlice(index * hitLayout.byteSize(), hitLayout.byteSize())),
            )
        }
    }

    /** Batched form of [anyHit]. Writes `0` or `1` per ray into [occluded]. */
    public fun batchedAnyHit(
        rayCount: Int,
        rays: MemorySegment,
        minDistances: MemorySegment,
        maxDistances: MemorySegment,
        occluded: MemorySegment,
    ) {
        val rayLayout = IPLRay.layout()
        for (index in 0 until rayCount) {
            val ray = rays.asSlice(index * rayLayout.byteSize(), rayLayout.byteSize())
            val origin = IPLRay.origin(ray)
            val direction = IPLRay.direction(ray)
            val blocked = anyHit(
                IPLVector3.x(origin), IPLVector3.y(origin), IPLVector3.z(origin),
                IPLVector3.x(direction), IPLVector3.y(direction), IPLVector3.z(direction),
                minDistances.getAtIndex(ValueLayout.JAVA_FLOAT, index.toLong()),
                maxDistances.getAtIndex(ValueLayout.JAVA_FLOAT, index.toLong()),
            )
            occluded.setAtIndex(ValueLayout.JAVA_BYTE, index.toLong(), if (blocked) 1 else 0)
        }
    }

    public companion object {
        /** Wires all four callbacks into an `IPLSceneSettings`, with stubs owned by [arena]. */
        internal fun install(
            callbacks: CustomSceneCallbacks,
            settings: MemorySegment,
            arena: Arena,
        ) {
            IPLSceneSettings.closestHitCallback(
                settings,
                IPLClosestHitCallback.allocate(
                    { ray, minDistance, maxDistance, hit, _ ->
                        try {
                            val origin = IPLRay.origin(ray)
                            val direction = IPLRay.direction(ray)
                            callbacks.closestHit(
                                IPLVector3.x(origin), IPLVector3.y(origin), IPLVector3.z(origin),
                                IPLVector3.x(direction), IPLVector3.y(direction), IPLVector3.z(direction),
                                minDistance, maxDistance,
                                HitResult(hit),
                            )
                        } catch (_: Throwable) {
                            IPLHit.distance(hit, -1f)
                        }
                    },
                    arena,
                ),
            )

            IPLSceneSettings.anyHitCallback(
                settings,
                IPLAnyHitCallback.allocate(
                    { ray, minDistance, maxDistance, occluded, _ ->
                        val blocked = try {
                            val origin = IPLRay.origin(ray)
                            val direction = IPLRay.direction(ray)
                            callbacks.anyHit(
                                IPLVector3.x(origin), IPLVector3.y(origin), IPLVector3.z(origin),
                                IPLVector3.x(direction), IPLVector3.y(direction), IPLVector3.z(direction),
                                minDistance, maxDistance,
                            )
                        } catch (_: Throwable) {
                            false
                        }
                        occluded.set(ValueLayout.JAVA_BYTE, 0L, if (blocked) 1 else 0)
                    },
                    arena,
                ),
            )

            IPLSceneSettings.batchedClosestHitCallback(
                settings,
                IPLBatchedClosestHitCallback.allocate(
                    { rayCount, rays, minDistances, maxDistances, hits, _ ->
                        try {
                            callbacks.batchedClosestHit(
                                rayCount,
                                rays.reinterpret(rayCount.toLong() * IPLRay.layout().byteSize()),
                                minDistances.reinterpret(rayCount.toLong() * Float.SIZE_BYTES),
                                maxDistances.reinterpret(rayCount.toLong() * Float.SIZE_BYTES),
                                hits.reinterpret(rayCount.toLong() * IPLHit.layout().byteSize()),
                            )
                        } catch (_: Throwable) {
                            // Report every ray in the batch as a miss.
                            val hitLayout = IPLHit.layout()
                            val all = hits.reinterpret(rayCount.toLong() * hitLayout.byteSize())
                            for (index in 0 until rayCount) {
                                IPLHit.distance(
                                    all.asSlice(index * hitLayout.byteSize(), hitLayout.byteSize()),
                                    -1f,
                                )
                            }
                        }
                    },
                    arena,
                ),
            )

            IPLSceneSettings.batchedAnyHitCallback(
                settings,
                IPLBatchedAnyHitCallback.allocate(
                    { rayCount, rays, minDistances, maxDistances, occluded, _ ->
                        val out = occluded.reinterpret(rayCount.toLong())
                        try {
                            callbacks.batchedAnyHit(
                                rayCount,
                                rays.reinterpret(rayCount.toLong() * IPLRay.layout().byteSize()),
                                minDistances.reinterpret(rayCount.toLong() * Float.SIZE_BYTES),
                                maxDistances.reinterpret(rayCount.toLong() * Float.SIZE_BYTES),
                                out,
                            )
                        } catch (_: Throwable) {
                            out.fill(0)
                        }
                    },
                    arena,
                ),
            )
        }
    }
}
