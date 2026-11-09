val song = datalog(
    """
    event(kick, 36, 100, T)  :- euc(T, 4, 16, 0).
    event(snare, 38, 90, T)  :- within(T, 16, 64), every(T, 8, 4).
    event(hh, 42, 45, T)     :- within(T, 32, 64), every(T, 2, 1).
    event(lead, N, 80, T)    :- euc(T, 5, 16, 2), D = (T div 3) mod 5, scale(48, minor_pent, D, 1, N).
    """,
)
val r = song.rhythm()
val text = (0L until 4L).joinToString("\n\n") { "bar $it\n" + r.grid(it) }
println(text)
display("grid", "<pre>$text</pre>")
showPatAudio(r.pattern(), cycles = 4, cps = 0.5)
