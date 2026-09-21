package afield.four

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class NativeWindowCaptureStalledException(message: String) : IllegalStateException(message)

internal class WindowCaptureRequest(
    val windowIndex: Int,
    val outputPath: String,
    val requestedAtNs: Long,
    val frameAtRequest: Long,
    val completion: CompletableDeferred<String> = CompletableDeferred()
)

/** Capture queue and per-window progress, independent of Vulkan so cancellation is testable. */
internal class WindowCaptureQueue {
    private val lock = Any()
    private val pending = mutableMapOf<Int, ArrayDeque<WindowCaptureRequest>>()
    private val frames = mutableMapOf<Int, Long>()

    fun enqueue(windowIndex: Int, outputPath: String, requestedAtNs: Long): WindowCaptureRequest =
        synchronized(lock) {
            WindowCaptureRequest(windowIndex, outputPath, requestedAtNs, frames[windowIndex] ?: 0L).also {
                pending.getOrPut(windowIndex) { ArrayDeque() }.addLast(it)
            }
        }

    fun pendingCount(windowIndex: Int): Int = synchronized(lock) { pending[windowIndex]?.size ?: 0 }

    // Called unconditionally at this window's capture point, even when its queue is empty.
    fun takeForFrame(windowIndex: Int): List<WindowCaptureRequest> = synchronized(lock) {
        frames[windowIndex] = (frames[windowIndex] ?: 0L) + 1L
        pending.remove(windowIndex)?.filter { it.completion.isActive } ?: emptyList()
    }

    suspend fun await(
        request: WindowCaptureRequest,
        timeoutMs: Long,
        noFrameTimeoutMs: Long = 5_000L
    ): String {
        try {
            require(timeoutMs > 0 && noFrameTimeoutMs > 0) { "Capture budgets must be positive" }
            return withTimeout(timeoutMs) {
                coroutineScope {
                    // Do not require the render/runloop dispatcher to execute its own watchdog.
                    val watchdog = launch(Dispatchers.Default) {
                        delay(noFrameTimeoutMs)
                        synchronized(lock) {
                            val current = frames[request.windowIndex] ?: 0L
                            if (pending[request.windowIndex]?.contains(request) == true &&
                                current == request.frameAtRequest
                            ) {
                                request.completion.completeExceptionally(NativeWindowCaptureStalledException(
                                    "Native window capture stalled: windowIndex=${request.windowIndex} " +
                                        "frame=$current unchanged for ${noFrameTimeoutMs}ms; " +
                                        "the renderer did not reach this window's capture point"
                                ))
                            }
                        }
                    }
                    try {
                        request.completion.await()
                    } finally {
                        watchdog.cancel()
                    }
                }
            }
        } finally {
            // A timed-out caller must not leave work behind for a future renderer frame.
            synchronized(lock) {
                pending[request.windowIndex]?.let { queue ->
                    queue.remove(request)
                    if (queue.isEmpty()) pending.remove(request.windowIndex)
                }
                request.completion.cancel()
            }
        }
    }
}
