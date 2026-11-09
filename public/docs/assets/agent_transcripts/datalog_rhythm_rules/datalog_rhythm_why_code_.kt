val r = datalog(
    """
    event(kick, 36, 100, T)  :- euc(T, 4, 16, 0).
    event(snare, 38, 90, T)  :- within(T, 16, 64), every(T, 8, 4).
    """,
).rhythm()
println(r.why("event(kick, 36, 100, 20)")?.render() ?: "no kick at 20")
println(
    "snare at step 4: " +
        (r.why("event(snare, 38, 90, 4)")?.render() ?: "did not play — within(T, 16, 64) starts at bar 1"),
)
