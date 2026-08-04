package afield.stdlib.noir.audioai

import afield.four.audio.WavFloatArrayLoader
import afield.stdlib.AudioVisualizer
import afield.stdlib.AudioVisualizerOptions
import afield.stdlib.WindowType
import afield.stdlib.stft
import afield.util.PngEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CompletableFuture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

data class CodecVisualizationData(
    val source: FloatArray,
    val reconstruction: FloatArray,
    val sampleRate: Int,
    val tokens: NpyIntTensor,
    val metrics: ReconstructionMetrics,
    val frameRate: Double,
    val cardinality: Int,
    val codecLabel: String,
    val sourceWav: ByteArray? = null,
    val reconstructionWav: ByteArray? = null,
)

/** Rendering shared by live Field output, tests, and the public executable documentation. */
object CodecVisualization {
    fun load(
        result: CodecReconstructionResult,
        modelInputWav: Path,
        reconstructionWav: Path,
        tokenNpy: Path,
    ): CodecVisualizationData {
        val source = WavFloatArrayLoader.loadWavToFloatArray(modelInputWav.toString())
        val reconstruction = WavFloatArrayLoader.loadWavToFloatArray(reconstructionWav.toString())
        require(source.sampleRate == reconstruction.sampleRate) { "aligned codec views require equal sample rates" }
        val frameRate = result.codec["frame_rate"]?.toString()?.trim('"')?.toDoubleOrNull() ?: 50.0
        val cardinality = result.codec["cardinality"]?.toString()?.trim('"')?.toIntOrNull() ?: 2048
        val model = result.codec["model_name"]?.toString()?.trim('"') ?: "EnCodec"
        return CodecVisualizationData(
            source.samples, reconstruction.samples, source.sampleRate, NpyIntTensor.read(tokenNpy),
            result.metrics, frameRate, cardinality, model,
            Files.readAllBytes(modelInputWav), Files.readAllBytes(reconstructionWav),
        )
    }

    fun renderHtml(data: CodecVisualizationData, width: Int = 1200): String {
        val sourceSpectrum = pngDataUri(renderLogSpectrogram(data.source, data.sampleRate, width, 280))
        val reconstructionSpectrum = pngDataUri(
            renderLogSpectrogram(data.reconstruction, data.sampleRate, width, 280)
        )
        val tokens = pngDataUri(renderTokens(data.tokens, data.cardinality, width, 210))
        val snr = data.metrics.snrDb?.let { "%.2f dB".format(Locale.US, it) } ?: "n/a"
        val convergence = data.metrics.spectralConvergence?.let { "%.4f".format(Locale.US, it) } ?: "n/a"
        val playerOptions = AudioVisualizerOptions(
            width = (width - 10) / 2,
            waveformHeight = 150,
            showPlayhead = true,
        )
        val sourcePlayer = AudioVisualizer.renderSamplesHtml(
            data.source, data.sampleRate, playerOptions, data.sourceWav
        )
        val reconstructionPlayer = AudioVisualizer.renderSamplesHtml(
            data.reconstruction, data.sampleRate, playerOptions, data.reconstructionWav
        )
        val tokenValues = data.tokens.values.joinToString(",")
        return """
            <div class="field-codec-view">
              <style>
                .field-codec-view { color:#d8e2eb; background:#11161d; padding:18px; border-radius:8px;
                  font:13px/1.35 system-ui,sans-serif; max-width:${width}px }
                .field-codec-view h2 { margin:0 0 4px; font-size:20px; font-weight:580 }
                .field-codec-view .meta { color:#91a4b5; margin-bottom:14px }
                .field-codec-view .metrics { display:flex; gap:18px; margin:8px 0 15px; color:#b9c8d4 }
                .field-codec-view section { margin:12px 0 18px }
                .field-codec-view h3 { margin:0 0 6px; font-size:12px; color:#8fd5e7;
                  text-transform:uppercase; letter-spacing:.08em }
                .field-codec-view img { display:block; width:100%; height:auto; background:#080c11 }
                .field-codec-view .pair { display:grid; grid-template-columns:1fr 1fr; gap:10px }
                .field-codec-view .token-map { position:relative }
                .field-codec-view .token-map canvas { position:absolute; inset:0; width:100%; height:100% }
                .field-codec-view .token-readout { color:#91a4b5; min-height:18px; margin-top:5px }
              </style>
              <h2>EnCodec reconstruction microscope</h2>
              <div class="meta">${escapeHtml(data.codecLabel)} · ${data.sampleRate} Hz mono ·
                ${data.tokens.codebooks} codebooks × ${data.tokens.frames} frames ·
                ${"%.2f".format(Locale.US, data.frameRate)} token frames/s</div>
              <div class="metrics"><span>RMSE ${"%.6f".format(Locale.US, data.metrics.rmse)}</span>
                <span>SNR $snr</span><span>spectral convergence $convergence</span></div>
              <div class="pair">
                <section><h3>Model input waveform</h3>$sourcePlayer</section>
                <section><h3>Decoded waveform</h3>$reconstructionPlayer</section>
              </div>
              <div class="pair">
                <section><h3>Model input · log-frequency STFT</h3><img src="$sourceSpectrum"></section>
                <section><h3>Decoded · log-frequency STFT</h3><img src="$reconstructionSpectrum"></section>
              </div>
              <section><h3>Discrete tokens · time → · coarse to fine ↓</h3>
                <div class="token-map" data-codebooks="${data.tokens.codebooks}"
                  data-frames="${data.tokens.frames}" data-values="$tokenValues">
                  <img src="$tokens"><canvas></canvas>
                </div>
                <div class="token-readout">hover to inspect a token</div>
              </section>
              <script>
                (() => {
                  const root=document.currentScript.closest('.field-codec-view');
                  const map=root.querySelector('.token-map'), canvas=map.querySelector('canvas');
                  const values=map.dataset.values.split(',').map(Number), books=+map.dataset.codebooks,
                    frames=+map.dataset.frames, readout=root.querySelector('.token-readout');
                  const draw=(event) => {
                    const r=canvas.getBoundingClientRect(), x=event.clientX-r.left, y=event.clientY-r.top;
                    const frame=Math.min(frames-1,Math.max(0,Math.floor(x/r.width*frames)));
                    const codebook=Math.min(books-1,Math.max(0,Math.floor(y/r.height*books)));
                    canvas.width=Math.max(1,Math.round(r.width)); canvas.height=Math.max(1,Math.round(r.height));
                    const c=canvas.getContext('2d'); c.strokeStyle='rgba(255,255,255,.8)'; c.lineWidth=1;
                    c.beginPath(); c.moveTo(x,0); c.lineTo(x,r.height); c.moveTo(0,y); c.lineTo(r.width,y); c.stroke();
                    readout.textContent='codebook '+codebook+' · frame '+frame+' · token '+values[codebook*frames+frame];
                  };
                  canvas.addEventListener('mousemove',draw);
                  canvas.addEventListener('mouseleave',() => {
                    canvas.getContext('2d').clearRect(0,0,canvas.width,canvas.height);
                    readout.textContent='hover to inspect a token';
                  });
                })();
              </script>
            </div>
        """.trimIndent()
    }

    fun renderWaveform(samples: FloatArray, width: Int, height: Int): ByteArray {
        val pixels = IntArray(width * height) { 0xff080c11.toInt() }
        val middle = height / 2
        for (x in 0 until width) {
            val from = x * samples.size / width
            val end = ((x + 1) * samples.size / width).coerceAtMost(samples.size)
            var low = 0f
            var high = 0f
            for (i in from until end) {
                low = minOf(low, samples[i])
                high = maxOf(high, samples[i])
            }
            val y0 = (middle - high.coerceIn(-1f, 1f) * (height * .46f)).roundToInt()
            val y1 = (middle - low.coerceIn(-1f, 1f) * (height * .46f)).roundToInt()
            for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(height - 1)) {
                pixels[x + y * width] = 0xff5bc5d8.toInt()
            }
        }
        for (x in 0 until width) pixels[x + middle * width] = 0xff33404c.toInt()
        return PngEncoder.encode(pixels, width, height)
    }

    fun renderLogSpectrogram(samples: FloatArray, sampleRate: Int, width: Int, height: Int): ByteArray {
        val fftSize = 1024
        val spec = stft(samples, fftSize, 256, WindowType.HANN, sampleRate.toDouble())
        val magnitudes = spec.magnitudes()
        val peak = spec.maxMagnitude().coerceAtLeast(1e-12)
        val pixels = IntArray(width * height)
        val minFrequency = 35.0
        val maxFrequency = sampleRate / 2.0
        val logRange = ln(maxFrequency / minFrequency)
        for (x in 0 until width) {
            val frame = if (width == 1) 0 else (x.toDouble() / (width - 1) * (spec.numFrames - 1))
                .roundToInt().coerceIn(0, spec.numFrames - 1)
            for (y in 0 until height) {
                val normalized = 1.0 - y.toDouble() / (height - 1).coerceAtLeast(1)
                val frequency = minFrequency * kotlin.math.exp(normalized * logRange)
                val bin = (frequency / sampleRate * fftSize).roundToInt().coerceIn(0, spec.numBins - 1)
                val db = 20.0 * log10((magnitudes[frame][bin] / peak).coerceAtLeast(1e-5))
                pixels[x + y * width] = cyanOrange((db + 90.0) / 90.0)
            }
        }
        return PngEncoder.encode(pixels, width, height)
    }

    fun renderTokens(tensor: NpyIntTensor, cardinality: Int, width: Int, height: Int): ByteArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val codebook = (y * tensor.codebooks / height).coerceAtMost(tensor.codebooks - 1)
            for (x in 0 until width) {
                val frame = (x * tensor.frames / width).coerceAtMost(tensor.frames - 1)
                val token = tensor[codebook, frame]
                pixels[x + y * width] = tokenColor(token, codebook, cardinality)
            }
        }
        for (codebook in 1 until tensor.codebooks) {
            val y = codebook * height / tensor.codebooks
            for (x in 0 until width) pixels[x + y * width] = 0xff111820.toInt()
        }
        return PngEncoder.encode(pixels, width, height)
    }

    private fun tokenColor(token: Long, codebook: Int, cardinality: Int): Int {
        val phase = (token.toDouble() / cardinality.coerceAtLeast(1) * 2.0 * PI) + codebook * 0.73
        val r = (105 + 92 * sin(phase)).roundToInt().coerceIn(0, 255)
        val g = (126 + 88 * sin(phase + 2.1)).roundToInt().coerceIn(0, 255)
        val b = (145 + 92 * cos(phase + .5)).roundToInt().coerceIn(0, 255)
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun cyanOrange(value: Double): Int {
        val x = value.coerceIn(0.0, 1.0)
        val (r, g, b) = if (x < .5) {
            val t = x * 2
            Triple(8 + t * 48, 12 + t * 173, 18 + t * 207)
        } else {
            val t = (x - .5) * 2
            Triple(56 + t * 199, 185 - t * 38, 225 - t * 176)
        }
        return (0xff shl 24) or (r.roundToInt() shl 16) or (g.roundToInt() shl 8) or b.roundToInt()
    }

    private fun pngDataUri(bytes: ByteArray) =
        "data:image/png;base64,${Base64.getEncoder().encodeToString(bytes)}"

    private fun escapeHtml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
}

/** Download the three binary assets off Field's runloop, then render one self-contained output view. */
fun FieldAudioClient.codecVisualizationHtml(
    result: CodecReconstructionResult,
    cacheDirectory: Path,
): CompletableFuture<String> {
    val sourcePath = cacheDirectory.resolve("${result.modelInput.id}.wav")
    val reconstructionPath = cacheDirectory.resolve("${result.reconstructionNative.id}.wav")
    val tokensPath = cacheDirectory.resolve("${result.tokens.id}.npy")
    val source = download(result.modelInput, sourcePath)
    val reconstruction = download(result.reconstructionNative, reconstructionPath)
    val tokens = download(result.tokens, tokensPath)
    return CompletableFuture.allOf(source, reconstruction, tokens).thenApply {
        CodecVisualization.renderHtml(
            CodecVisualization.load(result, sourcePath, reconstructionPath, tokensPath)
        )
    }
}
