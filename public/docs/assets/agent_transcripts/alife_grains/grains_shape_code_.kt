// < and > make the grains that follow shorter or longer, * and / denser or sparser,
// l and r move them left or right. X is rewritten like G but never plays.
val system = lSystem("G", 'G' to "G[lX]-*<G", 'X' to "+>G/X")
for ((i, s) in system.derivations(2).withIndex()) println("gen $i: $s")
val grains = decodeGrains(system, 4)
println(GrainSetStats(grains))
showImg(grainScoreImg(grains, 640, 220), caption = "gen 4: the trunk falls and quickens; each branch climbs and slows")
display("audio", renderGrains(grains).wav())
