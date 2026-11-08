import afield.audio.modal.*

// The radius-6 Penrose patch as a live voice. It strikes itself every 1.5 s at the vertex with
// the most confined weight; call drum.strike(x, y) to hit it somewhere else. Launch this box.
val graph = deBruijnTiling(symmetry = 5, radius = 6.0, scale = 1.0).vertexGraph()
val modes = graph.springModes(8.0)
val weight = modes.spectrum.zeroWeights()
val v = graph.interior(0.5 * graph.extent).maxBy { weight[it] }
val drum = ModalDrum(
    modes,
    fundamental = modes.fundamentalFor(220.0),
    alpha = 1.0,
    beta = 0.2,
    every = 1.5,
    autoX = graph.x[v],
    autoY = graph.y[v],
)
drum
