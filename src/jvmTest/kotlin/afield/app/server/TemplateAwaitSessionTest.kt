package afield.app.server

import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A page's `hello` attaches its document on a coroutine, so an `insert-template` the page sends straight
 * after can arrive before that socket has a session. On 2026-11-20 such an insert threw a bare
 * NullPointerException and was dropped without a word; the handler now waits for the document with
 * [awaitAttachedDocument] and inserts then, or says it could not.
 */
class TemplateAwaitSessionTest {
    private data class Doc(val name: String, val loading: Boolean)

    private fun await(lookup: () -> Doc?, isOpen: () -> Boolean = { true }, timeoutMs: Long = 2_000) = runBlocking {
        awaitAttachedDocument(lookup, isLoading = { it.loading }, isOpen = isOpen, timeoutMs = timeoutMs, pollMs = 5)
    }

    @Test
    fun aDocumentAlreadyThereIsReturnedAtOnce() {
        val doc = Doc("first", loading = false)
        assertEquals(doc, await({ doc }))
    }

    @Test
    fun aDocumentThatAttachesLaterIsWaitedFor() {
        val polls = AtomicInteger()
        val doc = Doc("late", loading = false)
        assertEquals(doc, await({ if (polls.incrementAndGet() < 4) null else doc }))
        assertEquals(4, polls.get())
    }

    @Test
    fun aDocumentStillLoadingIsWaitedFor() {
        // A box inserted while its document loads is not marked for saving (`Box.notePersistentChange`).
        val polls = AtomicInteger()
        val answer = await({ Doc("loading", loading = polls.incrementAndGet() < 3) })
        assertEquals(Doc("loading", loading = false), answer)
    }

    @Test
    fun aSocketThatClosesStopsTheWait() {
        assertNull(await({ null }, isOpen = { false }))
    }

    @Test
    fun aDocumentThatNeverAttachesTimesOut() {
        assertNull(await({ null }, timeoutMs = 50))
    }
}
