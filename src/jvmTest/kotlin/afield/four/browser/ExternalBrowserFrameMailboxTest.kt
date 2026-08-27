package afield.four.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ExternalBrowserFrameMailboxTest {
    private val spec = ExternalBrowserSurfaceSpec(width = 800, height = 600, slotCount = 3)
    private val releases = mutableListOf<BrowserFrameRelease>()
    private val mailbox = ExternalBrowserFrameMailbox(spec, releases::add)

    @Test
    fun `conflates same-slot paints without losing an unconsumed handle`() {
        val handle = ExternalBrowserImageHandle.MacIOSurfaceId(41)
        assertEquals(BrowserFrameOfferResult.ACCEPTED, mailbox.offer(frame(1, 0, handle)))
        assertEquals(BrowserFrameOfferResult.ACCEPTED_REPLACING_PENDING, mailbox.offer(frame(2, 0)))

        val selected = mailbox.takeLatest()!!
        assertEquals(2, selected.frameId)
        assertEquals(handle, selected.handle)
        assertEquals(listOf(1L), releases.map { it.frameId })
        assertEquals(BrowserFrameReleaseReason.SUPERSEDED, releases.single().reason)
    }

    @Test
    fun `takes only the newest slot and releases the others`() {
        mailbox.offer(frame(1, 0, ExternalBrowserImageHandle.MacIOSurfaceId(41)))
        mailbox.offer(frame(2, 1, ExternalBrowserImageHandle.MacIOSurfaceId(42)))

        assertEquals(2, mailbox.takeLatest()!!.frameId)
        assertEquals(listOf(1L), releases.map { it.frameId })
        assertEquals(BrowserFrameReleaseReason.SUPERSEDED, releases.single().reason)
    }

    @Test
    fun `a new producer stream resets frame ordering and sticky handles`() {
        mailbox.offer(frame(100, 0, ExternalBrowserImageHandle.MacIOSurfaceId(51), stream = 7))
        mailbox.takeLatest()

        assertEquals(BrowserFrameOfferResult.ACCEPTED, mailbox.offer(frame(0, 0, stream = 8)))
        assertNull(mailbox.takeLatest()!!.handle)
        assertEquals(BrowserFrameOfferResult.REJECTED_STALE, mailbox.offer(frame(101, 0, stream = 7)))
        assertEquals(BrowserFrameReleaseReason.STALE, releases.last().reason)
    }

    @Test
    fun `invalid stale and closed frames return their producer leases`() {
        val wrongSize = frame(1, 0).copy(width = 799)
        assertEquals(BrowserFrameOfferResult.REJECTED_INVALID, mailbox.offer(wrongSize))
        assertEquals(BrowserFrameReleaseReason.INVALID, releases.last().reason)

        val outside = frame(1, 0).copy(visibleRect = BrowserSurfaceRect(799, 0, 2, 1))
        assertEquals(BrowserFrameOfferResult.REJECTED_INVALID, mailbox.offer(outside))
        assertEquals(BrowserFrameReleaseReason.INVALID, releases.last().reason)

        val negativeTimestamp = frame(1, 0).copy(timestampMicros = -1)
        assertEquals(BrowserFrameOfferResult.REJECTED_INVALID, mailbox.offer(negativeTimestamp))
        assertEquals(BrowserFrameReleaseReason.INVALID, releases.last().reason)

        mailbox.offer(frame(2, 0))
        assertEquals(BrowserFrameOfferResult.REJECTED_STALE, mailbox.offer(frame(2, 1)))
        assertEquals(BrowserFrameReleaseReason.STALE, releases.last().reason)

        mailbox.close()
        assertEquals(BrowserFrameReleaseReason.CLOSED, releases.last().reason)
        assertEquals(BrowserFrameOfferResult.REJECTED_CLOSED, mailbox.offer(frame(3, 0)))
        assertEquals(BrowserFrameReleaseReason.CLOSED, releases.last().reason)
    }

    @Test
    fun `linux handle retains every plane field needed by an eventual importer`() {
        val handle = ExternalBrowserImageHandle.LinuxDmaBuf(
            planes = listOf(ExternalBrowserImageHandle.LinuxDmaBuf.Plane(fd = 7, stride = 4096, offset = 128, size = 1_048_576)),
            modifier = -1L
        )
        mailbox.offer(frame(1, 0, handle))
        assertIs<ExternalBrowserImageHandle.LinuxDmaBuf>(mailbox.takeLatest()!!.handle)
    }

    private fun frame(
        id: Long,
        slot: Int,
        handle: ExternalBrowserImageHandle? = null,
        stream: Long = 1
    ) = ExternalBrowserFrame(
        streamId = stream,
        frameId = id,
        slot = slot,
        width = spec.width,
        height = spec.height,
        pixelFormat = spec.pixelFormat,
        handle = handle
    )
}
