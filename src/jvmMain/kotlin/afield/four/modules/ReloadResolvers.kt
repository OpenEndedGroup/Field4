package afield.four.modules

/**
 * A resolver a module pointer's `reload` installed on a [Parameters] to carry resources over from the
 * module being replaced, remembering the resolver it wrapped.
 *
 * A reload is handed `current.parameters.copy()`, and a data-class copy carries the resolvers across
 * as they are — so the previous reload's carry-over wrapper came along, and wrapping *it* grew the
 * chain by one level per reload: after a hundred `alt-r`s, each UBO lookup went through a hundred
 * closures, every one of them consulting a `current` module. [reloadWrap] always wraps the
 * [original] — the resolver the user or `moduleHere` supplied — so the depth stays at one.
 */
internal class ReloadWrappedResolver<A, R>(
    val original: (A) -> R,
    private val body: (original: (A) -> R, A) -> R
) : (A) -> R {
    override fun invoke(p1: A): R = body(original, p1)
}

/**
 * Wrap [current] for a reload, unwrapping any wrapper an earlier reload installed first, so repeated
 * reloads replace the carry-over layer rather than stacking another one on it.
 */
@Suppress("UNCHECKED_CAST")
internal fun <A, R> reloadWrap(current: (A) -> R, body: (original: (A) -> R, A) -> R): (A) -> R {
    val original = (current as? ReloadWrappedResolver<A, R>)?.original ?: current
    return ReloadWrappedResolver(original, body)
}

/** How many reload wrappers sit in front of the user's resolver; 0 or 1 once [reloadWrap] is used. */
internal fun reloadWrapDepth(resolver: Function1<*, *>): Int {
    var depth = 0
    var r: Function1<*, *> = resolver
    while (r is ReloadWrappedResolver<*, *>) {
        depth++
        r = r.original
    }
    return depth
}
