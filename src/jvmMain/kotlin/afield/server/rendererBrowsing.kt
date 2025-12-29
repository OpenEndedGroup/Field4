package afield.server

import afield.four.CPUSpan
import afield.four.Renderer
import afield.four.device.*
import afield.four.modules.*
import afield.four.targets.ComputeTarget
import afield.four.targets.RenderTarget
import afield.four.texture.*
import afield.four.texture.video.VideoTexture
import afield.four.util.Window
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val rendererAdaptersRegistered = AtomicBoolean(false)
private val cpuLastPushMs = AtomicLong(0)

/** Call once from your app to expose the renderer and install all adaptors. */
fun installRendererBrowsing(
    renderer: Renderer,
    label: String = "Renderer",
    extraRoots: Map<String, Any?> = emptyMap()
) {
    registerRendererBrowsingAdapters()
    Inspector.setRoots { extraRoots + mapOf(label to renderer) }
}

/** One-time registration of Renderer/graphics Browsable adapters. */
fun registerRendererBrowsingAdapters() {
    if (!rendererAdaptersRegistered.compareAndSet(false, true)) return
    CPUSpan.addListener { maybePushCpuChanged() }

    // Generic helpers -----------------------------------------------------------------------------------------------

    BrowsableAdapters.register(Map::class) { m: Map<*, *>, _, title ->
        val entries = m.entries.associate { (k, v) -> (k?.toString() ?: "null") to v }
        object : Browsable {
            override fun heading(): String = title ?: "Map"
            override fun dir(): Map<String, Any?> = entries
            override fun html(): String = card(heading(), listOf("size" to m.size.toString()))
            override fun version(): Long = m.size.toLong()
        }
    }

    BrowsableAdapters.register(Iterable::class) { seq: Iterable<*>, _, title ->
        if (seq is Map<*, *>) return@register null // handled above
        val items = seq.toList()
        object : Browsable {
            override fun heading(): String = title ?: "List"
            override fun dir(): Map<String, Any?> = items.mapIndexed { idx, v -> "[${idx}]" to v }.toMap()
            override fun html(): String = card(heading(), listOf("items" to items.size.toString()))
            override fun version(): Long = items.size.toLong()
        }
    }

    // Renderer + windowing ------------------------------------------------------------------------------------------

    BrowsableAdapters.register(Renderer::class) { r, _, _ ->
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
                    "profiling" to r.windowContext(0).profilerState
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

            override fun version(): Long = r.frameCounter
        }
    }

    BrowsableAdapters.register(Renderer.WindowContext::class) { ctx, _, title ->
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

    BrowsableAdapters.register(Window::class) { w, _, title ->
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

    BrowsableAdapters.register(RenderTarget::class) { rt, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "RenderTarget"
            override fun dir(): Map<String, Any?> = linkedMapOf(
                "modules" to rt.modules,
                "meshModules" to rt.meshModules,
                "others" to rt.others,
                "preTargets" to rt.preTargets,
                "preRecorded" to (rt.preRecordedInsideRendering ?: emptyList<CommandBuffer>())
            )

            override fun html(): String {
                val rows = listOf(
                    "modules" to names(rt.modules.keys),
                    "mesh" to names(rt.meshModules.keys),
                    "others" to names(rt.others.keys),
                    "preTargets" to names(rt.preTargets.keys)
                )
                return card(heading(), rows, accent = "#c084fc")
            }

            override fun version(): Long =
                rt.modules.size + rt.meshModules.size + rt.others.size + rt.preTargets.size.toLong()
        }
    }

    BrowsableAdapters.register(ComputeTarget::class) { ct, _, title ->
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

    BrowsableAdapters.register(ModulePointer::class) { mp, _, title ->
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

    BrowsableAdapters.register(MeshModulePointer::class) { mp, _, title ->
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

    BrowsableAdapters.register(ComputeModulePointer::class) { mp, _, title ->
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

    BrowsableAdapters.register(Module::class) { m, _, title ->
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

            override fun version(): Long = m.parameters.renderer.frameCounter
        }
    }

    BrowsableAdapters.register(MeshModule::class) { m, _, title ->
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

            override fun version(): Long = m.parameters.renderer.frameCounter
        }
    }

    BrowsableAdapters.register(ComputeModule::class) { m, _, title ->
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

            override fun version(): Long = m.parameters.renderer.frameCounter
        }
    }

    BrowsableAdapters.register(Parameters::class) { p, _, title ->
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

    BrowsableAdapters.register(Module.Uniform::class) { u, _, title ->
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

    BrowsableAdapters.register(Module.TexturePointer::class) { t, _, title ->
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

    BrowsableAdapters.register(Module.TextureArrayPointer::class) { t, _, title ->
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

    BrowsableAdapters.register(Module.ImagePointer::class) { t, _, title ->
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

    BrowsableAdapters.register(Mesh::class) { m, _, title ->
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

    BrowsableAdapters.register(MeshMesh::class) { m, _, title ->
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

    BrowsableAdapters.register(ComputeMesh::class) { m, _, title ->
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

    BrowsableAdapters.register(Attachment::class) { a, _, title ->
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

    BrowsableAdapters.register(Image::class) { img, _, title ->
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

    BrowsableAdapters.register(ImageView::class) { view, _, title ->
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

    BrowsableAdapters.register(LoadedTexture::class) { tex, _, title ->
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

    BrowsableAdapters.register(HasImageView::class) { hv, _, title ->
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

    BrowsableAdapters.register(TextureSampler::class) { s, _, title ->
        object : Browsable {
            override fun heading(): String = title ?: "Sampler"
            override fun dir(): Map<String, Any?>? = null
            override fun html(): String = card(heading(), listOf("handle" to hex(s.vkSampler)), accent = "#fcd34d")
        }
    }

    BrowsableAdapters.register(VideoTexture::class) { v, _, title ->
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

    BrowsableAdapters.register(SwapChain::class) { sc, _, title ->
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

    BrowsableAdapters.register(Queue::class) { q, _, title ->
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

    BrowsableAdapters.register(CommandBuffer::class) { cb, _, title ->
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

    BrowsableAdapters.register(CommandPool::class) { cp, _, title ->
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

    BrowsableAdapters.register(Renderer.ProfilerState::class) { ps, _, title ->
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

    BrowsableAdapters.register(Renderer.FrameMarkers::class) { m, _, title ->
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

    BrowsableAdapters.register(Renderer.ModuleMarks::class) { m, _, title ->
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

    BrowsableAdapters.register(GpuProfiler::class) { p, _, title ->
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

    BrowsableAdapters.register(ProfilingOptions::class) { o, _, title ->
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
