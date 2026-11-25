package afield.lang.solidsl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kaleidoscopic fractals built from [Profile.Fold], with the Koch snowflake as the worked case.
 *
 * The method is Matt Keeter's "Koch snowflake as a signed distance field"
 * (mattkeeter.com/blog/2026-11-23-snowflake) with Alexa Schor's fold version of his "hexify" step:
 * a level is the hexagram unioned with six one-third copies of the previous level at the hexagon's
 * vertices. Written as a [Profile.Union] of six children that is 6^N leaves at level N; written as
 * [hexFolded] around one copy it is a constant number of nodes per level, so both the tree and the
 * GLSL [toGlsl] emits grow linearly in N.
 */

/** The hexagram (level-1 Koch snowflake) of circumradius [radius], centred on the origin. */
fun hexagram(radius: Double = 1.0): Profile {
    val inner = radius / sqrt(3.0)
    return Profile.Polygon(
        List(12) { i ->
            val a = PI / 2 + i * PI / 6
            val r = if (i % 2 == 0) radius else inner
            P2(r * cos(a), r * sin(a))
        },
    )
}

/**
 * The Koch snowflake at [level] (1 is the hexagram), circumradius 1, as a fold stack: each level is
 * `hexFolded(union(hexagram, previous level shrunk to 1/3 and moved to (0, 2/3)))`. O(N) nodes.
 */
fun kochSnowflake(level: Int): Profile {
    require(level >= 1) { "the Koch snowflake starts at level 1 (the hexagram), got $level" }
    var shape: Profile = hexagram()
    repeat(level - 1) {
        shape = Profile.Union(listOf(hexagram(), shape.sized(1.0 / 3.0).translated(0.0, 2.0 / 3.0))).hexFolded()
    }
    return shape
}

/**
 * The same snowflake as [kochSnowflake], written the naive way: six explicit copies of the previous
 * level per level, so 6^(N-1) hexagram leaves. Kept as the reference the fold version is measured
 * against, not as something to draw with.
 */
fun kochSnowflakeNaive(level: Int): Profile {
    require(level >= 1) { "the Koch snowflake starts at level 1 (the hexagram), got $level" }
    if (level == 1) return hexagram()
    val small = kochSnowflakeNaive(level - 1).sized(1.0 / 3.0)
    val copies = List(6) { i ->
        val a = PI / 2 + i * PI / 3
        small.translated(2.0 / 3.0 * cos(a), 2.0 / 3.0 * sin(a))
    }
    return Profile.Union(listOf(hexagram()) + copies)
}

/** The size of this profile as a tree — every node counted once per occurrence. */
fun Profile.profileNodeCount(): Int = 1 + when (this) {
    is Profile.Offset -> child.profileNodeCount()
    is Profile.Translate -> child.profileNodeCount()
    is Profile.Rotate -> child.profileNodeCount()
    is Profile.Scale -> child.profileNodeCount()
    is Profile.Fold -> child.profileNodeCount()
    is Profile.Union -> children.sumOf { it.profileNodeCount() }
    is Profile.Intersect -> children.sumOf { it.profileNodeCount() }
    is Profile.Difference -> children.sumOf { it.profileNodeCount() }
    is Profile.Circle, is Profile.Rect, is Profile.Polygon, Profile.Empty -> 0
}

/** The GLSL body this profile lowers to, as a function `float name(vec2 p)`, via [SolidExpr.toGlsl]'s 2D emitter. */
fun Profile.toGlsl2(functionName: String = "sdProfile"): String = profileGlsl(this, functionName)
