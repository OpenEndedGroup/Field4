package afield.four.texture.video

import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.ffmpeg.avformat.*
import org.bytedeco.ffmpeg.avcodec.*
import org.bytedeco.ffmpeg.avutil.*
import org.bytedeco.ffmpeg.swscale.*
import org.bytedeco.javacpp.*
import java.nio.ByteBuffer

/**
 * VideoDecoder wraps FFmpeg functionality to decode video frames.
 * Supports P010 format with semi-planar output (separate luma and chroma planes).
 */
class VideoDecoder(private val videoPath: String) {
    private var formatContext: AVFormatContext? = null
    private var codecContext: AVCodecContext? = null
    private var swsContext: SwsContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null
    private var frameP010: AVFrame? = null
    
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
                println("Error: Could not open video file: $videoPath")
                return false
            }
            
            // Retrieve stream information
            if (avformat_find_stream_info(formatContext, null as PointerPointer<*>?) < 0) {
                println("Error: Could not find stream information")
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
                println("Error: Could not find video stream")
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
                println("Error: Unsupported codec")
                return false
            }
            
            // Allocate codec context
            codecContext = avcodec_alloc_context3(codec)
            if (avcodec_parameters_to_context(codecContext, codecPar) < 0) {
                println("Error: Could not copy codec parameters")
                return false
            }
            
            // Open codec
            if (avcodec_open2(codecContext, codec, null as PointerPointer<*>?) < 0) {
                println("Error: Could not open codec")
                return false
            }
            
            // Allocate frame structures
            frame = av_frame_alloc()
            frameP010 = av_frame_alloc()
            packet = av_packet_alloc()
            
            // Allocate P010 frame buffer (semi-planar: Y plane + UV interleaved plane)
            frameP010!!.format(AV_PIX_FMT_P010LE)
            frameP010!!.width(width)
            frameP010!!.height(height)
            av_frame_get_buffer(frameP010, 32)
            
            // Initialize scaler context to convert to P010
            swsContext = sws_getContext(
                width, height, codecContext!!.pix_fmt(),
                width, height, AV_PIX_FMT_P010LE,
                SWS_BILINEAR, null, null, null as DoublePointer?
            )
            
            isOpen = true
            println("Video opened: ${width}x${height}, duration: ${duration}s, fps: $fps")
            return true
            
        } catch (e: Exception) {
            println("Error opening video: ${e.message}")
            e.printStackTrace()
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
                println("Error seeking to timestamp: $timestampSeconds")
                return false
            }
            
            avcodec_flush_buffers(codecContext)
            return true
            
        } catch (e: Exception) {
            println("Error during seek: ${e.message}")
            return false
        }
    }
    
    /**
     * Decode the next frame and return frame data in P010 format.
     * Returns a VideoFrame with separate luma and chroma buffers, or null if no more frames.
     */
    fun decodeNextFrame(): VideoFrame? {
        if (!isOpen) return null
        
        try {
            while (av_read_frame(formatContext, packet) >= 0) {
                if (packet!!.stream_index() == videoStreamIndex) {
                    if (avcodec_send_packet(codecContext, packet) < 0) {
                        av_packet_unref(packet)
                        continue
                    }
                    
                    val ret = avcodec_receive_frame(codecContext, frame)
                    av_packet_unref(packet)
                    
                    if (ret == AVERROR_EAGAIN || ret == AVERROR_EOF) {
                        continue
                    } else if (ret < 0) {
                        return null
                    }
                    
                    // Convert to P010 format
                    sws_scale(
                        swsContext,
                        frame!!.data(), frame!!.linesize(), 0, height,
                        frameP010!!.data(), frameP010!!.linesize()
                    )
                    
                    // Extract luma and chroma planes with validation
                    val lumaLineSize = frameP010!!.linesize(0)
                    val chromaLineSize = frameP010!!.linesize(1)
                    
                    if (lumaLineSize <= 0 || chromaLineSize <= 0) {
                        println("Error: Invalid linesize values: luma=$lumaLineSize, chroma=$chromaLineSize")
                        return null
                    }
                    
                    val lumaSize = lumaLineSize * height
                    val chromaSize = chromaLineSize * (height / 2)
                    
                    val lumaBuffer = frameP010!!.data(0).capacity(lumaSize.toLong()).asByteBuffer()
                    val chromaBuffer = frameP010!!.data(1).capacity(chromaSize.toLong()).asByteBuffer()
                    
                    // Create copies since the frame data will be reused
                    val lumaCopy = ByteBuffer.allocateDirect(lumaSize)
                    val chromaCopy = ByteBuffer.allocateDirect(chromaSize)
                    
                    lumaCopy.put(lumaBuffer)
                    chromaCopy.put(chromaBuffer)
                    
                    lumaCopy.flip()
                    chromaCopy.flip()
                    
                    return VideoFrame(
                        width = width,
                        height = height,
                        lumaData = lumaCopy,
                        chromaData = chromaCopy,
                        lumaStride = frameP010!!.linesize(0),
                        chromaStride = frameP010!!.linesize(1),
                        timestamp = frame!!.best_effort_timestamp() * av_q2d(formatContext!!.streams(videoStreamIndex).time_base())
                    )
                }
                av_packet_unref(packet)
            }
            
            return null
            
        } catch (e: Exception) {
            println("Error decoding frame: ${e.message}")
            return null
        }
    }
    
    /**
     * Close the decoder and free resources.
     */
    fun close() {
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
        
        frameP010?.let { av_frame_free(it) }
        frameP010 = null
        
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

/**
 * Represents a decoded video frame in P010 format with separate luma and chroma planes.
 */
data class VideoFrame(
    val width: Int,
    val height: Int,
    val lumaData: ByteBuffer,
    val chromaData: ByteBuffer,
    val lumaStride: Int,
    val chromaStride: Int,
    val timestamp: Double
)
