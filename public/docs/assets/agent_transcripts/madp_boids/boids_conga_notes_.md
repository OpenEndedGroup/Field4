# Conga

No alignment, no cohesion: one hand-written rule. Each dancer finds the closest boid ahead of it
and aims just behind it, so chains form, merge and chase their own tails. The rule is
`custom(46, 1.6)` in the code and three functions in the compute tab: `customStart` before the
neighbours, `customNeighbour` for each one in reach, and `customForce`, the force to apply.
