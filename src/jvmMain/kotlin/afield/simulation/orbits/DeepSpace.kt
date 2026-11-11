/**
 * The deep-space (SDP4) terms of SGP4: lunar and solar gravity, and the resonances of 12- and 24-hour orbits.
 *
 * [Sgp4] uses this for every element set whose period is 225 minutes or more (GPS, Galileo, geostationary,
 * Molniya and high transfer orbits). Written after `dscom`, `dpper`, `dsinit` and `dspace` in the reference
 * code published with Vallado, Crawford, Hujsak and Kelso, *Revisiting Spacetrack Report #3*, AIAA 2006-6753,
 * in its "improved" operation mode, and checked against the deep-space cases of that paper's verification
 * file in `Sgp4Test`.
 *
 * The reference code keeps the resonance integrator's last step in the satellite record and restarts from
 * there; this one integrates from the epoch on every call, which gives the same numbers (the steps fall on
 * the same 720-minute grid) and keeps [Sgp4] free of mutable state, so one propagator can serve several
 * threads.
 */

package afield.simulation.orbits

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean elements in the middle of a deep-space step: what [DeepSpace.secular] and [DeepSpace.periodics] update. */
internal class DeepElements(
    var em: Double,
    var argpm: Double,
    var inclm: Double,
    var mm: Double,
    var nodem: Double,
    var nm: Double,
)

/**
 * Lunar-solar and resonance coefficients for one deep-space element set, computed once.
 */
internal class DeepSpace(
    epochDays1950: Double,
    ecco: Double,
    inclo: Double,
    nodeo: Double,
    private val argpo: Double,
    mo: Double,
    private val no: Double,
    mdot: Double,
    nodedot: Double,
    private val argpdot: Double,
    xke: Double,
    private val gsto: Double,
) {
    private companion object {
        const val TWOPI = 2.0 * PI
        const val ZES = 0.01675
        const val ZEL = 0.05490
        const val ZNS = 1.19459e-5
        const val ZNL = 1.5835218e-4
        const val RPTIM = 4.37526908801129966e-3 // Earth's rotation, radians per minute
    }

    // dscom: solar and lunar periodic coefficients
    private val e3: Double; private val ee2: Double
    private val se2: Double; private val se3: Double
    private val sgh2: Double; private val sgh3: Double; private val sgh4: Double
    private val sh2: Double; private val sh3: Double
    private val si2: Double; private val si3: Double
    private val sl2: Double; private val sl3: Double; private val sl4: Double
    private val xgh2: Double; private val xgh3: Double; private val xgh4: Double
    private val xh2: Double; private val xh3: Double
    private val xi2: Double; private val xi3: Double
    private val xl2: Double; private val xl3: Double; private val xl4: Double
    private val zmol: Double; private val zmos: Double

    // dsinit: secular rates and resonance terms
    /** 0 none, 1 one-day (geosynchronous) resonance, 2 half-day (Molniya, GPS-like with e >= 0.5). */
    val irez: Int
    val dedt: Double; val didt: Double; val dmdt: Double; val dnodt: Double; val domdt: Double
    private var d2201 = 0.0; private var d2211 = 0.0; private var d3210 = 0.0; private var d3222 = 0.0
    private var d4410 = 0.0; private var d4422 = 0.0; private var d5220 = 0.0; private var d5232 = 0.0
    private var d5421 = 0.0; private var d5433 = 0.0
    private var del1 = 0.0; private var del2 = 0.0; private var del3 = 0.0
    private var xfact = 0.0; private var xlamo = 0.0

    init {
        // ---- dscom ----
        val c1ss = 2.9864797e-6
        val c1l = 4.7968065e-7
        val zsinis = 0.39785416
        val zcosis = 0.91744867
        val zcosgs = 0.1945905
        val zsings = -0.98088458

        val nm = no
        val em = ecco
        val snodm = sin(nodeo); val cnodm = cos(nodeo)
        val sinomm = sin(argpo); val cosomm = cos(argpo)
        val sinim = sin(inclo); val cosim = cos(inclo)
        val emsq = em * em
        val betasq = 1.0 - emsq
        val rtemsq = sqrt(betasq)

        val day = epochDays1950 + 18261.5
        val xnodce = (4.5236020 - 9.2422029e-4 * day).rem(TWOPI)
        val stem = sin(xnodce); val ctem = cos(xnodce)
        val zcosil = 0.91375164 - 0.03568096 * ctem
        val zsinil = sqrt(1.0 - zcosil * zcosil)
        val zsinhl = 0.089683511 * stem / zsinil
        val zcoshl = sqrt(1.0 - zsinhl * zsinhl)
        val gam = 5.8351514 + 0.0019443680 * day
        var zx = 0.39785416 * stem / zsinil
        val zy = zcoshl * ctem + 0.91744867 * zsinhl * stem
        zx = atan2(zx, zy)
        zx = gam + zx - xnodce
        val zcosgl = cos(zx); val zsingl = sin(zx)

        var zcosg = zcosgs; var zsing = zsings; var zcosi = zcosis; var zsini = zsinis
        var zcosh = cnodm; var zsinh = snodm
        var cc = c1ss
        val xnoi = 1.0 / nm

        // the first pass is the sun's, the second the moon's
        var ss1 = 0.0; var ss2 = 0.0; var ss3 = 0.0; var ss4 = 0.0; var ss5 = 0.0; var ss6 = 0.0; var ss7 = 0.0
        var sz1 = 0.0; var sz2 = 0.0; var sz3 = 0.0; var sz11 = 0.0; var sz12 = 0.0; var sz13 = 0.0
        var sz21 = 0.0; var sz22 = 0.0; var sz23 = 0.0; var sz31 = 0.0; var sz32 = 0.0; var sz33 = 0.0
        var s1 = 0.0; var s2 = 0.0; var s3 = 0.0; var s4 = 0.0; var s5 = 0.0; var s6 = 0.0; var s7 = 0.0
        var z1 = 0.0; var z2 = 0.0; var z3 = 0.0; var z11 = 0.0; var z12 = 0.0; var z13 = 0.0
        var z21 = 0.0; var z22 = 0.0; var z23 = 0.0; var z31 = 0.0; var z32 = 0.0; var z33 = 0.0
        for (lsflg in 1..2) {
            val a1 = zcosg * zcosh + zsing * zcosi * zsinh
            val a3 = -zsing * zcosh + zcosg * zcosi * zsinh
            val a7 = -zcosg * zsinh + zsing * zcosi * zcosh
            val a8 = zsing * zsini
            val a9 = zsing * zsinh + zcosg * zcosi * zcosh
            val a10 = zcosg * zsini
            val a2 = cosim * a7 + sinim * a8
            val a4 = cosim * a9 + sinim * a10
            val a5 = -sinim * a7 + cosim * a8
            val a6 = -sinim * a9 + cosim * a10

            val x1 = a1 * cosomm + a2 * sinomm
            val x2 = a3 * cosomm + a4 * sinomm
            val x3 = -a1 * sinomm + a2 * cosomm
            val x4 = -a3 * sinomm + a4 * cosomm
            val x5 = a5 * sinomm
            val x6 = a6 * sinomm
            val x7 = a5 * cosomm
            val x8 = a6 * cosomm

            z31 = 12.0 * x1 * x1 - 3.0 * x3 * x3
            z32 = 24.0 * x1 * x2 - 6.0 * x3 * x4
            z33 = 12.0 * x2 * x2 - 3.0 * x4 * x4
            z1 = 3.0 * (a1 * a1 + a2 * a2) + z31 * emsq
            z2 = 6.0 * (a1 * a3 + a2 * a4) + z32 * emsq
            z3 = 3.0 * (a3 * a3 + a4 * a4) + z33 * emsq
            z11 = -6.0 * a1 * a5 + emsq * (-24.0 * x1 * x7 - 6.0 * x3 * x5)
            z12 = -6.0 * (a1 * a6 + a3 * a5) + emsq * (-24.0 * (x2 * x7 + x1 * x8) - 6.0 * (x3 * x6 + x4 * x5))
            z13 = -6.0 * a3 * a6 + emsq * (-24.0 * x2 * x8 - 6.0 * x4 * x6)
            z21 = 6.0 * a2 * a5 + emsq * (24.0 * x1 * x5 - 6.0 * x3 * x7)
            z22 = 6.0 * (a4 * a5 + a2 * a6) + emsq * (24.0 * (x2 * x5 + x1 * x6) - 6.0 * (x4 * x7 + x3 * x8))
            z23 = 6.0 * a4 * a6 + emsq * (24.0 * x2 * x6 - 6.0 * x4 * x8)
            z1 = z1 + z1 + betasq * z31
            z2 = z2 + z2 + betasq * z32
            z3 = z3 + z3 + betasq * z33
            s3 = cc * xnoi
            s2 = -0.5 * s3 / rtemsq
            s4 = s3 * rtemsq
            s1 = -15.0 * em * s4
            s5 = x1 * x3 + x2 * x4
            s6 = x2 * x3 + x1 * x4
            s7 = x2 * x4 - x1 * x3

            if (lsflg == 1) {
                ss1 = s1; ss2 = s2; ss3 = s3; ss4 = s4; ss5 = s5; ss6 = s6; ss7 = s7
                sz1 = z1; sz2 = z2; sz3 = z3
                sz11 = z11; sz12 = z12; sz13 = z13
                sz21 = z21; sz22 = z22; sz23 = z23
                sz31 = z31; sz32 = z32; sz33 = z33
                zcosg = zcosgl; zsing = zsingl; zcosi = zcosil; zsini = zsinil
                zcosh = zcoshl * cnodm + zsinhl * snodm
                zsinh = snodm * zcoshl - cnodm * zsinhl
                cc = c1l
            }
        }

        zmol = (4.7199672 + 0.22997150 * day - gam).rem(TWOPI)
        zmos = (6.2565837 + 0.017201977 * day).rem(TWOPI)

        se2 = 2.0 * ss1 * ss6
        se3 = 2.0 * ss1 * ss7
        si2 = 2.0 * ss2 * sz12
        si3 = 2.0 * ss2 * (sz13 - sz11)
        sl2 = -2.0 * ss3 * sz2
        sl3 = -2.0 * ss3 * (sz3 - sz1)
        sl4 = -2.0 * ss3 * (-21.0 - 9.0 * emsq) * ZES
        sgh2 = 2.0 * ss4 * sz32
        sgh3 = 2.0 * ss4 * (sz33 - sz31)
        sgh4 = -18.0 * ss4 * ZES
        sh2 = -2.0 * ss2 * sz22
        sh3 = -2.0 * ss2 * (sz23 - sz21)
        ee2 = 2.0 * s1 * s6
        e3 = 2.0 * s1 * s7
        xi2 = 2.0 * s2 * z12
        xi3 = 2.0 * s2 * (z13 - z11)
        xl2 = -2.0 * s3 * z2
        xl3 = -2.0 * s3 * (z3 - z1)
        xl4 = -2.0 * s3 * (-21.0 - 9.0 * emsq) * ZEL
        xgh2 = 2.0 * s4 * z32
        xgh3 = 2.0 * s4 * (z33 - z31)
        xgh4 = -18.0 * s4 * ZEL
        xh2 = -2.0 * s2 * z22
        xh3 = -2.0 * s2 * (z23 - z21)

        // ---- dsinit ----
        val q22 = 1.7891679e-6
        val q31 = 2.1460748e-6
        val q33 = 2.2123015e-7
        val root22 = 1.7891679e-6
        val root44 = 7.3636953e-9
        val root54 = 2.1765803e-9
        val root32 = 3.7393792e-7
        val root52 = 1.1428639e-7

        irez = when {
            nm > 0.0034906585 && nm < 0.0052359877 -> 1
            nm >= 8.26e-3 && nm <= 9.24e-3 && em >= 0.5 -> 2
            else -> 0
        }

        val nearEquatorial = inclo < 5.2359877e-2 || inclo > PI - 5.2359877e-2
        val ses = ss1 * ZNS * ss5
        val sis = ss2 * ZNS * (sz11 + sz13)
        val sls = -ZNS * ss3 * (sz1 + sz3 - 14.0 - 6.0 * emsq)
        val sghs = ss4 * ZNS * (sz31 + sz33 - 6.0)
        var shs = -ZNS * ss2 * (sz21 + sz23)
        if (nearEquatorial) shs = 0.0
        if (sinim != 0.0) shs /= sinim
        val sgs = sghs - cosim * shs

        dedt = ses + s1 * ZNL * s5
        didt = sis + s2 * ZNL * (z11 + z13)
        dmdt = sls - ZNL * s3 * (z1 + z3 - 14.0 - 6.0 * emsq)
        val sghl = s4 * ZNL * (z31 + z33 - 6.0)
        var shll = -ZNL * s2 * (z21 + z23)
        if (nearEquatorial) shll = 0.0
        var domdt0 = sgs + sghl
        var dnodt0 = shs
        if (sinim != 0.0) {
            domdt0 -= cosim / sinim * shll
            dnodt0 += shll / sinim
        }
        domdt = domdt0
        dnodt = dnodt0

        val theta = gsto.rem(TWOPI)
        if (irez != 0) {
            val aonv = (nm / xke).pow(2.0 / 3.0)
            if (irez == 2) {
                val cosisq = cosim * cosim
                val e = ecco
                val esq = ecco * ecco
                val eoc = e * esq
                val g201 = -0.306 - (e - 0.64) * 0.440
                val g211: Double; val g310: Double; val g322: Double; val g410: Double; val g422: Double; val g520: Double
                if (e <= 0.65) {
                    g211 = 3.616 - 13.2470 * e + 16.2900 * esq
                    g310 = -19.302 + 117.3900 * e - 228.4190 * esq + 156.5910 * eoc
                    g322 = -18.9068 + 109.7927 * e - 214.6334 * esq + 146.5816 * eoc
                    g410 = -41.122 + 242.6940 * e - 471.0940 * esq + 313.9530 * eoc
                    g422 = -146.407 + 841.8800 * e - 1629.014 * esq + 1083.4350 * eoc
                    g520 = -532.114 + 3017.977 * e - 5740.032 * esq + 3708.2760 * eoc
                } else {
                    g211 = -72.099 + 331.819 * e - 508.738 * esq + 266.724 * eoc
                    g310 = -346.844 + 1582.851 * e - 2415.925 * esq + 1246.113 * eoc
                    g322 = -342.585 + 1554.908 * e - 2366.899 * esq + 1215.972 * eoc
                    g410 = -1052.797 + 4758.686 * e - 7193.992 * esq + 3651.957 * eoc
                    g422 = -3581.690 + 16178.110 * e - 24462.770 * esq + 12422.520 * eoc
                    g520 = if (e > 0.715) -5149.66 + 29936.92 * e - 54087.36 * esq + 31324.56 * eoc
                    else 1464.74 - 4664.75 * e + 3763.64 * esq
                }
                val g533: Double; val g521: Double; val g532: Double
                if (e < 0.7) {
                    g533 = -919.22770 + 4988.6100 * e - 9064.7700 * esq + 5542.21 * eoc
                    g521 = -822.71072 + 4568.6173 * e - 8491.4146 * esq + 5337.524 * eoc
                    g532 = -853.66600 + 4690.2500 * e - 8624.7700 * esq + 5341.4 * eoc
                } else {
                    g533 = -37995.780 + 161616.52 * e - 229838.20 * esq + 109377.94 * eoc
                    g521 = -51752.104 + 218913.95 * e - 309468.16 * esq + 146349.42 * eoc
                    g532 = -40023.880 + 170470.89 * e - 242699.48 * esq + 115605.82 * eoc
                }
                val sini2 = sinim * sinim
                val f220 = 0.75 * (1.0 + 2.0 * cosim + cosisq)
                val f221 = 1.5 * sini2
                val f321 = 1.875 * sinim * (1.0 - 2.0 * cosim - 3.0 * cosisq)
                val f322 = -1.875 * sinim * (1.0 + 2.0 * cosim - 3.0 * cosisq)
                val f441 = 35.0 * sini2 * f220
                val f442 = 39.3750 * sini2 * sini2
                val f522 = 9.84375 * sinim * (sini2 * (1.0 - 2.0 * cosim - 5.0 * cosisq) +
                    0.33333333 * (-2.0 + 4.0 * cosim + 6.0 * cosisq))
                val f523 = sinim * (4.92187512 * sini2 * (-2.0 - 4.0 * cosim + 10.0 * cosisq) +
                    6.56250012 * (1.0 + 2.0 * cosim - 3.0 * cosisq))
                val f542 = 29.53125 * sinim * (2.0 - 8.0 * cosim + cosisq * (-12.0 + 8.0 * cosim + 10.0 * cosisq))
                val f543 = 29.53125 * sinim * (-2.0 - 8.0 * cosim + cosisq * (12.0 + 8.0 * cosim - 10.0 * cosisq))
                val xno2 = nm * nm
                val ainv2 = aonv * aonv
                var temp1 = 3.0 * xno2 * ainv2
                var temp = temp1 * root22
                d2201 = temp * f220 * g201
                d2211 = temp * f221 * g211
                temp1 *= aonv
                temp = temp1 * root32
                d3210 = temp * f321 * g310
                d3222 = temp * f322 * g322
                temp1 *= aonv
                temp = 2.0 * temp1 * root44
                d4410 = temp * f441 * g410
                d4422 = temp * f442 * g422
                temp1 *= aonv
                temp = temp1 * root52
                d5220 = temp * f522 * g520
                d5232 = temp * f523 * g532
                temp = 2.0 * temp1 * root54
                d5421 = temp * f542 * g521
                d5433 = temp * f543 * g533
                xlamo = (mo + nodeo + nodeo - theta - theta).rem(TWOPI)
                xfact = mdot + dmdt + 2.0 * (nodedot + dnodt - RPTIM) - no
            } else {
                val g200 = 1.0 + emsq * (-2.5 + 0.8125 * emsq)
                val g310 = 1.0 + 2.0 * emsq
                val g300 = 1.0 + emsq * (-6.0 + 6.60937 * emsq)
                val f220 = 0.75 * (1.0 + cosim) * (1.0 + cosim)
                val f311 = 0.9375 * sinim * sinim * (1.0 + 3.0 * cosim) - 0.75 * (1.0 + cosim)
                var f330 = 1.0 + cosim
                f330 = 1.875 * f330 * f330 * f330
                del1 = 3.0 * nm * nm * aonv * aonv
                del2 = 2.0 * del1 * f220 * g200 * q22
                del3 = 3.0 * del1 * f330 * g300 * q33 * aonv
                del1 = del1 * f311 * g310 * q31 * aonv
                xlamo = (mo + nodeo + argpo - theta).rem(TWOPI)
                xfact = mdot + (argpdot + nodedot) - RPTIM + dmdt + domdt + dnodt - no
            }
        }
    }

    /**
     * dspace: lunar-solar secular drift and, for resonant orbits, the integrated mean motion, [t] minutes from epoch.
     * Updates [el] in place (its [DeepElements.nm] becomes the resonance-corrected mean motion).
     */
    fun secular(t: Double, el: DeepElements) {
        val fasx2 = 0.13130908
        val fasx4 = 2.8843198
        val fasx6 = 0.37448087
        val g22 = 5.7686396
        val g32 = 0.95240898
        val g44 = 1.8014998
        val g52 = 1.0508330
        val g54 = 4.4108898
        val stepp = 720.0
        val step2 = 259200.0

        val theta = (gsto + t * RPTIM).rem(TWOPI)
        el.em += dedt * t
        el.inclm += didt * t
        el.argpm += domdt * t
        el.nodem += dnodt * t
        el.mm += dmdt * t
        if (irez == 0) return

        // Euler-Maclaurin integration of the resonance, from the epoch in 720-minute steps.
        var atime = 0.0
        var xni = no
        var xli = xlamo
        val delt = if (t > 0.0) stepp else -stepp
        var xndt: Double
        var xldot: Double
        var xnddt: Double
        var ft: Double
        while (true) {
            if (irez != 2) {
                xndt = del1 * sin(xli - fasx2) + del2 * sin(2.0 * (xli - fasx4)) + del3 * sin(3.0 * (xli - fasx6))
                xldot = xni + xfact
                xnddt = del1 * cos(xli - fasx2) + 2.0 * del2 * cos(2.0 * (xli - fasx4)) + 3.0 * del3 * cos(3.0 * (xli - fasx6))
                xnddt *= xldot
            } else {
                val xomi = argpo + argpdot * atime
                val x2omi = xomi + xomi
                val x2li = xli + xli
                xndt = d2201 * sin(x2omi + xli - g22) + d2211 * sin(xli - g22) +
                    d3210 * sin(xomi + xli - g32) + d3222 * sin(-xomi + xli - g32) +
                    d4410 * sin(x2omi + x2li - g44) + d4422 * sin(x2li - g44) +
                    d5220 * sin(xomi + xli - g52) + d5232 * sin(-xomi + xli - g52) +
                    d5421 * sin(xomi + x2li - g54) + d5433 * sin(-xomi + x2li - g54)
                xldot = xni + xfact
                xnddt = d2201 * cos(x2omi + xli - g22) + d2211 * cos(xli - g22) +
                    d3210 * cos(xomi + xli - g32) + d3222 * cos(-xomi + xli - g32) +
                    d5220 * cos(xomi + xli - g52) + d5232 * cos(-xomi + xli - g52) +
                    2.0 * (d4410 * cos(x2omi + x2li - g44) + d4422 * cos(x2li - g44) +
                        d5421 * cos(xomi + x2li - g54) + d5433 * cos(-xomi + x2li - g54))
                xnddt *= xldot
            }
            if (abs(t - atime) >= stepp) {
                xli += xldot * delt + xndt * step2
                xni += xndt * delt + xnddt * step2
                atime += delt
            } else {
                ft = t - atime
                break
            }
        }
        val nm = xni + xndt * ft + xnddt * ft * ft * 0.5
        val xl = xli + xldot * ft + xndt * ft * ft * 0.5
        el.mm = if (irez != 1) xl - 2.0 * el.nodem + 2.0 * theta else xl - el.nodem - el.argpm + theta
        el.nm = nm
    }

    /**
     * dpper: the lunar-solar periodic terms at [t] minutes, applied to the osculating-to-be elements in [el]
     * (eccentricity, inclination, node, perigee, mean anomaly), with the Lyddane form below 0.2 rad of inclination.
     */
    fun periodics(t: Double, el: DeepElements) {
        var zm = zmos + ZNS * t
        var zf = zm + 2.0 * ZES * sin(zm)
        var sinzf = sin(zf)
        var f2 = 0.5 * sinzf * sinzf - 0.25
        var f3 = -0.5 * sinzf * cos(zf)
        val ses = se2 * f2 + se3 * f3
        val sis = si2 * f2 + si3 * f3
        val sls = sl2 * f2 + sl3 * f3 + sl4 * sinzf
        val sghs = sgh2 * f2 + sgh3 * f3 + sgh4 * sinzf
        val shs = sh2 * f2 + sh3 * f3
        zm = zmol + ZNL * t
        zf = zm + 2.0 * ZEL * sin(zm)
        sinzf = sin(zf)
        f2 = 0.5 * sinzf * sinzf - 0.25
        f3 = -0.5 * sinzf * cos(zf)
        val sel = ee2 * f2 + e3 * f3
        val sil = xi2 * f2 + xi3 * f3
        val sll = xl2 * f2 + xl3 * f3 + xl4 * sinzf
        val sghl = xgh2 * f2 + xgh3 * f3 + xgh4 * sinzf
        val shll = xh2 * f2 + xh3 * f3
        val pe = ses + sel
        val pinc = sis + sil
        val pl = sls + sll
        var pgh = sghs + sghl
        var ph = shs + shll

        el.inclm += pinc
        el.em += pe
        val sinip = sin(el.inclm)
        val cosip = cos(el.inclm)
        if (el.inclm >= 0.2) {
            ph /= sinip
            pgh -= cosip * ph
            el.argpm += pgh
            el.nodem += ph
            el.mm += pl
        } else {
            // Lyddane's modification, for orbits near the equator
            val sinop = sin(el.nodem)
            val cosop = cos(el.nodem)
            var alfdp = sinip * sinop
            var betdp = sinip * cosop
            val dalf = ph * cosop + pinc * cosip * sinop
            val dbet = -ph * sinop + pinc * cosip * cosop
            alfdp += dalf
            betdp += dbet
            el.nodem = el.nodem.rem(TWOPI)
            val xls = el.mm + el.argpm + pl + pgh + (cosip - pinc * sinip) * el.nodem
            val xnoh = el.nodem
            el.nodem = atan2(alfdp, betdp)
            if (abs(xnoh - el.nodem) > PI) {
                if (el.nodem < xnoh) el.nodem += TWOPI else el.nodem -= TWOPI
            }
            el.mm += pl
            el.argpm = xls - el.mm - cosip * el.nodem
        }
    }
}
