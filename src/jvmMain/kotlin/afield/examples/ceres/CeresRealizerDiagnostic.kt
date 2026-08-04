package afield.examples.ceres

import afield.stdlib.polytope.CeresRelaxationOptions
import afield.stdlib.polytope.RelaxationPlan
import afield.stdlib.polytope.conway
import afield.stdlib.polytope.relaxWithCeres

/** What the Ceres problem actually looks like for each solid, as Ceres itself reports it. */
fun main() {
    for (expr in listOf("C", "aC", "tC", "kC", "tI", "kkC")) {
        val seed = conway(expr).polyhedron
        val v = seed.topology.elements(0).size
        val e = seed.topology.elements(1).size
        val f = seed.topology.elements(2).size
        val r = seed.relaxWithCeres(
            RelaxationPlan.canonicalConditions(),
            CeresRelaxationOptions(maxIterations = 200),
        )
        val s = r.summary
        println(
            "%-4s V=%-3d E=%-4d F=%-3d | blocks=%-4d params=%-4d (points=%-4d) residuals=%-5d | %s".format(
                expr, v, e, f,
                s.parameterBlockCount,
                s.effectiveParameterCount,
                3 * v,
                s.residualCount,
                s,
            ),
        )
    }
}
