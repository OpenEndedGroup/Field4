// Launch it: Kotlin writes the score, one note every quarter second.
val cs = csound("""
instr 1
  aenv expseg 0.001, 0.005, 0.2, p3, 0.001
  a1 oscili aenv, cpsmidinn(p4)
  outs a1, a1
  prints "note %d\n", p4
endin
""")
val notes = listOf(57, 60, 64, 67, 69, 67, 64, 60)
var i = 0
cs.every(0.25) { event('i', 1, 0, 0.4, notes[i++ % notes.size]) }
cs
