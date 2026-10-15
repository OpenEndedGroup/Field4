package afield.io.timecode

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TimecodeTest {

    private val df = TimecodeRate.FPS_2997_DF

    @Test
    fun dropFrameSkipsTwoLabelsEachMinuteExceptTenths() {
        assertEquals("00:01:00;02", (Timecode.parse("00:00:59;29") + 1).toString())
        assertEquals("00:10:00;00", (Timecode.parse("00:09:59;29") + 1).toString())
        assertEquals("00:00:59;29", (Timecode.parse("00:01:00;02") - 1).toString())
        assertEquals("01:00:00;00", Timecode(107892, df).toString())
        assertEquals(17982, Timecode.parse("00:10:00;00").frames)
        assertEquals(1800, Timecode.parse("00:01:00;02").frames)
        assertFailsWith<IllegalArgumentException> { Timecode.parse("00:01:00;00") }
        assertEquals(df.framesPerDay - 1, Timecode.parse("23:59:59;29").frames)
        assertEquals("00:00:00;00", (Timecode.parse("23:59:59;29") + 1).toString())
    }

    @Test
    fun everyFrameRoundTripsThroughItsLabel() {
        for (rate in TimecodeRate.entries) {
            for (n in (0 until rate.framesPerDay step 7) + listOf(rate.framesPerDay - 1)) {
                val tc = Timecode(n, rate)
                assertEquals(tc, Timecode.of(tc.hours, tc.minutes, tc.seconds, tc.frame, rate), "rate $rate frame $n")
                assertEquals(tc, Timecode.parse(tc.toString(), rate))
            }
        }
    }

    @Test
    fun secondsAndArithmetic() {
        val t = Timecode.parse("01:00:00:00", TimecodeRate.FPS_25)
        assertEquals(3600.0, t.totalSeconds, 1e-9)
        assertEquals(t, Timecode.fromSeconds(3600.0, TimecodeRate.FPS_25))
        assertEquals(25, (t + 25) - t)
        // An hour of drop-frame labels is (within a frame) an hour of wall time.
        assertEquals(3600.0, Timecode.parse("01:00:00;00").totalSeconds, 1.0 / 29.97 * 4)
    }

    // --- LTC -------------------------------------------------------------------------------

    @Test
    fun ltcBitsRoundTripWithUserBitsAndEvenParity() {
        for (rate in TimecodeRate.entries) {
            val f = LtcFrame(Timecode.of(13, 47, 29, rate.nominalFps - 1, rate), userBits = 0x1234ABCD)
            val bits = f.toBits()
            assertEquals(0, bits.count { it } % 2, "$rate parity")
            assertEquals(f, LtcFrame.fromBits(bits, rate))
        }
    }

    private fun roundTrip(
        start: Timecode, frames: Int, sampleRate: Int = 48000,
        perturb: (FloatArray) -> FloatArray = { it },
    ): List<LtcDecodedFrame> {
        val pcm = LtcEncoder(start.rate, sampleRate).encode(start, frames, userBits = 0xCAFE0042.toInt())
        return LtcDecoder(sampleRate, start.rate).decodeAll(perturb(pcm))
    }

    private fun assertConsecutive(decoded: List<LtcDecodedFrame>, start: Timecode, frames: Int, reverse: Boolean = false, slack: Int = 3) {
        assertTrue(decoded.size >= frames - slack, "decoded ${decoded.size} of $frames from $start")
        for (i in 1 until decoded.size) {
            val step = if (reverse) -1 else 1
            assertEquals(decoded[i - 1].timecode + step, decoded[i].timecode, "frame $i")
            assertTrue(decoded[i].sampleStart > decoded[i - 1].sampleStart)
        }
        assertTrue(decoded.all { it.reverse == reverse && it.frame.userBits == 0xCAFE0042.toInt() })
        val all = (0 until frames).map { start + it }.toSet()
        assertTrue(decoded.all { it.timecode in all })
    }

    @Test
    fun ltcRoundTripsCleanAtEveryRateIncludingDropFrameBoundaries() {
        val starts = listOf(
            Timecode.parse("00:00:59;20"), Timecode.parse("00:09:59;20"), Timecode.parse("23:59:59;20"),
            Timecode.parse("10:00:00:00", TimecodeRate.FPS_24), Timecode.parse("00:59:59:20", TimecodeRate.FPS_25),
            Timecode.parse("01:02:03:04", TimecodeRate.FPS_30),
        )
        for (sr in listOf(44100, 48000, 96000)) for (start in starts) {
            val decoded = roundTrip(start, 40, sr)
            assertConsecutive(decoded, start, 40)
            if (start.rate.dropFrame && start.minutes != 9 && start.hours != 23) {
                assertTrue(decoded.any { it.timecode.toString() == "00:01:00;02" })
                assertTrue(decoded.none { it.timecode.toString().endsWith(":00;00") })
            }
        }
    }

    @Test
    fun ltcSurvivesGainOffsetAndNoise() {
        val rng = Random(7)
        for (rate in TimecodeRate.entries) {
            val start = Timecode.of(0, 0, 58, 0, rate)
            val decoded = roundTrip(start, 60) { pcm ->
                FloatArray(pcm.size) { (pcm[it] * 0.05f + 0.3f + rng.nextGaussian().toFloat() * 0.004f) }
            }
            assertConsecutive(decoded, start, 60)
        }
    }

    @Test
    fun ltcTracksSpeedChangesOfTenPercent() {
        for (rate in TimecodeRate.entries) for (speed in listOf(0.9, 1.1)) {
            val start = Timecode.of(0, 0, 59, 0, rate)
            val decoded = roundTrip(start, 60) { resample(it, speed) }
            assertConsecutive(decoded, start, 60)
            assertEquals(speed, decoded.last().speed, 0.02)
        }
        // A ramp from 0.9x to 1.1x within one stretch.
        val start = Timecode.parse("00:00:50;00")
        assertConsecutive(roundTrip(start, 120) { ramp(it, 0.9, 1.1) }, start, 120)
    }

    @Test
    fun ltcReadsBackwards() {
        for (rate in TimecodeRate.entries) {
            val start = Timecode.of(0, 0, 59, 10, rate)
            val decoded = roundTrip(start, 40) { it.reversedArray() }
            assertConsecutive(decoded, start, 40, reverse = true)
            assertTrue((decoded.first().timecode - start) in 36..39, "first reverse frame ${decoded.first().timecode}")
        }
    }

    @Test
    fun ltcStreamingInSmallBlocksMatchesWholeBuffer() {
        val start = Timecode.parse("00:00:59;00")
        val pcm = LtcEncoder(df).encode(start, 50, 0xCAFE0042.toInt())
        val whole = LtcDecoder(48000, df).decodeAll(pcm)
        val d = LtcDecoder(48000, df)
        val streamed = (pcm.indices step 61).flatMap { d.feed(pcm, it, minOf(61, pcm.size - it)) }
        assertEquals(whole.map { it.timecode to it.sampleStart }, streamed.map { it.timecode to it.sampleStart })
        // Frame start positions are where the encoder put them (80 bits per frame).
        val spf = 48000 / df.fps
        for (f in whole) assertEquals((f.timecode - start) * spf, f.sampleStart, 1.5)
    }

    @Test
    fun wavFileRoundTrip() {
        val start = Timecode.parse("00:00:00:00", TimecodeRate.FPS_25)
        val file = kotlin.io.path.createTempFile("ltc", ".wav").toFile()
        try {
            TimecodeWav.write(file, LtcEncoder.render(start, 2.0, userBits = 0xCAFE0042.toInt()))
            val (pcm, sr) = TimecodeWav.read(file)
            assertEquals(48000, sr)
            assertConsecutive(LtcDecoder(sr, TimecodeRate.FPS_25).decodeAll(pcm), start, 50, slack = 3)
        } finally { file.delete() }
    }

    // --- MTC -------------------------------------------------------------------------------

    @Test
    fun mtcFullFrameRoundTrips() {
        for (rate in TimecodeRate.entries) {
            val tc = Timecode.of(23, 59, 58, rate.nominalFps - 1, rate)
            assertEquals(tc, Mtc.parseFullFrame(Mtc.fullFrame(tc)))
            val d = MtcDecoder()
            d.accept(Mtc.fullFrameMessage(tc))
            assertEquals(tc, d.current)
        }
    }

    @Test
    fun mtcQuarterFramesRoundTripAcrossDropFrameBoundary() {
        for (rate in TimecodeRate.entries) {
            val d = MtcDecoder()
            val seen = ArrayList<Timecode>()
            d.onTimecode { seen += it }
            var tc = Timecode.of(0, 0, 59, 0, rate)
            val start = tc
            repeat(30) {
                for (m in Mtc.quarterFrameMessages(tc)) d.accept(m)
                tc += 2
            }
            assertEquals(30, seen.size, "$rate")
            seen.forEachIndexed { i, t -> assertEquals(start + 2 * i + 2, t) }
            if (rate.dropFrame) assertTrue(seen.any { it.toString() == "00:01:00;02" })
        }
    }

    @Test
    fun mtcDecoderResyncsAfterAJumpInTheMiddleOfACycle() {
        val d = MtcDecoder()
        val tc = Timecode.parse("01:00:00:00", TimecodeRate.FPS_25)
        Mtc.quarterFrames(tc + 100).take(5).forEach { d.quarter(it) }
        Mtc.quarterFrames(tc).forEach { d.quarter(it) }
        assertEquals(tc + 2, d.current)
    }

    @Test
    fun clockFollowsLtcAndFreewheels() {
        var now = 0L
        val clock = TimecodeClock(TimecodeRate.FPS_25) { now }.followLtc(48000)
        val start = Timecode.parse("10:00:00:00", TimecodeRate.FPS_25)
        val pcm = LtcEncoder(TimecodeRate.FPS_25).encode(start, 25)
        for (i in pcm.indices step 480) {
            clock.feedLtc(pcm, i, minOf(480, pcm.size - i))
            now += 10_000_000L
        }
        assertTrue(clock.locked)
        assertEquals((start + 25).totalSeconds, clock.seconds, 0.02)
        now += 2_000_000_000L
        assertTrue(!clock.locked)
    }

    @Test
    fun clockGenerates() {
        var now = 0L
        val clock = TimecodeClock(TimecodeRate.FPS_30) { now }.generate(Timecode.parse("00:00:10:00", TimecodeRate.FPS_30))
        now += 1_500_000_000L
        assertEquals("00:00:11:15", clock.now().toString())
    }

    companion object {
        fun resample(x: FloatArray, speed: Double): FloatArray {
            val n = (x.size / speed).toInt() - 1
            return FloatArray(n) { i ->
                val p = i * speed; val k = p.toInt(); val t = (p - k).toFloat()
                x[k] * (1 - t) + x[minOf(k + 1, x.size - 1)] * t
            }
        }

        fun ramp(x: FloatArray, from: Double, to: Double): FloatArray {
            val out = ArrayList<Float>()
            var p = 0.0
            while (p < x.size - 1) {
                val k = p.toInt(); val t = (p - k).toFloat()
                out += x[k] * (1 - t) + x[k + 1] * t
                p += from + (to - from) * (p / x.size)
            }
            return out.toFloatArray()
        }
    }
}
