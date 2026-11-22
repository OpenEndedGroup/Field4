import afield.media.image.util.PngEncoder
import afield.media.reconstruction.OidnComputeDenoiser
import afield.media.reconstruction.OidnImageSource

// Copy the particle render into an image source and denoise it once, with flat guides.
val image = OidnImageSource(renderer, 640, 360).upload(particleImage)
val denoiser = OidnComputeDenoiser(image)
val denoised = denoiser.denoiseImage()
val noisy = denoiser.read(image.radiance)

// The middle 400 columns of each, noisy on the left and denoised on the right, as sRGB.
fun srgb(x: Float) =
    (255 * (if (x <= 0.0031308f) 12.92f * x else 1.055f * x.pow(1 / 2.4f) - 0.055f)).toInt().coerceIn(0, 255)
val pixels =
    IntArray(800 * 360) { k ->
        val x = k % 800
        val y = k / 800
        val src = if (x < 400) noisy else denoised
        val i = (y * 640 + 120 + x % 400) * 4
        (255 shl 24) or (srgb(src[i]) shl 16) or (srgb(src[i + 1]) shl 8) or srgb(src[i + 2])
    }
display("main", PngEncoder.encode(pixels, 800, 360))
println("denoised ${image.width} x ${image.height} in ${denoiser.lastDenoiseMs.f0} ms")
denoiser.close()
image.close()
