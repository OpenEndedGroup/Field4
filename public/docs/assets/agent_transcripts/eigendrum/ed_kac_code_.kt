import afield.audio.engine.WavFloatArrayWriter
import afield.audio.modal.*

// Gordon, Webb and Wolpert's isospectral pair: you cannot hear the shape of a drum.
val a = drumMesh(KacPair.drumA, 0.06).modes(10)
val b = drumMesh(KacPair.drumB, 0.06).modes(10)
// λ A and λ B share one colour range, so matching rows come out the same colour.
val spectra = table(
    "mode" to (1..10).toList(),
    "λ A" to a.lambda.take(10),
    "λ B" to b.lambda.take(10),
    "diff %" to (0 until 10).map { 100 * abs(a.lambda[it] - b.lambda[it]) / a.lambda[it] },
).format("λ A") { (it as Double).f3 }
    .format("λ B") { (it as Double).f3 }
    .format("diff %") { (it as Double).f3 }
    .heat("λ A", "λ B", by = HeatScope.TABLE)
    .heat("diff %", colormap = "magma")
display("spectra", spectra)
val outlineA = polygonFLine(KacPair.drumA).apply {
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
val outlineB = polygonFLine(KacPair.drumB).apply {
    transform { v -> v.add(4f, 0f, 0f) }
    color = vec(1.0, 0.85, 0.3, 1.0)
    thickness = 2.0
}
val shapeA = a.modeMesh(2)
val shapeB = b.modeMesh(2).map { it.copy().apply { transform { v -> v.add(4f, 0f, 0f) } } }
lines(shapeA + shapeB + listOf(outlineA, outlineB), w = 520.0, h = 220.0, fitToSize = true)
val strikeA = a.render(a.strike(1.5, 1.5, 0.05), fundamental = 140.0, seconds = 2.0)
val strikeB = b.render(b.strike(1.5, 1.5, 0.05), fundamental = 140.0, seconds = 2.0)
display("drum A", WavFloatArrayWriter.encodeMono16(strikeA, 44100))
display("drum B", WavFloatArrayWriter.encodeMono16(strikeB, 44100))
