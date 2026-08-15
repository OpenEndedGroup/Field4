@file:afield.CompletionCategory("Graphics")

package afield.solidsl


/**
 * Rewriting a [SolidExpr] into a smaller one that means the same thing — the other half of what
 * making the 3D family symbolic bought, beside a second and third backend.
 *
 * ```kotlin
 * val messy = unionOf(listOf(sphere(1), sphere(1))).at(0, 0, 0).at(1, 0, 0) - SolidExpr.Empty
 * println(messy.simplified())        // Translate(child=Sphere(radius=1.0, segments=0), x=1.0, …)
 * ```
 *
 * None of these rewrites is available to a closure. Two `sdfSphere(1.0)` lambdas are different
 * objects and always will be; two `SolidExpr.Sphere(1.0)` are `==`. Nothing can ask a `(Vec3) -> Float`
 * whether it is ever non-zero, or where; a node knows its own [SolidExpr.bounds] without sampling.
 * This file is what those two facts are worth.
 *
 * # The rules, and which ones are interesting
 *
 * The dull ones first, because a notation accumulates them the moment anyone generates code into it:
 * identity transforms drop out, adjacent [SolidExpr.Translate]s and [SolidExpr.Scale]s fold, nested
 * booleans of the same kind flatten into one n-ary node (which matters more here than in most
 * algebras, because n-ary is exactly what reaches Manifold's *batch* boolean rather than a fold), and
 * [SolidExpr.Empty] is absorbed.
 *
 * Two of them are worth more than that:
 *
 *  - **Disjoint operands.** `a ∩ b` where the two bounds do not overlap is [SolidExpr.Empty]; `a − b`
 *    where `b` is nowhere near `a` is just `a`. That is a fact about the *expression*, decided from
 *    symbolic bounds with nothing sampled and nothing built. On a generated assembly — a hundred
 *    bores drilled through one plate of which twelve actually meet it — this is the difference
 *    between a shader that runs and one that does not.
 *
 *  - **A vanishing blend removes a refusal.** `SmoothUnion(a, b, k)` with `k` at or below zero *is*
 *    a plain union, and a plain union has an exact CSG form where a smooth blend never will. So
 *    simplification can move an expression from the level-set fallback onto the exact path, which is
 *    a strange and rather nice thing for a peephole optimiser to be able to do. [simplified] is
 *    therefore worth calling before [buildSolid] and not only before [toGlsl].
 *
 * # What it does not do
 *
 * It does not compose two [SolidExpr.Rotate]s unless they turn about the same single axis. Two
 * general Euler rotations compose to a matrix, and a matrix does not always come back out as three
 * Euler angles without a gimbal case to get wrong — and the whole discipline of this package is that
 * a rewrite which is *nearly* right is worse than no rewrite. It also does not reassociate
 * [SolidExpr.SmoothUnion], which is genuinely non-associative, or reorder a
 * [SolidExpr.Difference]'s operands, which is genuinely non-commutative.
 *
 * Every rule is checked by sampling: `SolidSLSimplifyTest` asserts that the simplified expression's
 * field agrees with the original's at thousands of points, for every rule and every combination it
 * has. A simplifier that quietly changes the shape is the worst thing in this package, so it is the
 * most heavily sampled.
 */

/**
 * This expression, rewritten smaller. Bottom-up, then repeated until it stops changing — a fixed
 * point, because flattening a boolean can expose a fold that exposes an identity.
 *
 * @param maxPasses a guard, not a tuning knob. Every rule strictly shrinks the tree or removes a
 *   node kind, so the fixed point arrives in two or three passes; the cap only keeps a future rule
 *   that accidentally oscillates from hanging a box.
 */
fun SolidExpr.simplified(maxPasses: Int = 8): SolidExpr {
    var current = this
    repeat(maxPasses) {
        val next = simplifyOnce(current)
        if (next == current) return current
        current = next
    }
    return current
}

/** [simplified] for a 2D region. */
fun Profile.simplified(maxPasses: Int = 8): Profile {
    var current = this
    repeat(maxPasses) {
        val next = simplifyProfileOnce(current)
        if (next == current) return current
        current = next
    }
    return current
}

/** How many nodes this expression has — the number [simplified] is trying to reduce. */
fun SolidExpr.nodeCount(): Int = 1 + children().sumOf { it.nodeCount() }

private fun simplifyOnce(e: SolidExpr): SolidExpr = when (e) {

    is SolidExpr.Sphere, is SolidExpr.Cuboid, is SolidExpr.Cylinder, is SolidExpr.Gyroid, SolidExpr.Empty -> e

    is SolidExpr.Extrude -> e.copy(profile = simplifyProfileOnce(e.profile))
    is SolidExpr.Revolve -> e.copy(profile = simplifyProfileOnce(e.profile))

    is SolidExpr.Translate -> {
        val c = simplifyOnce(e.child)
        when {
            e.x == 0.0 && e.y == 0.0 && e.z == 0.0 -> c
            c is SolidExpr.Empty -> c
            // Adjacent translations add. The commonest thing a generated expression has too many of.
            c is SolidExpr.Translate -> SolidExpr.Translate(c.child, c.x + e.x, c.y + e.y, c.z + e.z)
            else -> e.copy(child = c)
        }
    }

    is SolidExpr.Rotate -> {
        val c = simplifyOnce(e.child)
        when {
            e.x == 0.0 && e.y == 0.0 && e.z == 0.0 -> c
            c is SolidExpr.Empty -> c
            // Only about a shared single axis. Rz(b)Rz(a) = Rz(a+b) is exact; the general composition
            // is a matrix, and taking Euler angles back out of one has a gimbal case that a rewrite
            // must not get *nearly* right.
            c is SolidExpr.Rotate && sharesSingleAxis(e, c) ->
                SolidExpr.Rotate(c.child, c.x + e.x, c.y + e.y, c.z + e.z)
            else -> e.copy(child = c)
        }
    }

    is SolidExpr.Scale -> {
        val c = simplifyOnce(e.child)
        when {
            e.x == 1.0 && e.y == 1.0 && e.z == 1.0 -> c
            c is SolidExpr.Empty -> c
            c is SolidExpr.Scale -> SolidExpr.Scale(c.child, c.x * e.x, c.y * e.y, c.z * e.z)
            else -> e.copy(child = c)
        }
    }

    is SolidExpr.Mirror -> {
        val c = simplifyOnce(e.child)
        if (c is SolidExpr.Empty) c else e.copy(child = c)
    }

    is SolidExpr.Round -> {
        val c = simplifyOnce(e.child)
        // Not just tidiness: Round has no exact CSG form, so a zero one is a refusal that need not
        // have happened.
        if (e.radius == 0.0) c else e.copy(child = c)
    }

    is SolidExpr.Shell -> simplifyOnce(e.child).let { if (it is SolidExpr.Empty) it else e.copy(child = it) }

    is SolidExpr.Union -> {
        val flat = e.children
            .map { simplifyOnce(it) }
            .flatMap { if (it is SolidExpr.Union) it.children else listOf(it) }
            .filter { it !is SolidExpr.Empty }
            // Structural equality, which is the whole reason these are data classes. Two
            // `sdfSphere(1.0)` closures are different objects forever.
            .distinct()
        when (flat.size) {
            0 -> SolidExpr.Empty
            1 -> flat[0]
            else -> SolidExpr.Union(flat)
        }
    }

    is SolidExpr.Intersect -> {
        val flat = e.children
            .map { simplifyOnce(it) }
            .flatMap { if (it is SolidExpr.Intersect) it.children else listOf(it) }
            .distinct()
        when {
            flat.isEmpty() -> SolidExpr.Empty
            flat.any { it is SolidExpr.Empty } -> SolidExpr.Empty
            // Two operands that cannot touch intersect to nothing, decided from bounds alone.
            anyDisjointPair(flat) -> SolidExpr.Empty
            flat.size == 1 -> flat[0]
            else -> SolidExpr.Intersect(flat)
        }
    }

    is SolidExpr.Difference -> {
        val parts = e.children.map { simplifyOnce(it) }
        if (parts.isEmpty()) SolidExpr.Empty else {
            val head = parts[0]
            if (head is SolidExpr.Empty) SolidExpr.Empty else {
                val cutters = parts.drop(1)
                    .filter { it !is SolidExpr.Empty }
                    // A cutter that cannot reach the body removes nothing. On a generated assembly
                    // this is the rule that pays.
                    .filter { overlaps(head, it) }
                    .distinct()
                when {
                    // Anything minus itself is nothing.
                    cutters.any { it == head } -> SolidExpr.Empty
                    cutters.isEmpty() -> head
                    else -> SolidExpr.Difference(listOf(head) + cutters)
                }
            }
        }
    }

    is SolidExpr.SmoothUnion -> {
        val a = simplifyOnce(e.a); val b = simplifyOnce(e.b)
        when {
            // The rule that removes a refusal: a blend of radius zero is a union, and a union has an
            // exact CSG form.
            e.k <= 0.0 -> SolidExpr.Union(listOf(a, b))
            a is SolidExpr.Empty -> b
            b is SolidExpr.Empty -> a
            else -> SolidExpr.SmoothUnion(a, b, e.k)
        }
    }

    is SolidExpr.SmoothDifference -> {
        val a = simplifyOnce(e.a); val b = simplifyOnce(e.b)
        when {
            e.k <= 0.0 -> SolidExpr.Difference(listOf(a, b))
            b is SolidExpr.Empty -> a
            a is SolidExpr.Empty -> SolidExpr.Empty
            else -> SolidExpr.SmoothDifference(a, b, e.k)
        }
    }
}

private fun simplifyProfileOnce(p: Profile): Profile = when (p) {

    is Profile.Circle, is Profile.Rect, is Profile.Polygon, Profile.Empty -> p

    is Profile.Offset -> {
        val c = simplifyProfileOnce(p.child)
        if (p.delta == 0.0) c else p.copy(child = c)
    }

    is Profile.Translate -> {
        val c = simplifyProfileOnce(p.child)
        when {
            p.x == 0.0 && p.y == 0.0 -> c
            c is Profile.Translate -> Profile.Translate(c.child, c.x + p.x, c.y + p.y)
            else -> p.copy(child = c)
        }
    }

    is Profile.Rotate -> {
        val c = simplifyProfileOnce(p.child)
        when {
            p.degrees == 0.0 -> c
            // A 2D rotation is a scalar, so unlike the 3D case these always compose.
            c is Profile.Rotate -> Profile.Rotate(c.child, c.degrees + p.degrees)
            else -> p.copy(child = c)
        }
    }

    is Profile.Scale -> {
        val c = simplifyProfileOnce(p.child)
        when {
            p.x == 1.0 && p.y == 1.0 -> c
            c is Profile.Scale -> Profile.Scale(c.child, c.x * p.x, c.y * p.y)
            else -> p.copy(child = c)
        }
    }

    is Profile.Union -> {
        val flat = p.children.map { simplifyProfileOnce(it) }
            .flatMap { if (it is Profile.Union) it.children else listOf(it) }
            .filter { it !is Profile.Empty }
            .distinct()
        when (flat.size) {
            0 -> Profile.Empty
            1 -> flat[0]
            else -> Profile.Union(flat)
        }
    }

    is Profile.Intersect -> {
        val flat = p.children.map { simplifyProfileOnce(it) }
            .flatMap { if (it is Profile.Intersect) it.children else listOf(it) }
            .distinct()
        when {
            flat.isEmpty() -> Profile.Empty
            flat.any { it is Profile.Empty } -> Profile.Empty
            anyDisjointProfilePair(flat) -> Profile.Empty
            flat.size == 1 -> flat[0]
            else -> Profile.Intersect(flat)
        }
    }

    is Profile.Difference -> {
        val parts = p.children.map { simplifyProfileOnce(it) }
        if (parts.isEmpty()) Profile.Empty else {
            val head = parts[0]
            if (head is Profile.Empty) Profile.Empty else {
                val cutters = parts.drop(1)
                    .filter { it !is Profile.Empty }
                    .filter { profileOverlaps(head, it) }
                    .distinct()
                when {
                    cutters.any { it == head } -> Profile.Empty
                    cutters.isEmpty() -> head
                    else -> Profile.Difference(listOf(head) + cutters)
                }
            }
        }
    }
}

/** `true` when both rotations turn about the *same one* axis, so their angles simply add. */
private fun sharesSingleAxis(a: SolidExpr.Rotate, b: SolidExpr.Rotate): Boolean {
    fun axes(r: SolidExpr.Rotate) = listOf(r.x != 0.0, r.y != 0.0, r.z != 0.0)
    val pa = axes(a); val pb = axes(b)
    return pa.count { it } <= 1 && pb.count { it } <= 1 && pa == pb
}

/**
 * `true` when [a] and [b] certainly do not touch, decided from symbolic bounds.
 *
 * Conservative in the direction that matters: an unbounded node ([SolidExpr.Gyroid]) reports "might
 * touch", and a *touching* pair of bounds reports "might touch" even when the shapes inside them do
 * not. A rewrite may only fire on certainty.
 */
private fun definitelyDisjoint(a: SolidExpr, b: SolidExpr): Boolean {
    val ba = a.bounds() ?: return false
    val bb = b.bounds() ?: return false
    return ba.max.x < bb.min.x || bb.max.x < ba.min.x ||
        ba.max.y < bb.min.y || bb.max.y < ba.min.y ||
        ba.max.z < bb.min.z || bb.max.z < ba.min.z
}

private fun overlaps(a: SolidExpr, b: SolidExpr): Boolean = !definitelyDisjoint(a, b)

private fun anyDisjointPair(items: List<SolidExpr>): Boolean {
    for (i in items.indices) for (j in i + 1 until items.size) {
        if (definitelyDisjoint(items[i], items[j])) return true
    }
    return false
}

private fun profileDisjoint(a: Profile, b: Profile): Boolean {
    val ba = a.bounds() ?: return false
    val bb = b.bounds() ?: return false
    return ba.maxX < bb.minX || bb.maxX < ba.minX || ba.maxY < bb.minY || bb.maxY < ba.minY
}

private fun profileOverlaps(a: Profile, b: Profile): Boolean = !profileDisjoint(a, b)

private fun anyDisjointProfilePair(items: List<Profile>): Boolean {
    for (i in items.indices) for (j in i + 1 until items.size) {
        if (profileDisjoint(items[i], items[j])) return true
    }
    return false
}

/**
 * A one-line account of what [simplified] did to this expression, for a `println` in a box.
 *
 * Reports the node count, the emitted GLSL statement count and whether the exact CSG path is
 * available — that last one because it can *change*, which is the most surprising thing in this file.
 */
fun SolidExpr.simplificationReport(): String {
    val before = this
    val after = simplified()
    fun exactness(e: SolidExpr) = if (e.exactRefusal() == null) "exact" else "level set"
    return "nodes ${before.nodeCount()} -> ${after.nodeCount()}, " +
        "GLSL ${before.toGlsl().statementCount} -> ${after.toGlsl().statementCount} statements, " +
        "path ${exactness(before)} -> ${exactness(after)}"
}
