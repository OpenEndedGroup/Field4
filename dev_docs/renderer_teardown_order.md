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
  lock: a write that passed the check just before the device was freed can still fault.
- Since 2026-09-27 the two device calls that were unguarded refuse too. `Queue.submit` throws
  `IllegalStateException("Queue.submit after its Renderer began disposing …")`, and unlike the map
  check this one *is* a lock: `Renderer.cleanup()` closes submissions under
  `Queue.globalQueueLock` before its `waitIdle`, and `submit` checks under the same lock, so a
  submit either lands before the wait (which then covers it) or is refused. A descriptor push
  reads its buffer through `VulkanBuffer.descriptorBinding()`, which throws "… used after its
  Renderer was disposed …" in the same case, and "… used after cleanup() destroyed it …" once a
  buffer released with `cleanup()` has actually been destroyed (see
  [Immediate cleanup mid-frame](#immediate-cleanup-mid-frame-now-retired)). The renderer's own
  per-frame `vkQueueSubmit2` calls are not routed through `Queue.submit`; they run on the thread
  that runs cleanup, and `render()` refuses once cleanup has begun.
- Stopping the renderer box also stops its running children. Before 2026-09-26 they kept
  running with a dead renderer. The renderer box then carries a **`relaunch-stopped-dependents`**
  action that starts them again, see [Relaunching what teardown stopped](#relaunching-what-teardown-stopped).

## Relaunching what teardown stopped

Teardown remembers the dependents it stopped (`beginRendererTeardown` returns them, parents
before children) and `disposeBoxRenderer` installs a box action on the renderer box,
`relaunch-stopped-dependents`, labelled "Relaunch the N box(es) stopping this renderer stopped".
Run it from the command palette with the renderer box selected, or from the backdoor:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port <port> \
  --command 'action <renderer-box-uid> relaunch-stopped-dependents'
```

It launches the renderer box first if that box holds no renderer (so the children find one),
then the recorded boxes as one ordered launch batch, the way `launch-batch` does, and withdraws
itself. If you have already relaunched the renderer box by hand, it launches only the children.
A later teardown of the same box replaces the record. The teardown log names the action:
`renderer box Rend: to start the 2 stopped box(es) again, run its 'relaunch-stopped-dependents'
action …`, and the action logs `relaunched 3 box(es) for renderer box Rend: Rend, Ubo, Reader`.

The record lives in memory (`rendererTeardownStopped` in `agent_backdoor.kt`), not in the
document, so it does not survive a Field restart. That is the right lifetime: after a restart
nothing is running and there is nothing to put back.

One wrinkle for automation. When the action launches the renderer box itself, the backdoor
reports the action `ok: false`, because a second `Renderer` in the same JVM prints the Vulkan
loader's layer dump to that box as stderr (`VK | objects: Instance…: Using layer
configurations …`) and the backdoor counts stderr output as an execution error. The relaunch
itself succeeded (the log line above, and every box running afterwards). Relaunch the renderer
box with `launch @rend --no-fail-on-error` first, and the action then launches only children and
reports `ok: true`.

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
`Queue.submit`) happen *during* a live frame or capture, while the renderer is alive. They are
not this ordering bug. The first is explained below.

## Live-frame crash: a null buffer pushed mid-`realloc`

Written 2026-09-27.

**Reading the two surviving hs_errs.** Both fault at `MVKBuffer::getMTLBuffer()+0x10` with
`si_addr 0x68` and `x0 = 0`: MoltenVK was handed a NULL `MVKBuffer*` and read a field 0x68 bytes
into it. A `VkBuffer` handle *is* the `MVKBuffer*` on MoltenVK, so the handle reaching the driver
was 0. With the validation layer in the chain (it is, in both stacks) a destroyed handle also
arrives as 0, since the layer's unwrap of an unknown handle yields `VK_NULL_HANDLE`, so these logs
alone cannot tell "null" from "destroyed".

- `hs_err_pid67003` (2026-04-16, under the IntelliJ debugger): thread `renderer-worker`,
  `Module$Uniform.pushDescriptorSet` ← `Module.render` ← `RenderTarget.render` ←
  `Renderer.recordGraphicsPass`.
- `hs_err_pid73905` (2026-05-01): thread `main`, `TexturePreviewCache.capture` → `Queue.submit`,
  MoltenVK encoding a `vkCmdCopyImageToBuffer` whose buffer is the staging buffer created 30
  lines earlier in the same function. Explained, reproduced, and already fixed upstream on
  2026-05-02: see [The TexturePreviewCache crash](#the-texturepreviewcache-crash-hs_err_pid73905).

**What frees or nulls a buffer under a live renderer.** Not the obvious suspects. Garbage-collected
`VulkanBuffer`s go through `AutoDisposer.drain(device)` onto `Renderer.cleanup`, and that list is
released `8 × numImages` submitted frames later (`Renderer.render`, "Deferred cleanup queue"); a
buffer that is collected is by definition no longer bound by anything that records. Mesh vertex
buffers retire through the same queue (`VertexBuffer.cleanup`). So re-running or stopping a
graphics box does not free its buffers under a frame, and measurement agrees: a renderer plus a
UBO child (the shape of lesson `1b. hello triangle (UBO)`, with a mesh resized every frame),
relaunched 15 times and stopped-and-relaunched 15 more at 0.3–0.4 s intervals, **did not crash in
2 of 2 runs at `af3fb3fc7`**.

What does publish a 0 handle is `VulkanBuffer.realloc`. Until this change it cleared `buffer` to
0, then called `allocate()` (a `vmaCreateBuffer`), then stored the new handle. For the length of
that allocation, any other thread reading `buffer` got `VK_NULL_HANDLE`, and
`Module$Uniform.pushDescriptorSet` passes it straight to `vkCmdPushDescriptorSetKHR`, which
MoltenVK dereferences while recording. The threads that can read concurrently are the render
workers: `Renderer.render` records the compute pass and the graphics pass concurrently
(`asyncWorker` each), and on a device with `maintenance7` (this Mac) records each graphics module
into its own secondary command buffer in parallel. So a buffer written and grown from one pass
(a `before`/`after` Target, a compute-side `write {}` that outgrows its buffer, a raw thread) and
bound as a uniform by another races. Box code on the runloop does *not* race: `render()` runs on
the runloop and blocks it while the workers record (a box's top-level code and its `loop {}` both
print `field-runloop-main`).

**Reproduction.** The stressor makes the race frequent: a child box that reallocates the camera
UBO of its own module from a raw thread while the renderer records it every frame.

| Build | Runs | hs_err | Signature |
| --- | --- | --- | --- |
| `af3fb3fc7` (base) | 4 | 4 | `MVKBuffer::getMTLBuffer()+0x10`, `si_addr 0x68`, `renderer-worker`, `Module$Uniform.pushDescriptorSet` ← `recordGraphicsPass`: the same frames as `hs_err_pid67003` |
| this change | 5 | 0 | in all five a box run afterwards printed and the server log had no `VUID-` or `Validation Error`; in the two runs that counted, every stress thread finished its 20 000 reallocs of the 3 per-frame buffers (3 launches, 180 000 reallocs; 2 launches, 120 000) |

That is a synthetic trigger. It proves the mechanism and the fix; it does not prove that the April
crash came from this path rather than from something since fixed (the April `AutoDisposer`, for
one, drained every renderer's collected objects into whichever renderer drained first).

**The fix.**

- `VulkanBuffer.allocate(requested)` builds the new allocation in locals and publishes it last
  (the handle fields are `@Volatile`). A recorder reads either the old buffer, which stays alive
  until the retirement queue releases it, or the new one, never 0. The device address is computed
  eagerly, so a lazy `getGPUAddress()` cannot cache the previous allocation's address after a
  `realloc`.
- `VulkanBuffer.descriptorBinding()` returns the handle and the range as one immutable object, so
  a recorder cannot pair one allocation's handle with another's size, and refuses by name after
  `cleanup()`/`pushCleanup()` or once the renderer is disposed. `Module$Uniform` is the only
  buffer descriptor push in the tree, and uses it.
- `Queue.submit` refuses after teardown begins (above).
- `Renderer.cleanup`, the retirement list, is now a synchronized list; box threads add to it while
  the render thread swaps it out.

**Test.** `four.live-frame-buffer-lifetime.smoke` (`LiveFrameBufferLifetimeSmoke.kt`) checks the
three lifetimes on a real device without relying on a driver to fault: 27 million reads of the
handle across 20 000 reallocs see no zero; a buffer released with `cleanup()` stays valid for
the frames in flight (25 frames on this Mac) and then refuses a binding by name; after
`Renderer.cleanup()`, `Queue.submit` and `descriptorBinding()` refuse by name. Negative control,
run once: putting back `buffer = 0L` before `allocate` made it fail with 25.8 million zero handles
in 27.2 million reads, which is also a measure of how wide the window was.

## Immediate cleanup mid-frame, now retired

Written 2026-09-27, later the same night.

Until `f11771b33`, `VulkanBuffer.cleanup()`, `Module.cleanup()` and `ComputeModule.cleanup()`
destroyed their Vulkan handles at once, and so did everything built on them
(`GpuRadixSort.cleanup()`, `GaussianSplatRenderer`'s `Cloud.cleanup()`, `ImageReducer`, the RectSL
sequence renderer). Three child boxes under the renderer box above measured what that costs, each
run through `field_backdoor.py` on port 8472 with the lesson-1b shaders:

| Child box | at `28fa63187` | with `f11771b33` |
| --- | --- | --- |
| a raw thread replaces each of the module's per-frame camera UBOs with a new `VulkanBuffer` and calls `cleanup()` on the old one, 5 000 times | 2 of 3 runs crashed the JVM (`MVKBuffer::getMTLBuffer()+0x10` ← `Module$Uniform.pushDescriptorSet`, after `VUID-VkDescriptorBufferInfo-buffer-parameter`); the third survived only because the `descriptorBinding()` refusal threw on the render worker, which stopped the renderer box | 0 of 3; all 5 000 swaps done, no VUID, a box run afterwards printed |
| the same swap from the box's own `loop {}`, once per frame for 600 frames | 0 of 2 crashed, but each run logged `VUID-vkDestroyBuffer-buffer-00922` 40 times: a buffer destroyed while a submitted frame still used it. MoltenVK survives that; the spec calls it undefined | 0 of 2, no VUID |
| `module.current.cleanup()` on the box's own, still-installed module at frame 60 | 3 of 3 crashed the JVM (`MVKCmdBindGraphicsPipeline::isTessellationPipeline`, after `VUID-vkCmdBindPipeline-pipeline-parameter` and `VUID-vkDestroyPipeline-pipeline-00765`) | 0 of 3; the loop reached frame 300, no VUID |

The loop-only case is the one ordinary box code hits: box code runs on the runloop, which
`render()` blocks while it records, so it can never free a handle *during* recording, only while
earlier frames are still executing. The thread case is the race a worker, a raw `thread {}` or a
parallel recording pass can hit.

The fix uses the retirement queue that `realloc` already used rather than inventing one:

- `VulkanBuffer.cleanup()` queues the destruction on `renderer.cleanup`, released
  `8 × numImages` submitted frames later, while the renderer is alive; it destroys at once only
  after the renderer has closed (device idle; the teardown drain calls it that way). Between the
  two the buffer is still valid, and a render worker that read it a moment before is fine; after
  destruction, `map`, `unmap`, `descriptorBinding` refuse with "… used after cleanup() destroyed
  it …", and `realloc` of a released buffer refuses at once. `pushCleanup()` is now the same call.
- `Module.cleanup()` and `ComputeModule.cleanup()` set `valid = false` at once (now `@Volatile`),
  so no new frame records them, and retire the pipeline, layout, shader modules and descriptor
  layouts through the same queue.

The cost is that released memory is held for about 25 frames longer (measured in the smoke), the
same as a `realloc`'s old buffer.

The smoke covers the buffer half (valid across the frames in flight, refused after). The module
half is covered only by the live-Field runs above: a jvm-main smoke would need a whole shader
pipeline to build a `Module`.

## The TexturePreviewCache crash (`hs_err_pid73905`)

`git log -S "cpuOnly = true" -- src/jvmMain/kotlin/afield/four/FourVisualizers.kt` answers it. On
2026-05-01 `TexturePreviewCache.capture` created its staging buffer with `cpuOnly = true`. A
CPU-only `VulkanBuffer` is a `memAlloc`ed host block with `buffer = 0`; it has no VkBuffer. The
capture recorded `vkCmdCopyImageToBuffer(…, staging.buffer, …)` with that 0, and MoltenVK, which
encodes Metal commands at submit time, dereferenced the null `MVKBuffer*` inside
`MVKCmdBufferImageCopy::encode` during `vkQueueSubmit`: the `Queue.submit` frame in the hs_err.
`ed7fd0c26` ("harden docs capture pipeline", 2026-05-02, the next day) removed `cpuOnly = true`.

Reproduced on 2026-09-27 by putting that one line back and running the FIELD_TEST
`docs.fabricate.ambient-occlusion`, which drives `StandardRendererDocsHarness.captureFinalImage`
exactly as the hs_err's stack does: **2 of 2 runs crashed** with the same signature
(`MVKBuffer::getMTLBuffer()+0x10` ← `MVKCmdBufferImageCopy<1>::encode` ← `Queue.submit` ←
`TexturePreviewCache.capture`, on `field-runloop-main`). Without the line, 2 of 2 passed. Nothing
in this branch was needed for it; no code in `src/jvmMain` passes a CPU-only buffer to a transfer
command today (the only `cpuOnly = …` left is `magic.kt`'s inlined buffers), and
`descriptorBinding()` refuses a CPU-only buffer by name for the descriptor case.

## Two residual risks from 2026-09-26, checked

- **"Renderer properties are removed before the children stop, so a child mid-frame may log a
  missing-renderer error."** Not observed. A grandchild whose `loop {}` reads the inherited
  `renderer` every frame was running during `stop @rend` in 2 of 2 runs, and neither run showed an
  error output for it. The window is real in principle: stop runs on a websocket thread while the
  child's loop body runs on the runloop, and the body could read `renderer` in between. It is a
  few microseconds per 16 ms frame, and its worst case is an error line on a box that is being
  stopped anyway. Moving the removal after the join would close it and open a worse one: a
  renderer box relaunched within the 2 s join would find the dying renderer through `once.renderer
  {}` and reuse it. Left as is.
- **"`cleanedUp = true` is set before `drainDeferredCleanup`."** Intended, and harmless. The flag
  is what makes `render()`, `VulkanBuffer.map()` and now `descriptorBinding()` refuse, so it has to
  be set before the device is waited on, not after. The deferred actions themselves never consult
  it: every `renderer.cleanup` producer in `src/jvmMain` (grep `renderer.cleanup.add`,
  `renderer.cleanup +=`, `renderer.cleanup.addAll`) queues a destroy (`vmaDestroyBuffer` through
  `VulkanBuffer.Cleaner`/`cleanup()`, a command-buffer or pipeline destroy, a `close()`), none maps
  a buffer or submits. The one real hazard nearby was a producer adding to the list *after* the
  final drain, whose allocation would then be missed by the VMA leak check. The list is now
  synchronized and `realloc` refuses once cleanup has begun, but like the map check that is not
  a lock: a `realloc` that passed it just before teardown can still leak one allocation and make
  `cleanupAllocatorForRenderer` throw. Not observed.

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

### The live-frame crash and the relaunch action

Two more boxes, both run on 2026-09-27, one at a time, at `af3fb3fc7` and on this change. The
renderer box is `renderer_box.kt` above. The child's shaders are lesson 1b's, committed as
`public/docs/assets/canonical_graphics/85e8478b-529b-406e-b835-7c00912848f2_vertex_.glsl` and
`…_fragment_.glsl`; they declare the `camera` uniform block the child reallocates.

`realloc_stress_box.kt`, connected beneath the renderer box:

```kotlin
import afield.four.modules.Module

var m = Mesh()
var module = moduleHere()
module += "mainMesh" to m
renderer += module
m.set(3, 1) {
    pos.add(0, 0, 0, 1, 0, 0, 0, 1, 0)
    3 { col.add(1, 1, 1, 1) }
    tri.add(0, 1, 2)
}

// The `camera` UBO is a per-frame group of VulkanBuffers that Module$Uniform pushes every frame,
// on a render worker. This thread reallocates them as fast as it can.
val ubo = module.current.uniforms["camera"] as Module.Uniform
val report = java.io.File(System.getProperty("user.dir"), "build/live-frame-realloc-count.txt")
kotlin.concurrent.thread(isDaemon = true, name = "realloc-stress") {
    var n = 0
    try {
        while (n < 20000) {
            ubo.buffer.buffers.forEach { it.realloc(128L + (n % 7) * 64L) }
            n++
        }
        report.appendText("REALLOC_DONE $n\n")
    } catch (t: Throwable) {
        report.appendText("REALLOC_THREW after $n: $t\n")
    }
}
loop { frame() }
```

```bash
G=public/docs/assets/canonical_graphics/85e8478b-529b-406e-b835-7c00912848f2
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 8472 \
  --command 'new-box --name Rend --alias rend' \
  --command 'set @rend code --file renderer_box.kt' \
  --command 'launch @rend' --command 'sleep 4' \
  --command 'new-box --name Stress --alias stress --x 200' \
  --command 'connect @rend @stress' \
  --command "set @stress vertex --file ${G}_vertex_.glsl" \
  --command "set @stress fragment --file ${G}_fragment_.glsl" \
  --command 'launch @stress --file realloc_stress_box.kt' --command 'sleep 8' \
  --command 'stop @rend' --command 'sleep 4' \
  --command 'launch @rend --no-fail-on-error' --command 'sleep 4' \
  --command 'action @rend relaunch-stopped-dependents' --command 'sleep 6' \
  --command 'wait-running @stress --state running --timeout 10' \
  --command 'new-box --name Alive --alias alive --x 400' \
  --command 'run @alive --value "println(\"ALIVE_AFTER_RELAUNCH\")"'
```

At `af3fb3fc7` the JVM dies during the stress box's `sleep 8`; the next command (`stop @rend`)
fails with `no close frame received or sent`, and an `hs_err_pid*.log`
naming `MVKBuffer::getMTLBuffer()+0x10` and `Module$Uniform.pushDescriptorSet` appears in the
checkout root. (That base has no relaunch action either.) On this change,
`build/live-frame-realloc-count.txt` gains a `REALLOC_DONE 20000` line per launch of the stress
box, the teardown log says `stopped 1 dependent box(es) first: Stress` and names the action, the
action launches only `Stress` (the renderer is back already) and reports `ok: true`, and
`ALIVE_AFTER_RELAUNCH` prints. Drop the `launch @rend` line to let the action relaunch the
renderer too; it then does (`relaunched 2 box(es) for renderer box Rend: Rend, Stress` in the
server log), but reports `ok: false` for the stderr reason under
[Relaunching what teardown stopped](#relaunching-what-teardown-stopped), and the backdoor stops
the invocation there.

### Immediate `Module.cleanup()` mid-frame

Same renderer box and shaders as above; replace the stress box with `module_cleanup_box.kt` and
drop the `stop`/`action` lines (run on 2026-09-27 at `28fa63187` and at `f11771b33`):

```kotlin
var m = Mesh()
var module = moduleHere()
module += "mainMesh" to m
renderer += module
m.set(3, 1) {
    pos.add(0, 0, 0, 1, 0, 0, 0, 1, 0)
    3 { col.add(1, 1, 1, 1) }
    tri.add(0, 1, 2)
}

// Free this box's own pipeline with Module.cleanup() while the module is still installed and
// frames that bound it are in flight, the way a `Cloud.cleanup()` or a sort's cleanup() can.
val report = java.io.File(System.getProperty("user.dir"), "build/live-frame-cleanup.txt")
var n = 0
loop {
    n++
    if (n == 60) {
        module.current.cleanup()
        report.appendText("MODULE_CLEANED at frame $n\n")
    }
    if (n == 300) report.appendText("LOOP_REACHED $n\n")
    frame()
}
```

At `28fa63187` the JVM dies right after frame 60 (`MVKCmdBindGraphicsPipeline` in the hs_err).
With `f11771b33`, `build/live-frame-cleanup.txt` reads `MODULE_CLEANED at frame 60` then
`LOOP_REACHED 300` and the server log has no `VUID-`. The window stays up but shows nothing
either way: this box never writes its camera UBO, so its triangle is degenerate before and after
the cleanup. The test is that the JVM and the renderer survive, not a picture.

