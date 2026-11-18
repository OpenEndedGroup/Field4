package afield.graphics.scene.fabricated

import afield.graphics.render.Mesh
import afield.graphics.render.ModulePointer
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.buffers.defaultBuffer
import afield.math.linalg.Matrix4f

/**
 * The CPU half of the fabricated renderer's virtual shadow map, the opt-in alternative to its
 * fixed-resolution shadow pass ([StandardRenderer.Config.virtualShadows]).
 *
 * The directional light's orthographic view is treated as one very large depth map — at level 0,
 * [pagesPerSide] × [pageSize] texels on a side — cut into square pages. Only pages a visible pixel
 * needs are ever rendered. Each frame:
 *
 * 1. The lighting pass, while it shades, picks a level for every pixel from how many level-0
 *    texels one screen pixel covers (each level halves the resolution of the one before), and
 *    writes a 1 into [marks] for the page (and its filter neighbours) it is about to sample.
 * 2. [update], on the CPU before the next frame records, reads and clears those marks, gives each
 *    newly needed page a free physical page from the pool (evicting the least recently needed page
 *    when the pool is full), and writes the page table and the list of pages to render.
 * 3. The shadow pass draws every caster once per page in that list (instanced), into the page's
 *    tile of the pool texture, after a clear pass resets those tiles. Pages already resident are
 *    left alone: they are the cache, and stay valid until the light's matrix changes or
 *    [invalidate] is called (a caster that moved).
 * 4. The lighting pass samples depth through the page table, falling back to a coarser level
 *    where a finer page is not resident yet.
 *
 * The coarsest level is always fully requested, so no pixel is ever without a shadow page.
 *
 * Method after "Virtual Shadow Maps" (Timberdoodle / GPU Zen 3, as described at
 * https://graphics-programming.org/blog/virtual-shadow-maps). That design centres a set of
 * clipmap cascades on the camera and does marking and allocation on the GPU; this one keeps the
 * light's fixed orthographic box, uses mip-like levels of it in place of camera-centred clipmaps,
 * and allocates on the CPU from marks read back one frame late. No code was taken from it.
 */
class FabricateVirtualShadowMap(
	renderer: Renderer,
	/** Pages on a side of the finest level. Each coarser level has half as many. */
	val pagesPerSide: Int = 64,
	/** Physical pages on a side of the pool texture; the pool holds this squared. */
	val poolPagesPerSide: Int = 32,
	/** Texels on a side of one page. */
	val pageSize: Int = 128,
	/** How many levels, each half the resolution of the one before. */
	val levels: Int = 4
) {
	init {
		require(pagesPerSide shr (levels - 1) >= 1) { "pagesPerSide=$pagesPerSide is too small for $levels levels" }
		require(pagesPerSide < 4096 && poolPagesPerSide > 0) { "page grid out of range" }
	}

	/** Pool texture size in texels, on a side. */
	val poolSize: Int get() = poolPagesPerSide * pageSize

	/** Physical pages in the pool. */
	val poolPages: Int get() = poolPagesPerSide * poolPagesPerSide

	/** Offset of each level's pages in the page table; the last entry is the table length. */
	val levelOffsets: IntArray = IntArray(levels + 1).also {
		for (l in 0 until levels) it[l + 1] = it[l] + (pagesPerSide shr l) * (pagesPerSide shr l)
	}

	/** Entries in the page table, every level included. */
	val virtualPages: Int get() = levelOffsets[levels]

	/** Written by the lighting pass: non-zero for each virtual page a pixel sampled. Read and cleared by [update]. */
	val marks: FrameBufferGroup = defaultBuffer(name = "fabricateVsmMarks") {
		size = virtualPages.ints
	}.toBuffer(renderer)

	/**
	 * Virtual page → physical page, or -1, after a four-int header (pages per side, pool pages per
	 * side, levels, [debugView]). One per swapchain image, rewritten whole each frame.
	 */
	val pageTable: FrameBufferGroup = defaultBuffer(name = "fabricateVsmPageTable") {
		perFrame = true
		size = (virtualPages + TABLE_HEADER).ints
	}.toBuffer(renderer)

	/**
	 * This frame's pages to clear and render, after a two-int header (pages per side, pool pages per
	 * side): pairs of (packed virtual page, physical page).
	 */
	val renderList: FrameBufferGroup = defaultBuffer(name = "fabricateVsmRenderList") {
		perFrame = true
		size = (poolPages * 2 + LIST_HEADER).ints
	}.toBuffer(renderer)

	private val virtualToPhysical = IntArray(virtualPages) { -1 }
	private val physicalToVirtual = IntArray(poolPages) { -1 }
	private val lastNeeded = LongArray(poolPages) { Long.MIN_VALUE }
	private val allocatedAt = LongArray(poolPages) { Long.MIN_VALUE }
	private val requested = BooleanArray(virtualPages)
	private val free = ArrayDeque<Int>().also { q -> for (i in 0 until poolPages) q.addLast(i) }
	private val dirty = ArrayList<Int>()
	private val lastLight = FloatArray(16) { Float.NaN }
	private val lightNow = FloatArray(16)
	private var frame = 0L
	private var invalidated = true

	/** Keep rendered pages across frames (the cache). Off re-renders every needed page every frame. */
	var caching: Boolean = true

	/** 0 shades normally; 1 tints each pixel by the level and physical page its shadow came from. */
	var debugView: Int = 0

	/** Pages requested by the last [update]. */
	var requestedCount = 0
		private set

	/** Pages holding a rendered page after the last [update]. */
	var residentCount = 0
		private set

	/** Pages the last [update] scheduled for rendering. */
	var renderedCount = 0
		private set

	/** Requested pages the last [update] found already rendered. */
	var cachedCount = 0
		private set

	/** Pages evicted to make room, since construction. */
	var evictions = 0L
		private set

	/** Requested pages that found no room in the pool, in the last [update]. */
	var starvedCount = 0
		private set

	/** Frames [update] has run. */
	val frames: Long get() = frame

	/** Drop every rendered page, so the next frame re-renders all it needs. Call after moving a caster. */
	fun invalidate() {
		invalidated = true
	}

	/** Pack a page coordinate for the render list: level in the top byte, then y and x in 12 bits each. */
	fun pack(level: Int, x: Int, y: Int): Int = (level shl 24) or (y shl 12) or x

	/** Which (level, x, y) a page-table index addresses. */
	fun unpackIndex(index: Int): Triple<Int, Int, Int> {
		var level = 0
		while (index >= levelOffsets[level + 1]) level++
		val n = pagesPerSide shr level
		val local = index - levelOffsets[level]
		return Triple(level, local % n, local / n)
	}

	/** A copy of the page table as of the last [update]: physical page per virtual page, or -1. */
	fun pageTableSnapshot(): IntArray = virtualToPhysical.copyOf()

	/** A copy of which virtual pages the last [update] saw requested. */
	fun requestedSnapshot(): BooleanArray = requested.copyOf()

	/**
	 * Read the marks, allocate, and write this frame's page table and render list. Called once per
	 * frame from the bundle's auto-sync hook, before the frame records.
	 */
	fun update(lightViewProjection: Matrix4f, shadowModule: ModulePointer, clearMesh: Mesh?) {
		frame++
		lightViewProjection.get(lightNow)
		if (!lightNow.contentEquals(lastLight)) {
			lightNow.copyInto(lastLight)
			invalidated = true
		}
		if (invalidated || !caching) {
			invalidated = false
			for (p in 0 until poolPages) {
				val v = physicalToVirtual[p]
				if (v >= 0) virtualToPhysical[v] = -1
				physicalToVirtual[p] = -1
				lastNeeded[p] = Long.MIN_VALUE
			}
			free.clear()
			for (i in 0 until poolPages) free.addLast(i)
		}

		requested.fill(false)
		marks.mapAndRead_ints {
			for (i in 0 until virtualPages) {
				if (get(i) != 0) {
					requested[i] = true
					put(i, 0)
				}
			}
		}
		// The coarsest level is always wanted, so a pixel never falls through to "no shadow".
		for (i in levelOffsets[levels - 1] until levelOffsets[levels]) requested[i] = true

		dirty.clear()
		requestedCount = 0
		cachedCount = 0
		starvedCount = 0
		// Coarse levels first: if the pool runs out, it runs out on fine pages, which have a fallback.
		for (level in levels - 1 downTo 0) {
			for (v in levelOffsets[level] until levelOffsets[level + 1]) {
				if (!requested[v]) continue
				requestedCount++
				val resident = virtualToPhysical[v]
				if (resident >= 0) {
					lastNeeded[resident] = frame
					// A page is drawn again for a few frames after it is allocated: the frame that
					// records this update's list may not be the next one, so one draw can be missed.
					if (frame - allocatedAt[resident] < REDRAW_FRAMES) dirty += v else cachedCount++
					continue
				}
				val p = allocate()
				if (p < 0) {
					starvedCount++
					continue
				}
				virtualToPhysical[v] = p
				physicalToVirtual[p] = v
				lastNeeded[p] = frame
				allocatedAt[p] = frame
				dirty += v
			}
		}
		renderedCount = dirty.size
		residentCount = poolPages - free.size

		pageTable.mapAndRead_ints {
			put(0, pagesPerSide)
			put(1, poolPagesPerSide)
			put(2, levels)
			put(3, debugView)
			for (i in 0 until virtualPages) put(TABLE_HEADER + i, virtualToPhysical[i])
		}
		pageTable.markAsWritten()
		renderList.mapAndRead_ints {
			put(0, pagesPerSide)
			put(1, poolPagesPerSide)
			var o = LIST_HEADER
			for (v in dirty) {
				val (level, x, y) = unpackIndex(v)
				put(o++, pack(level, x, y))
				put(o++, virtualToPhysical[v])
			}
		}
		renderList.markAsWritten()

		val instances = dirty.size
		for (mesh in shadowModule.current.meshes.values) bindCaster(mesh, instances)
		clearMesh?.let { bindCaster(it, instances) }
	}

	private fun bindCaster(mesh: Mesh, instances: Int) {
		val push = mesh.push
		if (!push.offsets.containsKey("vsmPages")) return
		if (push.bindingFor("vsmPages") == null) {
			push.bind("vsmPages") { renderList }
			push["vsmMode"] = 1
		}
		mesh.instanceCount = instances
	}

	/**
	 * A free physical page, or the one needed least recently if that was more than a few frames ago.
	 * Marks arrive a frame or two late, so a page unmarked for a frame may still be in use.
	 */
	private fun allocate(): Int {
		free.removeFirstOrNull()?.let { return it }
		var best = -1
		var bestFrame = frame - EVICT_AFTER_FRAMES
		for (p in 0 until poolPages) {
			if (lastNeeded[p] < bestFrame) {
				bestFrame = lastNeeded[p]
				best = p
			}
		}
		if (best < 0) return -1
		val v = physicalToVirtual[best]
		if (v >= 0) virtualToPhysical[v] = -1
		physicalToVirtual[best] = -1
		evictions++
		return best
	}

	/** One line of the counters, for a status display. */
	fun describe(): String =
		"virtual ${pagesPerSide * pageSize}² in $levels levels, pool ${poolPages} pages of ${pageSize}²: " +
			"requested $requestedCount, resident $residentCount, rendered $renderedCount, cached $cachedCount" +
			(if (starvedCount > 0) ", starved $starvedCount" else "") + ", evictions $evictions"

	companion object {
		/** Frames a page must go unrequested before it may be evicted. */
		const val EVICT_AFTER_FRAMES = 8

		/** Frames a newly allocated page keeps being redrawn, covering frames in flight. */
		const val REDRAW_FRAMES = 4

		/** Ints ahead of the entries in [pageTable]. */
		const val TABLE_HEADER = 4

		/** Ints ahead of the pairs in [renderList]. */
		const val LIST_HEADER = 2
	}
}
