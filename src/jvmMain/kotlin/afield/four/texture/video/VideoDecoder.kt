package afield.four.texture.video

import afield.logger.lprintln
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.PointerPointer
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * VideoDecoder wraps FFmpeg functionality to decode video frames.
 * Converts decoded frames either to P010 for ordinary video display or to tightly-packed RGBA8 for
 * data-bearing RGB movies. The latter matters for atlases whose R/G/B channels are values rather
 * than colour: routing those through 4:2:0 YUV would irreversibly subsample the channels.
 */
class VideoDecoder(
    private val videoPath: String,
    val outputFormat: VideoDecodeFormat = VideoDecodeFormat.P010
) : AutoCloseable {
    private var formatContext: AVFormatContext? = null
    private var codecContext: AVCodecContext? = null
    private var swsContext: SwsContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null
    private var frameOutput: AVFrame? = null

    var videoStreamIndex: Int = -1
    var width: Int = 0
        private set
    var height: Int = 0
        private set
    var duration: Double = 0.0
        private set
    var fps: Double = 0.0
        private set

    private var isOpen = false
    private var inputEnded = false
    private var drainSent = false

    init {
        // Note: av_register_all() is deprecated in FFmpeg 4.0+, registration is now implicit
        // Initialize network protocols if needed
        avformat_network_init()
    }

    /**
     * Open the video file and initialize decoder.
     */
    fun open(): Boolean {
        if (isOpen) return true

        try {
            // Open video file
            formatContext = avformat_alloc_context()
            if (avformat_open_input(formatContext, videoPath, null, null) != 0) {
                lprintln("error,video", "Could not open video file: $videoPath")
                return false
            }

            // Retrieve stream information
            if (avformat_find_stream_info(formatContext, null as PointerPointer<*>?) < 0) {
                lprintln("error,video", "Could not find stream information")
                return false
            }

            // Find the video stream
            videoStreamIndex = -1
            for (i in 0 until formatContext!!.nb_streams()) {
                val stream = formatContext!!.streams(i)
                if (stream.codecpar().codec_type() == AVMEDIA_TYPE_VIDEO) {
                    videoStreamIndex = i
                    break
                }
            }

            if (videoStreamIndex == -1) {
                lprintln("error,video", "Could not find video stream")
                return false
            }

            val videoStream = formatContext!!.streams(videoStreamIndex)
            val codecPar = videoStream.codecpar()

            // Get video properties
            width = codecPar.width()
            height = codecPar.height()

            // Calculate duration and FPS
            val timeBase = videoStream.time_base()
            duration = videoStream.duration() * av_q2d(timeBase)

            val frameRate = videoStream.avg_frame_rate()
            fps = av_q2d(frameRate)

            // Find decoder
            val codec = avcodec_find_decoder(codecPar.codec_id())
            if (codec == null) {
                lprintln("error,video", "Unsupported codec")
                return false
            }

            // Allocate codec context
            codecContext = avcodec_alloc_context3(codec)
            if (avcodec_parameters_to_context(codecContext, codecPar) < 0) {
                lprintln("error,video", "Could not copy codec parameters")
                return false
            }

            // libavcodec's programmatic default is effectively single-threaded for this path, while
            // the ffmpeg CLI enables frame/slice workers automatically. A 6912x1440 lossless RGB
            // atlas is otherwise a half-second decode on an Apple desktop CPU.
            codecContext!!.thread_count(min(8, Runtime.getRuntime().availableProcessors()))
            codecContext!!.thread_type(AVCodecContext.FF_THREAD_FRAME or AVCodecContext.FF_THREAD_SLICE)

            // Open codec
            if (avcodec_open2(codecContext, codec, null as PointerPointer<*>?) < 0) {
                lprintln("error,video", "Could not open codec")
                return false
            }

            // Allocate frame structures
            frame = av_frame_alloc()
            frameOutput = av_frame_alloc()
            packet = av_packet_alloc()

            val avOutputFormat = when (outputFormat) {
                VideoDecodeFormat.P010 -> AV_PIX_FMT_P010LE
                VideoDecodeFormat.RGBA8 -> AV_PIX_FMT_RGBA
            }
            frameOutput!!.format(avOutputFormat)
            frameOutput!!.width(width)
            frameOutput!!.height(height)
            av_frame_get_buffer(frameOutput, 32)

            // swscale also handles native GBR/RGB codecs; RGBA8 deliberately avoids a YUV roundtrip.
            swsContext = sws_getContext(
                width, height, codecContext!!.pix_fmt(),
                width, height, avOutputFormat,
                SWS_BILINEAR, null, null, null as DoublePointer?
            )

            isOpen = true
            inputEnded = false
            drainSent = false
            lprintln("info,video", "Video opened: ${width}x${height}, duration: ${duration}s, fps: $fps")
            return true

        } catch (e: Exception) {
            lprintln("error,video", "Error opening video: ${e.message}")
            lprintln("error,video", e.stackTraceToString())
            cleanup()
            return false
        }
    }

    /**
     * Seek to a specific timestamp in seconds.
     */
    fun seek(timestampSeconds: Double): Boolean {
        if (!isOpen) return false

        try {
            val videoStream = formatContext!!.streams(videoStreamIndex)
            val timeBase = videoStream.time_base()
            val timestamp = (timestampSeconds / av_q2d(timeBase)).toLong()

            if (av_seek_frame(formatContext, videoStreamIndex, timestamp, AVSEEK_FLAG_BACKWARD) < 0) {
                lprintln("error,video", "Error seeking to timestamp: $timestampSeconds")
                return false
            }

            avcodec_flush_buffers(codecContext)
            inputEnded = false
            drainSent = false
            return true

        } catch (e: Exception) {
            lprintln("error,video", "Error during seek: ${e.message}")
            return false
        }
    }

    /**
     * Decode the next frame in [outputFormat]. Plane buffers are tightly packed even when FFmpeg's
     * working frame has aligned row padding, so Vulkan uploads never need to know the source stride.
     */
    fun decodeNextFrame(): VideoFrame? {
        if (!isOpen) return null

        try {
            if (!receiveNextFrame()) return null

            // Convert to the requested upload format.
            sws_scale(
                swsContext,
                frame!!.data(), frame!!.linesize(), 0, height,
                frameOutput!!.data(), frameOutput!!.linesize()
            )

            val planes = when (outputFormat) {
                VideoDecodeFormat.P010 -> {
                    val lumaStride = frameOutput!!.linesize(0)
                    val chromaStride = frameOutput!!.linesize(1)
                    if (lumaStride <= 0 || chromaStride <= 0) {
                        lprintln(
                            "error,video",
                            "Invalid P010 linesizes: luma=$lumaStride, chroma=$chromaStride"
                        )
                        return null
                    }
                    val rowBytes = width * 2
                    DecodedPlanes(
                        copyPlaneTight(frameOutput!!.data(0), lumaStride, rowBytes, height),
                        copyPlaneTight(frameOutput!!.data(1), chromaStride, rowBytes, height / 2),
                        rowBytes,
                        rowBytes
                    )
                }
                VideoDecodeFormat.RGBA8 -> {
                    val rgbaStride = frameOutput!!.linesize(0)
                    if (rgbaStride <= 0) {
                        lprintln("error,video", "Invalid RGBA linesize: $rgbaStride")
                        return null
                    }
                    val rowBytes = width * 4
                    DecodedPlanes(
                        copyPlaneTight(frameOutput!!.data(0), rgbaStride, rowBytes, height),
                        EMPTY_DIRECT_BUFFER,
                        rowBytes,
                        0
                    )
                }
            }

            return VideoFrame(
                width = width,
                height = height,
                format = outputFormat,
                plane0 = planes.plane0,
                plane1 = planes.plane1,
                plane0Stride = planes.stride0,
                plane1Stride = planes.stride1,
                timestamp = frame!!.best_effort_timestamp() * av_q2d(formatContext!!.streams(videoStreamIndex).time_base())
            )
        } catch (e: Exception) {
            lprintln("error,video", "Error decoding frame: ${e.message}")
            return null
        }
    }

    /** Drain receive before sending another packet, including delayed/B frames after demux EOF. */
    private fun receiveNextFrame(): Boolean {
        while (true) {
            val received = avcodec_receive_frame(codecContext, frame)
            if (received >= 0) return true
            if (received == AVERROR_EOF) return false
            check(received == AVERROR_EAGAIN()) { "avcodec_receive_frame failed: $received" }

            if (inputEnded) {
                if (drainSent) return false
                val sent = avcodec_send_packet(codecContext, null as AVPacket?)
                check(sent >= 0 || sent == AVERROR_EOF) { "video drain failed: $sent" }
                drainSent = true
                continue
            }

            while (true) {
                if (av_read_frame(formatContext, packet) < 0) {
                    inputEnded = true
                    break
                }
                if (packet!!.stream_index() != videoStreamIndex) {
                    av_packet_unref(packet)
                    continue
                }
                val sent = avcodec_send_packet(codecContext, packet)
                av_packet_unref(packet)
                check(sent >= 0) { "avcodec_send_packet failed: $sent" }
                break
            }
        }
    }

    /**
     * Close the decoder and free resources.
     */
    override fun close() {
        if (!isOpen) return
        cleanup()
    }

    private fun cleanup() {
        // Free scaler context
        swsContext?.let { sws_freeContext(it) }
        swsContext = null

        // Free frame structures (bytedeco handles the double-pointer semantics)
        frame?.let { av_frame_free(it) }
        frame = null

        frameOutput?.let { av_frame_free(it) }
        frameOutput = null

        // Free packet structure
        packet?.let { av_packet_free(it) }
        packet = null

        // Free codec context (bytedeco handles the double-pointer semantics)
        codecContext?.let {
            avcodec_free_context(it)
        }
        codecContext = null

        // Close format context (automatically frees the context)
        formatContext?.let {
            avformat_close_input(it)
        }
        formatContext = null

        isOpen = false
    }
}

private val EMPTY_DIRECT_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).asReadOnlyBuffer()

private data class DecodedPlanes(
    val plane0: ByteBuffer,
    val plane1: ByteBuffer,
    val stride0: Int,
    val stride1: Int
)

private fun copyPlaneTight(
    pointer: org.bytedeco.javacpp.BytePointer,
    sourceStride: Int,
    rowBytes: Int,
    rows: Int
): ByteBuffer {
    require(sourceStride >= rowBytes) { "source stride $sourceStride is smaller than row width $rowBytes" }
    val source = pointer.capacity(sourceStride.toLong() * rows).asByteBuffer()
    val copy = ByteBuffer.allocateDirect(rowBytes * rows)
    repeat(rows) { row ->
        val line = source.duplicate()
        line.position(row * sourceStride)
        line.limit(row * sourceStride + rowBytes)
        copy.put(line)
    }
    return copy.flip()
}

enum class VideoDecodeFormat { P010, RGBA8 }

/**
 * A decoded upload frame. P010 uses both planes; RGBA8 uses [plane0] and leaves [plane1] empty.
 */
data class VideoFrame(
    val width: Int,
    val height: Int,
    val format: VideoDecodeFormat,
    val plane0: ByteBuffer,
    val plane1: ByteBuffer,
    val plane0Stride: Int,
    val plane1Stride: Int,
    val timestamp: Double
) {
    val lumaData: ByteBuffer get() = plane0
    val chromaData: ByteBuffer get() = plane1
    val lumaStride: Int get() = plane0Stride
    val chromaStride: Int get() = plane1Stride
}
