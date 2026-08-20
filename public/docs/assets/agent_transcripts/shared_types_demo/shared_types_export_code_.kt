// @rootExported publishes the value to the document root, so any box can reach
// it. Before shared types the *value* crossed but its type did not.
@rootExported
val particle = shared.Particle(x = 1.5, y = 2.5, mass = 4.0)

println("exported: " + particle)
