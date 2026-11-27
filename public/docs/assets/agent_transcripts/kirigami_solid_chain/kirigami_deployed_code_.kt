import afield.examples.chain.KirigamiSolidChain
import kotlin.math.cos
import kotlin.math.sin

val closed = KirigamiSolidChain.measure(angle = 0.0)
val stages = listOf(0.0, 15.0, 30.0, 45.0).map { it to KirigamiSolidChain.measure(angle = Math.toRadians(it)) }
for ((deg, m) in stages) {
    val s = cos(m.angle) + sin(m.angle)
    println(
        "%4.0f deg  genus %d  parts %d  hull area x%.3f  (cos+sin)^2 %.3f  hinge gap %.0e"
            .format(
                deg,
                m.genus,
                m.parts,
                m.hullArea / closed.hullArea,
                s * s,
                m.hingeGap,
            ),
    )
}

showImgGrid(
    *stages
        .map { (deg, m) ->
            "${deg.toInt()} deg, genus ${m.genus}" to
                imgFromBytes(
                    m.mesh.renderPng(
                        MeshView(yawDegrees = 25.0, pitchDegrees = 40.0, distance = 2.7, width = 520, height = 520),
                    ),
                )
        }
        .toTypedArray(),
    columns = 4,
    cellSide = 300,
)
