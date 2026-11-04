import afield.data.query.datalog.Datalog

prop<Datalog>(islandModel)

val rules =
    """
    #datalog
    % how many target points lie within 12 units of each generated one
    support(A, N) :- pt(A, X, Y), N = count : { tgt(_, U, V), hypot(X - U, Y - V) < 12 }.
    stray(A) :- support(A, 0).

    % join kept points closer than 18, and label each piece by its smallest id
    near(A, B) :- kept(A), kept(B), pt(A, X, Y), pt(B, U, V), A != B, hypot(X - U, Y - V) < 18.
    reach(A, A) :- kept(A).
    reach(A, C) :- reach(A, B), near(B, C).
    island(A, L) :- kept(A), L = min B : { reach(A, B) }.
    size(L, N) :- island(_, L), N = count : { island(B, L) }.
"""

fun model(keep: String): Datalog {
    val d = Datalog()
    d.facts("pt", moonSamples.mapIndexed { i, p -> [i, p.x.toDouble(), p.y.toDouble()] })
    d.facts("tgt", moonTarget.mapIndexed { i, p -> [i, p.x.toDouble(), p.y.toDouble()] })
    d.add(rules)
    d.add(keep)
    return d
}
fun sizes(d: Datalog) = d.query("size(L, N)").rows.map { it[1] as Long }.sortedDescending()

val everything = model("kept(A) :- pt(A, _, _).")
islandModel = model("kept(A) :- pt(A, _, _), !stray(A).")

val strays = islandModel.query("stray(A)").size
markdown(
    "islands",
    """
    ~~~
strays (no target point within 12)   $strays of ${moonSamples.size}
every point                          ${sizes(everything).size} islands, sizes ${sizes(everything)}
strays left out                      ${sizes(islandModel).size} islands, sizes ${sizes(islandModel)}
    ~~~
""",
)
println("islands counted: $strays strays")
