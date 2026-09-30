package afield.lua

/**
 * What completion says about a Lua name: its signature and one line on what it does.
 *
 * Two catalogs, both resources beside the prelude: `lua-stdlib.txt` for the Lua 5.1 library and
 * LuaJIT 2.1's additions (`bit`, `jit`, `table.move`, ...), and `lua-field.txt` for what Field's
 * prelude adds (`display`, `line`, `play`, `field.*`, the methods of a line). Neither is generated
 * from a header, because Lua's headers say nothing about Lua functions; instead `LuaCatalogTest` holds
 * each to a live LuaJIT state in both directions, the way Pd's object list is held to its pinned
 * sources: a function the state has and the catalog does not fails, and so does a line naming a
 * function the state no longer has.
 */
object LuaCatalog {

    /** One catalogued function: [name] as it is reached (`string.format`, `Line.moveTo`, `print`). */
    class Entry(val name: String, val signature: String, val doc: String, val fromField: Boolean)

    private fun read(resource: String, fromField: Boolean): Map<String, Entry> {
        val text = LuaCatalog::class.java.getResource(resource)?.readText() ?: return emptyMap()
        val out = LinkedHashMap<String, Entry>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val signature = line.substringBefore("|").trim()
            val doc = line.substringAfter("|", "").trim()
            val name = signature.substringBefore("(").trim()
            out[name] = Entry(name, signature, doc, fromField)
        }
        return out
    }

    /** The Lua and LuaJIT library. */
    val stdlib: Map<String, Entry> by lazy { read("/afield/lua/lua-stdlib.txt", fromField = false) }

    /** What Field's prelude adds. */
    val field: Map<String, Entry> by lazy { read("/afield/lua/lua-field.txt", fromField = true) }

    /**
     * The entry for [name] reached at [path] (`""` for a global, `"string"`, `"field"`, `"field.Line"`).
     * Field's entry wins where both have one — `print` goes to the box, not stdout.
     */
    fun lookup(path: String, name: String): Entry? {
        val key = when (path) {
            "" -> name
            "field.Line" -> "Line.$name"
            else -> "$path.$name"
        }
        return field[key] ?: stdlib[key]
    }
}
