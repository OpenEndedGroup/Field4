# Renderer teardown order

What happens, in what order, when an `afield.four` Renderer owned by a box goes away, and what a
box that draws or writes buffers can rely on. Written 2026-09-26, after a JVM crash on this path
was reproduced and fixed.

## When a box's renderer is torn down

In an ordinary Field (not a standalone `main`), a Renderer's device is destroyed in exactly one
place, `disposeBoxRenderer(box)` in `src/jvmMain/kotlin/afield/agent_backdoor.kt`. It is reached
from two places:

| Trigger | Path |
| --- | --- |
| stop-box or stop-all on the box that holds `renderer` | `Server.stop(box)` → `disposeBoxRenderer` |
| the document's last websocket session goes away (the Field window closes or reloads, or the session opens another document) | `detachDocumentSession` / `registerDocumentSession` → `cleanupDetachedSession` → `SessionInformation.cleanup()` → `disposeBoxRenderer` for every box |

That list is complete for `src/jvmMain` outside `examples/`: those are all the callers of
`Renderer.cleanup()` apart from `Renderer.runForever`, which only standalone `main`s use.
`Renderer.run()`, which a box's `start(renderer::run)` uses, does **not** call cleanup when the
GLFW window closes. Its loop ends and the device stays alive until one of the two triggers above
happens. JVM exit (`exitProcess`) does not run renderer cleanup at all.

## The order

1. `renderer.running = false`, and the box's `renderer` and `window` properties are removed, so a
   second dispose finds nothing.
2. **Dependents are stopped.** A dependent is a transitive child of the renderer box that has
   jobs, running states or stop hooks (`rendererDependents` + `beginRendererTeardown`). Each is
   stopped with its engine's `stop`, the same call stop-box makes, so its jobs are cancelled and
   its stop hooks run. The owner's own jobs (the `start(renderer::run)` coroutine) are cancelled
   too. All of this happens synchronously on the caller's thread (a websocket thread, usually).
3. **On the main runloop, teardown waits for those jobs to finish.** The wait suspends rather
   than blocks, because the cancelled jobs are usually dispatched on the same runloop thread
   that runs the teardown, and blocking it would wait for work that can never run. It is bounded
   by `RENDERER_TEARDOWN_JOIN_MS` (2 s). A box still running at the deadline is logged by name
   (`renderer teardown for box …: N box(es) still running after 2000 ms, disposing anyway`), and
   teardown goes ahead anyway.
4. `Renderer.cleanup()`: it waits for the queues and the device to go idle, drains deferred
   releases, calls `AutoDisposer.releaseOwner(device)` for everything still device-owned, destroys
   the VMA allocator and then the device. The windows are destroyed after it.

Why "transitive children": that is the set `fromContext()` and inherited properties
(`prop<Renderer>(renderer)`) can resolve the renderer from, since both walk *up* `parents`. A box
outside it can only have the renderer through a global or a captured reference, and teardown has
no way to see those.

## The guarantee, and its limits

- A coroutine a dependent launched (`loop {}`, `frames {}`, `start(...)`, anything tracked in
  `box.jobs`) has **finished** before the device is freed, provided it reaches a suspension point
  and unwinds within 2 s. A `loop {}` suspends every frame, so it stops within one frame.
- After cleanup has begun, `VulkanBuffer.map()`, `unmap()` and `invalidate()` throw
  `IllegalStateException("… used after its Renderer was disposed …")` rather than touch freed
  memory. This covers every `mapAndWrite*` / `mapAndRead*`. It is a backstop for work teardown
  cannot stop (a raw `thread {}`, or a box that holds the renderer through a global). It is not a
  lock: a write that passed the check just before the device was freed can still fault. Other
  device calls (`Queue.submit`, descriptor pushes) have no such check.
- Stopping the renderer box now also stops its running children. Before this change they kept
  running with a dead renderer. If you meant to keep them, reconnect them to a live renderer and
  run them again.

## Why: the crash

`hs_err_pid53825.log` (2026-09-23, worktree `2026-09-23-2-gpu-dual-quaternion-skinning`): a
SIGSEGV (`SEGV_ACCERR`) on `field-runloop-main` in `VulkanBuffer.mapAndWrite`, called from
`GpuSkinner.uploadPalettes` in the `gpu_twist` demo box's `loop {}`
(`dev_docs/agents/demos/skinned_characters/`). Before this change `disposeBoxRenderer` cancelled
only the renderer box's jobs. `releaseOwner` then destroyed every device-owned allocation, the
child's buffer among them. AutoDisposer cleanups capture handles and never the wrapper object, so
the child's `VulkanBuffer.mappedMemory` still pointed at the freed mapping. On the next runloop
tick the child's loop wrote through it.

Commit `b5b9ad9e0` made disposal device-owned (`releaseOwner`). That was correct, but it is also
what turned a leak at this point into a free of memory that is still in use. It did not stop
dependents first.

The older MoltenVK crashes in the primary checkout (`hs_err_pid67003.log`:
`Module$Uniform.pushDescriptorSet` → `MVKBuffer::getMTLBuffer` inside
`Renderer.recordGraphicsPass`; `hs_err_pid73905.log`: `TexturePreviewCache.capture` →
`Queue.submit`) happen *during* a live frame or capture, while the renderer is alive. They are a
stale buffer handle inside a working renderer, not this ordering bug, and this change does not
address them.

## Verification

- `src/jvmTest/kotlin/afield/RendererTeardownOrderTest.kt` exercises the order with a fake device
  and no GPU. Dependents (one writing on the "render thread", one on a worker with 20 ms writes)
  must have completed before dispose, with zero writes after it. A non-descendant keeps running.
  The wait gives up at its bound when a job ignores cancellation, and an idle child costs nothing.
  Negative controls, run once on 2026-09-26: with the join removed, the ordering and bound tests
  fail. With the dependent stop removed, the ordering test fails.
- On a real Field (port 8472, MoltenVK, 2026-09-26), a renderer box (`start(renderer::run)`) with
  a connected child `loop {}` that `mapAndWrite`s a 1 MiB buffer every frame (the session log
  shows 330–360 frames written before the trigger):
  - base `9fa1b86e6`: `stop` on the renderer box gave a SIGSEGV in `mapAndWrite`. Opening another
    document (session detach) gave the same SIGSEGV. 2 of 2 crashed and left an hs_err file.
  - with this change, both triggers were clean: the log line
    `stopped 1 dependent box(es) first: Writer`, then `disposed renderer … openWindows 1 -> 0`,
    and a box run 4 s later printed. 2 of 2, no hs_err file.
  - a child writing from an untracked `thread {}` got the `IllegalStateException` after 171
    writes, and the JVM stayed up. One run, with the change only.

## Reproducing it

No demo document or transcript ships with this change. A demo directory would need a
`tools/test_demo_build_scripts.py` corpus count bump, and that file was off-limits the night this
landed. A transcript would need the gallery page and materialized assets regenerated for a scene
CI can only compile, since it opens a native window. These two boxes and one backdoor invocation
are the whole before/after. On base `9fa1b86e6` the last command fails because the JVM has
crashed; with the fix it prints.

Renderer box (`renderer_box.kt`):

```kotlin
import afield.four.util.Options
import afield.Rect

prop<Window>(window)
prop<Renderer>(renderer)

Options.enableHDR = false
once.renderer {
    window = Window("teardown repro", initialDimensions = Rect(80.0, 80.0, 480.0, 320.0))
    Renderer(window)
}
start(renderer::run)
```

Writer box (`writer_box.kt`), connected beneath it:

```kotlin
import afield.four.buffers.VulkanBuffer
import org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
import org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT

val buf = VulkanBuffer(renderer = renderer, requestedSize = 1L shl 20,
    usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, reqMask = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)
var n = 0
loop {
    buf.mapAndWrite { for (i in 0 until capacity()) put(i, n.toFloat()) }
    if (n % 30 == 0) println("wrote frame $n")
    n++
    frame()
}
```

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 8472 \
  --command 'new-box --name Rend --alias rend' \
  --command 'set @rend code --file renderer_box.kt' \
  --command 'launch @rend' --command 'sleep 4' \
  --command 'new-box --name Writer --alias writer --x 200' \
  --command 'connect @rend @writer' \
  --command 'launch @writer --file writer_box.kt' --command 'sleep 3' \
  --command 'stop @rend' --command 'sleep 4' \
  --command 'new-box --name Alive --alias alive --x 400' \
  --command 'run @alive --value "println(\"ALIVE_AFTER_TEARDOWN\")"'
```

Replace `stop @rend` with `open some_other_document` to drive the session-detach path instead.
Look for `hs_err_pid*.log` in the checkout root, which is the managed server's working directory.
