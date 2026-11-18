package afield.graphics.scene.fabricated

/**
 * The page bookkeeping behind [FabricateVirtualShadowMap], with no GPU in it: where each clipmap
 * level's window sits, which virtual pages are resident in which physical page, which pages a
 * frame must render, and which must be redrawn because a caster moved over them.
 *
 * Coordinates are the light's shadow-map uv, 0 to 1 across the light's orthographic box. Level
 * `l` cuts that box into [pagesOnSide]`(l)` pages a side, `clipPages << (levels - 1 - l)`: the
 * coarsest level is exactly [clipPages] a side and covers the whole box, and each finer level
 * doubles the resolution. Every level only *addresses* a window of [clipPages] × [clipPages] pages
 * around the camera ([placeWindows]), which is what keeps the page table small while the finest
 * level's resolution grows as `2^levels`. A page is identified by its global (level, x, y), not by
 * its slot in the window, so a page the window leaves stays cached and is found again when the
 * camera comes back, until the pool needs its room.
 *
 * One [update] per rendered frame: requests in, allocations and the render list out.
 */
class VirtualShadowPageCache(
	/** Pages on a side of each level's window, and of the whole coarsest level. */
	val clipPages: Int,
	/** Physical pages in the pool. */
	val poolPages: Int,
	/** How many levels, each twice the resolution of the next coarser one. */
	val levels: Int
) {
	init {
		require(clipPages in 1..4096 && levels >= 1) { "clipPages=$clipPages levels=$levels out of range" }
		require(pagesOnSide(0) <= 4096) {
			"clipPages=$clipPages with $levels levels gives ${pagesOnSide(0)} pages on a side of the finest level; the render list packs at most 4096"
		}
		require(poolPages > 0) { "poolPages must be positive" }
	}

	/** Pages on a side of the whole light box at [level]. */
	fun pagesOnSide(level: Int): Int = clipPages shl (levels - 1 - level)

	/** Entries in the page table (and the marks buffer): one window of [clipPages]² per level. */
	val tableEntries: Int get() = levels * clipPages * clipPages

	/** Each level's window origin in that level's pages, x then y, level by level. */
	val origins = IntArray(levels * 2)

	/**
	 * The coarsest level is requested whole when it fits in a quarter of the pool, so no pixel is
	 * ever without a shadow page; otherwise only the coarsest pages under some marked page are.
	 */
	val coarsestAlwaysResident: Boolean get() = clipPages * clipPages * 4 <= poolPages

	private val resident = HashMap<Long, Int>()
	private val physicalKey = LongArray(poolPages) { -1L }
	private val lastNeeded = LongArray(poolPages) { Long.MIN_VALUE }
	private val stale = BooleanArray(poolPages)
	private val free = ArrayDeque<Int>().also { q -> for (i in 0 until poolPages) q.addLast(i) }
	private val requested = HashSet<Long>()
	private var frame = 0L

	/** Pages requested by the last [update]. */
	var requestedCount = 0
		private set

	/** Pages the last [update] scheduled for rendering: newly allocated plus redrawn. */
	var renderedCount = 0
		private set

	/** Requested pages the last [update] found already rendered and current. */
	var cachedCount = 0
		private set

	/** Resident pages the last [update] scheduled for a redraw because a caster moved over them. */
	var redrawnCount = 0
		private set

	/** Requested pages that found no room in the pool, in the last [update]. */
	var starvedCount = 0
		private set

	/** Pages rendered (new or redrawn), since construction. */
	var renderedTotal = 0L
		private set

	/** Pages redrawn because a caster moved over them, since construction. */
	var redrawnTotal = 0L
		private set

	/** Pages evicted to make room, since construction. */
	var evictions = 0L
		private set

	/** Pages holding a rendered page. */
	val residentCount: Int get() = resident.size

	/** Pack a page as one key: level, then y, then x. */
	fun key(level: Int, x: Int, y: Int): Long = (level.toLong() shl 48) or (y.toLong() shl 24) or x.toLong()

	/** The level of a page [key]. */
	fun levelOf(key: Long): Int = (key ushr 48).toInt()

	/** The y page coordinate of a page [key], within its level. */
	fun yOf(key: Long): Int = ((key ushr 24) and 0xFFFFFF).toInt()

	/** The x page coordinate of a page [key], within its level. */
	fun xOf(key: Long): Int = (key and 0xFFFFFF).toInt()

	/**
	 * Centre every level's window on the camera at ([u], [v]) in light uv, snapped to that level's
	 * pages and kept inside the light box.
	 */
	fun placeWindows(u: Float, v: Float) {
		for (l in 0 until levels) {
			val n = pagesOnSide(l)
			origins[2 * l] = windowOrigin(u, n)
			origins[2 * l + 1] = windowOrigin(v, n)
		}
	}

	private fun windowOrigin(c: Float, n: Int): Int {
		val centre = if (c.isFinite()) kotlin.math.floor(c * n).toInt() else n / 2
		return (centre - clipPages / 2).coerceIn(0, n - clipPages)
	}

	/** The page-table slot of a global page under [windows] (default the current ones), or -1 outside its window. */
	fun slot(level: Int, x: Int, y: Int, windows: IntArray = origins): Int {
		val lx = x - windows[2 * level]
		val ly = y - windows[2 * level + 1]
		if (lx < 0 || ly < 0 || lx >= clipPages || ly >= clipPages) return -1
		return (level * clipPages + ly) * clipPages + lx
	}

	/** The global page a table slot addressed under [windows]. */
	fun pageAt(slot: Int, windows: IntArray): Long {
		val perLevel = clipPages * clipPages
		val level = slot / perLevel
		val local = slot % perLevel
		return key(level, windows[2 * level] + local % clipPages, windows[2 * level + 1] + local / clipPages)
	}

	/** Ask for a page this frame. Pages outside the light box are ignored. */
	fun request(level: Int, x: Int, y: Int) {
		val n = pagesOnSide(level)
		if (x < 0 || y < 0 || x >= n || y >= n) return
		requested += key(level, x, y)
	}

	/** Ask for the page a table slot addressed when the frame that marked it was recorded with [windows]. */
	fun requestSlot(slot: Int, windows: IntArray) {
		val k = pageAt(slot, windows)
		request(levelOf(k), xOf(k), yOf(k))
	}

	/** Drop every rendered page. */
	fun invalidateAll() {
		resident.clear()
		physicalKey.fill(-1L)
		lastNeeded.fill(Long.MIN_VALUE)
		stale.fill(false)
		free.clear()
		for (i in 0 until poolPages) free.addLast(i)
	}

	/**
	 * Every resident page touching the light-uv rectangle [u0]..[u1] × [v0]..[v1], at every level, is
	 * out of date: the next [update] redraws it if it is still requested and frees it otherwise.
	 * Returns how many pages that marked.
	 */
	fun invalidateRect(u0: Float, v0: Float, u1: Float, v1: Float): Int {
		if (!(u1 >= 0f && v1 >= 0f && u0 <= 1f && v0 <= 1f)) return 0
		var marked = 0
		for ((k, p) in resident) {
			val l = levelOf(k)
			val n = pagesOnSide(l).toFloat()
			val x = xOf(k)
			val y = yOf(k)
			// A page's filter taps read up to a texel past its edge, so a touching page counts.
			if (x + 1 >= u0 * n && x <= u1 * n && y + 1 >= v0 * n && y <= v1 * n && !stale[p]) {
				stale[p] = true
				marked++
			}
		}
		return marked
	}

	/**
	 * Allocate this frame's requests, coarse levels first so a full pool starves fine pages (which
	 * have a fallback) rather than coarse ones. Returns the pages to render this frame as
	 * (key, physical page) pairs, and clears the requests.
	 */
	fun update(): List<Pair<Long, Int>> {
		frame++
		val coarsest = levels - 1
		if (coarsestAlwaysResident) {
			for (y in 0 until clipPages) for (x in 0 until clipPages) requested += key(coarsest, x, y)
		} else {
			for (k in requested.toList()) {
				val shift = coarsest - levelOf(k)
				requested += key(coarsest, xOf(k) shr shift, yOf(k) shr shift)
			}
		}

		// A mark decoded through an older window can name a page this frame's table cannot address.
		requested.removeIf { slot(levelOf(it), xOf(it), yOf(it)) < 0 }
		val order = requested.sortedWith(compareByDescending<Long> { levelOf(it) }.thenBy { it })
		requested.clear()
		val render = ArrayList<Pair<Long, Int>>()
		var cached = 0
		var redrawn = 0
		var starved = 0
		for (k in order) {
			val have = resident[k]
			if (have != null) {
				lastNeeded[have] = frame
				if (stale[have]) {
					stale[have] = false
					render += k to have
					redrawn++
				} else cached++
				continue
			}
			val p = allocate()
			if (p < 0) {
				starved++
				continue
			}
			resident[k] = p
			physicalKey[p] = k
			lastNeeded[p] = frame
			stale[p] = false
			render += k to p
		}
		// A stale page nobody asked for this frame is not worth redrawing on spec.
		for (p in 0 until poolPages) if (stale[p]) release(p)

		requestedCount = order.size
		cachedCount = cached
		redrawnCount = redrawn
		starvedCount = starved
		renderedCount = render.size
		renderedTotal += render.size
		redrawnTotal += redrawn
		return render
	}

	/** Write the page table for the current windows: physical page per slot, or -1. */
	fun writeTable(put: (Int, Int) -> Unit) {
		for (l in 0 until levels) {
			val ox = origins[2 * l]
			val oy = origins[2 * l + 1]
			for (ly in 0 until clipPages) for (lx in 0 until clipPages) {
				put((l * clipPages + ly) * clipPages + lx, resident[key(l, ox + lx, oy + ly)] ?: -1)
			}
		}
	}

	/** The physical page holding a global page, or -1. */
	fun physicalOf(level: Int, x: Int, y: Int): Int = resident[key(level, x, y)] ?: -1

	private fun release(p: Int) {
		val k = physicalKey[p]
		if (k >= 0) resident.remove(k)
		physicalKey[p] = -1L
		lastNeeded[p] = Long.MIN_VALUE
		stale[p] = false
		free.addLast(p)
	}

	/**
	 * A free physical page, or the one needed least recently if that was more than
	 * [EVICT_AFTER_FRAMES] ago: frames still in flight may sample a page their table addressed.
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
		resident.remove(physicalKey[best])
		physicalKey[best] = -1L
		stale[best] = false
		evictions++
		return best
	}

	companion object {
		/** Frames a page must go unrequested before it may be evicted; more than any swapchain's frames in flight. */
		const val EVICT_AFTER_FRAMES = 8
	}
}
