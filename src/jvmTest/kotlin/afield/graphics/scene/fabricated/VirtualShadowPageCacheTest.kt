package afield.graphics.scene.fabricated

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The virtual shadow map's page bookkeeping, without a GPU: where the clipmap windows sit, that a
 * page outlives its window, that a moving caster redraws only the pages under it, and that a full
 * pool starves fine pages before coarse ones.
 */
class VirtualShadowPageCacheTest {

	private fun cache(clip: Int = 8, pool: Int = 64, levels: Int = 4) = VirtualShadowPageCache(clip, pool, levels)

	@Test
	fun levelsDoubleAndTheCoarsestCoversTheBox() {
		val c = cache()
		assertEquals(listOf(64, 32, 16, 8), (0 until 4).map { c.pagesOnSide(it) })
		c.placeWindows(0.9f, 0.1f)
		// The coarsest window is the whole level, wherever the camera is.
		assertEquals(0, c.origins[6])
		assertEquals(0, c.origins[7])
		// Finer windows are centred on the camera, snapped to their pages.
		assertEquals(57 - 4, c.origins[0])
		assertEquals(6 - 4, c.origins[1])
		// ... and kept inside the box.
		c.placeWindows(0.99f, 0.0f)
		assertEquals(64 - 8, c.origins[0])
		assertEquals(0, c.origins[1])
	}

	@Test
	fun slotsRoundTripThroughTheWindowThatMadeThem() {
		val c = cache()
		c.placeWindows(0.4f, 0.6f)
		val windows = c.origins.copyOf()
		for (level in 0 until 4) {
			val x = windows[2 * level] + 3
			val y = windows[2 * level + 1] + 5
			val slot = c.slot(level, x, y)
			assertTrue(slot >= 0)
			assertEquals(c.key(level, x, y), c.pageAt(slot, windows))
		}
		assertEquals(-1, c.slot(0, windows[0] - 1, windows[1]))
		assertEquals(-1, c.slot(0, windows[0] + 8, windows[1]))
	}

	@Test
	fun aPageRenderedOnceIsCachedAndSurvivesTheWindowMovingAway() {
		val c = cache()
		c.placeWindows(0.5f, 0.5f)
		c.request(0, 32, 32)
		val first = c.update()
		assertTrue(first.any { (k, _) -> k == c.key(0, 32, 32) }, "the requested page is rendered")
		val physical = c.physicalOf(0, 32, 32)

		c.placeWindows(0.5f, 0.5f)
		c.request(0, 32, 32)
		val second = c.update()
		assertTrue(second.none { (k, _) -> k == c.key(0, 32, 32) }, "a resident page is not rendered again")
		assertTrue(c.cachedCount >= 1)

		// The camera leaves: the page is outside level 0's window, so it is not addressed, but stays resident.
		c.placeWindows(0.05f, 0.05f)
		c.update()
		assertEquals(-1, c.slot(0, 32, 32))
		assertEquals(physical, c.physicalOf(0, 32, 32))

		// It comes back: found again, not re-rendered.
		c.placeWindows(0.5f, 0.5f)
		c.request(0, 32, 32)
		val back = c.update()
		assertTrue(back.none { (k, _) -> k == c.key(0, 32, 32) })
		assertEquals(physical, c.physicalOf(0, 32, 32))
	}

	@Test
	fun requestsOutsideTheCurrentWindowAreDropped() {
		val c = cache()
		c.placeWindows(0.5f, 0.5f)
		c.request(0, 0, 0)
		val render = c.update()
		assertTrue(render.none { (k, _) -> c.levelOf(k) == 0 })
		assertEquals(-1, c.physicalOf(0, 0, 0))
	}

	@Test
	fun aMovedCasterRedrawsOnlyThePagesUnderIt() {
		val c = cache()
		c.placeWindows(0.5f, 0.5f)
		val near = c.key(0, 30, 30)
		val far = c.key(0, 34, 34)
		c.request(0, 30, 30)
		c.request(0, 34, 34)
		c.update()

		// A caster over page (30, 30) at level 0 only: uv 30/64 .. 30.5/64.
		c.invalidateRect(30.1f / 64f, 30.1f / 64f, 30.5f / 64f, 30.5f / 64f)
		c.placeWindows(0.5f, 0.5f)
		c.request(0, 30, 30)
		c.request(0, 34, 34)
		val render = c.update()
		val keys = render.map { it.first }.toSet()
		assertTrue(near in keys, "the page under the caster is redrawn")
		assertTrue(far !in keys, "a page away from the caster is left alone")
		assertTrue(c.redrawnCount >= 1)
		// The coarse pages over the same spot are redrawn too.
		assertTrue(keys.any { c.levelOf(it) == 3 && c.xOf(it) == 3 && c.yOf(it) == 3 })
	}

	@Test
	fun aStalePageNobodyWantsIsFreedRatherThanRedrawn() {
		val c = cache()
		c.placeWindows(0.5f, 0.5f)
		c.request(0, 30, 30)
		c.update()
		assertTrue(c.physicalOf(0, 30, 30) >= 0)
		c.invalidateRect(30.1f / 64f, 30.1f / 64f, 30.5f / 64f, 30.5f / 64f)
		c.placeWindows(0.5f, 0.5f)
		val render = c.update()
		assertTrue(render.none { it.first == c.key(0, 30, 30) })
		assertEquals(-1, c.physicalOf(0, 30, 30))
	}

	@Test
	fun aFullPoolStarvesFinePagesNotCoarseOnes() {
		// 8 × 8 coarsest pages fill exactly a quarter of a 256-page pool, so it is always requested.
		val c = cache(clip = 8, pool = 256, levels = 4)
		assertTrue(c.coarsestAlwaysResident)
		c.placeWindows(0.5f, 0.5f)
		for (level in 0 until 3) {
			val ox = c.origins[2 * level]
			val oy = c.origins[2 * level + 1]
			for (y in 0 until 8) for (x in 0 until 8) c.request(level, ox + x, oy + y)
		}
		c.update()
		assertEquals(64 * 4, c.requestedCount)
		assertEquals(0, c.starvedCount)

		// A smaller pool, where the coarsest level is too big to keep whole: only the 16 coarsest pages
		// under the marked ones are wanted, and the 48 pages that go without are all finest-level ones.
		val small = cache(clip = 8, pool = 160, levels = 4)
		assertTrue(!small.coarsestAlwaysResident)
		small.placeWindows(0.5f, 0.5f)
		val windows = small.origins.copyOf()
		for (level in 0 until 3) {
			for (y in 0 until 8) for (x in 0 until 8) small.request(level, windows[2 * level] + x, windows[2 * level + 1] + y)
		}
		small.update()
		assertEquals(64 * 3 + 16, small.requestedCount)
		assertEquals(48, small.starvedCount)
		fun residentAt(level: Int) = (0 until 8).sumOf { y -> (0 until 8).count { x -> small.physicalOf(level, windows[2 * level] + x, windows[2 * level + 1] + y) >= 0 } }
		assertEquals(64, residentAt(2))
		assertEquals(64, residentAt(1))
		assertEquals(16, residentAt(0))
	}

	@Test
	fun aBigCoarsestLevelIsRequestedOnlyUnderMarkedPages() {
		val c = cache(clip = 8, pool = 128, levels = 3)
		assertTrue(!c.coarsestAlwaysResident)
		c.placeWindows(0.5f, 0.5f)
		c.request(0, c.origins[0] + 1, c.origins[1] + 1)
		c.update()
		// The marked page and its one coarsest ancestor, nothing else.
		assertEquals(2, c.requestedCount)
	}

	@Test
	fun lightRectProjectsABoxThroughTheModelAndLight() {
		val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
		val translate = identity.copyOf().also { it[12] = 0.5f }
		val r = FabricateVirtualShadowMap.lightRect(floatArrayOf(-0.5f, -0.5f, 0f, 0.5f, 0.5f, 0f), translate, identity)
		// x from 0 to 1 in clip space → u 0.5 to 1; y from -0.5 to 0.5 → v 0.25 to 0.75 (v is flipped).
		assertEquals(0.5f, r[0], 1e-6f)
		assertEquals(0.25f, r[1], 1e-6f)
		assertEquals(1.0f, r[2], 1e-6f)
		assertEquals(0.75f, r[3], 1e-6f)
	}
}
