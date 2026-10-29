import afield.math.oracle.*
import afield.math.temporal.*
import kotlin.math.*

// Needs the forecaster once: print(TimestralSession.install("mistralai/Timestral-28M"))
print(TimestralSession.status())

val rate = 20.0
val n = (20 * rate).toInt()
val history = Trajectory.of(rate, "x" to n { val t = it / rate; sin(2 * PI * 0.5 * t) * (1 + 0.3 * sin(2 * PI * 0.05 * t)) })
val forecast = TimestralSession().use { it.propose(ContinuationRequest(0, history, horizon = 200)).get() }

fun plot(line: FLine) = line.transform { vec(it.x * 30, -it.y * 120, 0) }
val ahead = DoubleArray(forecast.horizon) { (n + it) / rate }
val past = plot(FLine().apply { for (t in 0 until n) if (t == 0) moveTo(0.0, history.values[0][0]) else lineTo(t / rate, history.values[0][t]) })
    .apply { color = vec(1, 1, 1, 0.9); thickness = 1.5 }
val fan = [0.01 to 0.99, 0.1 to 0.9, 0.25 to 0.75].map { (lo, hi) ->
    plot(forecast.bandFLine(0, lo, hi, ahead)).apply { filled = true; stroked = false; color = vec(1, 0.5, 0.2, 0.18) }
}
val median = plot(forecast.medianFLine(0, ahead)).apply { color = vec(1, 0.5, 0.2, 1); thickness = 3.0 }
lines(fan + past + median, w = 900.0, h = 320.0, fitToSize = true)
