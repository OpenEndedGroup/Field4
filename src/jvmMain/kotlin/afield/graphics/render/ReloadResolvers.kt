package afield.graphics.render

/**
 * Keeps a module's resource resolvers one layer deep across repeated shader reloads.
 *
 * Each module pointer installs its carry-over behaviour by replacing the resolvers on its
 * [Parameters]. [Module], [ComputeModule] and [MeshModule] replace all three of `uboResolver`,
 * `ssboResolver` and `textureResolver`; [RayTracingModule] replaces the latter two. A reload is
 * handed a copy of the parameters, and a copy brings the resolvers across as they are, so each
 * wrap would otherwise land on the one the previous reload installed and every uniform, buffer and
 * texture lookup would walk one closure more than it did on the run before.
 *
 * `reloadWrap` unwraps before it wraps, so what sits underneath stays the resolver the box or
 * `moduleHere` supplied and the depth stays at one however often a shader is reloaded.
 * `reloadWrapDepth` reports that depth; `ReloadResolversTest` holds it at one across a hundred
 * reloads, and checks that replacing the resolver yourself makes the replacement what the next
 * reload wraps.
 */

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
