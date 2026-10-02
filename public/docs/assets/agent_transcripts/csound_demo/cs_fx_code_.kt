<CsoundSynthesizer>
<CsInstruments>
; The a-rate channel "in" is fed by Field: launch "Field into fx".
chn_a "in",  1
chn_a "wet", 2

instr Space
  ain     chnget    "in"
  aL, aR  reverbsc  ain, ain, 0.9, 8000
  outs      ain * 0.5 + aL * 0.5, ain * 0.5 + aR * 0.5
  chnset    aL, "wet"
endin
</CsInstruments>
<CsScore>
i "Space" 0 z
</CsScore>
</CsoundSynthesizer>
