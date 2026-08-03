import afield.graphics.FLine
import afield.stdlib.ceres.CachedCeresSolvers
import afield.stdlib.ceres.CeresExprVec
import afield.stdlib.ceres.CeresFeature
import afield.stdlib.ceres.CeresLinearSolver
import afield.stdlib.ceres.FfmCeresBackend
import afield.stdlib.ceres.PointCloud
import afield.stdlib.ceres.SolveOptions
import afield.stdlib.ceres.SolveRequest
import afield.stdlib.ceres.Vec3
import afield.stdlib.ceres.buildCeresProgram
import afield.stdlib.ceres.RobustLoss
import java.nio.file.Files
import java.nio.file.Path

// ---- the data to fit -------------------------------------------------------------
// Four points, so the answer has a closed form to check against: the circumsphere of a
// tetrahedron. Add a fifth and it becomes a genuine least-squares fit instead.
val data = listOf(
    Vec3(0.9, 0.6, 2.3),
    Vec3(1.0, -2.1, 0.5),
    Vec3(-0.4, 2.0, 0.5),
    Vec3(-0.4, -0.5, 2.3),
)
// ----------------------------------------------------------------------------------

val localWrapper = Path.of("build/ken-ceres/libken_ceres_jvm.dylib")
val backend = if (Files.exists(localWrapper)) FfmCeresBackend(localWrapper) else FfmCeresBackend()

fun dots(): List<FLine> = data.map { p ->
    val s = 0.06f
    FLine()
        .moveTo((p.x - s).toFloat(), p.y.toFloat(), p.z.toFloat())
        .lineTo((p.x + s).toFloat(), p.y.toFloat(), p.z.toFloat())
        .moveTo(p.x.toFloat(), (p.y - s).toFloat(), p.z.toFloat())
        .lineTo(p.x.toFloat(), (p.y + s).toFloat(), p.z.toFloat())
        .moveTo(p.x.toFloat(), p.y.toFloat(), (p.z - s).toFloat())
        .lineTo(p.x.toFloat(), p.y.toFloat(), (p.z + s).toFloat())
        .also {
            it.stroked = true
            it.filled = false
            it.thickness = 3.0
            it.color = vec(1.00, 0.52, 0.28, 1.00)
        }
}

if (CeresFeature.AbiV3 !in backend.supportedFeatures) {
    println("wrapper ${backend.abiVersion} cannot run free variables; drawing the data only")
    markdown(
        "fit",
        """
        ### This box needs the ABI v3 Ceres wrapper

        The loaded wrapper reports `${backend.abiVersion}`, and a free centre and radius are a
        v3 feature. Build one from the repository root and re-run:

        ```
        ./gradlew buildCeresNative
        ```
        """.trimIndent(),
    )
    displayOpen("fit")
    threeLines(dots(), channel = "fit3d", name = "ceres-sphere", height = 480, autoFrame = true)
    displayOpen("fit3d")
} else {
    // The unknowns are a centre and a radius. Neither is a point in the cloud — the data are
    // constants inside the expression, and this residual names no point at all. No opcode in
    // the vocabulary can say this: RADIUS_ABOUT_CENTROID fits a radius about the points' own
    // centroid, which is not the same thing as a free centre.
    val program = buildCeresProgram(RobustLoss.NONE) {
        val centre = variable("centre", Vec3(6.0, -6.0, 6.0))   // a deliberately bad start
        val radius = variable("radius", 0.1)
        expression(emptyList(), listOf(centre, radius)) { _, (c, r) ->
            data.map { p -> (CeresExprVec.of(p) - c.vec()).length() - r.scalar }
        }
    }

    val result = CachedCeresSolvers(backend).use { solvers ->
        solvers.solve(
            SolveRequest(
                PointCloud.of(Vec3(0.0, 0.0, 0.0)),
                program,
                options = SolveOptions(
                    maxIterations = 200,
                    linearSolver = CeresLinearSolver.SPARSE_NORMAL_CHOLESKY,
                    functionTolerance = 1e-14,
                    gradientTolerance = 1e-16,
                    parameterTolerance = 1e-14,
                    summary = true,
                ),
            ),
        )
    }
    val centre = result.variables.vec3("centre")
    val radius = result.variables.scalar("radius")

    // The closed form, for comparison: |c - p_i|^2 equal for every vertex, which after
    // subtracting the first equation is a 3x3 linear system. Solved by Cramer's rule.
    fun closedForm(): Pair<Vec3, Double> {
        fun n2(v: Vec3) = v.x * v.x + v.y * v.y + v.z * v.z
        val m = DoubleArray(9)
        val rhs = DoubleArray(3)
        for (i in 1..3) {
            m[3 * (i - 1)] = 2.0 * (data[i].x - data[0].x)
            m[3 * (i - 1) + 1] = 2.0 * (data[i].y - data[0].y)
            m[3 * (i - 1) + 2] = 2.0 * (data[i].z - data[0].z)
            rhs[i - 1] = n2(data[i]) - n2(data[0])
        }
        fun det(a: DoubleArray) =
            a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) +
                a[2] * (a[3] * a[7] - a[4] * a[6])
        val d = det(m)
        fun column(k: Int): Double {
            val c = m.copyOf()
            for (row in 0..2) c[3 * row + k] = rhs[row]
            return det(c) / d
        }
        val c = Vec3(column(0), column(1), column(2))
        return c to kotlin.math.sqrt(n2(Vec3(data[0].x - c.x, data[0].y - c.y, data[0].z - c.z)))
    }

    val (trueCentre, trueRadius) = closedForm()
    val centreError = kotlin.math.sqrt(
        (centre.x - trueCentre.x) * (centre.x - trueCentre.x) +
            (centre.y - trueCentre.y) * (centre.y - trueCentre.y) +
            (centre.z - trueCentre.z) * (centre.z - trueCentre.z),
    )

    println(
        """
        ${data.size} fixed points, two free variables, no point unknowns at all
        ceres       centre (${"%.9f".format(centre.x)}, ${"%.9f".format(centre.y)}, ${"%.9f".format(centre.z)})  radius ${"%.9f".format(radius)}
        closed form centre (${"%.9f".format(trueCentre.x)}, ${"%.9f".format(trueCentre.y)}, ${"%.9f".format(trueCentre.z)})  radius ${"%.9f".format(trueRadius)}
        centre off by ${"%.2e".format(centreError)}, radius off by ${"%.2e".format(kotlin.math.abs(radius - trueRadius))}
        ${result.summary}
        """.trimIndent(),
    )

    // The fitted sphere, as latitude and longitude circles.
    val steps = 72
    fun circle(build: (Double) -> Vec3): FLine {
        val line = FLine()
        for (i in 0..steps) {
            val p = build(2.0 * Math.PI * i / steps)
            if (i == 0) line.moveTo(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
            else line.lineTo(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
        }
        line.stroked = true
        line.filled = false
        line.thickness = 1.0
        line.color = vec(0.36, 0.78, 0.96, 0.55)
        return line
    }

    val shell = buildList {
        for (k in 1..7) {
            val phi = Math.PI * k / 8.0
            val z = radius * kotlin.math.cos(phi)
            val ring = radius * kotlin.math.sin(phi)
            add(circle { t -> Vec3(centre.x + ring * kotlin.math.cos(t), centre.y + ring * kotlin.math.sin(t), centre.z + z) })
        }
        for (k in 0..7) {
            val theta = Math.PI * k / 8.0
            add(
                circle { t ->
                    val ring = radius * kotlin.math.sin(t)
                    Vec3(
                        centre.x + ring * kotlin.math.cos(theta),
                        centre.y + ring * kotlin.math.sin(theta),
                        centre.z + radius * kotlin.math.cos(t),
                    )
                },
            )
        }
    }

    val cross = 0.14f
    val centreMark = FLine()
        .moveTo((centre.x - cross).toFloat(), centre.y.toFloat(), centre.z.toFloat())
        .lineTo((centre.x + cross).toFloat(), centre.y.toFloat(), centre.z.toFloat())
        .moveTo(centre.x.toFloat(), (centre.y - cross).toFloat(), centre.z.toFloat())
        .lineTo(centre.x.toFloat(), (centre.y + cross).toFloat(), centre.z.toFloat())
        .moveTo(centre.x.toFloat(), centre.y.toFloat(), (centre.z - cross).toFloat())
        .lineTo(centre.x.toFloat(), centre.y.toFloat(), (centre.z + cross).toFloat())
    centreMark.stroked = true
    centreMark.filled = false
    centreMark.thickness = 3.0
    centreMark.color = vec(0.99, 0.96, 0.62, 1.00)

    threeLines(
        dots() + shell + listOf(centreMark),
        channel = "fit3d",
        name = "ceres-sphere",
        height = 520,
        autoFrame = true,
        editable = false,
    )
    displayOpen("fit3d")

    markdown(
        "fit",
        """
        ### A centre and a radius, solved for

        Orange crosses are the fixed data. Blue is the sphere Ceres found; the yellow cross is
        its centre — a free variable, not a point in the cloud. From a start six units away and
        a radius an order of magnitude too small it lands on the closed-form circumcentre to
        `${"%.2e".format(centreError)}`.

        The whole residual is three lines of Kotlin:

        ```kotlin
        val centre = variable("centre", Vec3(6.0, -6.0, 6.0))
        val radius = variable("radius", 0.1)
        expression(emptyList(), listOf(centre, radius)) { _, (c, r) ->
            data.map { p -> (CeresExprVec.of(p) - c.vec()).length() - r.scalar }
        }
        ```

        It names no point at all — every unknown here is a free variable.
        """.trimIndent(),
    )
    displayOpen("fit")
}
