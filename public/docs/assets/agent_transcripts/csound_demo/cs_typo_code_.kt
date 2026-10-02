<CsoundSynthesizer>
<CsInstruments>
instr Beep
  asig  oscilx  0.2, 440       ; there is no oscilx: Csound's error lands on this line
  outs    asig, asig
endin
</CsInstruments>
<CsScore>
i "Beep" 0 1
</CsScore>
</CsoundSynthesizer>
