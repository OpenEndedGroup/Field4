/*
The distributions the shader beneath this box draws from. distHere and gpHere make them
includable by this box's shaders and its descendants': `#include "dist/ring"`,
`#include "dist/arc"`, `#include "gp/ridge"`. Edit one and run this box, then re-run the
shader box: its next compile includes the new graph.
*/
import afield.math.distribution.*

// a ring: radius about 1, any angle; drawn jointly, so x and y share the same radius and angle
val radius = normal(1, 0.08)
val angle = uniform(0, 2 * Math.PI)
val ring = [radius * angle.cos(), radius * angle.sin()]
distHere("ring", ring)

// the same ring, given x + y > 0.5: a posterior the shader draws by weighted reservoir sampling
val arc = ring.given(ring[0] + ring[1] gt 0.5)
distHere("arc", arc, posteriorPool = 16)

// a Gaussian process through five readings: each `curve` number is one whole random function
val xs = doubleArrayOf(0.5, 2.0, 3.5, 6.0, 7.5)
val ys = doubleArrayOf(0.2, 1.1, 0.4, 0.9, -0.3)
gpHere("ridge", GpShaderModel.of(xs, ys, lengthScale = 1.0, noiseStd = 0.1))

println("P(x + y > 0.5) on the ring = ${P(ring[0] + ring[1] gt 0.5, n = 50_000)}")
