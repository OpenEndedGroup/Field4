@file:afield.runtime.completion.CompletionCategory("Simulation")

/**
 * Two-line element sets and the SGP4 orbit propagator: where a catalogued satellite is, minutes from its epoch.
 *
 * A [Tle] is the two-line text that NORAD and CelesTrak publish for every tracked object. [Sgp4] turns one
 * into a position and velocity in the TEME frame (true equator, mean equinox, kilometres and km/s) at any
 * time, written after the reference code published with Vallado, Crawford, Hujsak and Kelso, *Revisiting
 * Spacetrack Report #3*, AIAA 2006-6753 (<https://celestrak.org/publications/AIAA/2006-6753/>), with the
 * WGS-72 constants and the "improved" operation mode:
 *
 *     import afield.simulation.orbits.*
 *
 *     val iss = Tle.parse(
 *         "1 25544U 98067A   26315.50000000  .00012000  00000+0  21000-3 0  9990",
 *         "2 25544  51.6300 120.0000 0004000  90.0000 270.0000 15.50000000000000",
 *     )
 *     val state = Sgp4(iss).propagate(minutesSinceEpoch = 90.0)
 *     println(state.position)   // km, TEME
 *
 * Only the near-earth branch is implemented: an orbit with a period of 225 minutes or more (GPS,
 * geostationary, Molniya) needs the deep-space SDP4 terms, and [Sgp4] refuses it with an
 * [IllegalArgumentException] rather than returning a wrong answer; [Tle.isDeepSpace] says which is which.
 * Of the CelesTrak "active" catalogue, about 95% of objects are near-earth.
 *
 * [Frames] turns TEME into Earth-fixed and geodetic coordinates and look angles from a place on the ground.
 */

package afield.simulation.orbits

import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One two-line element set, with its angles in radians and its mean motion in radians per minute, as SGP4 wants them.
 *
 * @param name the title line, or the catalogue number when the set came without one.
 * @param catalogNumber the NORAD catalogue number.
 * @param epochJulian the epoch as a UTC Julian date.
 * @param bstar the drag term, in inverse Earth radii.
 * @param inclination inclination, radians.
 * @param raan right ascension of the ascending node, radians.
 * @param eccentricity eccentricity.
 * @param argPerigee argument of perigee, radians.
 * @param meanAnomaly mean anomaly, radians.
 * @param meanMotion Kozai mean motion, radians per minute.
 */
data class Tle(
    val name: String,
    val catalogNumber: String,
    val epochJulian: Double,
    val bstar: Double,
    val inclination: Double,
    val raan: Double,
    val eccentricity: Double,
    val argPerigee: Double,
    val meanAnomaly: Double,
    val meanMotion: Double,
) {
    /** Orbital period in minutes, from the Kozai mean motion. */
    val periodMinutes: Double get() = 2 * PI / meanMotion

    /** True when the period is 225 minutes or more, the orbits for which SGP4 needs its deep-space terms. */
    val isDeepSpace: Boolean get() = periodMinutes >= 225.0

    /** The epoch as an [Instant]. */
    val epoch: Instant get() = Frames.instantOf(epochJulian)

    /** Minutes from the epoch to [time]. */
    fun minutesSinceEpoch(time: Instant): Double = (Frames.julianOf(time) - epochJulian) * 1440.0

    companion object {
        private const val XPDOTP = 1440.0 / (2.0 * PI)
        private const val DEG = PI / 180.0

        /** Parses one element set from its two lines, by fixed columns; anything past column 69 is ignored. */
        fun parse(line1: String, line2: String, name: String? = null): Tle {
            require(line1.startsWith("1") && line2.startsWith("2")) { "not a two-line element set:\n$line1\n$line2" }
            fun f(s: String) = s.trim().ifEmpty { "0" }.toDouble()
            fun col(l: String, a: Int, b: Int) = l.substring(a - 1, minOf(b, l.length))
            // "-11606-4" means -0.11606e-4: an implied leading decimal point and a signed exponent.
            fun implied(s: String): Double {
                val t = s.trim()
                if (t.isEmpty()) return 0.0
                val sign = if (t.startsWith("-")) -1.0 else 1.0
                val body = t.trimStart('-', '+')
                val e = body.lastIndexOfAny(charArrayOf('-', '+'))
                if (e <= 0) return sign * ("0.$body").toDouble()
                return sign * ("0." + body.substring(0, e)).toDouble() * 10.0.pow(body.substring(e).toDouble())
            }

            val catalog = col(line1, 3, 7).trim()
            val year2 = col(line1, 19, 20).trim().toInt()
            val days = f(col(line1, 21, 32))
            val year = if (year2 < 57) 2000 + year2 else 1900 + year2
            val bstar = implied(col(line1, 54, 61))
            return Tle(
                name = name?.trim()?.ifEmpty { null } ?: catalog,
                catalogNumber = catalog,
                epochJulian = Frames.julianDate(year, 1, 1) + days - 1.0,
                bstar = bstar,
                inclination = f(col(line2, 9, 16)) * DEG,
                raan = f(col(line2, 18, 25)) * DEG,
                eccentricity = ("0." + col(line2, 27, 33).trim()).toDouble(),
                argPerigee = f(col(line2, 35, 42)) * DEG,
                meanAnomaly = f(col(line2, 44, 51)) * DEG,
                meanMotion = f(col(line2, 53, 63)) / XPDOTP,
            )
        }

        /** Parses a whole file of two- or three-line sets (with or without name lines); malformed sets are skipped. */
        fun parseAll(text: String): List<Tle> {
            val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val out = ArrayList<Tle>()
            var i = 0
            var pendingName: String? = null
            while (i < lines.size) {
                val l = lines[i]
                if (l.startsWith("1 ") && i + 1 < lines.size && lines[i + 1].startsWith("2 ")) {
                    runCatching { parse(l, lines[i + 1], pendingName) }.onSuccess { out.add(it) }
                    pendingName = null
                    i += 2
                } else {
                    pendingName = l.removePrefix("0 ")
                    i++
                }
            }
            return out
        }
    }
}

/**
 * A satellite's state in the TEME frame.
 *
 * @param minutesSinceEpoch the time this state is for.
 * @param position kilometres.
 * @param velocity kilometres per second.
 */
data class TemeState(val minutesSinceEpoch: Double, val position: Xyz, val velocity: Xyz)

/**
 * The near-earth SGP4 propagator for one [Tle], initialised once and then evaluated at any time.
 *
 * [propagate] returns `null` when the model breaks down (the satellite has decayed, or the
 * eccentricity has left `[0, 1)`), which happens for real objects close to re-entry.
 *
 * @param tle the element set; must not be [Tle.isDeepSpace].
 */
class Sgp4(val tle: Tle) {
    private companion object {
        // WGS-72, as SGP4 was fitted with.
        const val MU = 398600.8
        const val RE = 6378.135
        val XKE = 60.0 / sqrt(RE * RE * RE / MU)
        const val J2 = 0.001082616
        const val J3 = -0.00000253881
        const val J4 = -0.00000165597
        const val J3OJ2 = J3 / J2
        const val X2O3 = 2.0 / 3.0
        const val TWOPI = 2.0 * PI
    }

    private val ecco = tle.eccentricity
    private val inclo = tle.inclination
    private val nodeo = tle.raan
    private val argpo = tle.argPerigee
    private val mo = tle.meanAnomaly
    private val bstar = tle.bstar

    private val noUnkozai: Double
    private var isimp = false
    private val con41: Double
    private val x1mth2: Double
    private val x7thm1: Double
    private val cc1: Double
    private val cc4: Double
    private val cc5: Double
    private val d2: Double
    private val d3: Double
    private val d4: Double
    private val delmo: Double
    private val eta: Double
    private val argpdot: Double
    private val omgcof: Double
    private val sinmao: Double
    private val t2cof: Double
    private val t3cof: Double
    private val t4cof: Double
    private val t5cof: Double
    private val xlcof: Double
    private val aycof: Double
    private val xmcof: Double
    private val mdot: Double
    private val nodedot: Double
    private val nodecf: Double

    init {
        require(!tle.isDeepSpace) {
            "${tle.name}: period ${"%.1f".format(tle.periodMinutes)} min needs the deep-space SDP4 terms, which Sgp4 does not implement"
        }
        // initl: recover the Brouwer mean motion from the Kozai one.
        val eccsq = ecco * ecco
        val omeosq = 1.0 - eccsq
        val rteosq = sqrt(omeosq)
        val cosio = cos(inclo)
        val cosio2 = cosio * cosio
        val ak = (XKE / tle.meanMotion).pow(X2O3)
        val d1 = 0.75 * J2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        val adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0))
        del = d1 / (adel * adel)
        noUnkozai = tle.meanMotion / (1.0 + del)
        val ao = (XKE / noUnkozai).pow(X2O3)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1.0 - 5.0 * cosio2
        con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1.0 - ecco)

        // sgp4init
        val ss = 78.0 / RE + 1.0
        val qzms2t = ((120.0 - 78.0) / RE).pow(4)
        if (rp < 220.0 / RE + 1.0) isimp = true
        var sfour = ss
        var qzms24 = qzms2t
        val perige = (rp - 1.0) * RE
        if (perige < 156.0) {
            sfour = perige - 78.0
            if (perige < 98.0) sfour = 20.0
            qzms24 = ((120.0 - sfour) / RE).pow(4)
            sfour = sfour / RE + 1.0
        }
        val pinvsq = 1.0 / posq
        val tsi = 1.0 / (ao - sfour)
        eta = ao * ecco * tsi
        val etasq = eta * eta
        val eeta = ecco * eta
        val psisq = abs(1.0 - etasq)
        val coef = qzms24 * tsi.pow(4)
        val coef1 = coef / psisq.pow(3.5)
        val cc2 = coef1 * noUnkozai * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
            0.375 * J2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)))
        cc1 = bstar * cc2
        val cc3 = if (ecco > 1.0e-4) -2.0 * coef * tsi * J3OJ2 * noUnkozai * sinio / ecco else 0.0
        x1mth2 = 1.0 - cosio2
        cc4 = 2.0 * noUnkozai * coef1 * ao * omeosq * (eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq) -
            J2 * tsi / (ao * psisq) * (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo)))
        cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
        val cosio4 = cosio2 * cosio2
        val temp1 = 1.5 * J2 * pinvsq * noUnkozai
        val temp2 = 0.5 * temp1 * J2 * pinvsq
        val temp3 = -0.46875 * J4 * pinvsq * pinvsq * noUnkozai
        mdot = noUnkozai + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
        argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
            temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
        val xhdot1 = -temp1 * cosio
        nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
        omgcof = bstar * cc3 * cos(argpo)
        xmcof = if (ecco > 1.0e-4) -X2O3 * coef * bstar / eeta else 0.0
        nodecf = 3.5 * omeosq * xhdot1 * cc1
        t2cof = 1.5 * cc1
        xlcof = if (abs(cosio + 1.0) > 1.5e-12) -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
        else -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / 1.5e-12
        aycof = -0.5 * J3OJ2 * sinio
        delmo = (1.0 + eta * cos(mo)).pow(3)
        sinmao = sin(mo)
        x7thm1 = 7.0 * cosio2 - 1.0
        if (!isimp) {
            val cc1sq = cc1 * cc1
            d2 = 4.0 * ao * tsi * cc1sq
            val temp = d2 * tsi * cc1 / 3.0
            d3 = (17.0 * ao + sfour) * temp
            d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1
            t3cof = d2 + 2.0 * cc1sq
            t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq))
            t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq))
        } else {
            d2 = 0.0; d3 = 0.0; d4 = 0.0; t3cof = 0.0; t4cof = 0.0; t5cof = 0.0
        }
    }

    /** The state at [time]. */
    fun propagate(time: Instant): TemeState? = propagate(tle.minutesSinceEpoch(time))

    /** The state [minutesSinceEpoch] minutes after the element set's epoch (negative goes back), or `null` if the model has broken down. */
    fun propagate(minutesSinceEpoch: Double): TemeState? {
        val t = minutesSinceEpoch
        val xmdf = mo + mdot * t
        val argpdf = argpo + argpdot * t
        val nodedf = nodeo + nodedot * t
        var argpm = argpdf
        var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1.0 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2
        if (!isimp) {
            val delomg = omgcof * t
            val delm = xmcof * ((1.0 + eta * cos(xmdf)).pow(3) - delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * t
            val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ += t3cof * t3 + t4 * (t4cof + t * t5cof)
        }
        if (tempa <= 0.0) return null
        val am = (XKE / noUnkozai).pow(X2O3) * tempa * tempa
        val nm = XKE / am.pow(1.5)
        var em = ecco - tempe
        if (em >= 1.0 || em < -0.001) return null
        if (em < 1.0e-6) em = 1.0e-6
        mm += noUnkozai * templ
        var xlm = mm + argpm + nodem
        nodem = nodem.rem(TWOPI)
        argpm = argpm.rem(TWOPI)
        xlm = xlm.rem(TWOPI)
        mm = (xlm - argpm - nodem).rem(TWOPI)
        val sinip = sin(tle.inclination)
        val cosip = cos(tle.inclination)

        // Long-period periodics.
        val axnl = em * cos(argpm)
        var temp = 1.0 / (am * (1.0 - em * em))
        val aynl = em * sin(argpm) + temp * aycof
        val xl = mm + argpm + nodem + temp * xlcof * axnl

        // Kepler's equation in the form SGP4 solves it.
        val u = (xl - nodem).rem(TWOPI)
        var eo1 = u
        var tem5 = 9999.9
        var ktr = 1
        var sineo1 = 0.0
        var coseo1 = 0.0
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
            eo1 += tem5
            ktr++
        }

        // Short-period periodics.
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1.0 - el2)
        if (pl < 0.0) return null
        val rl = am * (1.0 - ecose)
        val rdotl = sqrt(am) * esine / rl
        val rvdotl = sqrt(pl) / rl
        val betal = sqrt(1.0 - el2)
        temp = esine / (1.0 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1.0 - 2.0 * sinu * sinu
        temp = 1.0 / pl
        val temp1 = 0.5 * J2 * temp
        val temp2 = temp1 * temp
        val mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodem + 1.5 * temp2 * cosip * sin2u
        val xinc = tle.inclination + 1.5 * temp2 * cosip * sinip * cos2u
        val mvt = rdotl - nm * temp1 * x1mth2 * sin2u / XKE
        val rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / XKE

        val sinsu = sin(su); val cossu = cos(su)
        val snod = sin(xnode); val cnod = cos(xnode)
        val sini = sin(xinc); val cosi = cos(xinc)
        val xmx = -snod * cosi
        val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu
        val vx = xmx * cossu - cnod * sinsu
        val vy = xmy * cossu - snod * sinsu
        val vz = sini * cossu
        if (mrt < 1.0) return null // below the surface: decayed
        val vk = RE * XKE / 60.0
        return TemeState(
            t,
            Xyz(mrt * ux * RE, mrt * uy * RE, mrt * uz * RE),
            Xyz((mvt * ux + rvdot * vx) * vk, (mvt * uy + rvdot * vy) * vk, (mvt * uz + rvdot * vz) * vk),
        )
    }
}

/**
 * A double-precision 3-vector for orbit work: a `Vec3` is single precision, about half a metre at orbital radius.
 *
 * @param x first component.
 * @param y second component.
 * @param z third component.
 */
data class Xyz(val x: Double, val y: Double, val z: Double) {
    /** Euclidean length. */
    val length: Double get() = sqrt(x * x + y * y + z * z)

    /** Component-wise difference. */
    operator fun minus(o: Xyz) = Xyz(x - o.x, y - o.y, z - o.z)

    /** Scales every component. */
    operator fun times(s: Double) = Xyz(x * s, y * s, z * s)

    /** Single-precision copy, for drawing. */
    fun toVec3(): afield.math.linalg.Vec3 = afield.math.linalg.Vec3(x.toFloat(), y.toFloat(), z.toFloat())
}
