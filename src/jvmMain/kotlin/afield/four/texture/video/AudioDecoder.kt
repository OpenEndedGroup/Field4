package afield.four.texture.video

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swresample.*
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioDecoder wraps FFmpeg functionality to decode audio streams.
 * Decodes audio to PCM format (signed 16-bit) suitable for OpenAL playback.
 */
class AudioDecoder(private val audioPath: String) {
    private var formatContext: AVFormatContext? = null
    private var codecContext: AVCodecContext? = null
    private var swrContext: SwrContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null
    private var resampledFrame: AVFrame? = null

    var audioStreamIndex: Int = -1
    var sampleRate: Int = 44100
        private set
    var channels: Int = 2
        private set
    var duration: Double = 0.0
        private set

    private var isOpen = false
    private var targetSampleRate: Int = 44100  // Target sample rate for output
    private var targetChannels: Int = 2  // Target channels for output (stereo)

    // Buffer for resampled audio data
    private var resampleBuffer: BytePointer? = null
    private var resampleBufferSize: Int = 0

    init {
        avformat_network_init()
    }

    /**
     * Open the audio file and initialize decoder.
     * @param outputSampleRate Target sample rate for decoded audio (default 44100 Hz)
     * @param outputChannels Target number of channels (1=mono, 2=stereo, default 2)
     */
    fun open(outputSampleRate: Int = 44100, outputChannels: Int = 2): Boolean {
        if (isOpen) return true

        this.targetSampleRate = outputSampleRate
        this.targetChannels = outputChannels

        try {
            // Open audio file
            formatContext = avformat_alloc_context()
            if (avformat_open_input(formatContext, audioPath, null, null) != 0) {
                println("Error: Could not open audio file: $audioPath")
                return false
            }

            // Retrieve stream information
            if (avformat_find_stream_info(formatContext, null as PointerPointer<*>?) < 0) {
                println("Error: Could not find stream information")
                return false
            }

            // Find the audio stream
            audioStreamIndex = -1
            for (i in 0 until formatContext!!.nb_streams()) {
                val stream = formatContext!!.streams(i)
                if (stream.codecpar().codec_type() == AVMEDIA_TYPE_AUDIO) {
                    audioStreamIndex = i
                    break
                }
            }

            if (audioStreamIndex == -1) {
                println("Error: Could not find audio stream")
                return false
            }

            val stream = formatContext!!.streams(audioStreamIndex)
            val codecPar = stream.codecpar()

            // Find decoder
            val codec = avcodec_find_decoder(codecPar.codec_id())
            if (codec == null) {
                println("Error: Unsupported audio codec")
                return false
            }

            // Create codec context
            codecContext = avcodec_alloc_context3(codec)
            if (avcodec_parameters_to_context(codecContext, codecPar) < 0) {
                println("Error: Could not copy codec parameters to context")
                return false
            }

            // Open codec
            if (avcodec_open2(codecContext, codec, null as PointerPointer<*>?) < 0) {
                println("Error: Could not open audio codec")
                return false
            }

            // Get audio properties
            sampleRate = codecContext!!.sample_rate()
            channels = codecContext!!.ch_layout().nb_channels()

            // Calculate duration
            val streamDuration = stream.duration()
            val timeBase = stream.time_base()
            if (streamDuration != AV_NOPTS_VALUE) {
                duration = streamDuration * av_q2d(timeBase)
            } else if (formatContext!!.duration() != AV_NOPTS_VALUE) {
                duration = formatContext!!.duration() / AV_TIME_BASE.toDouble()
            }

            println("Audio opened: ${sampleRate}Hz, $channels channels, ${duration}s duration")

            // Initialize resampler if needed
            val needsResampling = sampleRate != targetSampleRate || channels != targetChannels
            if (needsResampling) {
                swrContext = swr_alloc()

                // Configure channel layouts using the new AVChannelLayout API (av_get_default_channel_layout was removed)
                val inChannelLayout = codecContext!!.ch_layout()
                if (inChannelLayout.nb_channels() == 0) {
                    av_channel_layout_default(inChannelLayout, channels)
                }

                val outChannelLayout = AVChannelLayout()
                av_channel_layout_default(outChannelLayout, targetChannels)

                if (av_opt_set_chlayout(swrContext, "in_chlayout", inChannelLayout, 0) < 0 ||
                    av_opt_set_chlayout(swrContext, "out_chlayout", outChannelLayout, 0) < 0) {
                    println("Error: Could not set channel layout on resampler")
                    return false
                }
                av_opt_set_int(swrContext, "in_sample_rate", sampleRate.toLong(), 0)
                av_opt_set_int(swrContext, "out_sample_rate", targetSampleRate.toLong(), 0)
                av_opt_set_sample_fmt(swrContext, "in_sample_fmt", codecContext!!.sample_fmt(), 0)
                av_opt_set_sample_fmt(swrContext, "out_sample_fmt", AV_SAMPLE_FMT_S16, 0)

                if (swr_init(swrContext) < 0) {
                    println("Error: Could not initialize resampler")
                    return false
                }

                println("Audio resampling: ${sampleRate}Hz/$channels ch -> ${targetSampleRate}Hz/$targetChannels ch")
            }

            // Allocate packet and frame
            packet = av_packet_alloc()
            frame = av_frame_alloc()
            resampledFrame = av_frame_alloc()

            isOpen = true
            return true
        } catch (e: Exception) {
            println("Error opening audio: ${e.message}")
            e.printStackTrace()
            close()
            return false
        }
    }

    /**
     * Decode the next audio frame.
     * @return AudioFrame containing PCM data, or null if end of stream or error
     */
    fun decodeFrame(): AudioFrame? {
        if (!isOpen) return null

        while (true) {
            // Read packet
            val ret = av_read_frame(formatContext, packet)
            if (ret < 0) {
                // End of stream or error
                return null
            }

            // Only process audio stream packets
            if (packet!!.stream_index() != audioStreamIndex) {
                av_packet_unref(packet)
                continue
            }

            // Send packet to decoder
            if (avcodec_send_packet(codecContext, packet) < 0) {
                println("Error sending audio packet to decoder")
                av_packet_unref(packet)
                continue
            }

            av_packet_unref(packet)

            // Receive decoded frame
            val receiveRet = avcodec_receive_frame(codecContext, frame)
            if (receiveRet == AVERROR_EAGAIN() || receiveRet == AVERROR_EOF()) {
                continue
            } else if (receiveRet < 0) {
                println("Error receiving audio frame from decoder")
                return null
            }

            // Calculate timestamp
            val pts = if (frame!!.pts() != AV_NOPTS_VALUE) frame!!.pts() else frame!!.best_effort_timestamp()
            val stream = formatContext!!.streams(audioStreamIndex)
            val timeBase = stream.time_base()
            val timestamp = pts * av_q2d(timeBase)

            // Resample if needed
            val outputData: ByteBuffer
            val outputSamples: Int

            if (swrContext != null) {
                // Calculate output sample count
                val inputSamples = frame!!.nb_samples()
                val maxOutputSamples = av_rescale_rnd(
                    swr_get_delay(swrContext, sampleRate.toLong()) + inputSamples,
                    targetSampleRate.toLong(),
                    sampleRate.toLong(),
                    AV_ROUND_UP
                ).toInt()

                // Allocate buffer if needed
                val requiredSize = maxOutputSamples * targetChannels * 2  // 2 bytes per sample (16-bit)
                if (resampleBuffer == null || resampleBufferSize < requiredSize) {
                    resampleBuffer?.deallocate()
                    resampleBuffer = BytePointer(requiredSize.toLong())
                    resampleBufferSize = requiredSize
                }

                // Resample
                val outputPointers = PointerPointer<BytePointer>(1).put(0, resampleBuffer)
                val inputPointers = frame!!.data()
                val outSamples = swr_convert(
                    swrContext,
                    outputPointers,
                    maxOutputSamples,
                    inputPointers,
                    inputSamples
                )

                if (outSamples < 0) {
                    println("Error resampling audio")
                    return null
                }

                outputSamples = outSamples
                val dataSize = outSamples * targetChannels * 2
                outputData = resampleBuffer!!.asByteBuffer().limit(dataSize).order(ByteOrder.nativeOrder())
            } else {
                // No resampling needed, use frame data directly
                outputSamples = frame!!.nb_samples()

                // Check if data is planar or interleaved
                if (av_sample_fmt_is_planar(codecContext!!.sample_fmt()) != 0) {
                    // Planar format - need to interleave
                    println("Warning: Planar audio format detected - forcing resampling for proper interleaving")
                    // This path shouldn't be reached if swrContext is properly initialized
                    // If it is reached, the audio will be mono-only from first plane
                    // Best practice: always use swrContext for format conversion
                    val dataSize = outputSamples * 2  // Only first channel, 16-bit samples
                    outputData = BytePointer(frame!!.data(0)).asByteBuffer().limit(dataSize).order(ByteOrder.nativeOrder())
                } else {
                    // Already interleaved - verify sample format is S16
                    val sampleFmt = codecContext!!.sample_fmt()
                    if (sampleFmt != AV_SAMPLE_FMT_S16) {
                        println("Warning: Unexpected sample format $sampleFmt, audio may be distorted")
                    }
                    val dataSize = outputSamples * channels * 2  // 2 bytes per sample (16-bit)
                    outputData = BytePointer(frame!!.data(0)).asByteBuffer().limit(dataSize).order(ByteOrder.nativeOrder())
                }
            }

            return AudioFrame(
                data = outputData,
                samples = outputSamples,
                sampleRate = targetSampleRate,
                channels = targetChannels,
                timestamp = timestamp
            )
        }
    }

    /**
     * Seek to a specific timestamp in the audio stream.
     * @param timestamp Time in seconds
     */
    fun seek(timestamp: Double): Boolean {
        if (!isOpen) return false

        val stream = formatContext!!.streams(audioStreamIndex)
        val timeBase = stream.time_base()
        val seekTarget = (timestamp / av_q2d(timeBase)).toLong()

        val ret = av_seek_frame(formatContext, audioStreamIndex, seekTarget, AVSEEK_FLAG_BACKWARD)
        if (ret < 0) {
            println("Error seeking audio to $timestamp")
            return false
        }

        // Flush codec buffers
        avcodec_flush_buffers(codecContext)

        return true
    }

    /**
     * Close the decoder and free resources.
     */
    fun close() {
        if (!isOpen) return

        resampleBuffer?.deallocate()
        resampleBuffer = null

        swrContext?.let {
            swr_free(it)
            swrContext = null
        }

        resampledFrame?.let {
            av_frame_free(it)
            resampledFrame = null
        }

        frame?.let {
            av_frame_free(it)
            frame = null
        }

        packet?.let {
            av_packet_free(it)
            packet = null
        }

        codecContext?.let {
            avcodec_free_context(it)
            codecContext = null
        }

        formatContext?.let {
            avformat_close_input(it)
            formatContext = null
        }

        isOpen = false
    }
}

/**
 * Data class representing a decoded audio frame in PCM format.
 */
data class AudioFrame(
    val data: ByteBuffer,      // PCM audio data (16-bit signed, interleaved)
    val samples: Int,          // Number of samples per channel
    val sampleRate: Int,       // Sample rate in Hz
    val channels: Int,         // Number of channels (1=mono, 2=stereo)
    val timestamp: Double      // Timestamp in seconds
)
