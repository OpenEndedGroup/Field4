# Lorenz attractor

A continuous chaotic flow, not a map. Every particle starts near the others and is carried along
the Lorenz equations (`sigma = 10`, `rho = 28`, `beta = 8/3`), one Euler step of `dt = 0.006`
at a time. The trajectories never settle and never repeat, but every one of them traces the same
two lobes. `project` turns the 3D path into a view, and `color` shades it by height.

More particles mean more trajectories exploring the same attractor. This box uses 12 000; at
60 000 the trajectories merge into a smooth sheet and the spirals are lost.
