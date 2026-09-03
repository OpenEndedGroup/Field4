// `Throwable` is `java.lang`, and `.h2` refuses to reflect into platform packages -- a
// `Thread` or a `ClassLoader` by property is a JVM field dump. So exceptions get an adapter:
// type, message, the cause chain, and the stack as a scrollable list.
//
// Click `stack` and `causes`.

val boom = runCatching {
    try {
        throw IllegalArgumentException("the inner reason")
    } catch (t: Throwable) {
        throw IllegalStateException("the outer failure", t)
    }
}.exceptionOrNull()!!

display("error", boom.h2)
displayOpen("error")