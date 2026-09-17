package afield.four.texture.mega

import org.lwjgl.stb.STBImageWrite.stbi_write_png
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The half of a [MegaTexture] that runs on the CPU: the catalog's index stability, the sparse page
 * cache, and the decoder's request/cancel/deliver loop. No Vulkan device is involved.
 *
 * Index stability is the subtle one and the reason this file exists. An image's index is its only
 * name — the shader asks for `megaTexture(id, uv)`, the cache stores its pages at
 * `index * stride`, and the feedback buffer is indexed by it. If a rescan renumbered the
 * directory, every cached page would describe a different photograph and the whole wall would come
 * back subtly wrong rather than failing.
 */
class MegaTexturePipelineTest {

    private val temporaries = mutableListOf<Path>()

    @AfterTest
    fun cleanUp() {
        temporaries.reversed().forEach { root ->
            runCatching {
                Files.walk(root).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    /** A square page ladder for the cache and decoder to size themselves from. */
    private fun squareLayout(imageCount: Int, minPageSize: Int, levels: Int) = MegaTextureLayout(
        imageCount = imageCount,
        levels = levels,
        atlasEdge = 4096,
        layersPerLevel = 2,
        maxBaseLevelLayers = 64,
        topPageWidth = minPageSize shl (levels - 1)
    )

    private fun temporaryDirectory(name: String): Path =
        Files.createTempDirectory("megatexture-$name").also { temporaries.add(it) }

    /**
     * A solid-colour PNG, so a decoded page's pixels can be asserted exactly.
     *
     * PNG rather than JPEG deliberately: a JPEG of a flat colour still comes back a value or two
     * off, and this needs to distinguish "the decode path works" from "the decode path is close".
     */
    private fun writeImage(into: Path, name: String, width: Int, height: Int, rgb: Triple<Int, Int, Int>): Path {
        val pixels = MemoryUtil.memAlloc(width * height * 4)
        try {
            for (i in 0 until width * height) {
                pixels.put(rgb.first.toByte())
                pixels.put(rgb.second.toByte())
                pixels.put(rgb.third.toByte())
                pixels.put(0xff.toByte())
            }
            pixels.flip()
            val path = into.resolve(name)
            assertTrue(
                stbi_write_png(path.toString(), width, height, 4, pixels, width * 4),
                "could not write $path"
            )
            return path
        } finally {
            MemoryUtil.memFree(pixels)
        }
    }

    // ---------------------------------------------------------------------------------- catalog

    @Test
    fun rescanningKeepsEveryIndexAndAppendsTheNewFile() {
        val images = temporaryDirectory("catalog-images")
        val cache = temporaryDirectory("catalog-cache")
        writeImage(images, "b.png", 4, 4, Triple(10, 20, 30))
        writeImage(images, "c.png", 4, 4, Triple(40, 50, 60))

        val first = MegaTextureCatalog.scan(listOf(images), cacheRoot = cache)
        assertEquals(2, first.count)
        val before = (0 until first.count).associateWith { first.path(it)!!.fileName.toString() }

        // 'a' sorts before both, so a scan that re-sorted would renumber everything.
        writeImage(images, "a.png", 4, 4, Triple(70, 80, 90))
        val second = MegaTextureCatalog.scan(listOf(images), cacheRoot = cache)

        assertEquals(3, second.count)
        before.forEach { (index, name) ->
            assertEquals(name, second.path(index)!!.fileName.toString(), "index $index moved")
        }
        assertEquals("a.png", second.path(2)!!.fileName.toString(), "the new file is appended")
        assertTrue(second.changedIndices.isEmpty())
    }

    @Test
    fun aRewrittenFileIsReportedAsChanged() {
        val images = temporaryDirectory("changed-images")
        val cache = temporaryDirectory("changed-cache")
        val target = writeImage(images, "one.png", 4, 4, Triple(1, 2, 3))
        writeImage(images, "two.png", 4, 4, Triple(4, 5, 6))
        val first = MegaTextureCatalog.scan(listOf(images), cacheRoot = cache)
        assertTrue(first.changedIndices.isEmpty())
        val index = first.indexOf(target)!!

        // A different size makes the change detectable without depending on filesystem mtime
        // granularity, which is a second on some filesystems and would make this flaky.
        writeImage(images, "one.png", 8, 8, Triple(1, 2, 3))
        val second = MegaTextureCatalog.scan(listOf(images), cacheRoot = cache)
        assertContentEquals(intArrayOf(index), second.changedIndices)
    }

    @Test
    fun twoSourceSpecsGetSeparateCaches() {
        val a = temporaryDirectory("spec-a")
        val b = temporaryDirectory("spec-b")
        val cache = temporaryDirectory("spec-cache")
        writeImage(a, "x.png", 4, 4, Triple(1, 1, 1))
        writeImage(b, "y.png", 4, 4, Triple(2, 2, 2))
        val first = MegaTextureCatalog.scan(listOf(a), cacheRoot = cache)
        val second = MegaTextureCatalog.scan(listOf(b), cacheRoot = cache)
        assertTrue(first.cacheDir != second.cacheDir, "one index space per source spec, not per root")
    }

    @Test
    fun maxImagesTruncatesWithoutDisturbingIndices() {
        val images = temporaryDirectory("max-images")
        val cache = temporaryDirectory("max-cache")
        repeat(5) { writeImage(images, "i$it.png", 4, 4, Triple(it, it, it)) }
        val catalog = MegaTextureCatalog.scan(listOf(images), cacheRoot = cache, maxImages = 3)
        assertEquals(3, catalog.count)
        assertEquals("i0.png", catalog.path(0)!!.fileName.toString())
    }

    // ------------------------------------------------------------------------------ page cache

    @Test
    fun pagesRoundTripThroughTheSparseCache() {
        val dir = temporaryDirectory("cache-round-trip")
        MegaTexturePyramidCache(dir, 100, squareLayout(100, 8, 3), 2).use { cache ->
            val stride = 16 * 16 * 4
            val page = MemoryUtil.memAlloc(stride)
            try {
                for (i in 0 until stride) page.put(i, ((i * 7) and 0xff).toByte())
                assertFalse(cache.has(42, 1))
                cache.write(42, 1, page)
                assertTrue(cache.has(42, 1))
                assertFalse(cache.has(43, 1), "writing one page must not mark its neighbour present")

                val read = MemoryUtil.memAlloc(stride)
                try {
                    assertTrue(cache.read(42, 1, read))
                    for (i in 0 until stride) {
                        assertEquals(page.get(i), read.get(i), "byte $i differs after a round trip")
                    }
                } finally {
                    MemoryUtil.memFree(read)
                }
            } finally {
                MemoryUtil.memFree(page)
            }
        }
    }

    @Test
    fun presenceAndSourceSizesSurviveAReopen() {
        val dir = temporaryDirectory("cache-reopen")
        val stride = 8 * 8 * 4
        val page = MemoryUtil.memAlloc(stride)
        try {
            for (i in 0 until stride) page.put(i, (i and 0xff).toByte())
            MegaTexturePyramidCache(dir, 64, squareLayout(64, 8, 2), 1).use { cache ->
                cache.write(7, 0, page)
                cache.setSourceSize(7, 1920, 1080)
            }
            MegaTexturePyramidCache(dir, 64, squareLayout(64, 8, 2), 1).use { cache ->
                assertTrue(cache.has(7, 0), "presence must be persisted, or every start re-decodes")
                assertEquals(1920 to 1080, cache.sourceSize(7))
                assertNull(cache.sourceSize(8))
            }
        } finally {
            MemoryUtil.memFree(page)
        }
    }

    @Test
    fun invalidateForgetsOnlyTheNamedIndices() {
        val dir = temporaryDirectory("cache-invalidate")
        val stride = 8 * 8 * 4
        val page = MemoryUtil.memAlloc(stride)
        try {
            MegaTexturePyramidCache(dir, 64, squareLayout(64, 8, 2), 1).use { cache ->
                cache.write(3, 0, page)
                cache.write(4, 0, page)
                cache.invalidate(intArrayOf(3))
                assertFalse(cache.has(3, 0))
                assertTrue(cache.has(4, 0))
            }
        } finally {
            MemoryUtil.memFree(page)
        }
    }

    @Test
    fun aLevelTooBigToCacheIsSkippedRatherThanAllocated() {
        val dir = temporaryDirectory("cache-cap")
        // Level 1 pages are 16×16×4 = 1 KiB, so a million images is 1 GiB of sparse file. With a
        // 4 KiB cap neither level qualifies and nothing is created.
        MegaTexturePyramidCache(dir, 1_000_000, squareLayout(1_000_000, 8, 3), 2, maxVirtualBytes = 4096
        ).use { cache ->
            assertEquals(emptyList(), cache.cachedLevels())
            assertFalse(cache.has(0, 0))
            val page = MemoryUtil.memAlloc(8 * 8 * 4)
            try {
                cache.write(0, 0, page)
                assertFalse(cache.has(0, 0), "an uncached level must stay absent, not lie about it")
            } finally {
                MemoryUtil.memFree(page)
            }
        }
    }

    // --------------------------------------------------------------------------- aspect fitting

    @Test
    fun fitIntoKeepsAspectAndNeverCollapsesToZero() {
        assertEquals(64 to 36, MegaTextureDecoder.fitInto(1920, 1080, 64))
        assertEquals(36 to 64, MegaTextureDecoder.fitInto(1080, 1920, 64))
        assertEquals(8 to 8, MegaTextureDecoder.fitInto(500, 500, 8))
        // A 4000×1 panorama in an 8-pixel page: one row, not none.
        assertEquals(8 to 1, MegaTextureDecoder.fitInto(4000, 1, 8))
        assertEquals(1 to 8, MegaTextureDecoder.fitInto(1, 4000, 8))
        // An unknown source size has to fall back to the full square: publishing 0 would make the
        // shader's used rectangle empty and the image invisible.
        assertEquals(16 to 16, MegaTextureDecoder.fitInto(0, 0, 16))
    }

    @Test
    fun aFilmFrameFillsAFilmPageExactly() {
        // The point of rectangular pages: same shape in, no padding out.
        assertEquals(1920 to 798, MegaTextureDecoder.fitInto(1920, 798, 1920, 798))
        assertEquals(960 to 399, MegaTextureDecoder.fitInto(1920, 798, 960, 399))
        // And the square page it replaces wastes 63% of itself on the same frame.
        val (sw, sh) = MegaTextureDecoder.fitInto(1920, 798, 2048, 2048)
        assertEquals(2048 to 851, sw to sh)
        assertTrue(sw * sh < 2048 * 2048 * 45 / 100, "a 1920x798 frame should fill under 45% of a square page")
    }

    @Test
    fun fitIntoContainsRatherThanCropsInAPage() {
        // A picture taller than its page is limited by height, not width, so nothing is cut off.
        assertEquals(400 to 800, MegaTextureDecoder.fitInto(1000, 2000, 1920, 800))
        // And one wider than its page is limited by width.
        assertEquals(1920 to 480, MegaTextureDecoder.fitInto(4000, 1000, 1920, 800))
    }

    @Test
    fun fitIntoNeverExceedsThePage() {
        val pages = listOf(8 to 8, 16 to 16, 64 to 64, 512 to 512, 1920 to 798, 15 to 7, 100 to 1)
        for ((pw, ph) in pages) {
            for (w in listOf(1, 3, 17, 640, 4096, 12000)) {
                for (h in listOf(1, 3, 17, 480, 4096, 12000)) {
                    val (fw, fh) = MegaTextureDecoder.fitInto(w, h, pw, ph)
                    assertTrue(fw in 1..pw && fh in 1..ph, "fitInto($w,$h,${pw}x$ph) = $fw×$fh")
                }
            }
        }
    }

    // ------------------------------------------------------------------------------ the decoder

    private class Harness(
        val catalog: MegaTextureCatalog,
        val cache: MegaTexturePyramidCache,
        val decoder: MegaTextureDecoder
    ) : AutoCloseable {
        override fun close() {
            decoder.close()
            cache.close()
        }
    }

    private fun harness(
        images: Path,
        cacheDir: Path,
        levels: Int = 3,
        minPageSize: Int = 8,
        maxLevelCached: Int = 2,
        // Non-null for a rectangular page ladder whose top page is this size.
        sourcePage: Pair<Int, Int>? = null,
        stillWanted: (Int, Int, Long) -> Boolean = { _, _, _ -> true }
    ): Harness {
        val catalog = MegaTextureCatalog.of(
            Files.list(images).use { it.sorted().toList() },
            cacheDir.also { it.createDirectories() }
        )
        val layout = MegaTextureLayout(
            imageCount = catalog.count,
            levels = levels,
            atlasEdge = 4096,
            layersPerLevel = 2,
            maxBaseLevelLayers = 64,
            topPageWidth = sourcePage?.first ?: (minPageSize shl (levels - 1)),
            topPageHeight = sourcePage?.second ?: (minPageSize shl (levels - 1))
        )
        val cache = MegaTexturePyramidCache(cacheDir, catalog.count, layout, maxLevelCached)
        return Harness(
            catalog, cache,
            MegaTextureDecoder(catalog, cache, layout, threads = 2, stillWanted = stillWanted)
        )
    }

    private fun awaitPage(decoder: MegaTextureDecoder, timeoutMillis: Long = 20_000): MegaDecodedPage? {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            decoder.poll()?.let { return it }
            Thread.sleep(5)
        }
        return null
    }

    @Test
    fun aRequestComesBackAsThePageThatWasAskedFor() {
        val images = temporaryDirectory("decode-images")
        val cacheDir = temporaryDirectory("decode-cache")
        writeImage(images, "flat.png", 128, 64, Triple(200, 100, 50))

        harness(images, cacheDir).use { h ->
            h.decoder.request(0, 1, score = 1f, generation = 1)
            val page = assertNotNull(awaitPage(h.decoder), "no page arrived")
            assertEquals(0, page.index)
            assertEquals(1, page.level)
            assertEquals(16 to 16, page.pageWidth to page.pageHeight)
            assertEquals(16 to 8, page.usedWidth to page.usedHeight, "2:1 source in a 16px page")
            assertEquals(128 to 64, page.sourceWidth to page.sourceHeight)
            assertFalse(page.fromCache, "the first decode cannot be a cache hit")

            // Top-left pixel of the used rectangle is the flat source colour.
            assertEquals(200, page.pixels.get(0).toInt() and 0xff)
            assertEquals(100, page.pixels.get(1).toInt() and 0xff)
            assertEquals(50, page.pixels.get(2).toInt() and 0xff)
            assertEquals(255, page.pixels.get(3).toInt() and 0xff)

            // The mean colour the shader falls back to is that same colour, packed 0xAABBGGRR.
            assertEquals(0xff, (page.averageColor ushr 24) and 0xff)
            assertEquals(200, page.averageColor and 0xff)
            assertEquals(100, (page.averageColor ushr 8) and 0xff)
            assertEquals(50, (page.averageColor ushr 16) and 0xff)
            h.decoder.recycle(page)
        }
    }

    @Test
    fun aRectangularPageComesBackFullOfPicture() {
        val images = temporaryDirectory("film-images")
        val cacheDir = temporaryDirectory("film-cache")
        // A 2.406:1 frame, the shape of the film set this was built for.
        writeImage(images, "frame.png", 1920, 798, Triple(90, 120, 150))

        harness(images, cacheDir, levels = 4, sourcePage = 1920 to 798).use { h ->
            h.decoder.request(0, 3, score = 1f, generation = 1)
            val page = assertNotNull(awaitPage(h.decoder), "no page arrived")
            assertEquals(1920 to 798, page.pageWidth to page.pageHeight)
            // Every texel of the page is picture — that is the saving, measured.
            assertEquals(1920 to 798, page.usedWidth to page.usedHeight)
            // Bottom-right texel of the page is real picture, not pool garbage.
            val at = ((798 - 1) * 1920 + (1920 - 1)) * 4
            assertEquals(90, page.pixels.get(at).toInt() and 0xff)
            assertEquals(120, page.pixels.get(at + 1).toInt() and 0xff)
            assertEquals(150, page.pixels.get(at + 2).toInt() and 0xff)
            h.decoder.recycle(page)
        }
    }

    @Test
    fun oneDecodeFillsEveryCacheableLevel() {
        val images = temporaryDirectory("pyramid-images")
        val cacheDir = temporaryDirectory("pyramid-cache")
        writeImage(images, "flat.png", 64, 64, Triple(11, 22, 33))

        harness(images, cacheDir, levels = 3, maxLevelCached = 2).use { h ->
            h.decoder.request(0, 2, score = 1f, generation = 1)
            val page = assertNotNull(awaitPage(h.decoder))
            h.decoder.recycle(page)
            for (level in 0..2) {
                assertTrue(h.cache.has(0, level), "level $level should have been cached for free")
            }
            assertEquals(1L, h.decoder.decodedFromSource.get(), "exactly one JPEG/PNG decode")
        }
    }

    @Test
    fun theSecondRequestIsServedFromDisk() {
        val images = temporaryDirectory("warm-images")
        val cacheDir = temporaryDirectory("warm-cache")
        writeImage(images, "flat.png", 96, 32, Triple(7, 9, 11))

        harness(images, cacheDir).use { h ->
            h.decoder.request(0, 1, 1f, 1)
            h.decoder.recycle(assertNotNull(awaitPage(h.decoder)))
        }
        // A fresh decoder over the same cache directory: this is the warm-start path.
        harness(images, cacheDir).use { h ->
            h.decoder.request(0, 1, 1f, 1)
            val page = assertNotNull(awaitPage(h.decoder))
            assertTrue(page.fromCache, "the page was cached; it should not have been re-decoded")
            assertEquals(1L, h.decoder.decodedFromCache.get())
            assertEquals(0L, h.decoder.decodedFromSource.get())
            // Aspect has to survive a warm start, which is what the cache's dims file is for.
            assertEquals(16 to 5, page.usedWidth to page.usedHeight, "3:1 source in a 16px page")
            h.decoder.recycle(page)
        }
    }

    @Test
    fun aRequestThatIsNoLongerWantedIsDroppedInsteadOfDecoded() {
        val images = temporaryDirectory("cancel-images")
        val cacheDir = temporaryDirectory("cancel-cache")
        repeat(4) { writeImage(images, "i$it.png", 64, 64, Triple(it * 10, 0, 0)) }

        // Only image 2 is still wanted. The other three are cancelled at the moment a worker
        // picks them up, which is how cancellation stays free no matter how deep the queue is.
        harness(images, cacheDir, stillWanted = { index, _, _ -> index == 2 }).use { h ->
            repeat(4) { h.decoder.request(it, 0, 1f, 1) }
            val page = assertNotNull(awaitPage(h.decoder))
            assertEquals(2, page.index)
            h.decoder.recycle(page)

            val deadline = System.nanoTime() + 10_000_000_000L
            while (h.decoder.cancelled.get() < 3 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(3L, h.decoder.cancelled.get())
            assertEquals(1L, h.decoder.decodedFromSource.get())
            assertNull(h.decoder.poll(), "a cancelled request must not produce a page")
        }
    }

    @Test
    fun aMissingFileIsAFailureAndNotAHang() {
        val images = temporaryDirectory("missing-images")
        val cacheDir = temporaryDirectory("missing-cache")
        writeImage(images, "real.png", 32, 32, Triple(1, 2, 3))
        val catalog = MegaTextureCatalog.of(
            listOf(images.resolve("real.png"), images.resolve("does-not-exist.png")),
            cacheDir
        )
        MegaTexturePyramidCache(cacheDir, 2, squareLayout(2, 8, 2), 1).use { cache ->
            MegaTextureDecoder(catalog, cache, squareLayout(2, 8, 2), threads = 1) { _, _, _ -> true }.use { decoder ->
                decoder.request(1, 0, 1f, 1)
                val deadline = System.nanoTime() + 10_000_000_000L
                while (decoder.decodeFailures.get() == 0L && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals(1L, decoder.decodeFailures.get())
                assertNull(decoder.poll())
            }
        }
    }

    @Test
    fun trimKeepsTheBestScoringRequests() {
        val images = temporaryDirectory("trim-images")
        val cacheDir = temporaryDirectory("trim-cache")
        writeImage(images, "one.png", 16, 16, Triple(0, 0, 0))
        val catalog = MegaTextureCatalog.of(listOf(images.resolve("one.png")), cacheDir)

        // One worker, parked inside stillWanted, so the queue is not being drained while the trim
        // is measured. Letting the worker run instead made this flaky — it cancelled 48 of the 50
        // before trimTo was even called.
        val parked = java.util.concurrent.CountDownLatch(1)
        val arrived = java.util.concurrent.CountDownLatch(1)
        MegaTexturePyramidCache(cacheDir, 1, squareLayout(1, 8, 2), 1).use { cache ->
            MegaTextureDecoder(catalog, cache, squareLayout(1, 8, 2), threads = 1, stillWanted = { _, _, _ ->
                arrived.countDown()
                parked.await()
                false
            }).use { decoder ->
                repeat(50) { decoder.request(0, 0, score = it.toFloat(), generation = 1) }
                assertTrue(arrived.await(10, java.util.concurrent.TimeUnit.SECONDS), "no worker woke up")
                val before = decoder.queueDepth
                assertTrue(before > 10, "expected a backlog, got $before")

                decoder.trimTo(10)
                assertEquals(10, decoder.queueDepth)
                assertEquals((before - 10).toLong(), decoder.cancelled.get())
                parked.countDown()
            }
        }
    }

    // ------------------------------------------------------------------------------ mean colour

    @Test
    fun theMeanColourIgnoresThePagesUnusedMargin() {
        // A 32-pixel page holding a 32×8 picture: the margin below row 8 is left at whatever the
        // memory pool last held, and must not be averaged in.
        val pageSize = 32
        val page = MemoryUtil.memAlloc(pageSize * pageSize * 4)
        try {
            for (i in 0 until pageSize * pageSize * 4) page.put(i, 0xff.toByte())
            for (y in 0 until 8) {
                for (x in 0 until pageSize) {
                    val at = (y * pageSize + x) * 4
                    page.put(at, 20.toByte())
                    page.put(at + 1, 40.toByte())
                    page.put(at + 2, 60.toByte())
                    page.put(at + 3, 0xff.toByte())
                }
            }
            val mean = MegaTextureDecoder.averageOf(page, pageSize, usedWidth = pageSize, usedHeight = 8)
            assertEquals(20, mean and 0xff)
            assertEquals(40, (mean ushr 8) and 0xff)
            assertEquals(60, (mean ushr 16) and 0xff)
        } finally {
            MemoryUtil.memFree(page)
        }
    }

    @Test
    fun anEmptyUsedRectangleGivesAGreyRatherThanDividingByZero() {
        val page: ByteBuffer = MemoryUtil.memAlloc(64)
        try {
            assertEquals(
                0xff808080.toInt(),
                MegaTextureDecoder.averageOf(page, pageWidth = 4, usedWidth = 0, usedHeight = 0)
            )
        } finally {
            MemoryUtil.memFree(page)
        }
    }
}
