package afield.stdlib

import afield.manifold.ManifoldLibrary
import afield.manifold.Solid
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CPU half of `public/docs/chain_life3d_render.md`, held to what the page says.
 *
 * The page's claim is that a three-dimensional automaton's *component count* is a question the
 * Boolean layer can answer and a mesh library cannot, and that the negative genus Manifold reports
 * is that count in disguise. Both are arithmetic rather than approximations, so both are asserted
 * exactly here. The volume figures are not: they move with the sampling spacing, which is the third
 * thing the page says and the last test below.
 *
 * The Manifold half skips with a printed reason when the library is absent, as
 * `SolidSLConventionProbe` does. The two `alife_ca` calls the page teaches — [asDensityField] and
 * [marchBounds] — are exercised before that guard, so `DocumentedApiEvaluationTest` stays satisfied
 * on a machine with no Manifold.
 */
class Life3dSolidTest {

    /** The page's volume: 45% fill in a 16-cubed box at the centre of 36 cubed, seed 11. */
    private fun pageVolume(generations: Int = 8): IntVolume {
        val n = 36
        val rng = Random(11)
        val v = IntVolume(n, n, n, IntArray(n * n * n), wrap = false)
        val half = 8
        val from = n / 2 - half
        for (z in from until from + 2 * half) {
            for (y in from until from + 2 * half) {
                for (x in from until from + 2 * half) {
                    v[x, y, z] = if (rng.nextDouble() < 0.45) 1 else 0
                }
            }
        }
        var stepped = v
        repeat(generations) { stepped = stepped.step(LIFE3D_BLOB) }
        return stepped
    }

    @Test
    fun `the seeded run is reproducible and decays`() {
        val populations = (0..8).map { pageVolume(it).population() }
        assertEquals(1852, populations.first(), "the seed is not what the page publishes")
        assertEquals(164, populations.last(), "eight generations of LIFE3D_BLOB is not 164 cells")
        // Monotone decay is not a property of the rule in general; it is a property of this run, and
        // the page shows the sequence rather than claiming the rule always decays.
        assertTrue(
            populations.zipWithNext().all { (a, b) -> b <= a },
            "this seed's population is expected to fall every generation: $populations",
        )
    }

    @Test
    fun `marchBounds grows the volume by one voxel on every side`() {
        val bounds = pageVolume().marchBounds()
        assertEquals(-1f, bounds.min.x)
        assertEquals(-1f, bounds.min.y)
        assertEquals(-1f, bounds.min.z)
        assertEquals(36f, bounds.max.x)
        assertEquals(36f, bounds.max.y)
        assertEquals(36f, bounds.max.z)
    }

    /**
     * The trap the page carries a callout about: a radius-one blur over filaments and isolated cells
     * never reaches an iso of 0.5, so the surface comes back empty from both marchers with no error.
     */
    @Test
    fun `a radius-one blur marches this volume to nothing and radius zero does not`() {
        val volume = pageVolume()
        val blurred = volume.asDensityField(smoothRadius = 1, iso = 0.5)
        val raw = volume.asDensityField(smoothRadius = 0, iso = 0.5)
        val bounds = volume.marchBounds()
        assertEquals(
            0,
            marchingCubes(blurred, bounds, resolution = 110, positiveOutside = false).triangleCount,
            "a radius-one blur is expected to leave nothing above the iso for this rule",
        )
        assertTrue(
            marchingCubes(raw, bounds, resolution = 110, positiveOutside = false).triangleCount > 1000,
            "the raw occupancy should march to a real surface",
        )
    }

    @Test
    fun `the negative genus is one minus the component count`() {
        if (!ManifoldLibrary.isAvailable) {
            println("Life3dSolidTest: skipping the Manifold half — ${ManifoldLibrary.describe()}")
            return
        }
        val volume = pageVolume()
        val field = volume.asDensityField(smoothRadius = 0, iso = 0.5)
        Solid.scope {
            val whole = Solid.levelSet(volume.marchBounds(), edgeLength = 0.7, parallel = false) { x, y, z ->
                field(vec(x, y, z)).toDouble()
            }
            val parts = whole.decompose()
            assertEquals(
                1 - parts.size, whole.genus,
                "Manifold's genus over k disjoint spheres is 1 - k; got ${whole.genus} for ${parts.size}",
            )
            assertTrue(parts.all { it.genus == 0 }, "a cluster of this automaton should be a sphere")
            // Independently integrated, so a decomposition that lost or duplicated a cluster would
            // not add up. Float tolerance rather than equality: these are separate integrations.
            assertTrue(
                abs(parts.sumOf { it.volume } - whole.volume) < 1e-6,
                "components sum to ${parts.sumOf { it.volume }} against the whole's ${whole.volume}",
            )
            Unit
        }
    }

    @Test
    fun `the sequential level set is reproducible where the parallel one is not`() {
        if (!ManifoldLibrary.isAvailable) {
            println("Life3dSolidTest: skipping the Manifold half — ${ManifoldLibrary.describe()}")
            return
        }
        val volume = pageVolume()
        val field = volume.asDensityField(smoothRadius = 0, iso = 0.5)
        val bounds = volume.marchBounds()
        fun march(parallel: Boolean): Triple<Long, Int, Double> = Solid.scope {
            val s = Solid.levelSet(bounds, edgeLength = 0.7, parallel = parallel) { x, y, z ->
                field(vec(x, y, z)).toDouble()
            }
            Triple(s.triangleCount, s.decompose().size, s.volume)
        }

        val sequential = (1..3).map { march(parallel = false) }
        assertEquals(
            1, sequential.distinct().size,
            "parallel = false is expected to return the same mesh every time, got $sequential",
        )

        // The other direction is a statement about a library we do not control, so it is asserted
        // only as far as it can be: whatever the parallel path returns has to be the same *shape* of
        // answer. That it sometimes differs run to run is recorded in Solid.levelSet's KDoc and
        // measured on the page; a test that demanded it differ would fail on a one-core machine.
        val parallel = march(parallel = true)
        assertTrue(parallel.first > 0, "the parallel path returned no triangles")
        assertTrue(
            abs(parallel.third - sequential.first().third) < 1.0,
            "the two paths should agree on volume to within a unit: $parallel against ${sequential.first()}",
        )
    }

    /** The page's third claim: both the count and the volume move with the sampling spacing. */
    @Test
    fun `the cluster count and the volume both depend on the spacing`() {
        if (!ManifoldLibrary.isAvailable) {
            println("Life3dSolidTest: skipping the Manifold half — ${ManifoldLibrary.describe()}")
            return
        }
        val volume = pageVolume()
        val field = volume.asDensityField(smoothRadius = 0, iso = 0.5)
        val bounds = volume.marchBounds()
        fun at(edge: Double): Pair<Int, Double> = Solid.scope {
            val s = Solid.levelSet(bounds, edgeLength = edge, parallel = false) { x, y, z ->
                field(vec(x, y, z)).toDouble()
            }
            s.decompose().size to s.volume
        }
        val coarse = at(1.4)
        val middle = at(0.7)
        assertTrue(
            coarse.first < middle.first,
            "a coarse spacing bridges diagonal neighbours, so it should find fewer clusters: " +
                "${coarse.first} against ${middle.first}",
        )
        assertTrue(
            coarse.second < middle.second,
            "a trilinear iso-surface over a 0/1 grid gains volume as the sampling gets finer: " +
                "${coarse.second} against ${middle.second}",
        )
        // Field's own marcher moves the same way, which is what makes it a sampling effect rather
        // than a difference between the two implementations.
        val low = marchingCubes(field, bounds, resolution = 55, positiveOutside = false).signedVolume()
        val high = marchingCubes(field, bounds, resolution = 110, positiveOutside = false).signedVolume()
        assertTrue(low < high, "marchingCubes should gain volume with resolution too: $low against $high")
    }
}
