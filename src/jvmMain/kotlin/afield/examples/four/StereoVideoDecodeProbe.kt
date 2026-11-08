package afield.examples.four

import afield.graphics.texture.video.VideoDecoder

/** Optional local-file throughput probe: the actual P010 conversion/copy path, without a Vulkan device. */
object StereoVideoDecodeProbe {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Pass a local video path" }
        for ((stride, skip) in listOf(1 to false, 2 to false, 2 to true)) {
            VideoDecoder(args[0]).use { decoder ->
                check(decoder.open())
                decoder.decodeNextFrame()?.close() // initialize FFmpeg workers and plane pool
                val start = System.nanoTime()
                var count = 0
                var mediaSeconds = 0.0
                while (mediaSeconds < 12.0) {
                    val frame = decoder.decodeNextFrame(stride, skip) ?: break
                    mediaSeconds = frame.timestamp
                    count++
                    frame.close()
                }
                val seconds = (System.nanoTime() - start) / 1e9
                println("Video decode: stride=$stride nonref=$skip selected=$count media=${mediaSeconds}s wall=${seconds}s selectedFPS=${count / seconds} sustainableSpeed=${mediaSeconds / seconds}x")
            }
        }
    }
}
