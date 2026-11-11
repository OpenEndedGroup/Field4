import afield.math.distribution.*

val mu = normal(0, 10)
val reading = mu given observed(normal(mu, 1), 2.9, 3.3, 3.1)

val p = uniform(0, 1)
val coin = p given observed(bernoulli(p), 1, 1, 0, 1, 1, 1, 0, 1, 1, 0)

loop {
    __m("reading").distribution(reading, batch = 2000, title = "mu given three readings", exact = 9.3 / 3.01)
    __m("coin").distribution(coin, batch = 2000, title = "bias given 7 heads in 10", exact = 8.0 / 12)
    frame()
}
