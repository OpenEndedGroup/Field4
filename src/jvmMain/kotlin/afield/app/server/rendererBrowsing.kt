package afield.app.server

import afield.graphics.vulkan.CPUSpan
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.device.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import afield.graphics.vulkan.targets.ComputeTarget
import afield.graphics.vulkan.targets.RenderTarget
import afield.graphics.texture.*
import afield.graphics.texture.video.VideoTexture
import afield.graphics.vulkan.util.Window
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val rendererAdaptersRegistered = AtomicBoolean(false)
private val cpuLastPushMs = AtomicLong(0)

/** Frames per live-update step for adapters whose version follows the frame counter. */
internal const val LIVE_FRAMES = 60L

/**
 * One-time registration of the Renderer/graphics [Browsable] adapters, so `column("r", renderer)` and
 * `tree("r", renderer)` open the renderer as targets (in draw order), modules with their shaders and
 * bindings, textures, CPU span timings and profiler state. Called from [installInspectorHandler] at
 * server start; idempotent.
 *
 * Thread ownership: `dir()`/`html()` run inside `Inspector.handle`, which the `__browsing__` handler
 * runs `inMain`, the same Runloop thread that records and submits frames, so they never read a
 * target mid-record. `version()` runs on the inspector's poll timer and reads only scalars
 * (frame counter, map sizes), quantised so a live renderer refreshes the browser about once a second
 * rather than on every frame.
 */
fun registerRendererBrowsingAdapters() {
    if (!rendererAdaptersRegistered.compareAndSet(false, true)) return
    CPUSpan.addListener { maybePushCpuChanged() }

    // Renderer + windowing ------------------------------------------------------------------------------------------

    reg<Renderer> { r, _, _ ->
        object : Browsable {
            override fun heading(): String = "Renderer"
            override fun dir(): Map<String, Any?> {
                val contexts = r.windows.indices.associate { i -> "context[$i]" to r.windowContext(i) }
                val windows = r.windows.withIndex().associate { (i, w) -> "window[$i]" to w }
                val queues = mapOf("graphics" to r.graphQueue, "present" to r.presentQueue)
                return linkedMapOf(
                    "render" to r.renderList,
                    "compute" to r.computeList,
                    "contexts" to contexts,
                    "windows" to windows,
                    "queues" to queues,
                    "cpu" to CpuNode,
                    "device" to r.device,
                    "pipelineCache" to r.pipelineCache,
                    "profiling" to runCatching { r.windowContext(0).profilerState }.getOrNull()
                )
            }

            override fun html(): String {
                val cpu = CPUSpan.snapshot()
                val cpuLine = cpu.joinToString(" · ") {
                    "${it.label}:${"%.2f".format(it.p50Ms)}ms p50/${"%.2f".format(it.p90Ms)} p90"
                }.ifBlank { "n/a" }
                val rows = listOf(
                    "frame" to "#${r.frameCounter}",
                    "images" to "${r.currentImage}/${r.numImages}",
                    "gfx modules" to "${r.renderList.modules.size} + mesh ${r.renderList.meshModules.size}",
                    "compute modules" to "${r.computeList.modules.size}",
                    "windows" to r.windows.joinToString { it.title },
                    "cpu" to cpuLine
                )
                return card("Renderer", rows, accent = "#7dd3fc")
            }

            override fun version(): Long = r.frameCounter / LIVE_FRAMES
        }
    }

    reg<Renderer.WindowContext> { ctx, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "WindowContext"
            override fun dir(): Map<String, Any?> {
                return linkedMapOf(
                    "window" to ctx.window,
                    "swapchain" to ctx.swapChain,
                    "renderTarget" to ctx.renderTarget,
                    "depthAttachments" to ctx.depthAttachments,
                    "commandBuffers" to ctx.commandBuffers.toList(),
                    "computeCommandBuffers" to ctx.computeCommandBuffers.toList(),
                    "fences" to ctx.fences.toList(),
                    "computeFences" to ctx.computeFences.toList(),
                    "computeSemaphores" to ctx.computeFinishedSemaphores.toList(),
                    "secondary" to mapOf(
                        "graphics" to ctx.graphicsSecondary,
                        "compute" to ctx.computeSecondary
                    ),
                    "commandPools" to mapOf(
                        "graphics" to ctx.graphicsPoolsPerThread,
                        "compute" to ctx.computePoolsPerThread
                    ),
                    "profiling" to ctx.profilerState
                )
            }

            override fun html(): String {
                val extent = ctx.swapChain.swapChainExtent
                val rows = listOf(
                    "image" to "${ctx.currentImage}/${ctx.numImages}",
                    "swapchain" to "${extent.width()}x${extent.height()} ${ctx.swapChain.surfaceFormat.imageFormat}",
                    "modules" to "${ctx.renderTarget.modules.size} gfx / ${ctx.renderTarget.meshModules.size} mesh",
                    "secondary" to "${ctx.graphicsSecondary.size} gfx / ${ctx.computeSecondary.size} cs"
                )
                return card(heading(), rows, accent = "#a3e635")
            }

            override fun version(): Long = (ctx.currentImage.toLong() shl 32) or ctx.numImages.toLong()
        }
    }

    reg<Window> { w, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "Window"
            override fun dir(): Map<String, Any?>? = null
            override fun html(): String = card(
                heading(),
                listOf(
                    "title" to w.title,
                    "size" to "${w.width}x${w.height}",
                    "fullscreen" to w.fullscreen.toString(),
                    "resized" to w.isResized.toString()
                ),
                accent = "#38bdf8"
            )

            override fun version(): Long = (w.width.toLong() shl 32) or w.height.toLong()
        }
    }

    // Targets --------------------------------------------------------------------------------------------------------

    reg<RenderTarget> { rt, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "RenderTarget"
            // Keys in the order RenderTarget.render records them: pre-targets, then graphics modules,
            // then mesh-shader modules (each map in insertion order), then the "others" targets.
            override fun dir(): Map<String, Any?> = linkedMapOf(
                "preTargets" to rt.preTargets,
                "modules" to rt.modules,
                "meshModules" to rt.meshModules,
                "others" to rt.others,
                "preRecorded" to (rt.preRecordedInsideRendering ?: emptyList<CommandBuffer>())
            )

            override fun html(): String {
                val rows = listOf(
                    "1. preTargets" to names(rt.preTargets.keys),
                    "2. modules" to names(rt.modules.keys),
                    "3. mesh" to names(rt.meshModules.keys),
                    "4. others" to names(rt.others.keys)
                )
                return card(heading(), rows, accent = "#c084fc")
            }

            override fun version(): Long =
                rt.modules.size + rt.meshModules.size + rt.others.size + rt.preTargets.size.toLong()
        }
    }

    reg<ComputeTarget> { ct, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "ComputeTarget"
            override fun dir(): Map<String, Any?> = linkedMapOf(
                "modules" to ct.modules,
                "others" to ct.others
            )

            override fun html(): String = card(
                heading(),
                listOf(
                    "modules" to names(ct.modules.keys),
                    "others" to names(ct.others.keys)
                ),
                accent = "#22d3ee"
            )

            override fun version(): Long = ct.modules.size.toLong()
        }
    }

    // Modules --------------------------------------------------------------------------------------------------------

    reg<ModulePointer> { mp, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: mp.current.parameters.name
            override fun dir(): Map<String, Any?> = mapOf("module" to mp.current)
            override fun html(): String = card(
                heading(),
                listOf(
                    "valid" to mp.current.valid.toString(),
                    "enabled" to mp.current.enabled.toString(),
                    "meshes" to mp.current.meshes.size.toString()
                ),
                accent = "#f97316"
            )

            override fun version(): Long = mp.current.parameters.hashCode().toLong()
        }
    }

    reg<MeshModulePointer> { mp, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: mp.current.parameters.name
            override fun dir(): Map<String, Any?> = mapOf("module" to mp.current)
            override fun html(): String = card(
                heading(),
                listOf(
                    "valid" to mp.current.valid.toString(),
                    "enabled" to mp.current.enabled.toString(),
                    "meshes" to mp.current.meshes.size.toString()
                ),
                accent = "#fb7185"
            )

            override fun version(): Long = mp.current.parameters.hashCode().toLong()
        }
    }

    reg<ComputeModulePointer> { mp, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: mp.current.parameters.name
            override fun dir(): Map<String, Any?> = mapOf("module" to mp.current)
            override fun html(): String = card(
                heading(),
                listOf(
                    "valid" to mp.current.valid.toString(),
                    "enabled" to mp.current.enabled.toString(),
                    "tasks" to mp.current.meshes.size.toString()
                ),
                accent = "#0ea5e9"
            )

            override fun version(): Long = mp.current.parameters.hashCode().toLong()
        }
    }

    reg<Module> { m, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: m.parameters.name
            override fun dir(): Map<String, Any?> = linkedMapOf(
                "parameters" to m.parameters,
                "uniforms" to m.uniforms,
                "liveUniforms" to m.liveUniformStructures,
                "meshes" to m.meshes
            )

            override fun html(): String {
                val rows = listOf(
                    "valid" to m.valid.toString(),
                    "enabled" to m.enabled.toString(),
                    "pipelines" to if (m.pipeline != 0L) "built" else "missing",
                    "uniforms" to m.uniforms.size.toString(),
                    "meshes" to m.meshes.size.toString(),
                    "shaders" to "${trimName(m.parameters.vertex)} / ${trimName(m.parameters.fragment)}"
                )
                return card(heading(), rows, accent = "#22c55e")
            }

            override fun version(): Long = m.parameters.renderer.frameCounter / LIVE_FRAMES
        }
    }

    reg<MeshModule> { m, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: m.parameters.name
            override fun dir(): Map<String, Any?> = linkedMapOf(
                "parameters" to m.parameters,
                "uniforms" to m.uniforms,
                "liveUniforms" to m.liveUniformStructures,
                "meshes" to m.meshes
            )

            override fun html(): String {
                val rows = listOf(
                    "valid" to m.valid.toString(),
                    "enabled" to m.enabled.toString(),
                    "meshes" to m.meshes.size.toString(),
                    "shaders" to "${trimName(m.parameters.task)} / ${trimName(m.parameters.mesh)} / ${trimName(m.parameters.fragment)}"
                )
                return card(heading(), rows, accent = "#d946ef")
            }

            override fun version(): Long = m.parameters.renderer.frameCounter / LIVE_FRAMES
        }
    }

    reg<ComputeModule> { m, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: m.parameters.name
            override fun dir(): Map<String, Any?> = linkedMapOf(
                "parameters" to m.parameters,
                "uniforms" to m.uniforms,
                "liveUniforms" to m.liveUniformStructures,
                "tasks" to m.meshes
            )

            override fun html(): String {
                val rows = listOf(
                    "valid" to m.valid.toString(),
                    "enabled" to m.enabled.toString(),
                    "tasks" to m.meshes.size.toString(),
                    "shader" to trimName(m.parameters.compute)
                )
                return card(heading(), rows, accent = "#38bdf8")
            }

            override fun version(): Long = m.parameters.renderer.frameCounter / LIVE_FRAMES
        }
    }

    reg<Parameters> { p, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: p.name
            override fun dir(): Map<String, Any?>? = null
            override fun html(): String {
                val rows = listOf(
                    "name" to p.name,
                    "color" to "${p.color} attachments @ ${p.imageFormat}",
                    "depth" to if (p.depth > 0) "enabled (${p.depthFormat})" else "disabled",
                    "primitive" to p.primitiveType.toString(),
                    "viewport" to (p.viewport?.let { "${it.w.toInt()}x${it.h.toInt()}" } ?: "full"),
                    "blend" to p.blend.toString()
                )
                return card(heading(), rows, accent = "#f59e0b")
            }

            override fun version(): Long = (p.name.hashCode().toLong() shl 32) or p.imageFormat.toLong()
        }
    }

    reg<Module.Uniform> { u, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: u.name
            override fun dir(): Map<String, Any?> = mapOf("buffer" to u.buffer, "source" to u.source)
            override fun html(): String = card(
                heading(),
                listOf(
                    "set/binding" to "${u.binding.set}/${u.binding.binding}",
                    "kind" to "UBO/SSBO",
                    "size" to u.buffer.get().requestedSize.toString()
                ),
                accent = "#67e8f9"
            )
        }
    }

    reg<Module.TexturePointer> { t, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: t.name
            override fun dir(): Map<String, Any?> = mapOf(
                "texture" to t.texture,
                "sampler" to t.sampler
            )

            override fun html(): String = card(
                heading(),
                listOf(
                    "set/binding" to "${t.binding.set}/${t.binding.binding}",
                    "views" to t.texture.imageViewArray.size.toString()
                ),
                accent = "#f472b6"
            )
        }
    }

    reg<Module.TextureArrayPointer> { t, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: t.name
            override fun dir(): Map<String, Any?> = mapOf(
                "texture" to t.texture,
                "sampler" to t.sampler
            )

            override fun html(): String = card(
                heading(),
                listOf(
                    "set/binding" to "${t.binding.set}/${t.binding.binding}",
                    "views" to t.texture.imageViewArray.size.toString()
                ),
                accent = "#c4b5fd"
            )
        }
    }

    reg<Module.ImagePointer> { t, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: t.name
            override fun dir(): Map<String, Any?> = mapOf("image" to t.texture)
            override fun html(): String = card(
                heading(),
                listOf("set/binding" to "${t.binding.set}/${t.binding.binding}"),
                accent = "#fb7185"
            )
        }
    }

    // Meshes --------------------------------------------------------------------------------------------------------

    reg<Mesh> { m, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "Mesh"
            override fun dir(): Map<String, Any?> = linkedMapOf(
                "vertices" to m.v,
                "namedVertices" to m.vn,
                "triangleCount" to m.numTriangle,
                "vertexCount" to m.numVert,
                "pushConstants" to m.push
            )

            override fun html(): String = card(
                heading(),
                listOf(
                    "verts" to m.numVert.toString(),
                    "triangles" to m.numTriangle.toString(),
                    "instances" to m.instanceCount.toString()
                ),
                accent = "#10b981"
            )
        }
    }

    reg<MeshMesh> { m, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "Mesh task"
            override fun dir(): Map<String, Any?> = mapOf("pushConstants" to m.push)
            override fun html(): String = card(
                heading(),
                listOf("x/y/z" to "${m.x} / ${m.y} / ${m.z}"),
                accent = "#f97316"
            )
        }
    }

    reg<ComputeMesh> { m, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "Compute task"
            override fun dir(): Map<String, Any?> = mapOf("pushConstants" to m.push)
            override fun html(): String = card(
                heading(),
                listOf("x/y/z" to "${m.x} / ${m.y} / ${m.z}"),
                accent = "#22d3ee"
            )
        }
    }

    // Textures ------------------------------------------------------------------------------------------------------

    reg<Attachment> { a, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: if (a.isDepthAttachment) "Depth Attachment" else "Attachment"
            override fun dir(): Map<String, Any?> = mapOf("image" to a.image, "view" to a.imageView)
            override fun html(): String = card(
                heading(),
                listOf(
                    "depth" to a.isDepthAttachment.toString(),
                    "format" to a.image.format.toString()
                ),
                accent = "#60a5fa"
            )
        }
    }

    reg<Image> { img, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "Image"
            override fun dir(): Map<String, Any?>? = null
            override fun html(): String = card(
                heading(),
                listOf(
                    "format" to img.format.toString(),
                    "mips" to img.mipLevels.toString(),
                    "handle" to hex(img.vkImage)
                ),
                accent = "#0ea5e9"
            )
        }
    }

    reg<ImageView> { view, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "ImageView"
            override fun dir(): Map<String, Any?> = view.underlyingImage?.let { mapOf("image" to it) } ?: emptyMap()
            override fun html(): String = card(
                heading(),
                listOf(
                    "format" to view.imageViewData.format.toString(),
                    "mips" to view.imageViewData.mipLevels.toString(),
                    "view" to hex(view.vkImageView)
                ),
                accent = "#a78bfa"
            )
        }
    }

    reg<LoadedTexture> { tex, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: tex.fileName
            override fun dir(): Map<String, Any?> = mapOf("image" to tex.image, "view" to tex.imageView)
            override fun html(): String = card(
                heading(),
                listOf(
                    "size" to "${tex.width}x${tex.height}",
                    "format" to tex.image.format.toString(),
                    "staging" to (tex.stgBuffer != null).toString()
                ),
                accent = "#f472b6"
            )
        }
    }

    reg<HasImageView> { hv, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: hv.imageView.toString()
            override fun dir(): Map<String, Any?> = mapOf("view" to hv.imageView)
            override fun html(): String = card(
                heading(),
                listOf("view" to hex(hv.imageView.vkImageView)),
                accent = "#22c55e"
            )
        }
    }

    reg<TextureSampler> { s, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "Sampler"
            override fun dir(): Map<String, Any?>? = null
            override fun html(): String = card(heading(), listOf("handle" to hex(s.vkSampler)), accent = "#fcd34d")
        }
    }

    reg<VideoTexture> { v, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "VideoTexture"
            override fun dir(): Map<String, Any?>? = null
            override fun html(): String {
                val width = readField<Int>(v, "videoWidth") ?: 0
                val height = readField<Int>(v, "videoHeight") ?: 0
                val playing = readField<Boolean>(v, "isPlaying") ?: false
                val ts = readField<Double>(v, "currentTimestamp") ?: 0.0
                val path = readField<String>(v, "currentVideoPath") ?: "(none)"
                val slot = readField<Any>(v, "currentSlot")?.let { (it as? java.util.concurrent.atomic.AtomicInteger)?.get() } ?: 0
                return card(
                    heading(),
                    listOf(
                        "video" to path,
                        "size" to if (width > 0) "${width}x${height}" else "unloaded",
                        "timestamp" to "%.2fs".format(ts),
                        "playing" to playing.toString(),
                        "slot" to slot.toString()
                    ),
                    accent = "#fb7185"
                )
            }

            override fun version(): Long =
                (readField<java.util.concurrent.atomic.AtomicInteger>(v, "currentSlot")?.get()?.toLong() ?: 0L) + v.timestamp.hashCode()
        }
    }

    // Device/runtime -------------------------------------------------------------------------------------------------

    reg<SwapChain> { sc, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "SwapChain"
            override fun dir(): Map<String, Any?> = linkedMapOf(
                "images" to sc.imageViews.toList(),
                "syncSemaphores" to sc.syncSemaphoresList.toList()
            )

            override fun html(): String {
                val rows = listOf(
                    "extent" to "${sc.swapChainExtent.width()}x${sc.swapChainExtent.height()}",
                    "format" to sc.surfaceFormat.imageFormat.toString(),
                    "images" to sc.imageViews.size.toString(),
                    "currentFrame" to sc.currentFrame.toString()
                )
                return card(heading(), rows, accent = "#60a5fa")
            }

            override fun version(): Long = sc.currentFrame.toLong()
        }
    }

    reg<Queue> { q, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "Queue"
            override fun dir(): Map<String, Any?>? = null
            override fun html(): String = card(
                heading(),
                listOf(
                    "family" to q.queueFamilyIndex.toString(),
                    "handle" to hex(q.vkQueue.address())
                ),
                accent = "#facc15"
            )
        }
    }

    reg<CommandBuffer> { cb, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "CommandBuffer"
            override fun dir(): Map<String, Any?>? = null
            override fun html(): String = card(
                heading(),
                listOf("handle" to hex(cb.vkCommandBuffer.address())),
                accent = "#a3e635"
            )
        }
    }

    reg<CommandPool> { cp, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "CommandPool"
            override fun dir(): Map<String, Any?>? = null
            override fun html(): String = card(
                heading(),
                listOf(
                    "family" to cp.queueFamilyIndex.toString(),
                    "handle" to hex(cp.vkCommandPool)
                ),
                accent = "#f97316"
            )
        }
    }

    // Profiling ------------------------------------------------------------------------------------------------------

    reg<Renderer.ProfilerState> { ps, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "ProfilerState"
            override fun dir(): Map<String, Any?> = linkedMapOf(
                "profiler" to ps.profiler,
                "markers" to ps.markers.toList(),
                "perImageModuleMarks" to ps.perImageModuleMarks.withIndex().associate { (i, v) -> "image[$i]" to v },
                "pending" to ps.pending
            )

            override fun html(): String = card(
                heading(),
                listOf(
                    "markers" to ps.markers.size.toString(),
                    "pending" to ps.pending.size.toString()
                ),
                accent = "#22c55e"
            )

            override fun version(): Long = ps.pending.size.toLong()
        }
    }

    reg<Renderer.FrameMarkers> { m, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "Frame markers"
            override fun dir(): Map<String, Any?> = emptyMap()
            override fun html(): String = card(
                heading(),
                listOf(
                    "frame" to m.perfFrameIndex.toString(),
                    "ts" to "${m.tsFrameBegin}/${m.tsAfterCompute}/${m.tsAfterGraphics}/${m.tsFrameEnd}",
                    "pipeStats" to "${m.pipeStatsCompute}/${m.pipeStatsGraphics}"
                ),
                accent = "#c084fc"
            )
        }
    }

    reg<Renderer.ModuleMarks> { m, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: m.name
            override fun dir(): Map<String, Any?> = emptyMap()
            override fun html(): String = card(
                heading(),
                listOf(
                    "timestamps" to "${m.tsBegin}/${m.tsEnd}",
                    "pipeStats" to m.pipeStatsIndex.toString(),
                    "perf" to m.perfIndex.toString()
                ),
                accent = "#fb7185"
            )
        }
    }

    reg<GpuProfiler> { p, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "GpuProfiler"
            override fun dir(): Map<String, Any?> = mapOf("perfMeta" to p.perfMeta)
            override fun html(): String = card(
                heading(),
                listOf(
                    "enabled" to p.enabled.toString(),
                    "timestamps" to p.timestampsEnabled.toString(),
                    "pipeStats" to p.pipeStatsEnabled.toString(),
                    "perfCounters" to p.perfQueryEnabled.toString(),
                    "period(ns)" to "%.2f".format(p.timestampPeriodNs)
                ),
                accent = "#4ade80"
            )
        }
    }

    reg<ProfilingOptions> { o, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "ProfilingOptions"
            override fun dir(): Map<String, Any?> = emptyMap()
            override fun html(): String = card(
                heading(),
                listOf(
                    "enabled" to o.enabled.toString(),
                    "timestamps" to o.timestamps.toString(),
                    "pipelineStats" to o.pipelineStats.toString(),
                    "perfCounters" to o.perfCounters.toString()
                ),
                accent = "#93c5fd"
            )
        }
    }
}

/**
 * Registers [adapter] with [BrowsableAdapters], shaped so its card is seen: the inspector shows
 * `html()` only for leaves, so a node with children gets its card as a first `summary` row, and a
 * node whose `dir()` is empty becomes a leaf.
 */
private inline fun <reified T : Any> reg(noinline adapter: (T, Browsable?, String?) -> Browsable?) {
    BrowsableAdapters.register(T::class) { v: T, parent, title -> adapter(v, parent, title)?.let(::withSummaryRow) }
}

internal fun withSummaryRow(b: Browsable): Browsable {
    val d = b.dir()
    if (d.isNullOrEmpty()) return object : Browsable {
        override fun heading(): String = b.heading()
        override fun html(): String? = b.html()
        override fun version(): Long = b.version()
    }
    if (b.html() == null) return b
    return object : Browsable {
        override fun heading(): String = b.heading()
        override fun dir(): Map<String, Any?> = linkedMapOf<String, Any?>("summary" to SummaryCard(b)) + (b.dir() ?: emptyMap())
        override fun version(): Long = b.version()
    }
}

/** The card of a directory node, as a leaf row. */
private class SummaryCard(val of: Browsable) : Browsable {
    override fun heading(): String = of.heading()
    override fun html(): String? = of.html()
    override fun version(): Long = of.version()
    override fun equals(other: Any?) = other is SummaryCard && other.of.heading() == of.heading()
    override fun hashCode(): Int = of.heading().hashCode()
}

// -- helpers ---------------------------------------------------------------------------------------------------------

private fun card(title: String, rows: List<Pair<String, String>>, accent: String = "#9ca3af"): String {
    val body = rows.joinToString("") { (k, v) ->
        """
        <div style="display:flex;gap:6px;align-items:flex-start;padding:1px 0;">
          <div style="flex:0 0 80px;text-align:right;color:#6b7280;font-size:11px;letter-spacing:.05em;text-transform:uppercase;">${escapeHtml(k)}</div>
          <div style="flex:0 0 6px;text-align:center;color:#4b5563;">|</div>
          <div style="flex:1;">${ToStringHighlighter.render(v)}</div>
        </div>
        """.trimIndent()
    }
    return """
      <div style="padding:4px 0;font-family:inherit;">
        $body
      </div>
    """.trimIndent()
}

private fun names(keys: Collection<String>): String = if (keys.isEmpty()) "-" else keys.joinToString(", ")

private fun trimName(path: String): String = path.substringAfterLast('/').ifBlank { path }

private fun hex(v: Long): String = "0x" + v.toString(16)

private fun fmt(v: Double): String = String.format("%.2f", v)

private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun <T> readField(target: Any, name: String): T? {
    return runCatching {
        val f: Field = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        f.get(target) as T?
    }.getOrNull()
}

// Browsable CPU node (stable identity for change pushes)
private object CpuNode : Browsable {
    override fun heading(): String = "CPU"
    override fun dir(): Map<String, Any?>? = null
    override fun html(): String {
        val snaps = CPUSpan.snapshot()
        if (snaps.isEmpty()) return "<div style='color:#6b7280;'>no samples</div>"

        val header = listOf("span", "p1", "p10", "p50", "p90", "p99")
        val rows = snaps.joinToString("") { s ->
            """
            <div style="display:grid;grid-template-columns:120px repeat(5,70px);gap:8px;align-items:center;padding:2px 0;">
              <div style="color:#e5e7eb;font-family:'JetBrains Mono','SFMono-Regular',monospace;">${escapeHtml(s.label)}</div>
              <div style="color:#cbd5e1;text-align:right;font-family:'JetBrains Mono','SFMono-Regular',monospace;">${fmt(s.p1Ms)}</div>
              <div style="color:#cbd5e1;text-align:right;font-family:'JetBrains Mono','SFMono-Regular',monospace;">${fmt(s.p10Ms)}</div>
              <div style="color:#e5e7eb;text-align:right;font-family:'JetBrains Mono','SFMono-Regular',monospace;">${fmt(s.p50Ms)}</div>
              <div style="color:#fbbf24;text-align:right;font-family:'JetBrains Mono','SFMono-Regular',monospace;">${fmt(s.p90Ms)}</div>
              <div style="color:#f97316;text-align:right;font-family:'JetBrains Mono','SFMono-Regular',monospace;">${fmt(s.p99Ms)}</div>
            </div>
            """.trimIndent()
        }
        val headRow = header.joinToString("") {
            """
            <div style="text-transform:uppercase;font-size:11px;letter-spacing:.08em;color:#9ca3af;text-align:${if (it == "span") "left" else "right"};font-family:'JetBrains Mono','SFMono-Regular',monospace;">${escapeHtml(it)}</div>
            """.trimIndent()
        }
        val n = snaps.firstOrNull()?.n ?: 0
        return """
        <div style="padding:4px 0;font-family:inherit;">
          <div style="color:#6b7280;font-size:11px;letter-spacing:.05em;text-transform:uppercase;margin-bottom:4px;">window · $n samples</div>
          <div style="display:grid;grid-template-columns:120px repeat(5,70px);gap:8px;padding:2px 0;border-bottom:1px solid #1f2937;">$headRow</div>
          $rows
        </div>
        """.trimIndent()
    }

    override fun version(): Long = CPUSpan.version()
}

private fun maybePushCpuChanged(intervalMs: Long = 1000) {
    val now = System.currentTimeMillis()
    val last = cpuLastPushMs.get()
    if (now - last < intervalMs) return
    if (cpuLastPushMs.compareAndSet(last, now)) {
        Inspector.changed(CpuNode)
    }
}
