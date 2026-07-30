package afield.stdlib

import afield.Box
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The box-output contract of the imaging helpers (`imaging_field.kt`).
 *
 * The invariant that matters here is a *counting* one, and it is the one that used to be broken:
 * `display(channel, …)` **replaces** that channel's contents rather than appending, so anything a
 * helper wants on screen at once has to leave as **one** payload. [Box.showImg] emitted the caption
 * and then the PNG as two `display` calls on the same channel, so the caption was written and
 * immediately overwritten — silently, with no error, and the docs captures that asked for a caption
 * came out without one.
 *
 * These tests therefore assert payload *count* as well as content. A test that only checked "the
 * caption text was passed to display somewhere" passed against the broken version.
 */
class ImagingBoxOutputTest {

    /** Capture every `display(channel, content)` the box emits, in order. */
    private fun Box.captureDisplays(): MutableList<Pair<String, Any?>> {
        val seen = mutableListOf<Pair<String, Any?>>()
        output.displayProxy = { channel, content -> seen += channel to content }
        return seen
    }

    private fun ramp(w: Int = 8, h: Int = 8) = Img.generate(w, h) { x, _ -> x / (w - 1).toFloat() }

    private fun assertIsPng(bytes: ByteArray) {
        assertTrue(bytes.size >= 8, "too short to be a PNG: ${bytes.size} bytes")
        assertEquals(0x89.toByte(), bytes[0], "PNG magic byte 0")
        assertEquals('P'.code.toByte(), bytes[1], "PNG magic byte 1")
        assertEquals('N'.code.toByte(), bytes[2], "PNG magic byte 2")
        assertEquals('G'.code.toByte(), bytes[3], "PNG magic byte 3")
    }

    // ==========================================================================================
    // showImg
    // ==========================================================================================

    @Test
    fun showImgWithNoCaptionEmitsOnePngBytePayload() {
        val box = Box("imaging-output-test")
        val seen = box.captureDisplays()

        box.showImg(ramp())

        assertEquals(1, seen.size, "one display per showImg")
        assertEquals("main", seen[0].first)
        val bytes = seen[0].second
        assertIs<ByteArray>(bytes, "the no-caption path stays the raw-bytes path")
        assertIsPng(bytes)
    }

    @Test
    fun showImgWithACaptionEmitsExactlyOnePayloadCarryingBoth() {
        val box = Box("imaging-output-test")
        val seen = box.captureDisplays()

        box.showImg(ramp(), caption = "one glider, five generations")

        // The regression: this used to be 2, and the second overwrote the first.
        assertEquals(1, seen.size, "the caption and the picture must be ONE display payload")
        assertEquals("main", seen[0].first)
        val html = seen[0].second
        assertIs<String>(html, "the captioned path is a single HTML figure")
        assertTrue("one glider, five generations" in html, "caption text survives: $html")
        assertTrue("<figcaption" in html, "caption is a real figcaption: $html")
        assertTrue("data:image/png;base64," in html, "picture is inline in the same payload: $html")
    }

    @Test
    fun captionedFigureEmbedsTheSamePngTheBytesPathWouldSend() {
        val img = ramp(16, 9)
        val plain = Box("imaging-output-test").let { b ->
            val seen = b.captureDisplays(); b.showImg(img); seen[0].second as ByteArray
        }
        val captioned = Box("imaging-output-test").let { b ->
            val seen = b.captureDisplays(); b.showImg(img, caption = "same picture"); seen[0].second as String
        }

        val b64 = java.util.Base64.getEncoder().encodeToString(plain)
        assertTrue(b64 in captioned, "the figure inlines the identical PNG, not a re-encoded one")
        assertIsPng(java.util.Base64.getDecoder().decode(b64))
    }

    @Test
    fun captionIsHtmlEscaped() {
        val box = Box("imaging-output-test")
        val seen = box.captureDisplays()

        box.showImg(ramp(), caption = "a < b & c > d, \"quoted\" & 'single'")

        val html = seen.single().second as String
        assertFalse("a < b" in html, "raw '<' would open a tag: $html")
        assertTrue("a &lt; b &amp; c &gt; d" in html, "escaped caption: $html")
        assertTrue("&quot;quoted&quot;" in html, "escaped double quotes (the img alt uses ' but be safe): $html")
        assertTrue("&#39;single&#39;" in html, "escaped single quotes, which delimit the style attributes: $html")
    }

    @Test
    fun outOfRangeImageSaysItWasNormalisedInTheCaption() {
        val box = Box("imaging-output-test")
        val seen = box.captureDisplays()

        // A signed gradient — exactly the Sobel/Laplacian case the KDoc promises to disclose.
        box.showImg(Img.generate(8, 8) { x, _ -> x - 4f }, caption = "a signed gradient")

        val html = seen.single().second as String
        assertTrue("a signed gradient" in html, "the user's caption is still there: $html")
        assertTrue("normalised for display" in html, "normalisation is disclosed: $html")
    }

    @Test
    fun inRangeImageDoesNotClaimNormalisation() {
        val box = Box("imaging-output-test")
        val seen = box.captureDisplays()

        box.showImg(ramp(), caption = "already 0..1")

        val html = seen.single().second as String
        assertFalse("normalised for display" in html, "nothing to disclose for a 0..1 image: $html")
    }

    @Test
    fun showImgHonoursTheChannelOnBothPaths() {
        val box = Box("imaging-output-test")
        val seen = box.captureDisplays()

        box.showImg(ramp(), channel = "plain")
        box.showImg(ramp(), channel = "captioned", caption = "hello")

        assertEquals(listOf("plain", "captioned"), seen.map { it.first })
    }

    @Test
    fun showImgClampsToMaxSideOnBothPaths() {
        val big = ramp(400, 100)

        val plainBox = Box("imaging-output-test")
        val plainSeen = plainBox.captureDisplays()
        plainBox.showImg(big, maxSide = 40)
        // 400x100 scaled so the long edge is 40 -> 40x10. Decode-free check: re-encode and compare.
        val expected = big.scaleBy(40.0 / 400.0).toPngBytes()
        assertTrue((plainSeen.single().second as ByteArray).contentEquals(expected), "bytes path scaled to maxSide")

        val figureBox = Box("imaging-output-test")
        val figureSeen = figureBox.captureDisplays()
        figureBox.showImg(big, maxSide = 40, caption = "small")
        val b64 = java.util.Base64.getEncoder().encodeToString(expected)
        assertTrue(b64 in (figureSeen.single().second as String), "figure path scaled to maxSide identically")
    }

    // ==========================================================================================
    // showImgGrid — already one payload, guarded so it stays that way
    // ==========================================================================================

    @Test
    fun showImgGridEmitsOneMontagePayloadWithLabelsBakedIn() {
        val box = Box("imaging-output-test")
        val seen = box.captureDisplays()

        box.showImgGrid("left" to ramp(), "right" to ramp(), cellSide = 64)

        assertEquals(1, seen.size, "a grid is one montage, one display")
        assertIsPng(seen[0].second as ByteArray)
    }
}
