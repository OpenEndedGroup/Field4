package afield.study.source

/**
 * A one-argument Kotlin function with durable source, created by `sourceBlock { index: Int -> ... }`.
 * [source] contains a complete callable expression, usually a lambda with its typed parameter. Saving keeps source and owner, never a closure.
 * Restored functions compile once in the owning box; call [prepare] during setup to keep compilation
 * out of a render callback. Captured local variables must still exist when that source is restored.
 */
class SourceBackedFunction<A, R>(
    val boxId: String,
    val source: String,
    private val function: ((A) -> R)? = null,
) : (A) -> R {
    @Volatile private var hydrated: ((A) -> R)? = null

    /** Compile a restored function once; a freshly authored function already has its live callable. */
    @Suppress("UNCHECKED_CAST")
    fun prepare(): (A) -> R = function ?: hydrated ?: synchronized(this) {
        hydrated ?: run {
            val restored = SourceBackedBlock<Any?>(boxId, source)()
            require(restored is Function1<*, *>) { "Stored source in $boxId did not produce a one-argument function" }
            (restored as (A) -> R).also { hydrated = it }
        }
    }

    override fun invoke(value: A): R = prepare()(value)
}
