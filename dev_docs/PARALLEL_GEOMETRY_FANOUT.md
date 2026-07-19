# Parallel geometry fan-out

`ParallelGeometryPool` lets worker-owned geometry buffers drive one draw or compute
dispatch per writer. Its authoritative phase, lifetime, ordering, liveness, cleanup,
and push-constant rules are documented beside the implementation in
[`ParallelGeometryPool.kt`](../src/jvmMain/kotlin/afield/four/buffers/ParallelGeometryPool.kt).

The minimal shape is:

```kotlin
val pool = ParallelGeometryPool(renderer)
mesh.push.bind("positionBuffer") { pool.ref("position") }

renderer.geometryProducers["positions"] = { phase ->
    repeat(4) { index ->
        phase.submit {
            pool.writer(index.toLong()).apply {
                write("position") {
                    // Write this worker's positions to the DynamicBuffer.
                }
                publish(vertexCount = 1024)
            }
        }
    }
}
```

The renderer opens and seals the normal geometry phase around
`geometryProducers`. Producers launched earlier in the Runloop must use
`Renderer.beginGeometryWrites()` and `endGeometryWrites()`, or install the renderer's
Runloop begin hook. Those methods currently support only the primary window.

There is not yet a `FIELD_TEST` for this path. Until one exists, changes require a real
renderer smoke that publishes at least two writer ids, confirms deterministic fan-out,
omits one writer on the next frame to confirm strict liveness, and exercises cleanup.
