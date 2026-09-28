// A Field sawtooth through a Pd lowpass: `input` feeds the patch's [adc~]. Launch it.
val fx = pd("""
#X obj 20 20 adc~;
#X obj 20 60 lop~ 600;
#X obj 20 100 *~ 0.15;
#X obj 20 140 dac~;
#X obj 160 100 env~ 8192;
#X obj 160 140 s level;
#X connect 0 0 1 0;
#X connect 1 0 2 0;
#X connect 2 0 3 0;
#X connect 2 0 3 1;
#X connect 2 0 4 0;
#X connect 4 0 5 0;
""", input = SawOsc(Param(110f)).pan())
var n = 0
fx.receive("level") { m ->
    if (n++ % 10 == 0) display("level", "<div style='padding:8px'>after the lowpass: ${"%.1f".format(m.float ?: 0f)} dB</div>")
}
fx
