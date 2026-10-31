@file:afield.runtime.completion.CompletionCategory("Internal")
package afield.media.browser

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.*

/** Layout-aware evdev keys, including AltGr, dead keys and Compose, without a desktop seat. */
internal class WiVRnKeyboardMap(
    layout: String = System.getenv("FIELD_VR_KEYBOARD_LAYOUT") ?: "us",
    variant: String = System.getenv("FIELD_VR_KEYBOARD_VARIANT") ?: "",
    options: String = System.getenv("FIELD_VR_KEYBOARD_OPTIONS") ?: "",
) : AutoCloseable {
    private val linker = Linker.nativeLinker()
    private val library = SymbolLookup.libraryLookup("libxkbcommon.so.0", Arena.global())
    private fun fn(name: String, ret: MemoryLayout?, vararg args: MemoryLayout) = linker.downcallHandle(
        library.find(name).orElseThrow(), if (ret == null) FunctionDescriptor.ofVoid(*args) else FunctionDescriptor.of(ret, *args))
    private val contextNew = fn("xkb_context_new", ADDRESS, JAVA_INT)
    private val contextFree = fn("xkb_context_unref", null, ADDRESS)
    private val keymapNew = fn("xkb_keymap_new_from_names", ADDRESS, ADDRESS, ADDRESS, JAVA_INT)
    private val keymapFree = fn("xkb_keymap_unref", null, ADDRESS)
    private val stateNew = fn("xkb_state_new", ADDRESS, ADDRESS)
    private val stateFree = fn("xkb_state_unref", null, ADDRESS)
    private val update = fn("xkb_state_update_key", JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)
    private val keySym = fn("xkb_state_key_get_one_sym", JAVA_INT, ADDRESS, JAVA_INT)
    private val keyText = fn("xkb_state_key_get_utf8", JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG)
    private val modActive = fn("xkb_state_mod_name_is_active", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)
    private val symName = fn("xkb_keysym_get_name", JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG)
    private val symUnicode = fn("xkb_keysym_to_utf32", JAVA_INT, JAVA_INT)
    private val composeTableNew = fn("xkb_compose_table_new_from_locale", ADDRESS, ADDRESS, ADDRESS, JAVA_INT)
    private val composeTableFree = fn("xkb_compose_table_unref", null, ADDRESS)
    private val composeNew = fn("xkb_compose_state_new", ADDRESS, ADDRESS, JAVA_INT)
    private val composeFree = fn("xkb_compose_state_unref", null, ADDRESS)
    private val composeFeed = fn("xkb_compose_state_feed", JAVA_INT, ADDRESS, JAVA_INT)
    private val composeStatus = fn("xkb_compose_state_get_status", JAVA_INT, ADDRESS)
    private val composeText = fn("xkb_compose_state_get_utf8", JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG)
    private val composeReset = fn("xkb_compose_state_reset", null, ADDRESS)
    private val context = (contextNew.invokeExact(0) as MemorySegment).also { check(it != MemorySegment.NULL) }
    private val keymap: MemorySegment
    private var state: MemorySegment
    private val table: MemorySegment
    private val compose: MemorySegment
    private var closed = false

    init {
        keymap = Arena.ofConfined().use { arena ->
            val names = arena.allocate(5 * ADDRESS.byteSize(), ADDRESS.byteAlignment())
            listOf("evdev", "pc105", layout, variant, options).forEachIndexed { index, value ->
                names.set(ADDRESS, index * ADDRESS.byteSize(), arena.allocateFrom(value))
            }
            keymapNew.invokeExact(context, names, 0) as MemorySegment
        }
        if (keymap == MemorySegment.NULL) {
            contextFree.invokeExact(context)
            throw IllegalArgumentException("Cannot load XKB layout $layout ($variant, $options)")
        }
        state = stateNew.invokeExact(keymap) as MemorySegment
        check(state != MemorySegment.NULL)
        table = Arena.ofConfined().use { arena ->
            val locale = listOf("LC_ALL", "LC_CTYPE", "LANG").firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
                ?: "C.UTF-8"
            composeTableNew.invokeExact(context, arena.allocateFrom(locale), 0) as MemorySegment
        }
        compose = if (table == MemorySegment.NULL) MemorySegment.NULL else composeNew.invokeExact(table, 0) as MemorySegment
    }

    fun update(code: Int, down: Boolean) { update.invokeExact(state, code + 8, if (down) 1 else 0) as Int }

    fun modifiers(): Set<BrowserModifier> = Arena.ofConfined().use { arena ->
        buildSet {
            for ((name, modifier) in listOf("Shift" to BrowserModifier.SHIFT, "Control" to BrowserModifier.CONTROL,
                "Mod1" to BrowserModifier.ALT, "Mod4" to BrowserModifier.META)) {
                if ((modActive.invokeExact(state, arena.allocateFrom(name), 8) as Int) > 0) add(modifier)
            }
        }
    }

    /** Return keydown metadata and printable text together, so Chromium honors preventDefault. */
    fun key(code: Int, repeat: Boolean, modifiers: Set<BrowserModifier>): BrowserKeyEvent = Arena.ofConfined().use { arena ->
        val physical = evdevKeys[code] ?: Key("Unidentified", "Unidentified", 0)
        val buffer = arena.allocate(256)
        val sym = keySym.invokeExact(state, code + 8) as Int
        val named = (symName.invokeExact(sym, buffer, buffer.byteSize()) as Int).let { if (it > 0) buffer.getString(0) else "" }
        val utf8 = (keyText.invokeExact(state, code + 8, buffer, buffer.byteSize()) as Int).let { if (it > 0) buffer.getString(0) else "" }
        val unicode = symUnicode.invokeExact(sym) as Int
        val logical = if (unicode in 32..0x10ffff && unicode != 127) String(Character.toChars(unicode)) else ""
        var text = utf8.takeIf { it.isNotEmpty() && it.first() >= ' ' } ?: ""
        val key = when {
            named.startsWith("dead_") -> "Dead"
            named == "ISO_Level3_Shift" -> "AltGraph"
            named == "Multi_key" -> "Compose"
            logical.isNotEmpty() -> logical
            else -> physical.key
        }
        if (compose != MemorySegment.NULL) {
            composeFeed.invokeExact(compose, sym) as Int
            when (composeStatus.invokeExact(compose) as Int) {
                1 -> text = "" // composing
                2 -> {
                    text = (composeText.invokeExact(compose, buffer, buffer.byteSize()) as Int)
                        .let { if (it > 0) buffer.getString(0) else "" }
                    composeReset.invokeExact(compose)
                }
                3 -> { text = ""; composeReset.invokeExact(compose) }
            }
        }
        if (physical.key == "Enter") text = "\r"
        if (modifiers.any { it == BrowserModifier.CONTROL || it == BrowserModifier.META || it == BrowserModifier.ALT }) text = ""
        val virtualKey = if (key.length == 1 && key[0].isLetter() && key[0].code < 128) key[0].uppercaseChar().code else physical.virtualKey
        BrowserKeyEvent(if (text.isEmpty()) BrowserKeyEventType.RAW_KEY_DOWN else BrowserKeyEventType.KEY_DOWN,
            key, physical.code, modifiers, windowsVirtualKeyCode = virtualKey, nativeVirtualKeyCode = code,
            autoRepeat = repeat, keypad = physical.code.startsWith("Numpad"), text = text.takeIf(String::isNotEmpty))
    }

    fun reset() {
        stateFree.invokeExact(state)
        state = stateNew.invokeExact(keymap) as MemorySegment
        check(state != MemorySegment.NULL)
        if (compose != MemorySegment.NULL) composeReset.invokeExact(compose)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (compose != MemorySegment.NULL) composeFree.invokeExact(compose)
        if (table != MemorySegment.NULL) composeTableFree.invokeExact(table)
        stateFree.invokeExact(state)
        keymapFree.invokeExact(keymap)
        contextFree.invokeExact(context)
    }

    private data class Key(val key: String, val code: String, val virtualKey: Int)
    companion object {
        private val evdevKeys: Map<Int, Key> = buildMap {
            fun add(scan: Int, key: String, code: String = key, vk: Int = 0) { put(scan, Key(key, code, vk)) }
            add(1,"Escape",vk=27)
            "1234567890".forEachIndexed { i,c -> add(i+2,"$c","Digit$c",c.code) }
            add(12,"-","Minus",189); add(13,"=","Equal",187); add(14,"Backspace",vk=8); add(15,"Tab",vk=9)
            listOf(16 to "qwertyuiop",30 to "asdfghjkl",44 to "zxcvbnm").forEach { (start,row) ->
                row.forEachIndexed { i,c -> add(start+i,"$c","Key${c.uppercaseChar()}",c.uppercaseChar().code) }
            }
            add(26,"[","BracketLeft",219);add(27,"]","BracketRight",221);add(28,"Enter",vk=13)
            add(29,"Control","ControlLeft",17);add(39,";","Semicolon",186);add(40,"'","Quote",222)
            add(41,"`","Backquote",192);add(42,"Shift","ShiftLeft",16);add(43,"\\","Backslash",220)
            add(51,",","Comma",188);add(52,".","Period",190);add(53,"/","Slash",191)
            add(54,"Shift","ShiftRight",16);add(55,"*","NumpadMultiply",106);add(56,"Alt","AltLeft",18)
            add(57," ","Space",32);add(58,"CapsLock",vk=20)
            for (i in 1..10) add(58+i,"F$i",vk=111+i)
            add(69,"NumLock",vk=144);add(70,"ScrollLock",vk=145)
            for ((scan,digit) in listOf(71 to 7,72 to 8,73 to 9,75 to 4,76 to 5,77 to 6,79 to 1,80 to 2,81 to 3,82 to 0))
                add(scan,"$digit","Numpad$digit",96+digit)
            add(74,"-","NumpadSubtract",109);add(78,"+","NumpadAdd",107);add(83,".","NumpadDecimal",110)
            add(86,"\\","IntlBackslash",226);add(87,"F11",vk=122);add(88,"F12",vk=123)
            add(96,"Enter","NumpadEnter",13);add(97,"Control","ControlRight",17);add(98,"/","NumpadDivide",111)
            add(99,"PrintScreen",vk=44);add(100,"Alt","AltRight",18);add(102,"Home",vk=36)
            add(103,"ArrowUp",vk=38);add(104,"PageUp",vk=33);add(105,"ArrowLeft",vk=37);add(106,"ArrowRight",vk=39)
            add(107,"End",vk=35);add(108,"ArrowDown",vk=40);add(109,"PageDown",vk=34)
            add(110,"Insert",vk=45);add(111,"Delete",vk=46);add(119,"Pause",vk=19)
            add(125,"Meta","MetaLeft",91);add(126,"Meta","MetaRight",92);add(127,"ContextMenu",vk=93)
        }
    }
}
