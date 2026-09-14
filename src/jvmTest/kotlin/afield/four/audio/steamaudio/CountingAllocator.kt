package afield.four.audio.steamaudio

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicLong

/**
 * A [MemoryAllocator] backed by libc `malloc`/`free` that counts what it hands out.
 *
 * This is the instrument the leak tests measure with. Steam Audio's own allocator is opaque, so
 * the only way to see whether closing an object returns its memory is to supply the allocator
 * ourselves and watch the books.
 *
 * ### Alignment
 *
 * Steam Audio asks for SIMD-aligned blocks and then uses aligned loads, so an under-aligned
 * pointer is a crash rather than a slowdown. Rather than reach for `aligned_alloc` (C11, absent
 * from older Windows runtimes) or `_aligned_malloc` (Windows-only), this over-allocates with
 * plain `malloc` and stores the original pointer in the eight bytes immediately before the
 * aligned block -- the classic portable trick, and it needs nothing beyond `malloc` and `free`,
 * which are in the default lookup on all three platforms.
 */
class CountingAllocator : MemoryAllocator {

    private val liveBlocks = AtomicLong(0)
    private val liveBytes = AtomicLong(0)
    private val totalAllocations = AtomicLong(0)
    private val totalFrees = AtomicLong(0)
    private val peakBytes = AtomicLong(0)

    /** Blocks allocated but not yet freed. */
    val outstandingBlocks: Long get() = liveBlocks.get()

    /** Bytes requested but not yet freed, excluding this allocator's own bookkeeping overhead. */
    val outstandingBytes: Long get() = liveBytes.get()

    /** Total allocations ever made. */
    val allocationCount: Long get() = totalAllocations.get()

    /** Total frees ever made. */
    val freeCount: Long get() = totalFrees.get()

    /** Largest [outstandingBytes] seen. */
    val peakOutstandingBytes: Long get() = peakBytes.get()

    override fun allocate(size: Long, alignment: Long): MemorySegment {
        require(alignment > 0 && (alignment and (alignment - 1)) == 0L) {
            "alignment must be a power of two, got $alignment"
        }
        // Room for the requested block, the worst-case alignment shift, and the header holding
        // the original malloc pointer.
        val overhead = HEADER_BYTES + alignment
        val raw = systemMalloc(size + overhead)
        if (raw.address() == 0L) return MemorySegment.NULL

        val rawAddress = raw.address()
        val alignedAddress = (rawAddress + overhead) and (alignment - 1).inv()
        // The header sits immediately before the aligned block, which is guaranteed to be
        // inside the malloc'd region because we reserved `overhead` bytes in front of it.
        val header = MemorySegment.ofAddress(alignedAddress - HEADER_BYTES)
            .reinterpret(HEADER_BYTES)
        header.set(ValueLayout.JAVA_LONG, 0L, rawAddress)

        liveBlocks.incrementAndGet()
        totalAllocations.incrementAndGet()
        val now = liveBytes.addAndGet(size)
        peakBytes.accumulateAndGet(now, ::maxOf)

        // Size is recorded so free() can subtract the right amount. It lives in a second
        // header slot, hence HEADER_BYTES being two longs wide.
        MemorySegment.ofAddress(alignedAddress - HEADER_BYTES + Long.SIZE_BYTES)
            .reinterpret(Long.SIZE_BYTES.toLong())
            .set(ValueLayout.JAVA_LONG, 0L, size)

        return MemorySegment.ofAddress(alignedAddress).reinterpret(size)
    }

    override fun free(block: MemorySegment) {
        if (block.address() == 0L) return
        val header = MemorySegment.ofAddress(block.address() - HEADER_BYTES).reinterpret(HEADER_BYTES)
        val rawAddress = header.get(ValueLayout.JAVA_LONG, 0L)
        val size = header.get(ValueLayout.JAVA_LONG, Long.SIZE_BYTES.toLong())

        liveBlocks.decrementAndGet()
        liveBytes.addAndGet(-size)
        totalFrees.incrementAndGet()

        systemFree(MemorySegment.ofAddress(rawAddress))
    }

    /** A snapshot, for comparing two points in a test. */
    fun snapshot(): Snapshot = Snapshot(outstandingBlocks, outstandingBytes, allocationCount, freeCount)

    data class Snapshot(
        val blocks: Long,
        val bytes: Long,
        val allocations: Long,
        val frees: Long,
    ) {
        override fun toString(): String =
            "$blocks blocks / $bytes bytes outstanding (alloc $allocations, free $frees)"
    }

    override fun toString(): String = snapshot().toString()

    private companion object {
        /** Two longs: the original malloc pointer, and the requested size. */
        const val HEADER_BYTES = 16L

        private val linker = Linker.nativeLinker()
        private val lookup = linker.defaultLookup()

        private val mallocHandle = linker.downcallHandle(
            lookup.find("malloc").orElseThrow { UnsatisfiedLinkError("malloc not found") },
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
        )

        private val freeHandle = linker.downcallHandle(
            lookup.find("free").orElseThrow { UnsatisfiedLinkError("free not found") },
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
        )

        // Deliberately not named malloc/free: a member function named `free` would shadow a
        // companion function of the same name at the call site inside `free`, turning the
        // libc call into unbounded recursion. That mistake segfaults rather than failing
        // cleanly, so the names are kept distinct.
        fun systemMalloc(bytes: Long): MemorySegment = mallocHandle.invokeExact(bytes) as MemorySegment

        fun systemFree(pointer: MemorySegment) {
            freeHandle.invokeExact(pointer)
        }
    }
}
