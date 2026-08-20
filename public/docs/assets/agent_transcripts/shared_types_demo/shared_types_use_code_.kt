// Named arguments, copy, destructuring and the data-class toString all work,
// because shared.Particle is a real compiled class with real metadata -- not a
// type name re-spelled as text.
val p = shared.Particle(x = 3.0, y = 4.0, mass = 2.0)
println("toString: " + p)
println("copy: " + p.copy(mass = 10.0))
val (x, y, m) = p
println("destructured: $x $y $m")
println("momentum: " + p.momentum)
