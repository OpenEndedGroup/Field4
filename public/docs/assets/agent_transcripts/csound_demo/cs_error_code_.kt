// oscilx is not an opcode (oscili is). Run it: the error is marked on line 4.
csoundOffline("""
instr 1
  a1 oscilx 0.2, 440
  outs a1, a1
endin
""", "i1 0 1")
