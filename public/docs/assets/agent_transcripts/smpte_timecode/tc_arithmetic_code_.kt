import afield.timecode.*

val t = Timecode.parse("00:00:59;29")
markdown("main", "`$t` + 1 frame = `${t + 1}`  \n`${Timecode.parse("00:09:59;29")}` + 1 frame = `${Timecode.parse("00:09:59;29") + 1}`  \nan hour of drop-frame labels is ${"%.3f".format(Timecode.parse("01:00:00;00").totalSeconds)} s of wall time")
