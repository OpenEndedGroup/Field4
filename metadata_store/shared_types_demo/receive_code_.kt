// _particle arrives from box 3. These lines only COMPILE because the type
// crossed the box boundary: a box-declared class would have landed here as
// Any? and .x would not resolve.
println(_particle)
println("x + y  = " + (_particle.x + _particle.y))
println("momentum = " + _particle.momentum)