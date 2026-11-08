package afield.graphics.render

/** Supplies target/resource/pipeline settings to a module without owning its shader source. */
fun interface ConfiguresModule {
    fun configure(parameters: Parameters)
}
