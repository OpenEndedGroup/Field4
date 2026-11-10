val drums = notation.space("drums", x = 0.0..16.0, y = 0.0..3.0)
val r = datalog(
    """
    event(V, 36, 100, T) :- hit(P, V, _), pos(T, P).
    event(hh, 42, 40, T) :- every(T, 2, 1), pos(T, P), span(hh, A, B, _), P >= A, P < B.
    """,
).rhythm()
    .notationHits(drums, ["kick", "snare", "hh"])
println(r.grid(0))
patSynth(r.pattern(), cps = 0.5)
