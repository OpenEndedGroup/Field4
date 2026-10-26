// {{ remoteAR }}
// The marker lands on line 5, the throw inside mean.
fun mean(values: List<Double>): Double {
    if (values.isEmpty())
        throw IllegalArgumentException("mean of nothing")
    return values.sum() / values.size
}

println(mean(emptyList()))
