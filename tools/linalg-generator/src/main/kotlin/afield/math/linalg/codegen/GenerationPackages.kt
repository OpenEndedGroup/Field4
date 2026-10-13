package afield.math.linalg.codegen

import java.io.InputStream
import java.nio.file.Path
import java.util.Properties

/** Output names, independent of the generator's own implementation package. */
data class GenerationPackages(
    val linalg: String,
    val extensions: String,
    val halfCodec: String,
    val completionAnnotation: String,
) {
    init {
        listOf(linalg, extensions, halfCodec, completionAnnotation).forEach { name ->
            require(name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)+")) &&
                name.split('.').none { it in KEYWORDS }) { "Invalid qualified Kotlin name: $name" }
        }
    }

    val arena get() = "$linalg.arena"
    val storage get() = "$linalg.storage"
    val bulk get() = "$linalg.bulk"
    fun sourcePath(packageName: String, filename: String): Path = Path.of(packageName.replace('.', '/'), filename)

    companion object {
        private val KEYS = setOf("linalg.package", "extensions.package", "halfCodec.package", "completionAnnotation.type")
        private val KEYWORDS = setOf("as", "break", "class", "continue", "do", "else", "false", "for", "fun",
            "if", "in", "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
            "true", "try", "typealias", "typeof", "val", "var", "when", "while")

        fun default(): GenerationPackages = checkNotNull(GenerationPackages::class.java.getResourceAsStream("/linalg-packages.properties")) {
            "Missing linalg-packages.properties"
        }.use(::read)

        fun read(input: InputStream): GenerationPackages {
            val properties = object : Properties() {
                override fun put(key: Any, value: Any): Any? {
                    require(!containsKey(key)) { "Duplicate generator setting: $key" }
                    return super.put(key, value)
                }
            }
            properties.load(input.reader(Charsets.UTF_8))
            require(properties.stringPropertyNames() == KEYS) { "Generator package settings must contain exactly: ${KEYS.sorted()}" }
            return GenerationPackages(properties.getProperty("linalg.package"), properties.getProperty("extensions.package"),
                properties.getProperty("halfCodec.package"), properties.getProperty("completionAnnotation.type"))
        }
    }
}
