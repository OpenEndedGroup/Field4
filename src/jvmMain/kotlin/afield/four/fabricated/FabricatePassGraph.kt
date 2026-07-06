package afield.four.fabricated

/**
 * Small ordered map for renderer passes. Kotlin's default mutableMapOf currently preserves
 * insertion order, but this wrapper makes that contract explicit and gives tests a pure data
 * structure to exercise before we touch Vulkan objects.
 */
class FabricatePassGraph<T>(initial: Iterable<Pair<String, T>> = emptyList()) {
    private val ordered = LinkedHashMap<String, T>()

    init {
        initial.forEach { (name, target) -> addLast(name, target) }
    }

    val size: Int
        get() = ordered.size

    fun names(): List<String> = ordered.keys.toList()

    fun entries(): List<Pair<String, T>> = ordered.entries.map { it.key to it.value }

    operator fun contains(name: String): Boolean = ordered.containsKey(name)

    operator fun get(name: String): T? = ordered[name]

    fun addLast(name: String, target: T): FabricatePassGraph<T> {
        require(name.isNotBlank()) { "pass name must not be blank" }
        require(name !in ordered) { "pass '$name' already exists" }
        ordered[name] = target
        return this
    }

    fun replace(name: String, target: T): FabricatePassGraph<T> {
        require(name in ordered) { "cannot replace missing pass '$name'" }
        ordered[name] = target
        return this
    }

    fun putOrReplace(name: String, target: T): FabricatePassGraph<T> {
        require(name.isNotBlank()) { "pass name must not be blank" }
        ordered[name] = target
        return this
    }

    fun insertBefore(name: String, before: String, target: T): FabricatePassGraph<T> {
        require(name.isNotBlank()) { "pass name must not be blank" }
        require(name !in ordered) { "pass '$name' already exists" }
        require(before in ordered) { "anchor pass '$before' does not exist" }
        rebuild { rebuilt ->
            ordered.forEach { (existingName, existingTarget) ->
                if (existingName == before) rebuilt[name] = target
                rebuilt[existingName] = existingTarget
            }
        }
        return this
    }

    fun insertAfter(name: String, after: String, target: T): FabricatePassGraph<T> {
        require(name.isNotBlank()) { "pass name must not be blank" }
        require(name !in ordered) { "pass '$name' already exists" }
        require(after in ordered) { "anchor pass '$after' does not exist" }
        rebuild { rebuilt ->
            ordered.forEach { (existingName, existingTarget) ->
                rebuilt[existingName] = existingTarget
                if (existingName == after) rebuilt[name] = target
            }
        }
        return this
    }

    fun remove(name: String): T? = ordered.remove(name)

    fun clear(): FabricatePassGraph<T> {
        ordered.clear()
        return this
    }

    private fun rebuild(populate: (LinkedHashMap<String, T>) -> Unit) {
        val rebuilt = LinkedHashMap<String, T>(ordered.size + 1)
        populate(rebuilt)
        ordered.clear()
        ordered.putAll(rebuilt)
    }
}
