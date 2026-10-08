import afield.timecode.*

// A locate, then two seconds of running MTC at 25 fps, decoded as it would be on the far end of a cable.
val decoder = MtcDecoder()
val clock = TimecodeClock(TimecodeRate.FPS_25).followMtc(decoder)

var tc = Timecode.parse("09:59:58:00", TimecodeRate.FPS_25)
decoder.accept(Mtc.fullFrameMessage(tc))
val afterLocate = clock.now()
repeat(25) {                      // 25 cycles of 8 quarter frames = 50 frames
    Mtc.quarterFrameMessages(tc).forEach { decoder.accept(it) }
    tc += 2
}
markdown("main", "full frame: `${Mtc.fullFrame(Timecode.parse("09:59:58:00", TimecodeRate.FPS_25)).joinToString(" ") { "%02X".format(it) }}`  \n" +
    "clock after the locate: `$afterLocate`  \nclock after 50 frames of quarter frames: `${clock.now()}` (locked: ${clock.locked})")
