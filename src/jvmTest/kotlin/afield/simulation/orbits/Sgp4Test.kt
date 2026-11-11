package afield.simulation.orbits

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SGP4 against the published verification vectors.
 *
 * `SGP4-VER.TLE` and `tcppver.out` are the test element sets and the C++ reference output distributed with
 * Vallado, Crawford, Hujsak and Kelso, "Revisiting Spacetrack Report #3", AIAA 2006-6753
 * (<https://celestrak.org/publications/AIAA/2006-6753/>), as mirrored in python-sgp4.
 */
class Sgp4Test {
    private fun resource(name: String) = javaClass.getResourceAsStream("/afield/orbits/$name")!!.bufferedReader().readText()

    private fun referenceRows(): Map<String, List<DoubleArray>> {
        val out = LinkedHashMap<String, MutableList<DoubleArray>>()
        var current: MutableList<DoubleArray>? = null
        for (line in resource("tcppver.out").lines()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size == 2 && parts[1] == "xx") {
                current = ArrayList<DoubleArray>().also { out[parts[0]] = it }
            } else if (parts.size >= 7) {
                current?.add(DoubleArray(7) { parts[it].toDouble() })
            }
        }
        return out
    }

    /** Every near-earth case in SGP4-VER matches the reference output to well under a metre and a mm/s. */
    @Test
    fun nearEarthMatchesTheVerificationVectors() {
        val tles = Tle.parseAll(resource("SGP4-VER.TLE")).associateBy { it.catalogNumber.trimStart('0').ifEmpty { "0" } }
        val ref = referenceRows()
        var compared = 0
        var worstKm = 0.0
        var worstKms = 0.0
        val satellites = ArrayList<String>()
        for ((num, rows) in ref) {
            val tle = tles[num] ?: continue
            if (tle.isDeepSpace) continue
            val sgp4 = Sgp4(tle)
            satellites.add(num)
            for (row in rows) {
                val s = assertNotNull(sgp4.propagate(row[0]), "$num at ${row[0]} min: reference has a state, we broke down")
                worstKm = maxOf(worstKm, (s.position - Xyz(row[1], row[2], row[3])).length)
                worstKms = maxOf(worstKms, (s.velocity - Xyz(row[4], row[5], row[6])).length)
                compared++
            }
        }
        println("SGP4-VER near-earth: ${satellites.size} satellites $satellites, $compared states, worst ${worstKm * 1000} m, ${worstKms * 1e6} mm/s")
        assertTrue(satellites.size >= 8, "near-earth cases found: $satellites")
        assertTrue(worstKm < 1e-3, "worst position error $worstKm km")
        assertTrue(worstKms < 1e-6, "worst velocity error $worstKms km/s")
    }

    /** The first row of the first case, written out, so a parsing slip cannot pass by comparing nothing. */
    @Test
    fun vanguardAtEpoch() {
        val tle = Tle.parse(
            "1 00005U 58002B   00179.78495062  .00000023  00000-0  28098-4 0  4753",
            "2 00005  34.2682 348.7242 1859667 331.7664  19.3264 10.82419157413667",
        )
        assertEquals(2.8098e-5, tle.bstar, 1e-12)
        assertEquals(0.1859667, tle.eccentricity, 1e-12)
        val s = Sgp4(tle).propagate(0.0)!!
        // tcppver.out, satellite 5, t = 0.
        assertEquals(7022.46529266, s.position.x, 1e-6)
        assertEquals(-1400.08296755, s.position.y, 1e-6)
        assertEquals(0.03995155, s.position.z, 1e-6)
        assertEquals(1.893841015, s.velocity.x, 1e-9)
    }

    /** Deep-space orbits are refused, not silently wrong. */
    @Test
    fun deepSpaceIsRefused() {
        val gps = Tle.parseAll(resource("SGP4-VER.TLE")).first { it.isDeepSpace }
        val r = runCatching { Sgp4(gps) }
        assertTrue(r.isFailure)
    }

    /** Geodetic round trip and the epoch arithmetic. */
    @Test
    fun framesRoundTrip() {
        val p = Frames.ecefOf(41.88, -87.63, 0.2)
        val g = Frames.geodetic(p)
        assertEquals(41.88, g.latitudeDeg, 1e-9)
        assertEquals(-87.63, g.longitudeDeg, 1e-9)
        assertEquals(0.2, g.heightKm, 1e-6)
        val t = Instant.parse("2026-11-11T12:00:00Z")
        assertEquals(2461356.0, Frames.julianOf(t), 1e-9)
        assertEquals(t, Frames.instantOf(Frames.julianOf(t)))
        // The 2000 epoch from the TLE above: day 179.78495062 of 2000 is 27 June, 18:50:19.73 UTC.
        val tle = Tle.parse(
            "1 00005U 58002B   00179.78495062  .00000023  00000-0  28098-4 0  4753",
            "2 00005  34.2682 348.7242 1859667 331.7664  19.3264 10.82419157413667",
        )
        assertEquals(Instant.parse("2000-06-27T18:50:19.733571Z").toEpochMilli().toDouble(), tle.epoch.toEpochMilli().toDouble(), 2.0)
    }

    /** The bundled catalogue loads, propagates, and the ISS passes over Chicago several times a week. */
    @Test
    fun bundledCatalogueAndPasses() {
        val sky = SatelliteCatalogue.bundled()
        println("bundled: ${sky.size} near-earth, ${sky.skippedDeepSpace} deep-space skipped, epoch ${sky.epoch}")
        assertTrue(sky.size > 10_000)
        val positions = sky.positionsEcef(sky.epoch)
        val alive = positions.filterNotNull()
        assertTrue(alive.size > 0.95 * sky.size, "alive ${alive.size}")
        val iss = assertNotNull(sky.byName("ISS (ZARYA)"))
        val h = iss.geodetic(sky.epoch)!!
        assertTrue(h.heightKm in 370.0..460.0, "ISS height $h")
        val chicago = Observer(41.88, -87.63)
        val passes = chicago.passes(iss, sky.epoch, sky.epoch.plus(Duration.ofDays(7)), minElevationDeg = 10.0)
        println("ISS over Chicago in a week: ${passes.size}; " + passes.take(3).joinToString { "${it.rise} ${"%.1f".format(it.maxElevationDeg)}°" })
        assertTrue(passes.size in 5..60, "passes ${passes.size}")
        assertTrue(passes.all { abs(it.duration.toMinutes()) < 15 })
    }

    /** The GPU's secular model stays close to full SGP4 over an hour from the catalogue epoch. */
    @Test
    fun meanElementsTrackSgp4() {
        val sky = SatelliteCatalogue.bundled()
        val errs = ArrayList<Double>()
        for (sat in sky.satellites.filterIndexed { i, _ -> i % 20 == 0 }) {
            val dt = sat.tle.minutesSinceEpoch(sky.epoch)
            val me = sat.sgp4.meanElements.shifted(dt)
            for (t in listOf(0.0, 30.0, 60.0)) {
                val s = sat.sgp4.propagate(dt + t) ?: continue
                errs.add((me.position(t) - s.position).length)
            }
        }
        errs.sort()
        val median = errs[errs.size / 2]
        val p90 = errs[errs.size * 9 / 10]
        println("mean elements vs SGP4 over ${errs.size} states: median ${"%.1f".format(median)} km, p90 ${"%.1f".format(p90)} km")
        assertTrue(median < 50.0, "median $median km")
        assertEquals(sky.size, sky.meanElementsAt(sky.epoch).size)
        assertEquals(3600L, Duration.ofHours(1).toSeconds())
    }
}
