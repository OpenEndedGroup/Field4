package afield.graphics.scene.video

import afield.math.linalg.Vector4f

/** FFmpeg AVCOL_SPC tags: BT.709, BT.601 (both 525/625), BT.2020 non-constant luminance. */
internal object StereoVideoColorimetry {
    fun parameters(space: Int, range: Int): Vector4f {
        val (kr, kb) =
            when (space) {
                5,
                6 -> .299f to .114f
                9 -> .2627f to .0593f
                else -> .2126f to .0722f // unspecified SDR defaults to BT.709
            }
        return Vector4f(kr, kb, if (range == 2) 1f else 0f, 0f)
    }
}
