import afield.graphics.FLine
import afield.stdlib.Vec3
import afield.stdlib.ceres.CachedCeresSolvers
import afield.stdlib.ceres.CeresLinearSolver
import afield.stdlib.ceres.FfmCeresBackend
import afield.stdlib.ceres.PointCloud
import afield.stdlib.ceres.SolveOptions
import afield.stdlib.ceres.SolveRequest
import afield.stdlib.polytope.*
import java.nio.file.Files
import java.nio.file.Path

// ---- the one parameter to change -------------------------------------------------
// Conway prefix notation, read right to left. "tI" — the truncated icosahedron, a football —
// is the default because it is nearly round, so every solved plane sits on the surface where
// you can see it. Set this to "kkC" for the standing counter-example in
// dev_docs/POLYTOPE_ALGEBRA.md that neither Kotlin iteration canonicalizes and that the
// plane-as-a-free-variable formulation cracked: 12 iterations, tangency 2.7e-08. Its
// canonical form is a stellation, so the stems end up inside it. Also try "aC", "gD", "kkD".
val expression = "tI"
// ----------------------------------------------------------------------------------

// Nothing is committed, so look where each build task puts its artifact. Constructing the
// backend is what refuses a missing or too-old library, so that is the check.
val backend = listOf(
    Path.of("build/ken-ceres-redistributable/libken_ceres_jvm.dylib"),
    Path.of("build/ken-ceres/libken_ceres_jvm.dylib"),
).filter { Files.exists(it) }
    .firstNotNullOfOrNull { runCatching { FfmCeresBackend(it) }.getOrNull() }

val seed = conway(expression).polyhedron
val problem = RealizationProblem.of(seed)
val plan = RelaxationPlan.canonicalConditions()
val faces = seed.topology.elements(2)

fun faintSeed() = seed.toFaceFLines { _, line ->
    line.filled = true
    line.stroked = true
    line.thickness = 1.0
    line.color = vec(0.55, 0.60, 0.68, 0.13)
}

if (backend == null) {
    println("no Ceres wrapper built; drawing the unsolved seed")
    markdown(
        "ceres",
        """
        ### This box needs the native Ceres wrapper

        The solver is an optional extra and no binary is committed, so there is nothing here
        to solve yet. Build one from the repository root and re-run this box:

        ```
        ./gradlew buildCeresRedistributable
        ```

        That produces a self-contained library — `otool -L` shows only `libc++` and
        `libSystem` — from pinned Ceres and Eigen source. `dev_docs/CERES.md` has the whole
        story.
        """.trimIndent(),
    )
    displayOpen("ceres")
    threeLines(faintSeed(), channel = "solid", name = "ceres-planes", height = 520, autoFrame = true)
    displayOpen("solid")
} else {
    val options = SolveOptions(
        maxIterations = 500,
        linearSolver = CeresLinearSolver.SPARSE_NORMAL_CHOLESKY,
        functionTolerance = 1e-10,
        gradientTolerance = 1e-14,
        parameterTolerance = 1e-12,
        summary = true,
    )

    fun solve(planes: PlaneFormulation) = CachedCeresSolvers(backend).use { solvers ->
        solvers.solve(
            SolveRequest(
                PointCloud(problem.initialCoordinates),
                CeresRealization.program(plan, problem, planes, 1.0, 0.01),
                options = options,
            ),
        )
    }

    // Each face's plane as a free unit normal (on a sphere manifold) plus a free scalar
    // offset, written in Kotlin as `corner . n - d` — and the same parameterisation as the
    // hand-written C++ opcode, so the two can be compared on the spot.
    val expressed = solve(PlaneFormulation.HESSE_EXPRESSION)
    val opcode = solve(PlaneFormulation.HESSE)
    val agreement = expressed.points.values.indices.maxOf {
        kotlin.math.abs(expressed.points.values[it] - opcode.points.values[it])
    }

    val solved = seed.copy(
        realization = Realization3(
            (0 until problem.vertexCount).map { i ->
                Vec3(
                    expressed.points.values[3 * i].toFloat(),
                    expressed.points.values[3 * i + 1].toFloat(),
                    expressed.points.values[3 * i + 2].toFloat(),
                )
            },
        ),
    )

    // The free variables, read back in declaration order: two blocks per face, a unit normal
    // and an offset. They are not in the point cloud — they never were points.
    val planes = faces.indices.map { f ->
        expressed.variables[2 * f].vec3() to expressed.variables[2 * f + 1].scalar
    }
    val worstUnit = planes.maxOf { (n, _) ->
        kotlin.math.abs(kotlin.math.sqrt(n.x * n.x + n.y * n.y + n.z * n.z) - 1.0)
    }
    val diagnostics = solved.canonicalDiagnostics()

    println(
        """
        $expression — ${solved.topology.elements(0).size} vertices, ${solved.topology.elements(1).size} edges, ${faces.size} faces
        ${expressed.variables.size} free variables (${planes.size} planes: a unit normal + an offset each)
        expression : ${expressed.summary}
        opcode     : ${opcode.summary}
        the two answers differ by at most ${"%.2e".format(agreement)}
        canonical  : tangency spread ${"%.3e".format(diagnostics.tangencyRadiusSpread)}  centre ${"%.3e".format(diagnostics.tangencyCentroidOffset)}  planarity ${"%.3e".format(diagnostics.maxFacePlanarityError)}
        every solved normal is still unit to ${"%.2e".format(worstUnit)} — the sphere manifold's own doing,
        since no residual ever asks it to stay there
        """.trimIndent(),
    )

    val status: (String) -> Unit = { message -> markdown("plane", message) }

    // Colour each face by how far its own corners sit from the plane Ceres solved for.
    val faceLines = solved.toFaceFLines { face, line ->
        val f = faces.indexOfFirst { it.id == face.id }
        val (n, d) = planes[f]
        val cycle = solved.topology.faceCycle(face.id)
        val index = solved.topology.elements(0).mapIndexed { at, v -> v.id.value to at }.toMap()
        val corners = solved.realization!!.vertexCoordinates
        val worst = cycle.maxOf { vertexValue ->
            val p = corners[index.getValue(vertexValue)]
            kotlin.math.abs(p.x * n.x + p.y * n.y + p.z * n.z - d)
        }
        line.filled = true
        line.stroked = true
        line.thickness = 1.2
        line.color = vec(0.36, 0.78, 0.96, 0.72)
        line.hoverColor = vec(0.99, 0.96, 0.62, 0.95)
        line.onClick = {
            status(
                """
                **face ${face.id.value} of ${faces.size}** — degree ${cycle.size}

                Its plane is two free variables Ceres solved for, not geometry anyone supplied:

                - normal `n` = `(${"%.6f".format(n.x)}, ${"%.6f".format(n.y)}, ${"%.6f".format(n.z)})`, on a sphere manifold
                - offset `d` = `${"%.6f".format(d)}`
                - `|n|` = `${"%.15f".format(kotlin.math.sqrt(n.x * n.x + n.y * n.y + n.z * n.z))}`
                - worst corner off this plane: `${"%.3e".format(worst)}`

                The residual is just `corner . n - d`, written in Kotlin and differentiated by
                Ceres on `ceres::Jet` — no C++, no finite differences.
                """.trimIndent(),
            )
        }
    }

    // One stem per free variable: from the point of that face's plane nearest the origin,
    // outward along the normal. Both endpoints come from `n` and `d` alone — this is the part
    // of the picture you cannot draw at all without reading the free variables back.
    val stem = 0.6
    val stems = planes.map { (n, d) ->
        FLine()
            .moveTo((n.x * d).toFloat(), (n.y * d).toFloat(), (n.z * d).toFloat())
            .lineTo((n.x * (d + stem)).toFloat(), (n.y * (d + stem)).toFloat(), (n.z * (d + stem)).toFloat())
            .also {
                it.stroked = true
                it.filled = false
                it.thickness = 3.0
                it.color = vec(1.00, 0.47, 0.18, 1.00)
            }
    }

    threeLines(
        faintSeed() + faceLines + stems,
        channel = "solid",
        name = "ceres-planes",
        height = 560,
        autoFrame = true,
        editable = false,
    )
    displayOpen("solid")

    status(
        """
        ### ${planes.size} planes, solved for

        Faint grey is the seed embedding; blue is the canonical form Ceres found; each orange
        stem is one of the free variables — a unit normal, drawn from the point of its plane
        nearest the origin.

        Click a face to read its plane.
        """.trimIndent(),
    )
    displayOpen("plane")
}
