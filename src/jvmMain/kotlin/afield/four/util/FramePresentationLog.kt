package afield.four.util

import afield.logger.lprintln
import org.lwjgl.vulkan.KHRSwapchain
import org.lwjgl.vulkan.VK10
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object FramePresentationLog {
    private data class PresentKey(val swapchain: Long, val presentId: Long)

    private data class PresentRecord(
        val frameId: Long,
        val windowIndex: Int,
        val imageIndex: Int,
        val callNs: Long,
        val returnNs: Long
    )

    @Volatile
    var enabled: Boolean = Options.framePresentationLog

    @Volatile
    var waitAfterPresent: Boolean = Options.framePresentationLogWaitAfterPresent

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var path: Path? = Options.framePresentationLogPath?.takeIf { it.isNotBlank() }?.let(Path::of)
    private var originNs: Long = System.nanoTime()
    private var linesSinceFlush = 0
    private val pendingPresents = mutableMapOf<PresentKey, PresentRecord>()
    private val lastPresentReturnNsByWindow = mutableMapOf<Int, Long>()
    private val lastObservedPresentNsByWindow = mutableMapOf<Int, Long>()
    private val lastScanoutNsByWindow = mutableMapOf<Int, Long>()

    fun start(
        outputPath: String? = null,
        waitAfterPresent: Boolean = Options.framePresentationLogWaitAfterPresent,
        append: Boolean = false
    ): String {
        synchronized(lock) {
            closeLocked()
            this.waitAfterPresent = waitAfterPresent
            path = outputPath?.takeIf { it.isNotBlank() }?.let(Path::of) ?: defaultPath()
            originNs = System.nanoTime()
            linesSinceFlush = 0
            pendingPresents.clear()
            lastPresentReturnNsByWindow.clear()
            lastObservedPresentNsByWindow.clear()
            lastScanoutNsByWindow.clear()
            writer = openWriter(path!!, append)
            writeHeaderLocked()
            enabled = true
            writeLineLocked(originNs, "log_start", -1L, -1, -1, null, null, null, "wait_after_present=$waitAfterPresent")
            flushLocked()
            return path!!.toAbsolutePath().normalize().toString()
        }
    }

    fun stop() {
        synchronized(lock) {
            if (writer != null) {
                writeLineLocked(System.nanoTime(), "log_stop", -1L, -1, -1, null, null, null, "")
            }
            enabled = false
            closeLocked()
        }
    }

    fun mark(message: String) {
        synchronized(lock) {
            if (!enabled) return
            ensureWriterLocked()
            writeLineLocked(System.nanoTime(), "mark", -1L, -1, -1, null, null, null, message)
            flushLocked()
        }
    }

    fun addLine(message: String) = mark(message)

    internal fun presentReturned(
        swapchain: Long,
        frameId: Long,
        windowIndex: Int,
        imageIndex: Int,
        presentId: Long?,
        callNs: Long,
        returnNs: Long,
        result: Int
    ) {
        synchronized(lock) {
            if (!enabled) return
            ensureWriterLocked()
            val intervalMs = lastPresentReturnNsByWindow.put(windowIndex, returnNs)?.let { nsToMs(returnNs - it) }
            val queueReturnMs = nsToMs(returnNs - callNs)
            writeLineLocked(
                returnNs,
                "present_return",
                frameId,
                windowIndex,
                imageIndex,
                presentId,
                queueReturnMs,
                intervalMs,
                "result=${vkResultName(result)}"
            )
            if (presentId != null) {
                pendingPresents[PresentKey(swapchain, presentId)] =
                    PresentRecord(frameId, windowIndex, imageIndex, callNs, returnNs)
            }
            flushIfNeededLocked()
        }
    }

    internal fun presentObserved(
        swapchain: Long,
        presentId: Long,
        reason: String,
        result: Int,
        waitBeginNs: Long,
        waitEndNs: Long
    ) {
        synchronized(lock) {
            if (!enabled) return
            ensureWriterLocked()
            val record = pendingPresents[PresentKey(swapchain, presentId)]
            val windowIndex = record?.windowIndex ?: -1
            val intervalMs = lastObservedPresentNsByWindow.put(windowIndex, waitEndNs)?.let { nsToMs(waitEndNs - it) }
            val sincePresentCallMs = record?.let { nsToMs(waitEndNs - it.callNs) }
            val waitMs = nsToMs(waitEndNs - waitBeginNs)
            writeLineLocked(
                waitEndNs,
                "present_observed",
                record?.frameId ?: -1L,
                windowIndex,
                record?.imageIndex ?: -1,
                presentId,
                sincePresentCallMs,
                intervalMs,
                "reason=$reason result=${vkResultName(result)} wait_ms=${formatMs(waitMs)}"
            )
            flushIfNeededLocked()
        }
    }

    /**
     * Called by [SwapChain.drainPastTimings] for each VK_EXT_present_timing stage timestamp
     * returned by `vkGetPastPresentationTimingEXT`.
     *
     * Writes a `scanout_timing` row to the CSV.  The `dt_ms` column holds the time from the
     * original vkQueuePresentKHR call to this scanout stage.  The `interval_ms` column holds the
     * scanout-to-scanout interval (the key jitter signal).  The `detail` column carries the
     * stage flag, nominal refresh duration, actual interval deviation from the refresh period,
     * and whether the timing report was complete.
     */
    internal fun scanoutObserved(
        swapchain: Long,
        presentId: Long,
        stageFlag: Int,
        stageNs: Long,
        refreshDurationNs: Long,
        reportComplete: Boolean
    ) {
        synchronized(lock) {
            if (!enabled) return
            ensureWriterLocked()
            val record = pendingPresents[PresentKey(swapchain, presentId)]
            val windowIndex = record?.windowIndex ?: -1

            val intervalNs = lastScanoutNsByWindow.put(windowIndex, stageNs)?.let { stageNs - it }
            val intervalMs = intervalNs?.let { nsToMs(it) }
            val sincePresentCallMs = record?.let { nsToMs(stageNs - it.callNs) }

            val stageLabel = stageLabel(stageFlag)
            val jitterDetail = if (intervalNs != null && refreshDurationNs > 0L) {
                val jitterNs = intervalNs - refreshDurationNs
                " jitter_ns=$jitterNs"
            } else ""
            val refreshDetail = if (refreshDurationNs > 0L) " refresh_ns=$refreshDurationNs" else ""

            writeLineLocked(
                stageNs,
                "scanout_timing",
                record?.frameId ?: -1L,
                windowIndex,
                record?.imageIndex ?: -1,
                presentId,
                sincePresentCallMs,
                intervalMs,
                "stage=$stageLabel$refreshDetail$jitterDetail report_complete=$reportComplete"
            )
            flushIfNeededLocked()
        }
    }

    private fun stageLabel(stageFlag: Int): String = when (stageFlag) {
        0x1 -> "QUEUE_OPERATIONS_END"
        0x2 -> "REQUEST_DEQUEUED"
        0x4 -> "IMAGE_FIRST_PIXEL_OUT"
        0x8 -> "IMAGE_FIRST_PIXEL_VISIBLE"
        else -> "stage_0x${stageFlag.toString(16)}"
    }

    private fun ensureWriterLocked() {
        if (writer != null) return
        if (path == null) path = defaultPath()
        writer = openWriter(path!!, append = true)
        if (runCatching { Files.size(path!!) }.getOrDefault(0L) == 0L) {
            writeHeaderLocked()
        }
    }

    private fun openWriter(path: Path, append: Boolean): BufferedWriter {
        path.parent?.let { Files.createDirectories(it) }
        val options = if (append) {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)
        } else {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        }
        lprintln("info,perf", "frame presentation log: ${path.toAbsolutePath().normalize()}")
        return Files.newBufferedWriter(path, *options)
    }

    private fun writeHeaderLocked() {
        writer?.write("mono_ns,relative_ns,event,frame,window,image,present_id,dt_ms,interval_ms,detail\n")
    }

    private fun writeLineLocked(
        monoNs: Long,
        event: String,
        frameId: Long,
        windowIndex: Int,
        imageIndex: Int,
        presentId: Long?,
        dtMs: Double?,
        intervalMs: Double?,
        detail: String
    ) {
        writer?.apply {
            write(monoNs.toString())
            write(",")
            write((monoNs - originNs).toString())
            write(",")
            write(csv(event))
            write(",")
            write(if (frameId >= 0L) frameId.toString() else "")
            write(",")
            write(if (windowIndex >= 0) windowIndex.toString() else "")
            write(",")
            write(if (imageIndex >= 0) imageIndex.toString() else "")
            write(",")
            write(presentId?.toString() ?: "")
            write(",")
            write(dtMs?.let(::formatMs) ?: "")
            write(",")
            write(intervalMs?.let(::formatMs) ?: "")
            write(",")
            write(csv(detail))
            write('\n'.code)
        }
        linesSinceFlush++
    }

    private fun flushIfNeededLocked() {
        if (linesSinceFlush >= Options.framePresentationLogFlushEvery) flushLocked()
    }

    private fun flushLocked() {
        writer?.flush()
        linesSinceFlush = 0
    }

    private fun closeLocked() {
        runCatching {
            writer?.flush()
            writer?.close()
        }
        writer = null
    }

    private fun defaultPath(): Path {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return Path.of(".field-frame-logs", "frame-presents-$stamp.csv")
    }

    private fun csv(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun nsToMs(ns: Long): Double = ns.toDouble() / 1_000_000.0

    private fun formatMs(ms: Double): String = String.format(Locale.US, "%.6f", ms)

    private fun vkResultName(result: Int): String = when (result) {
        VK10.VK_SUCCESS -> "VK_SUCCESS"
        KHRSwapchain.VK_SUBOPTIMAL_KHR -> "VK_SUBOPTIMAL_KHR"
        KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR -> "VK_ERROR_OUT_OF_DATE_KHR"
        VK10.VK_TIMEOUT -> "VK_TIMEOUT"
        else -> result.toString()
    }
}
