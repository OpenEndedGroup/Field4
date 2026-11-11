/*
Every 120 frames: read the trail back, extract the network between the food sources and
score it as Tero did, beside the minimum spanning tree and the Delaunay triangulation of
the same dots. A road is a pixel at a quarter of the level an agent standing on it every
frame would reach. The slider sets the trail's half-life: shorter thins the network
towards a tree, longer lets it hold loops.
*/
slider("half-life (frames)", min = 2.0, max = 40.0, step = 0.5, initialValue = 8.3) { h ->
    physarum.trail.settings.halfLifeFrames = vec(h, h, h, h)
}

frames {
    while (frame()) {
        val pending = physarum.trail.read()
        while (!pending.isDone && frame()) {}
        if (!pending.isDone) break
        val trail = pending.get().channel(0)
        val food = physarum.settings.food
        val decay = physarum.trail.decayFactors().x
        val network = TransportNetwork.extract(trail, food, threshold = 0.25 / (1.0 - decay))
        val scores = [
            network.score("physarum"),
            TransportNetwork.mst(food).score("MST"),
            TransportNetwork.delaunay(food).score("Delaunay"),
        ]
        display("scores", "<pre>" + NetworkScore.table(scores) + "</pre>")
        // flipped, so it reads the same way up as the drawing and the window
        showImg(
            network.drawOver(trail.colormap("magma", 0f, (1.0 / (1.0 - decay)).toFloat())).flipY(),
            channel = "network",
            maxSide = 512,
        )
        repeat(120) { frame() }
    }
}
