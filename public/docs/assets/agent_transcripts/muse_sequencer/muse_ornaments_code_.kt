val muse = PatMuse(seed = 3).lock(8, atCycle = 4)
val loop = muse.rightLoop!!
val plain = loop.pattern()
loop.improvise = 0.4
showPatRollOf(
    "locked" to plain,
    "improvise 0.4" to loop.pattern(),
    title = "the same loop, plain and improvising",
    cycles = 8,
    pitched = true,
)
