val drums = notation.space("drums", x = 0.0..16.0, y = 0.0..3.0)
val r =
    datalog("event(V, 36, 100, T) :- hit(P, V, _), pos(T, P).").rhythm().notationHits(drums, ["kick", "snare", "hh"])
println(r.grid(0))
println(r.why("event(snare, 36, 100, 4)")?.render() ?: "no snare at step 4 — nothing drawn there")
