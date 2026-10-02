// Launch it, then move the sliders. chn_k declares each channel's default and range.
val drone = csound("""
chn_k "pitch", 1, 2, 110, 55, 440
chn_k "cutoff", 1, 2, 1200, 200, 6000
instr 1
  kf chnget "pitch"
  kc chnget "cutoff"
  kf port kf, 0.05
  kc port kc, 0.05
  a1 vco2 0.12, kf
  a1 moogladder a1, kc, 0.3
  outs a1, a1
  printks "level %.3f\n", 1, rms(a1)
endin
""", "i1 0 z")
csoundControls(drone)
drone
