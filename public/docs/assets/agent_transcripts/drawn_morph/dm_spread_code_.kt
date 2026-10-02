// How big the in-between cloud is (RMS distance from its centre), for both pairings.
val from = notation.named("from")!!.points.resampleByLength(240, closed = true)
val to = notation.named("to")!!.points.resampleByLength(240, closed = true)
val partner = matchPoints(from, to).reorder(to)
val ts = (0..50).map { it / 50.0 }
showSignals(
    "matched" to ts.map { spreadOf(morphBetween(from, partner, it)) },
    "by list index" to ts.map { spreadOf(morphBetween(from, to, it)) },
    x = ts, title = "how big the cloud is, through the morph", xTitle = "t", yTitle = "spread", channel = "spread",
)
