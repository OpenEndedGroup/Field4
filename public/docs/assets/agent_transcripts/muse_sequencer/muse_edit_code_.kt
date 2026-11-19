// A locked loop corrected by hand: the third event becomes a triad on the octave, the last a rest.
val loop = PatMuseChannel(seed = 3).lock(8, atCycle = 4)
val before = loop.pattern()
loop.set(2, degrees = [7, 9, 11], velocity = 0.8)
loop.set(7, degrees = [])
showPatRollOf(
    "as locked" to before,
    "edited" to loop.pattern(),
    title = "editing a locked loop",
    cycles = 4,
    pitched = true,
)
println(loop)
