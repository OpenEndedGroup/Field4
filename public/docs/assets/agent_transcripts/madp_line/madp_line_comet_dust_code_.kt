// Comet Dust: great with quick slashes and zig-zags. atomize() breaks the stroke into segments
// that wiggle() shakes apart, dots() scatters sparks, and the long trail turns them to dust.
eachStroke(trail = 0.92) {
    draw { t ->
        val base = resample(points, 42)
        val surge = 5 + tri01(t * 0.55 + heldNoise(2.4, t, 5.0) * 0.3) * 4
        val segments = wiggle(atomize(base), surge, 0.02, t * 0.8)
        segments.overshoot = 4.0
        segments.nodes.forEachIndexed { i, node ->
            if (node !is FLine.MoveTo) node.color = cssHsla(40 + i / 2 * 7 + t * 40, 0.95, 0.62, 0.42)
        }

        val sparks = dots(wiggle(scale(base, 1.12), 10.0, 0.03, -t * 0.7), 1.5)
        sparks.nodes.forEachIndexed { i, node -> node.color = cssHsla(200 + i * 11 + t * 65, 0.95, 0.72, 0.88) }

        val halo = dots(scale(base, 1.24 + tri01(t * 0.35 + heldNoise(4.1, t, 4.0) * 0.5) * 0.18), 0.8)
        halo.color = rgba(255, 255, 255, 0.22)
        [segments, sparks, halo]
    }
}
