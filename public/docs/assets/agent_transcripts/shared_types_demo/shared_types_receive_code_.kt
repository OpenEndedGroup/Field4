// _particle arrives from box 3. These lines only COMPILE because the type crossed
// the box boundary: a box-declared class lands here as Any?, and .x does not
// resolve on Any?.
println("received: " + _particle)
println("x + y = " + (_particle.x + _particle.y))
println("momentum = " + _particle.momentum)
