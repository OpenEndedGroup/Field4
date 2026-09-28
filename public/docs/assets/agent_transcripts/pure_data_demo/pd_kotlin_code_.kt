// A Pd patch as a Kotlin value. Launch it; move the Pitch slider.
val patch = pd("""
#N canvas 0 0 520 300 12;
#X obj 20 20 hsl 128 15 55 440 0 0 empty pitch Pitch -2 -8 0 10 #fcfcfc #000000 #000000 0 1;
#X msg 20 50 \$1 40;
#X obj 20 80 line~;
#X obj 20 110 tabosc4~ wave;
#X obj 20 140 *~ 0.1;
#X obj 20 180 dac~;
#X obj 220 20 table wave 2051;
#X obj 220 140 env~ 8192;
#X obj 220 180 s level;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 2 0 3 0;
#X connect 3 0 4 0;
#X connect 4 0 5 0;
#X connect 4 0 5 1;
#X connect 4 0 7 0;
#X connect 7 0 8 0;
""")

// tabosc4~ wants 2^n + 3 points: one guard point before, two after.
val partials = listOf(1.0, 0.5, 0.33, 0.25, 0.2, 0.16)
val wave = FloatArray(2051) { i ->
    val phase = 2 * PI * (i - 1) / 2048
    (partials.withIndex().sumOf { (k, a) -> a * sin((k + 1) * phase) } * 0.4).toFloat()
}
patch.writeArray("wave", wave)
println("wrote ${patch.arraySize("wave")} points into [table wave]")

pdControls(patch)          // one slider, from the patch's own hsl
patch.send("pitch", 110)

var n = 0
patch.receive("level") { m ->
    // env~ reports in dB, 100 = full scale; this lambda runs on Field's main thread.
    if (n++ % 10 == 0) display("level", "<div style='padding:8px'>[s level] ${"%.1f".format(m.float ?: 0f)} dB</div>")
}
patch
