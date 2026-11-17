val muse = PatMuse(seed = 3)
val free = muse.rightPattern()
muse.lock(8, atCycle = 4)
showPatRollOf(
    "free" to free,
    "locked" to muse.rightPattern(),
    title = "right hand: free, then the last 8 events before cycle 4, locked",
    cycles = 8,
    pitched = true,
)
