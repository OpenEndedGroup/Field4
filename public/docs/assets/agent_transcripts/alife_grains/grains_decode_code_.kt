// G plays a grain and moves on in time, f rests, + and - step the pitch,
// [ and ] save and restore time and pitch, so each bracket is a new voice.
val system = lSystem("G", 'G' to "G[+G-G]fG")
for ((i, s) in system.derivations(2).withIndex()) println("gen $i: $s")
val grains = decodeGrains(system, 3)
println(GrainSetStats(grains))
showImg(grainScoreImg(grains, 640, 220), caption = "time across, pitch up; gold trunk, blue and red branches")
display("audio", renderGrains(grains).wav())
