package afield.four.fabricated

/**
 * The ordered, name-keyed map both of this package's pass collections are built on.
 *
 * [FabricatePassGraph] is a `LinkedHashMap` with an API that makes the ordering part of the
 * contract rather than a property of whichever map a caller happened to reach for. Names are
 * unique and non-blank, and the three ways of putting one in differ in what they will accept:
 * `addLast` refuses a name already present, `replace` refuses one that is not, and `putOrReplace`
 * takes either. `insertBefore` and `insertAfter` position a new pass relative to one already
 * there, and both rebuild the map end to end, because a `LinkedHashMap` has no way to place an
 * entry anywhere but last.
 *
 * Two collections here are this map with a type argument and a vocabulary on top.
 * [FabricatePostStack] holds [FabricatePostEffect]s, which is what lets a feature insert a post
 * pass beside one it did not add; [FabricateFrameGraph] holds `afield.four` targets, which is what
 * gives the renderer's off-screen passes names and an order before any of them reach a
 * `RenderTarget`. Nothing in this file imports anything, so the ordering rules can be exercised on
 * plain strings, which is what `FabricatePassGraphTestMain.kt` does.
 */

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
