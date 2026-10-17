import afield.io.timecode.*

// Follow LTC delivered in audio-sized blocks, and read the clock between blocks, as a draw loop would.
val clock = TimecodeClock(TimecodeRate.FPS_24).followLtc(48000)
val ltc = LtcEncoder.render(Timecode.parse("01:00:00:00", TimecodeRate.FPS_24), 1.0)
val readings = ArrayList<String>()
for (i in ltc.indices step 512) {
    clock.feedLtc(ltc, i, minOf(512, ltc.size - i))
    Thread.sleep(10)              // ~ the real duration of 512 samples
    if (clock.locked && i % (512 * 16) == 0) readings += "${clock.now()} (${clock.source}, speed ${"%.2f".format(clock.speed)})"
}
markdown("main", readings.joinToString("  \n"))
