<CsoundSynthesizer>
<CsOptions>
-m0
</CsOptions>
<CsInstruments>
; sr, ksmps, nchnls and 0dbfs come from Field: 48000, 32, 2 and 1.

chn_k "cutoff", 1, 3, 2400, 200, 8000   ; written by Field, read here
chn_k "level",  2                       ; written here, read by Field
chn_S "voice",  3

massign 0, "Key"                        ; MIDI notes, from any channel

instr Pluck                             ; p4 MIDI note, p5 level in dBFS
  icps  =         cpsmidinn(p4)
  asig  pluck     ampdbfs(p5), icps, icps, 0, 1
  kcut  chnget    "cutoff"
  kcut  port      kcut, 0.02
  asig  butterlp  asig, kcut
  aenv  linsegr   1, p3, 1, 0.3, 0
  asig  =         asig * aenv
  chnmix    asig * 0.25, "reverb"
  outs      asig, asig
endin

instr Key
  inote notnum
  prints    "Key %d\n", inote
  icps  cpsmidi
  iamp  ampmidi   0.3
  asig  pluck     iamp, icps, icps, 0, 1
  aenv  madsr     0.005, 0.1, 0.6, 0.4
  chnmix    asig * aenv * 0.25, "reverb"
  outs      asig * aenv, asig * aenv
endin

instr Reverb
  ain     chnget    "reverb"
  aL, aR  reverbsc  ain, ain, 0.85, 10000
  outs      aL, aR
  chnclear  "reverb"
  krms    rms       aL + ain
  chnset    krms, "level"
  outvalue  "level", krms
  Svoice  chnget    "voice"
  outvalue  "said", Svoice
endin
</CsInstruments>
<CsScore>
i "Reverb" 0 z
i "Pluck"  0    0.5  57  -12
i .        +    .    60  .
i .        +    .    64  .
i .        +    1.5  67  -9
; Alt-Enter one of these while the box plays:
i "Pluck"  0    1    69  -9
i "Pluck"  0    2    45  -6
</CsScore>
</CsoundSynthesizer>
