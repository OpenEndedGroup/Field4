import afield.data.query.datalog.Datalog

prop<List<Int>>(needsBridge)

val d = Datalog()
d.facts("piece", stencilPieces)
d.facts("glyph", stencilGlyphs)
d.add(
    """
    #datalog
    % the plate is the piece with the largest area; every other piece is an island
    biggest(M) :- M = max A : { piece(_, _, _, A) }.
    plate(P) :- piece(P, _, _, A), biggest(A).
    island(P, X) :- piece(P, X, _, _), !plate(P).

    % an island belongs to the glyph whose advance contains its centre
    falls(G, P) :- island(P, X), glyph(G, _, X0, X1), X > X0, X < X1.
    lost(G, C, N) :- glyph(G, C, _, _), N = count : { falls(G, P) }, N > 0.
    needs(G) :- falls(G, _).
    safe(C) :- glyph(G, C, _, _), !needs(G).
""",
)

val lost = d.query("lost(G, C, N)").rows.sortedBy { it[0] as Long }
needsBridge = lost.map {
    (it[0] as Long).toInt()
}
val safe = d.query("safe(C)").column("C")
markdown(
    "judged",
    """
    ~~~
islands               ${d.query("island(P, X)").size}
letters that lose one ${lost.joinToString("  ") { "${it[1]}:${it[2]}" }}
letters that are safe ${safe.joinToString("  ")}
    ~~~
""",
)
println("judged: ${needsBridge.size} letters need bridges")
