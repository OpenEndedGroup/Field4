package afield.graphics.scene.fabricated

import afield.graphics.render.Mesh
import afield.graphics.render.ModulePointer
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.buffers.defaultBuffer
import afield.math.linalg.Matrix4f
import afield.math.linalg.Vector3f
import java.util.IdentityHashMap

/**
 * The CPU half of the fabricated renderer's virtual shadow map, the opt-in alternative to its
 * fixed-resolution shadow pass ([StandardRenderer.Config.virtualShadows]).
 *
 * The directional light's orthographic view is treated as a set of clipmap levels cut into square
 * pages of [pageSize] texels. The coarsest level covers the light's whole box in [clipPages] ×
 * [clipPages] pages; each finer level doubles the resolution, so the finest has
 * `clipPages · 2^(levels-1)` pages a side. Every level addresses only a window of [clipPages] ×
 * [clipPages] pages centred on the camera, and only pages a visible pixel needs are ever rendered.
 * Each rendered frame, from the renderer's geometry phase (after that frame slot's fence, before it
 * records):
 *
 * 1. The lighting pass of the frame that last used this slot picked a level for every pixel from
 *    how many finest-level texels one screen pixel covers, and wrote a 1 into this slot's [marks]
 *    for the page (and its filter neighbours) it sampled, at the finest level whose window holds it.
 * 2. [update] reads and clears those marks, decoding them with the windows that frame used, moves
 *    the windows to the camera, finds the casters that moved, gives each newly needed page a free
 *    physical page from the pool (evicting the least recently needed page when the pool is full),
 *    and writes this slot's page table and list of pages to render.
 * 3. The shadow pass draws every caster once per page in that list (instanced), into the page's
 *    tile of the pool texture, after a clear pass resets those tiles. Pages already resident are
 *    left alone: they are the cache. A page stays cached when the window moves off it, and is found
 *    again if the camera returns before the pool needs its room.
 * 4. The lighting pass samples depth through the page table, falling back to a coarser level where
 *    a finer page is not resident yet or lies outside its level's window.
 *
 * The cache is dropped when the light's matrix changes. A caster whose model matrix changes, whose
 * position buffer is rewritten, or that appears or disappears, has only the pages under its old and
 * new light-space bounds redrawn. Geometry the GPU writes is not seen; call [invalidate] (or
 * [invalidateWorldBox]) for it.
 *
 * Method after "Virtual Shadow Maps" (Timberdoodle / GPU Zen 3, as described at
 * https://graphics-programming.org/blog/virtual-shadow-maps). That design keeps its clipmaps in a
 * light space that moves with the camera and does marking and allocation on the GPU; this one keeps
 * the light's fixed orthographic box, centres each level's window on the camera inside it, and
 * allocates on the CPU from marks read back a few frames late. No code was taken from it.
 */
class FabricateVirtualShadowMap(
	private val renderer: Renderer,
	/** Pages on a side of each level's window, and of the whole coarsest level. */
	val clipPages: Int = 32,
	/** Physical pages on a side of the pool texture; the pool holds this squared. */
	val poolPagesPerSide: Int = 32,
	/** Texels on a side of one page. */
	val pageSize: Int = 128,
	/** How many levels, each twice the resolution of the next coarser one. */
	val levels: Int = 5
) {
	init {
		require(poolPagesPerSide in 1..4095) { "poolPagesPerSide=$poolPagesPerSide out of range" }
	}

	/** The page bookkeeping, for inspection and tests. */
	val pages = VirtualShadowPageCache(clipPages, poolPagesPerSide * poolPagesPerSide, levels)

	/** Pool texture size in texels, on a side. */
	val poolSize: Int get() = poolPagesPerSide * pageSize

	/** Physical pages in the pool. */
	val poolPages: Int get() = poolPagesPerSide * poolPagesPerSide

	/** Texels on a side of the finest level, were it all rendered. */
	val virtualSize: Int get() = pages.pagesOnSide(0) * pageSize

	/** Written by the lighting pass: non-zero for each page-table slot a pixel sampled. One per frame slot; read and cleared by [update]. */
	val marks: FrameBufferGroup = defaultBuffer(name = "fabricateVsmMarks") {
		perFrame = true
		size = pages.tableEntries.ints
	}.toBuffer(renderer)

	/**
	 * Slot → physical page, or -1, after a header: clip pages, pool pages per side, levels,
	 * [debugView], then each level's window origin (x, y) in that level's pages. One per frame
	 * slot, rewritten whole each frame.
	 */
	val pageTable: FrameBufferGroup = defaultBuffer(name = "fabricateVsmPageTable") {
		perFrame = true
		size = (pages.tableEntries + tableHeader(levels)).ints
	}.toBuffer(renderer)

	/**
	 * This frame's pages to clear and render, after a three-int header (clip pages, pool pages per
	 * side, levels): pairs of (packed global page, physical page).
	 */
	val renderList: FrameBufferGroup = defaultBuffer(name = "fabricateVsmRenderList") {
		perFrame = true
		size = (poolPages * 2 + LIST_HEADER).ints
	}.toBuffer(renderer)

	private val slots = maxOf(renderer.numImages, 1)
	private val windowsBySlot = Array(slots) { IntArray(levels * 2) }
	private val slotRecorded = BooleanArray(slots)
	private val lastLight = FloatArray(16) { Float.NaN }
	private val lightNow = FloatArray(16)
	private var frame = 0L
	private var invalidated = true
	private val casters = IdentityHashMap<Mesh, CasterState>()
	private val pendingRects = ArrayList<FloatArray>()

	private class CasterState(
		val model: FloatArray,
		var positions: Any?,
		var writes: Long,
		var vertices: Int,
		var contentHash: Int,
		/** Local-space bounds (min xyz, max xyz), or null when the positions could not be read. */
		var local: FloatArray?
	)

	/** Keep rendered pages across frames (the cache). Off re-renders every needed page every frame. */
	var caching: Boolean = true

	/** Follow caster motion, redrawing only the pages a moving caster covers. Off leaves casters to [invalidate]. */
	var trackCasters: Boolean = true

	/** 0 shades normally; 1 tints each pixel by the level and physical page its shadow came from. */
	var debugView: Int = 0

	/** Pages requested by the last [update]. */
	val requestedCount: Int get() = pages.requestedCount

	/** Pages holding a rendered page after the last [update]. */
	val residentCount: Int get() = pages.residentCount

	/** Pages the last [update] scheduled for rendering. */
	val renderedCount: Int get() = pages.renderedCount

	/** Requested pages the last [update] found already rendered. */
	val cachedCount: Int get() = pages.cachedCount

	/** Resident pages the last [update] redrew because a caster moved over them. */
	val redrawnCount: Int get() = pages.redrawnCount

	/** Pages evicted to make room, since construction. */
	val evictions: Long get() = pages.evictions

	/** Requested pages that found no room in the pool, in the last [update]. */
	val starvedCount: Int get() = pages.starvedCount

	/** Casters the last [update] found moved, changed, added or removed. */
	var movedCasters = 0
		private set

	/** Frames [update] has run. */
	val frames: Long get() = frame

	/** Drop every rendered page, so the next frame re-renders all it needs. */
	fun invalidate() {
		invalidated = true
	}

	/**
	 * Redraw the pages under a world-space box on the next frame: for geometry changed in a way
	 * [update] cannot see, such as a position buffer the GPU writes.
	 */
	fun invalidateWorldBox(min: Vector3f, max: Vector3f) {
		pendingRects += floatArrayOf(min.x, min.y, min.z, max.x, max.y, max.z)
	}

	/** Pack a page for the render list: level in the top byte, then y and x in 12 bits each. */
	fun pack(level: Int, x: Int, y: Int): Int = (level shl 24) or (y shl 12) or x

	/**
	 * Bind the render list on every caster and the clear mesh, with nothing to draw yet. Called at
	 * attach, so the first frame records with its references assigned.
	 */
	fun bindCasters(shadowModule: ModulePointer, clearMesh: Mesh?) {
		for (mesh in shadowModule.current.meshes.values) bindCaster(mesh, 0)
		clearMesh?.let { bindCaster(it, 0) }
	}

	/**
	 * Read this slot's marks, allocate, and write this slot's page table and render list. Called once
	 * per rendered frame, for [imageIndex], from the renderer's geometry phase.
	 */
	fun update(
		lightViewProjection: Matrix4f,
		camera: Vector3f,
		shadowModule: ModulePointer,
		clearMesh: Mesh?,
		imageIndex: Int = renderer.currentImage
	) {
		frame++
		val slot = Math.floorMod(imageIndex, slots)
		lightViewProjection.get(lightNow)
		if (!lightNow.contentEquals(lastLight)) {
			lightNow.copyInto(lastLight)
			invalidated = true
		}
		if (invalidated || !caching) {
			invalidated = false
			pages.invalidateAll()
		}

		val cam = projectUv(lastLight, camera.x, camera.y, camera.z)
		pages.placeWindows(cam[0], cam[1])

		// The marks in this slot were written by the frame that last recorded it, through its windows.
		val windows = windowsBySlot[slot]
		val haveWindows = slotRecorded[slot]
		marks.getAt(slot).mapAndRead_ints {
			for (i in 0 until pages.tableEntries) {
				if (get(i) != 0) {
					if (haveWindows) pages.requestSlot(i, windows)
					put(i, 0)
				}
			}
		}

		movedCasters = if (trackCasters) trackCasterMotion(shadowModule, clearMesh) else 0
		for (box in pendingRects) {
			val r = lightRect(box, IDENTITY, lastLight)
			pages.invalidateRect(r[0], r[1], r[2], r[3])
		}
		pendingRects.clear()

		val render = pages.update()
		pages.origins.copyInto(windows)
		slotRecorded[slot] = true

		val header = tableHeader(levels)
		pageTable.getAt(slot).mapAndRead_ints {
			put(0, clipPages)
			put(1, poolPagesPerSide)
			put(2, levels)
			put(3, debugView)
			for (i in 0 until levels * 2) put(4 + i, pages.origins[i])
			pages.writeTable { index, physical -> put(header + index, physical) }
		}
		pageTable.markAsWritten(slot)
		renderList.getAt(slot).mapAndRead_ints {
			put(0, clipPages)
			put(1, poolPagesPerSide)
			put(2, levels)
			var o = LIST_HEADER
			for ((k, physical) in render) {
				put(o++, pack(pages.levelOf(k), pages.xOf(k), pages.yOf(k)))
				put(o++, physical)
			}
		}
		renderList.markAsWritten(slot)

		val instances = render.size
		for (mesh in shadowModule.current.meshes.values) bindCaster(mesh, instances)
		clearMesh?.let { bindCaster(it, instances) }
	}

	/**
	 * Compare every caster with what it was last frame; for each that changed, mark the pages under
	 * its old and new bounds stale. Returns how many changed.
	 */
	private fun trackCasterMotion(shadowModule: ModulePointer, clearMesh: Mesh?): Int {
		var changed = 0
		val seen = java.util.Collections.newSetFromMap(IdentityHashMap<Mesh, Boolean>())
		val model = FloatArray(16)
		for (mesh in shadowModule.current.meshes.values) {
			if (mesh === clearMesh) continue
			seen += mesh
			val m = runCatching { mesh.push.get<Any?>("model") as? Matrix4f }.getOrNull()
			if (m != null) m.get(model) else IDENTITY.copyInto(model)
			val positions = runCatching { mesh.push.bindingFor("positionBuffer")?.invoke() }.getOrNull()
			val writes = (positions as? FrameBufferGroup)?.writeCount ?: 0L
			val vertices = mesh.numVert
			val old = casters[mesh]
			if (old == null) {
				val read = localBounds(positions, vertices)
				casters[mesh] = CasterState(model.copyOf(), positions, writes, vertices, read?.second ?: 0, read?.first)
				// A caster added after the first frame lands on pages already rendered without it.
				if (frame > 1) {
					invalidateCaster(read?.first, model)
					changed++
				}
				continue
			}
			var geometryChanged = old.positions !== positions || old.vertices != vertices
			var newLocal = old.local
			var newHash = old.contentHash
			if (geometryChanged || old.writes != writes) {
				val read = localBounds(positions, vertices)
				newLocal = read?.first
				newHash = read?.second ?: 0
				if (read == null || newHash != old.contentHash) geometryChanged = true
			}
			val moved = !model.contentEquals(old.model)
			if (moved || geometryChanged) {
				invalidateCaster(old.local, old.model)
				invalidateCaster(newLocal, model)
				changed++
			}
			model.copyInto(old.model)
			old.positions = positions
			old.writes = writes
			old.vertices = vertices
			old.local = newLocal
			old.contentHash = newHash
		}
		val gone = casters.keys.filter { it !in seen }
		for (mesh in gone) {
			val s = casters.remove(mesh) ?: continue
			invalidateCaster(s.local, s.model)
			changed++
		}
		return changed
	}

	private fun invalidateCaster(local: FloatArray?, model: FloatArray) {
		if (local == null) {
			pages.invalidateRect(0f, 0f, 1f, 1f)
			return
		}
		val r = lightRect(local, model, lastLight)
		pages.invalidateRect(r[0], r[1], r[2], r[3])
	}

	/**
	 * Local bounds and a content hash of the first [vertices] positions (vec4s) of a CPU-written
	 * position buffer, or null when there is none to read.
	 */
	private fun localBounds(positions: Any?, vertices: Int): Pair<FloatArray, Int>? {
		val group = positions as? FrameBufferGroup ?: return null
		if (group.buffers.isEmpty()) return null
		val b = FloatArray(6)
		var hash = 1
		var any = false
		val ok = runCatching {
			val buffer = group.getLastWritten()
			val capacity = (buffer.requestedSize / 16L).toInt()
			val n = if (vertices > 0) minOf(vertices, capacity) else capacity
			buffer.mapAndRead {
				for (i in 0 until n) {
					val x = get(4 * i)
					val y = get(4 * i + 1)
					val z = get(4 * i + 2)
					hash = 31 * (31 * (31 * hash + x.toRawBits()) + y.toRawBits()) + z.toRawBits()
					if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
					if (!any) {
						b[0] = x; b[1] = y; b[2] = z; b[3] = x; b[4] = y; b[5] = z
						any = true
					} else {
						b[0] = minOf(b[0], x); b[1] = minOf(b[1], y); b[2] = minOf(b[2], z)
						b[3] = maxOf(b[3], x); b[4] = maxOf(b[4], y); b[5] = maxOf(b[5], z)
					}
				}
			}
		}.isSuccess
		if (!ok || !any) return null
		return b to hash
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

	/** One line of the counters, for a status display. */
	fun describe(): String =
		"virtual ${virtualSize}² in $levels clipmap levels of ${clipPages}² pages, pool $poolPages pages of ${pageSize}²: " +
			"requested $requestedCount, resident $residentCount, rendered $renderedCount, cached $cachedCount" +
			(if (redrawnCount > 0) ", redrawn $redrawnCount" else "") +
			(if (starvedCount > 0) ", starved $starvedCount" else "") + ", evictions $evictions"

	companion object {
		/** Ints ahead of the entries in [pageTable]: four, then a window origin per level. */
		fun tableHeader(levels: Int): Int = 4 + 2 * levels

		/** Ints ahead of the pairs in [renderList]. */
		const val LIST_HEADER = 3

		private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

		/** Shadow-map uv (0.5 + 0.5 x, 0.5 - 0.5 y) of a world point under a column-major light matrix. */
		internal fun projectUv(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
			val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
			val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
			val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
			val w = if (cw == 0f) 1f else cw
			return floatArrayOf(0.5f + 0.5f * cx / w, 0.5f - 0.5f * cy / w)
		}

		/** The light-uv rectangle (u0, v0, u1, v1) covering a local box under [model] then [light]. */
		internal fun lightRect(local: FloatArray, model: FloatArray, light: FloatArray): FloatArray {
			val r = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
			for (c in 0 until 8) {
				val lx = if (c and 1 == 0) local[0] else local[3]
				val ly = if (c and 2 == 0) local[1] else local[4]
				val lz = if (c and 4 == 0) local[2] else local[5]
				val wx = model[0] * lx + model[4] * ly + model[8] * lz + model[12]
				val wy = model[1] * lx + model[5] * ly + model[9] * lz + model[13]
				val wz = model[2] * lx + model[6] * ly + model[10] * lz + model[14]
				val uv = projectUv(light, wx, wy, wz)
				r[0] = minOf(r[0], uv[0]); r[1] = minOf(r[1], uv[1])
				r[2] = maxOf(r[2], uv[0]); r[3] = maxOf(r[3], uv[1])
			}
			return r
		}
	}
}
