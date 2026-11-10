package afield.audio.synth


/**
 * The piano clips on `public/docs/sound.md`, rendered offline for the docs runner's `audio="…"`
 * fences (`renderWav(scene, path)`, see `public/docs_hooks.py` `ensure_audio`).
 *
 * Each scene is the same short phrase, so a reader comparing two players hears only the control
 * that changed.
 */
object PianoDocs {
    private const val PHRASE = "<[a2,e3] [f2,c3]> [e4 a4 c5 e5]"

    private fun phrase(p: Piano, seconds: Double = 6.0) =
        pianoRender(seconds, p) { play(pat(PHRASE), cycles = 4, cps = 0.8, startFrame = 0) }

    /** Every scene by name: the piano it plays through. */
    val scenes: Map<String, () -> PianoRender> = mapOf(
        "piano-board" to { phrase(Piano(sampleRate = 44_100)) },
        "piano-stiff-100" to { phrase(Piano(sampleRate = 44_100, inharmonicity = Param(100f))) },
        "piano-stiff-100-pinned" to { phrase(Piano(sampleRate = 44_100, inharmonicity = Param(100f), pinFundamental = Param(1f))) },
        "piano-stiff-negative" to { phrase(Piano(sampleRate = 44_100, inharmonicity = Param(-100f), pinFundamental = Param(1f))) },
        "piano-close" to {
            phrase(Piano(sampleRate = 44_100, dry = Param(1f), room = Param(0f), micPosition = Param(0.08f), thump = Param(1f), keyNoise = Param(1f), damperNoise = Param(2f)))
        },
        "piano-close-middle" to {
            phrase(Piano(sampleRate = 44_100, dry = Param(1f), room = Param(0f), micPosition = Param(0.5f), thump = Param(1f), keyNoise = Param(1f)))
        },
        "piano-close-moving" to {
            phrase(
                Piano(
                    sampleRate = 44_100, dry = Param(0.8f), room = Param(0.15f),
                    micPosition = Param(0.25f).modulate(SineOsc(freq = Param(0.4f)) * 0.2f),
                    inharmonicity = Param(1f).modulate(SineOsc(freq = Param(180f)) * 40f), pinFundamental = Param(1f), thump = Param(0.6f),
                ),
            )
        },
    )

    /** Offline-renders scene [name] to a stereo 16-bit WAV at [outPath], peak-normalised to 0.89. */
    fun renderWav(name: String, outPath: String) {
        val r = scenes[name]?.invoke() ?: error("no piano scene '$name'; have ${scenes.keys}")
        require(r.left.all { it.isFinite() } && r.peak > 0f) { "scene '$name' rendered silence or non-finite samples" }
        r.writeWav(java.io.File(outPath), 0.89f)
    }
}
