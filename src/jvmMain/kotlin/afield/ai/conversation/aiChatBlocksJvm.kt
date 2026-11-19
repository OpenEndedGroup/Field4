package afield.ai.conversation

/** On the JVM a substring is already a copy, so there is nothing to detach. */
internal actual fun detached(s: String): String = s
