@file:afield.runtime.completion.CompletionCategory("Simulation")

/**
 * From SGP4's TEME frame to the ground: Earth-fixed coordinates, latitude and longitude, look angles, and passes.
 *
 * [Frames.temeToEcef] turns a TEME position by Greenwich mean sidereal time (polar motion is ignored, a few
 * metres); [Frames.geodetic] gives WGS-84 latitude, longitude and height; an [Observer] at a place on the
 * ground gives azimuth, elevation and range to a satellite, and [Observer.passes] finds every interval in
 * which a satellite is above a minimum elevation:
 *
 *     import afield.simulation.orbits.*
 *     import java.time.*
 *
 *     val iss = SatelliteCatalogue.bundled().byName("ISS (ZARYA)")!!
 *     val here = Observer(latitudeDeg = 41.88, longitudeDeg = -87.63)
 *     val start = iss.tle.epoch
 *     for (p in here.passes(iss, start, start.plus(Duration.ofDays(7)), minElevationDeg = 10.0))
 *         println("${p.rise}  max ${p.maxElevationDeg.f0}°")
 */

package afield.simulation.orbits

import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Time scales and frame rotations for [Sgp4] output. */
object Frames {
    private const val DEG = PI / 180.0
    private const val J2000_UNIX_JD = 2440587.5

    /** WGS-84 equatorial radius, km. */
    const val EARTH_RADIUS_KM = 6378.137
    private const val WGS84_F = 1.0 / 298.257223563

    /** Julian date at 0h UTC of a calendar day (Gregorian, valid 1900–2100). */
    fun julianDate(year: Int, month: Int, day: Int): Double =
        367.0 * year - floor(7.0 * (year + floor((month + 9) / 12.0)) * 0.25) + floor(275.0 * month / 9.0) + day + 1721013.5

    /** The Julian date of [time]. */
    fun julianOf(time: Instant): Double = J2000_UNIX_JD + (time.epochSecond + time.nano * 1e-9) / 86400.0

    /** The [Instant] of a Julian date, to the microsecond. */
    fun instantOf(julian: Double): Instant {
        val micros = Math.round((julian - J2000_UNIX_JD) * 86400.0e6)
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L)
    }

    /** Greenwich mean sidereal time in radians, IAU 1982 (the form the SGP4 reference code uses). */
    fun gmst(julian: Double): Double {
        val t = (julian - 2451545.0) / 36525.0
        var temp = -6.2e-6 * t * t * t + 0.093104 * t * t + (876600.0 * 3600 + 8640184.812866) * t + 67310.54841
        temp = (temp * DEG / 240.0).rem(2 * PI)
        return if (temp < 0) temp + 2 * PI else temp
    }

    /** A TEME vector at [julian] in the Earth-fixed frame (rotation by GMST; polar motion ignored). */
    fun temeToEcef(teme: Xyz, julian: Double): Xyz {
        val g = gmst(julian)
        val c = cos(g); val s = sin(g)
        return Xyz(c * teme.x + s * teme.y, -s * teme.x + c * teme.y, teme.z)
    }

    /** WGS-84 latitude and longitude (degrees) and height (km) of an Earth-fixed point, by Bowring's iteration. */
    fun geodetic(ecef: Xyz): Geodetic {
        val e2 = WGS84_F * (2 - WGS84_F)
        val p = sqrt(ecef.x * ecef.x + ecef.y * ecef.y)
        val lon = atan2(ecef.y, ecef.x)
        var lat = atan2(ecef.z, p * (1 - e2))
        var h = 0.0
        repeat(6) {
            val sl = sin(lat)
            val n = EARTH_RADIUS_KM / sqrt(1 - e2 * sl * sl)
            h = p / cos(lat) - n
            lat = atan2(ecef.z, p * (1 - e2 * n / (n + h)))
        }
        return Geodetic(lat / DEG, lon / DEG, h)
    }

    /** The Earth-fixed position of a place on the WGS-84 ellipsoid. */
    fun ecefOf(latitudeDeg: Double, longitudeDeg: Double, heightKm: Double = 0.0): Xyz {
        val e2 = WGS84_F * (2 - WGS84_F)
        val lat = latitudeDeg * DEG; val lon = longitudeDeg * DEG
        val n = EARTH_RADIUS_KM / sqrt(1 - e2 * sin(lat) * sin(lat))
        return Xyz((n + heightKm) * cos(lat) * cos(lon), (n + heightKm) * cos(lat) * sin(lon), (n * (1 - e2) + heightKm) * sin(lat))
    }
}

/**
 * A point given as WGS-84 latitude, longitude and height.
 *
 * @param latitudeDeg degrees north.
 * @param longitudeDeg degrees east, in (-180, 180].
 * @param heightKm height above the ellipsoid, km.
 */
data class Geodetic(val latitudeDeg: Double, val longitudeDeg: Double, val heightKm: Double)

/**
 * Where a satellite is in the sky.
 *
 * @param azimuthDeg degrees clockwise from north.
 * @param elevationDeg degrees above the horizon (negative below).
 * @param rangeKm distance, km.
 */
data class LookAngles(val azimuthDeg: Double, val elevationDeg: Double, val rangeKm: Double)

/**
 * One interval in which a satellite is above an [Observer]'s minimum elevation.
 *
 * @param rise when it climbs past the minimum elevation.
 * @param set when it drops below it again.
 * @param culmination the moment of greatest elevation.
 * @param maxElevationDeg that greatest elevation.
 */
data class Pass(val rise: Instant, val set: Instant, val culmination: Instant, val maxElevationDeg: Double) {
    /** How long it is above the minimum elevation. */
    val duration: Duration get() = Duration.between(rise, set)
}

/**
 * A place on the ground, for look angles and passes.
 *
 * @param latitudeDeg degrees north.
 * @param longitudeDeg degrees east.
 * @param heightKm height above the WGS-84 ellipsoid, km.
 */
data class Observer(val latitudeDeg: Double, val longitudeDeg: Double, val heightKm: Double = 0.0) {
    private val site = Frames.ecefOf(latitudeDeg, longitudeDeg, heightKm)
    private val lat = Math.toRadians(latitudeDeg)
    private val lon = Math.toRadians(longitudeDeg)

    /** Look angles to an Earth-fixed point. */
    fun look(ecef: Xyz): LookAngles {
        val d = ecef - site
        val sl = sin(lat); val cl = cos(lat); val so = sin(lon); val co = cos(lon)
        val east = -so * d.x + co * d.y
        val north = -sl * co * d.x - sl * so * d.y + cl * d.z
        val up = cl * co * d.x + cl * so * d.y + sl * d.z
        val r = d.length
        var az = Math.toDegrees(atan2(east, north))
        if (az < 0) az += 360.0
        return LookAngles(az, Math.toDegrees(asin(up / r)), r)
    }

    /** Look angles to [satellite] at [time], or `null` if its model has broken down. */
    fun look(satellite: Satellite, time: Instant): LookAngles? {
        val s = satellite.sgp4.propagate(time) ?: return null
        return look(Frames.temeToEcef(s.position, Frames.julianOf(time)))
    }

    /**
     * Every pass of [satellite] above [minElevationDeg] between [from] and [to].
     *
     * Samples every [step] (30 s by default, short against a low-orbit pass of several minutes) and refines
     * each rise and set by bisection to about a second.
     */
    fun passes(
        satellite: Satellite, from: Instant, to: Instant, minElevationDeg: Double = 10.0, step: Duration = Duration.ofSeconds(30),
    ): List<Pass> {
        fun el(t: Instant) = look(satellite, t)?.elevationDeg ?: -90.0
        fun edge(a0: Instant, b0: Instant): Instant {
            var a = a0; var b = b0
            val aboveA = el(a) >= minElevationDeg
            while (Duration.between(a, b).toMillis() > 1000) {
                val m = a.plusMillis(Duration.between(a, b).toMillis() / 2)
                if ((el(m) >= minElevationDeg) == aboveA) a = m else b = m
            }
            return b
        }
        val out = ArrayList<Pass>()
        var t = from
        val first = el(t)
        var rise: Instant? = if (first >= minElevationDeg) from else null
        var best = first; var bestT = from
        while (t < to) {
            val n = t.plus(step)
            val e = el(n)
            if (rise == null && e >= minElevationDeg) {
                rise = edge(t, n); best = e; bestT = n
            } else if (rise != null) {
                if (e > best) { best = e; bestT = n }
                if (e < minElevationDeg) {
                    out.add(Pass(rise, edge(t, n), bestT, best))
                    rise = null
                }
            }
            t = n
        }
        return out
    }
}

/**
 * A running clock for animating the sky: simulated time starting at [reference], running [speed] times real time.
 *
 * Read [minutes] (since [reference]) and [gmst] every frame and hand them to a shader; [rate] changes the speed (0 stops it).
 *
 * @param reference the simulated time at which the clock starts.
 * @param speed simulated seconds per real second.
 */
class SkyClock(val reference: Instant, speed: Double = 60.0) {
    /** Simulated seconds per real second; change it with [rate]. */
    var speed: Double = speed
        private set
    private var realStart = System.nanoTime()
    private var simAtStart = 0.0

    /** Simulated minutes since [reference]. */
    fun minutes(): Double = simAtStart + (System.nanoTime() - realStart) / 6.0e10 * speed

    /** The simulated time now. */
    fun instant(): Instant = reference.plusNanos((minutes() * 6.0e10).toLong())

    /** Greenwich mean sidereal time at the simulated time, radians. */
    fun gmst(): Double = Frames.gmst(Frames.julianOf(reference) + minutes() / 1440.0)

    /** Changes the rate without a jump in simulated time. */
    fun rate(newSpeed: Double) {
        simAtStart = minutes(); realStart = System.nanoTime(); speed = newSpeed
    }
}
