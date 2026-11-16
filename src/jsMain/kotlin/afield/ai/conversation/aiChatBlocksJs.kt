package afield.ai.conversation

/** A round trip through JSON is the one copy V8 always makes flat and fresh: no slice or concatenation survives it. */
internal actual fun detached(s: String): String = js("JSON.parse(JSON.stringify(s))").unsafeCast<String>()
