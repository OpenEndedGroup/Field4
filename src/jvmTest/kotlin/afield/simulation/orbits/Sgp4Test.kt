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

    /** Each block of `tcppver.out` in order: satellite number and its rows (time, position, velocity). One satellite can appear twice. */
    private fun referenceBlocks(): List<Pair<String, List<DoubleArray>>> {
        val out = ArrayList<Pair<String, MutableList<DoubleArray>>>()
        for (line in resource("tcppver.out").lines()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size == 2 && parts[1] == "xx") {
                out.add(parts[0] to ArrayList())
            } else if (parts.size >= 7) {
                out.lastOrNull()?.second?.add(DoubleArray(7) { parts[it].toDouble() })
            }
        }
        return out
    }

    /** SGP4-VER's cases in file order, each with the start, stop and step (minutes) written after column 69 of its second line. */
    private fun verificationCases(): List<Pair<Tle, DoubleArray>> {
        val lines = resource("SGP4-VER.TLE").lines().filter { it.startsWith("1 ") || it.startsWith("2 ") }
        return lines.chunked(2).map { (l1, l2) ->
            Tle.parse(l1, l2) to l2.substring(69).trim().split(Regex("\\s+")).map { it.toDouble() }.toDoubleArray()
        }
    }

    private class Comparison(val satellites: List<String>, val states: Int, val worstKm: Double, val worstKms: Double, val stopsMatched: Int)

    /**
     * Propagates every case whose deep-space flag is [deep] at the reference's times. Where the reference stops early
     * (its propagator reported an error), the next time step must give `null` here too.
     */
    private fun compare(deep: Boolean): Comparison {
        val cases = verificationCases()
        val blocks = referenceBlocks()
        assertEquals(cases.size, blocks.size, "one reference block per verification case")
        var compared = 0
        var worstKm = 0.0
        var worstKms = 0.0
        var stops = 0
        val satellites = ArrayList<String>()
        for ((i, case) in cases.withIndex()) {
            val (tle, range) = case
            val (num, rows) = blocks[i]
            assertEquals(num, tle.catalogNumber.trimStart('0').ifEmpty { "0" })
            if (tle.isDeepSpace != deep) continue
            val sgp4 = Sgp4(tle)
            satellites.add(num)
            // When the reference fails at t = 0 it writes the previous block's last state again as a placeholder.
            val previous = blocks.getOrNull(i - 1)?.second?.lastOrNull()
            if (rows.size == 1 && previous != null && (1..6).all { rows[0][it] == previous[it] }) {
                assertEquals(null, sgp4.propagate(0.0), "$num: the reference fails at epoch, so should we")
                stops++
                continue
            }
            for (row in rows) {
                val s = assertNotNull(sgp4.propagate(row[0]), "$num at ${row[0]} min: reference has a state, we broke down")
                worstKm = maxOf(worstKm, (s.position - Xyz(row[1], row[2], row[3])).length)
                worstKms = maxOf(worstKms, (s.velocity - Xyz(row[4], row[5], row[6])).length)
                compared++
            }
            val (start, stop, step) = range.toList()
            val last = rows.lastOrNull()?.get(0) ?: continue
            if (last < stop - 1e-9) {
                val next = minOf(maxOf(last, start) + step, stop)
                assertEquals(null, sgp4.propagate(next), "$num: the reference stops after $last min, so $next min should break down")
                stops++
            }
        }
        return Comparison(satellites, compared, worstKm, worstKms, stops)
    }

    /** Every near-earth case in SGP4-VER matches the reference output to well under a metre and a mm/s. */
    @Test
    fun nearEarthMatchesTheVerificationVectors() {
        val c = compare(deep = false)
        println("SGP4-VER near-earth: ${c.satellites.size} cases ${c.satellites}, ${c.states} states, worst ${c.worstKm * 1000} m, ${c.worstKms * 1e6} mm/s, ${c.stopsMatched} early stops matched")
        assertTrue(c.satellites.size >= 8, "near-earth cases found: ${c.satellites}")
        assertTrue(c.worstKm < 1e-3, "worst position error ${c.worstKm} km")
        assertTrue(c.worstKms < 1e-6, "worst velocity error ${c.worstKms} km/s")
    }

    /**
     * Every deep-space case (GPS, geostationary, Molniya, the 12- and 24-hour resonances, the Lyddane switch near the
     * equator, and a run 3.5 years from epoch) matches the reference output.
     */
    @Test
    fun deepSpaceMatchesTheVerificationVectors() {
        val c = compare(deep = true)
        println("SGP4-VER deep-space: ${c.satellites.size} cases ${c.satellites}, ${c.states} states, worst ${c.worstKm * 1000} m, ${c.worstKms * 1e6} mm/s, ${c.stopsMatched} early stops matched")
        assertTrue(c.satellites.size >= 20, "deep-space cases found: ${c.satellites}")
        assertTrue(c.worstKm < 1e-3, "worst position error ${c.worstKm} km")
        assertTrue(c.worstKms < 1e-6, "worst velocity error ${c.worstKms} km/s")
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

    /** A geostationary satellite stays over one longitude, near the equator, at about 35,786 km, for a week. */
    @Test
    fun geostationaryStaysPut() {
        val geo = verificationCases().first { it.first.catalogNumber == "28626" }.first
        assertTrue(geo.isDeepSpace)
        val sat = Satellite(geo)
        val lons = (0..7).map { day ->
            val g = assertNotNull(sat.geodetic(geo.epoch.plus(Duration.ofDays(day.toLong()))))
            assertTrue(abs(g.latitudeDeg) < 2.0, "latitude $g")
            assertTrue(g.heightKm in 35_700.0..35_900.0, "height $g")
            g.longitudeDeg
        }
        assertTrue(lons.max() - lons.min() < 1.0, "longitudes $lons")
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
        val deep = sky.satellites.count { it.tle.isDeepSpace }
        println("bundled: ${sky.size} objects, $deep deep-space, epoch ${sky.epoch}")
        assertTrue(sky.size > 10_000)
        assertTrue(deep > 500, "deep-space objects $deep")
        assertEquals(deep, SatelliteCatalogue.bundled(includeDeepSpace = false).skippedDeepSpace)
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
        // the culmination is refined between samples: nothing a few seconds either side is higher
        for (p in passes) {
            assertEquals(p.maxElevationDeg, chicago.look(iss, p.culmination)!!.elevationDeg, 1e-9)
            for (s in listOf(-5L, 5L)) assertTrue(chicago.look(iss, p.culmination.plusSeconds(s))!!.elevationDeg <= p.maxElevationDeg + 1e-6)
        }

        // the visible ones are a subset: sunlit satellite, dark sky, inside a pass
        val seen = chicago.visiblePasses(iss, sky.epoch, sky.epoch.plus(Duration.ofDays(7)), minElevationDeg = 10.0, maxSunElevationDeg = -6.0)
        println("ISS visible from Chicago in a week: ${seen.size}; " + seen.take(3).joinToString { "${it.rise} for ${it.duration.seconds} s, ${"%.1f".format(it.maxElevationDeg)}°" })
        assertTrue(seen.size in 1 until passes.size, "visible ${seen.size} of ${passes.size}")
        for (v in seen) {
            assertTrue(passes.any { !v.rise.isBefore(it.rise) && !v.set.isAfter(it.set) }, "visible part $v outside every pass")
            assertTrue(chicago.sunElevationDeg(v.culmination) <= -6.0)
            assertEquals(true, iss.isSunlit(v.culmination))
        }

        // a geostationary satellite is one pass that lasts the whole interval
        val geo = sky.satellites.first { it.tle.periodMinutes in 1430.0..1442.0 && it.tle.inclination < Math.toRadians(0.1) && chicago.look(it, sky.epoch)!!.elevationDeg > 20 }
        val geoPasses = chicago.passes(geo, sky.epoch, sky.epoch.plus(Duration.ofDays(1)))
        assertEquals(1, geoPasses.size, "${geo.name}: $geoPasses")
        assertEquals(Duration.ofDays(1), geoPasses[0].duration)
    }

    /** The sun's position against Vallado's example 5-1, its height over Chicago at noon, and the Earth's shadow. */
    @Test
    fun sunAndShadow() {
        // Fundamentals of Astrodynamics and Applications, example 5-1: 2 April 2006, 0h UT.
        val s = Sun.position(Frames.julianDate(2006, 4, 2))
        val expected = Xyz(146_186_178.0, 28_788_757.0, 12_481_065.0)
        assertTrue((s - expected).length / expected.length < 1e-4, "sun $s")
        // local noon in Chicago on 11 November is about 17:51 UTC; the sun is about 30.6° up
        val noon = Observer(41.88, -87.63).sunElevationDeg(Instant.parse("2026-11-11T17:51:00Z"))
        assertTrue(noon in 29.5..31.5, "noon sun $noon")
        val jd = Frames.julianOf(Instant.parse("2026-11-11T00:00:00Z"))
        val u = Sun.position(jd).let { it * (1.0 / it.length) }
        assertTrue(Frames.isSunlit(u * 7000.0, jd))
        assertTrue(!Frames.isSunlit(u * -7000.0, jd))
        // behind the Earth but far enough to the side is lit again
        val side = Xyz(-u.y, u.x, 0.0).let { it * (1.0 / it.length) }
        assertTrue(Frames.isSunlit(u * -7000.0 + side * 6500.0, jd))
    }

    /** The GPU's secular model stays close to full SGP4 over an hour from the catalogue epoch. */
    @Test
    fun meanElementsTrackSgp4() {
        val sky = SatelliteCatalogue.bundled()
        for (deep in listOf(false, true)) {
            val errs = ArrayList<Double>()
            val every = if (deep) 1 else 20
            for (sat in sky.satellites.filter { it.tle.isDeepSpace == deep }.filterIndexed { i, _ -> i % every == 0 }) {
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
            println("mean elements vs SGP4, ${if (deep) "deep-space" else "near-earth"}, over ${errs.size} states: median ${"%.1f".format(median)} km, p90 ${"%.1f".format(p90)} km")
            assertTrue(median < 50.0, "median $median km")
        }
        assertEquals(sky.size, sky.meanElementsAt(sky.epoch).size)
        assertEquals(3600L, Duration.ofHours(1).toSeconds())
    }
}
