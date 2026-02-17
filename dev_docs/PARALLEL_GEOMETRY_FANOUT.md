# Parallel Fan-Out Geometry Buffers

This document describes the new `afield.four` parallel geometry flow:

1. `Renderer` runs a dedicated per-frame geometry phase after image acquire and before command recording.
2. Worker threads write to per-writer, per-channel frame buffers in a `ParallelGeometryPool`.
3. Writers call `publish(...)` for the current frame.
4. Any `Mesh`/`ComputeMesh`/`MeshMesh` push constant that references `pool.ref("channel")` will fan out automatically and dispatch once per active writer for that frame.

## Minimal setup

```kotlin
import afield.four.Renderer
import afield.four.buffers.ParallelGeometryPool
import afield.four.modules.Mesh
import afield.four.modules.ModulePointer

fun setup(renderer: Renderer, module: ModulePointer, mesh: Mesh) {
    val pool = ParallelGeometryPool(renderer = renderer)

    // Push constants that are buffer_reference pointers in your shader.
    mesh.push.bind("positionBuffer") { pool.ref("position") }
    mesh.push.bind("normalBuffer") { pool.ref("normal") }

    renderer.geometryProducers["parallel-geometry"] = { scope ->
        val workers = 4
        repeat(workers) { workerIndex ->
            scope.submit {
                val writer = pool.writer(writerId = workerIndex.toLong())

                writer.write("position") {
                    // write vec4 positions into DynamicBuffer
                }
                writer.write("normal") {
                    // write vec4 normals into DynamicBuffer
                }

                // strict liveness: must publish every frame you want to render
                writer.publish(vertexCount = 1024)
            }
        }
    }
}
```

## Runloop-driven geometry window (optional)

If you want to launch geometry workers from anywhere in the Runloop (not only inside `geometryProducers`), open the frame's geometry window explicitly:

```kotlin
// Early in the frame, before renderer.render():
renderer.beginGeometryWrites()

// Any thread can now write/publish:
val writer = pool.writer()
writer.write("position") { /* ... */ }
writer.publish(vertexCount = 256)

// Optional explicit close:
renderer.endGeometryWrites()
// If omitted, renderer.render() auto-closes at frame start.
```

Notes:
- `beginGeometryWrites()` acquires the frame image up front, so writes stay pinned to the correct per-frame buffer.
- `render()` will not reacquire for window 0 when a pre-opened geometry window exists for that frame.
- Registered `renderer.geometryProducers` still run when the window is closed (explicitly or auto-close at render start).

If you are using `Runloop`, you can install an automatic pre-frame hook:

```kotlin
renderer.installRunloopGeometryBeginHook()
```

`Runloop` now provides `preRun` and `postRun` sequence maps. The renderer hook installs into `preRun`.

## Behavior notes

- Liveness is strict current-frame only: if a writer does not call `publish(...)` this frame, it is not rendered.
- Dispatch order is deterministic: ascending `writerId`.
- v1 supports one `ParallelGeometryPool` per push-constant set in a mesh/task; mixing pools in one dispatch fails fast.
- Idle writer slots are retained for 120 frames by default, then scheduled for cleanup.

## Optional publish overrides

`publish(...)` can override per-writer draw/dispatch size:

- `vertexCount`, `indexCount` for `Mesh`
- `dispatchX`, `dispatchY`, `dispatchZ` for `ComputeMesh` and `MeshMesh`

If omitted, existing mesh/task values are used.
