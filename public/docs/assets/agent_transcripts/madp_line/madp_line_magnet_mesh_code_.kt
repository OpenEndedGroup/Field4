// Magnet Mesh: draw two or more strokes near each other. Each point is pulled towards the
// nearest point of another stroke (closestPoint()), and every fifth one sprouts a filament to
// it, so neighbouring strokes slowly knit into a moving web.
eachStroke(trail = 0.14) {
    draw { t ->
        val links = FLine()
        links.overshoot = 3.0
        val body = ArrayList<Vec2>()
        val hues = ArrayList<Double>()
        for ((i, p) in points.withIndex()) {
            val near = closestPoint(p.x, p.y)
            var hue = 200.0
            if (near != null && near.dist < 180) {
                val dx = near.x - p.x
                val dy = near.y - p.y
                val dist = max(1e-9, hypot(dx, dy))
                val force = (180 - near.dist) * 0.08
                p.ax += dx / dist * force
                p.ay += dy / dist * force
                hue = 110 + (180 - near.dist) * 0.8
                if (i % 5 == 0) links.segment(p.x, p.y, near.x, near.y, rgba(120, 255, 170, 0.1 + (180 - near.dist) / 220))
            }
            body += p.at
            hues += hue
        }
        val line = smooth(body)
        line.color = cssHsla(hues.last(), 0.92, 0.68, 0.88)
        line.overshoot = 6.0
        val beads = dots(body, 1.3)
        beads.nodes.forEachIndexed { i, node -> node.color = cssHsla(hues[i], 0.92, 0.68, 0.88) }
        listOf(links, line, beads)
    }
}
