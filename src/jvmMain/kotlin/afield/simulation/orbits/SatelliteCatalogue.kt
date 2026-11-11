@file:afield.runtime.completion.CompletionCategory("Simulation")

/**
 * A catalogue of satellites: element sets ready to propagate, from a bundled CelesTrak snapshot or a fresh download.
 *
 * [SatelliteCatalogue.bundled] reads the snapshot of CelesTrak's "active" group that ships with Field
 * (taken 2026-11-11, 15,884 objects); [SatelliteCatalogue.fetch] downloads a current group, caches
 * it under `~/.afield/cache/celestrak/` and falls back to the cache, then to the snapshot, when offline.
 * Element sets age: SGP4 is good to a few kilometres for a day or two either side of the epoch and drifts
 * by tens of kilometres a week out, so a demo that wants today's sky should fetch.
 *
 * Every object is kept, near-earth and deep-space (see [Tle.isDeepSpace]); pass `includeDeepSpace = false` for
 * the low orbits alone, and [SatelliteCatalogue.skippedDeepSpace] counts what was left out. Data: CelesTrak,
 * <https://celestrak.org>, by T. S. Kelso, which asks that it be credited.
 *
 *     import afield.simulation.orbits.*
 *
 *     val sky = SatelliteCatalogue.bundled()
 *     val now = sky.epoch
 *     val positions = sky.positionsEcef(now)   // km, one per satellite
 */

package afield.simulation.orbits

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPInputStream

/**
 * One catalogued object: its element set and its initialised propagator.
 *
 * @param tle the element set.
 */
class Satellite(val tle: Tle) {
    /** The propagator, initialised once. */
    val sgp4 = Sgp4(tle)

    /** The title line. */
    val name: String get() = tle.name

    /** Earth-fixed position at [time], km, or `null` if the model has broken down. */
    fun ecef(time: Instant): Xyz? = sgp4.propagate(time)?.let { Frames.temeToEcef(it.position, Frames.julianOf(time)) }

    /** Latitude, longitude and height at [time]. */
    fun geodetic(time: Instant): Geodetic? = ecef(time)?.let { Frames.geodetic(it) }

    /** True when the satellite is in sunlight at [time], false in the Earth's shadow, `null` if its model has broken down. */
    fun isSunlit(time: Instant): Boolean? = sgp4.propagate(time)?.let { Frames.isSunlit(it.position, Frames.julianOf(time)) }

    override fun toString() = "Satellite($name)"
}

/**
 * A list of [Satellite]s.
 *
 * @param satellites the objects.
 * @param skippedDeepSpace how many deep-space element sets were left out.
 * @param source where the element sets came from.
 */
class SatelliteCatalogue(val satellites: List<Satellite>, val skippedDeepSpace: Int = 0, val source: String = "") {
    /** Number of satellites. */
    val size: Int get() = satellites.size

    /**
     * A time at which the whole catalogue is fresh: the latest near-earth element-set epoch. Deep-space objects are
     * left out of it because CelesTrak publishes a few of their sets with predicted epochs days ahead (Chandra and
     * XMM-Newton in the bundled snapshot); a catalogue with no near-earth objects uses its latest epoch of all.
     */
    val epoch: Instant get() = Frames.instantOf(
        (satellites.filter { !it.tle.isDeepSpace }.ifEmpty { satellites }).maxOf { it.tle.epochJulian },
    )

    /** The first satellite whose name is [name] (case-insensitive). */
    fun byName(name: String): Satellite? = satellites.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Satellites whose name contains [fragment]. */
    fun search(fragment: String): List<Satellite> = satellites.filter { it.name.contains(fragment, ignoreCase = true) }

    /** Earth-fixed positions (km) of every satellite at [time]; a broken-down one is `null`. */
    fun positionsEcef(time: Instant): List<Xyz?> {
        val jd = Frames.julianOf(time)
        val g = Frames.gmst(jd)
        val c = kotlin.math.cos(g); val s = kotlin.math.sin(g)
        return satellites.map { sat ->
            sat.sgp4.propagate(sat.tle.minutesSinceEpoch(time))?.position?.let { p -> Xyz(c * p.x + s * p.y, -s * p.x + c * p.y, p.z) }
        }
    }

    /** Every satellite's [MeanElements], moved to the epoch [reference] so a shader's time can count minutes from it. */
    fun meanElementsAt(reference: Instant): List<MeanElements> =
        satellites.map { it.sgp4.meanElements.shifted(it.tle.minutesSinceEpoch(reference)) }

    companion object {
        /** The resource holding the bundled snapshot. */
        const val BUNDLED_RESOURCE = "/afield/orbits/celestrak_active_2026-11-11.tle.gz"

        private val cacheDir = File(System.getProperty("user.home"), ".afield/cache/celestrak")

        /** Builds a catalogue from TLE text; [includeDeepSpace] `false` keeps only the near-earth objects. */
        fun parse(text: String, source: String = "text", includeDeepSpace: Boolean = true): SatelliteCatalogue {
            val all = Tle.parseAll(text)
            val kept = if (includeDeepSpace) all else all.filter { !it.isDeepSpace }
            return SatelliteCatalogue(kept.map { Satellite(it) }, all.size - kept.size, source)
        }

        /** The snapshot of CelesTrak's "active" group bundled with Field; [includeDeepSpace] `false` keeps only the near-earth objects. */
        fun bundled(includeDeepSpace: Boolean = true): SatelliteCatalogue {
            val stream = SatelliteCatalogue::class.java.getResourceAsStream(BUNDLED_RESOURCE)
                ?: error("bundled TLE snapshot $BUNDLED_RESOURCE missing")
            val text = GZIPInputStream(stream).bufferedReader().use { it.readText() }
            return parse(text, "bundled CelesTrak active, 2026-11-11", includeDeepSpace)
        }

        /**
         * A CelesTrak group ("active", "stations", "visual", "starlink", ...), downloaded at most every [maxAge].
         *
         * A failed download falls back to the last cached copy, then to [bundled]. [includeDeepSpace] `false` keeps only
         * the near-earth objects.
         */
        fun fetch(group: String = "active", maxAge: Duration = Duration.ofHours(6), includeDeepSpace: Boolean = true): SatelliteCatalogue {
            val cache = File(cacheDir, "$group.tle")
            val fresh = cache.exists() && System.currentTimeMillis() - cache.lastModified() < maxAge.toMillis()
            if (!fresh) {
                runCatching {
                    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    val req = HttpRequest.newBuilder(URI("https://celestrak.org/NORAD/elements/gp.php?GROUP=$group&FORMAT=tle"))
                        .timeout(Duration.ofSeconds(30)).header("User-Agent", "Field4").build()
                    val res = client.send(req, HttpResponse.BodyHandlers.ofString())
                    check(res.statusCode() == 200 && res.body().contains("\n1 ")) { "CelesTrak answered ${res.statusCode()}" }
                    cacheDir.mkdirs()
                    cache.writeText(res.body())
                }
            }
            if (cache.exists()) return parse(cache.readText(), "CelesTrak $group, cached ${Instant.ofEpochMilli(cache.lastModified())}", includeDeepSpace)
            return bundled(includeDeepSpace)
        }
    }
}

/** The world's coastlines, for drawing a globe: Natural Earth's 1:110m coastline (public domain, naturalearthdata.com). */
object Coastlines {
    /** Each coastline as a polyline of points on the ellipsoid (height 0). */
    val lines: List<List<Geodetic>> by lazy {
        val text = Coastlines::class.java.getResourceAsStream("/afield/orbits/ne_110m_coastline.txt")!!.bufferedReader().use { it.readText() }
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            line.split(' ').map { p ->
                val (lon, lat) = p.split(',')
                Geodetic(lat.toDouble(), lon.toDouble(), 0.0)
            }
        }
    }
}
