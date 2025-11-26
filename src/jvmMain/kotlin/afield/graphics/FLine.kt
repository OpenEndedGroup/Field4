package afield.graphics

import afield.HasProperties
import afield.Rect
import afield.stdlib.*
import earcut4j.Earcut
import field.graphics.FLinesAndJavaShapes
import field.graphics.FLinesAndJavaShapes.Cursor
import org.joml.Vector3d
import org.joml.minus
import org.joml.plus
import org.joml.times
import kotlin.math.abs
import kotlin.math.min

class FLine : HasProperties {

    var nodes = arrayListOf<Node>()
    val properties = mutableMapOf<String, Any?>()


    sealed class Node(val n: Int) : HasProperties {
        val storage = mutableListOf<Vec3>()
        val properties = mutableMapOf<String, Any?>()

        fun set(vararg data: Vec3): Node {
            storage.clear()
            storage.addAll(data)
            if (storage.size != n) throw IllegalArgumentException(" expecting $n elements, got ${storage.size}")
            return this
        }

        abstract fun create(): Node

        fun copy(transform: (Vec3) -> Vec3 = { it }): Node {
            val next = create()
            storage.forEach { next.storage.add(transform(it)) }
            return next
        }

        var to: Vec3
            get() = storage[0]
            set(v) {
                storage[0] = v
            }

        override fun setPropertyRaw(name: String, value: Any, doResync: Boolean) {
            properties.put(name, value)
        }

        override fun getPropertyRaw(name: String, default: Any?): Any? = properties.get(name)

    }

    class MoveTo : Node(1) {
        override fun create(): Node = MoveTo()
        override fun toString(): String {
            return "MoveTo"
        }
    }

    class LineTo : Node(1) {
        override fun create(): Node = LineTo()
        override fun toString(): String {
            return "LineTo"
        }
    }

    class CubicTo : Node(3) {
        override fun create(): Node = CubicTo()
        var c1: Vec3
            get() = storage[1]
            set(v) {
                storage[1] = v
            }
        var c2: Vec3
            get() = storage[2]
            set(v) {
                storage[2] = v
            }

        override fun toString(): String {
            return "CubicTo"
        }
    }

    class Close : Node(0) {
        override fun create(): Node = MoveTo()
        override fun toString(): String {
            return "Close"
        }
    }

    var breakNext = false

    fun breakNext() {
        breakNext = true
    }

    /**
     * some documentation
     */
    fun moveTo(x: Number, y: Number, z: Number = 0): Node {
        nodes.add(MoveTo().set(Vec3(x.toFloat(), y.toFloat(), z.toFloat())))
        return nodes.last()
    }

    /**
     * some documentation
     */
    fun moveTo(v: Vec3): Node {
        return moveTo(v.x, v.y, v.z)
    }

    fun moveTo(v: Vec2): Node {
        return moveTo(v.x, v.y, 0.0)
    }


    fun moveTo(v: Vector3d): Node {
        return moveTo(v.x, v.y, v.z)
    }

    fun lineTo(x: Number, y: Number, z: Number = 0): Node {
        if (breakNext) {
            breakNext = false
            return moveTo(x, y, z)
        }
        nodes.add(LineTo().set(Vec3(x.toFloat(), y.toFloat(), z.toFloat())))
        return nodes.last()
    }

    fun lineTo(v: Vec3): Node {
        return lineTo(v.x, v.y, v.z)
    }

    fun lineTo(v: Vector3d): Node {
        return lineTo(v.x, v.y, v.z)
    }

    fun lineTo(v: Vec2): Node {
        return lineTo(v.x, v.y, 0)
    }

    fun cubicTo(
        x0: Number,
        y0: Number,
        z0: Number = 0,
        x1: Number,
        y1: Number,
        z1: Number = 0,
        x: Number,
        y: Number,
        z: Number = 0
    ): Node {
        if (breakNext) {
            breakNext = false
            return moveTo(x, y, z)
        }
        nodes.add(
            CubicTo().set(
                Vec3(x.toFloat(), y.toFloat(), z.toFloat()),
                Vec3(x0.toFloat(), y0.toFloat(), z0.toFloat()),
                Vec3(x1.toFloat(), y1.toFloat(), z1.toFloat()),
            )
        )
        return nodes.last()
    }



    fun byThickening(_thickness: Float) = FLinesAndJavaShapes.javaShapeToFLine(
        FLinesAndJavaShapes.flineToJavaShape(
            copy {
                thickness = _thickness.toDouble()
            })
    )

    fun byFiltering(predicate: (Vec3?, Node) -> Boolean): FLine {
        fun removeInstruction(at: Vec3?, node: Node, inside: FLine) {
            val i = inside.nodes.indexOf(node)
            if (at == null) {
                inside.nodes.removeAt(i)
            } else inside.nodes[i] = MoveTo().set(node.to)
        }
        return copy {
            val segments = ArrayList(this.nodes)
            var at: Vec3? = null
            segments.forEachIndexed { i, node ->
                if (!predicate(at, node)) {
                    removeInstruction(at, node, this)
                }
                at = node.to
            }
        }
    }

//    @Doc("this is some documentation, in *markdown* format _really_")
//    fun frob(x: Number, y: Number, z: Number = 0): Node {
//        return MoveTo()
//    }

    fun cubicTo(
        x0: Number,
        y0: Number,
        x1: Number,
        y1: Number,
        x: Number,
        y: Number,
    ): Node {
        if (breakNext) {
            breakNext = false
            return moveTo(x, y, 0.0)
        }
        nodes.add(
            CubicTo().set(
                vec(x, y, 0.0),
                vec(x0, y0, 0.0),
                vec(x1, y1, 0.0),
            )
        )
        return nodes.last()
    }

    fun cubicTo(v0: Vec3, v1: Vec3, v: Vec3): Node {
        return cubicTo(v0.x, v0.y, v0.z, v1.x, v1.y, v1.z, v.x, v.y, v.z)
    }

    fun close(): Node {
        return Close()
    }

    fun transform(by: (Vec3) -> Vec3): FLine {
        val r = FLine()
        nodes.forEach {
            r.nodes.add(it.copy(by))
        }
        r.properties.putAll(properties)
        return r
    }

    operator fun plus(n: Vec3): FLine {
        return transform { it + n }
    }

    operator fun minus(n: Vec3): FLine {
        return transform { it - n }
    }

    operator fun times(n: Vec3): FLine {
        return transform { it * n }
    }

    operator fun times(n: Number): FLine {
        return transform { it * n }
    }


    operator fun plus(n: Vec2) = this + n.v3

    operator fun minus(n: Vec2) = this - n.v3

    operator fun times(n: Vec2) = this * n.v3


    fun copyTo(other: Node) {
        nodes.add(other.copy())
    }

    fun copy() = this.transform { Vec3(it) }
    fun copy(block: FLine.() -> Unit) = this.copy().apply(block)

    fun cursor(tol: Float = 0.01f): FLinesAndJavaShapes.Cursor = FLinesAndJavaShapes.Cursor(this, tol = tol.toFloat())

    fun cursor(closestTo: Vec3): Cursor {
        val c = this.cursor()
        val t = FLinesAndJavaShapes.closestT(this, closestTo)
        c.setT(t)
        return c
    }


    fun sampleByNumber(steps: Int): List<Vec3> {
        val c = cursor()
        val r = ArrayList<Vec3>()
        for (i in 0 until steps) {
            c.setD(c.lengthD() * i / (steps - 1f))
            r.add(c.position())
        }
        return r
    }


    /**
     * returns a list of `Vec3` (i.e. positions) by repeatedly moving along the line by 'distance' and recording those positions. Note that while the start of the line will be in this list the
     * end point will probably not (unless the length of the line just happens to be exactly divisible by `distance`)
     */
    fun sampleByDistance(distance: Float): List<Vec3> {
        val c = cursor()
        val r = ArrayList<Vec3>()
        val tol = min(0.1f, 0.1f * distance).toFloat()
        while (c.d <= c.lengthD()) {
            r.add(c.position())
            val was = c.d
            c.setD(c.d + distance)
            val now = c.d
            if (now - was < abs(distance - tol) && abs(now - c.lengthD()) < tol) break
        }
        return r
    }

    override fun setPropertyRaw(name: String, value: Any, doResync: Boolean) {
        properties.put(name, value)
    }

    override fun getPropertyRaw(name: String, default: Any?): Any? = properties.get(name)

    override fun toString(): String {
        return "FLine[{${nodes.size}n]"
    }

    fun pieces(): List<FLine> {
        val ff = mutableListOf<FLine>()

        var d = FLine()
        for (i in this.nodes.indices) {
            if (this.nodes[i] is MoveTo) {
                d = FLine()
                ff.add(d)
            }

            d.nodes.add(this.nodes[i].copy())
        }
        return ff
    }

    fun fast2DBounds(): Rect {
        var r: Rect? = null
        nodes.forEach {
            if (r == null) r = Rect(it.to.x.d, it.to.y.d, 0.0, 0.0)
            else {
                val mix = Math.min(r.x.d, it.to.x.d)
                val miy = Math.min(r.y.d, it.to.y.d)
                val mxx = Math.max(r.x.d + r.w, it.to.x.d)
                val mxy = Math.max(r.y.d + r.h, it.to.y.d)
                r = Rect(mix, miy, mxx - mix, mxy - miy)
            }
        }
        return r!!
    }

    fun data(format: String, parameters: List<Any>): FLine {
        val points = parametersToPoints(parameters) // -> MutableList<Vec3>
        val ast = Parser(format).parse()
        val exec = Executor(this, points)
        exec.exec(ast)

        // If you'd prefer to silently ignore leftovers, delete this check.
        if (exec.remainingPoints() != 0) {
            throw IllegalArgumentException(
                "Unused parameters: ${exec.remainingPoints()} point(s) left after applying format '$format'. " +
                        "Consider adding a trailing '+' group if you intend to consume everything."
            )
        }
        return this
    }


    fun uniform_linearize(samplesPerCubic: Int = 100, a: (Boolean, Vec3) -> Unit) {
        val storage = vec(0, 0, 0)
        val at = vec(0, 0, 0)
        var ok = false
        var breakNext = true;
        var lastOpen = vec(0, 0, 0)
        var lastOpenOk = false
        nodes.forEach {
            when (it) {
                is MoveTo -> {
                    storage.set(it.to)
                    ok = true;
                    breakNext = true;
                    lastOpen.set(it.to)
                    lastOpenOk = true;
                }

                is LineTo -> {
                    if (!ok) {
                        storage.set(it.to)
                        breakNext = true
                        ok = true;
                        lastOpen.set(it.to)
                        lastOpenOk = true;
                    } else {
                        if (breakNext) {
                            a(true, storage)
                        }
                        storage.set(it.to)
                        a(false, storage)
                        ok = true
                        breakNext = false
                    }
                }

                is CubicTo -> {
                    if (!ok) {
                        storage.set(it.to)
                        breakNext = true
                        ok = true;
                        lastOpen.set(it.to)
                        lastOpenOk = true;
                    } else {
                        if (breakNext) {
                            a(true, storage)
                            breakNext = false
                        }
                        at.set(storage)
                        uniform_linearize_cubic(a, at, it.c1, it.c2, it.to, samplesPerCubic, storage)
                    }
                }

                is Close -> {
                    if (lastOpenOk && ok) {
                        a(false, lastOpen)
                        storage.set(lastOpen)
                    }
                }
            }
        }
    }

    fun uniform_linearize_cubic(
        dest: (Boolean, Vec3) -> Unit, a: Vec3, c1: Vec3, c2: Vec3, b: Vec3, samplesPerCubic: Int = 100, storage: Vec3
    ) {
        samplesPerCubic {
            val alpha = (it + 1) / samplesPerCubic.d

            val oma = 1 - alpha
            val oma2 = oma * oma
            val oma3 = oma2 * oma
            val alpha2 = alpha * alpha
            val alpha3 = alpha2 * alpha

            storage.x = ((a.x * oma3) + (3 * c1.x * alpha * oma2) + (3 * c2.x * alpha2 * oma) + (b.x * alpha3)).f
            storage.y = ((a.y * oma3) + (3 * c1.y * alpha * oma2) + (3 * c2.y * alpha2 * oma) + (b.y * alpha3)).f
            storage.z = ((a.z * oma3) + (3 * c1.z * alpha * oma2) + (3 * c2.z * alpha2 * oma) + (b.z * alpha3)).f
            dest(false, storage)
        }
    }

    fun tessellate(samplesPerCubic: Int = 100, meshAcceptor : (DoubleArray, List<Int>) -> Unit) {
        var d = ArrayList<Double>()

        fun dispatch() {
            val da = d.toDoubleArray()
            val c = Earcut.earcut(da,  null, 3)
            meshAcceptor(da, c)
        }

        uniform_linearize(samplesPerCubic) { breakBefore, at ->
            if (breakBefore) {
                if (d.size >= 9) {
                    dispatch()
                }
                d.clear()
            }
            d.add(at.x.d)
            d.add(at.y.d)
            d.add(at.z.d)
        }
        if (d.size >= 9) {
            dispatch()
        }
    }

}


private fun FLine.parametersToPoints(params: List<Any>): MutableList<Vec3> {
    val out = mutableListOf<Vec3>()
    var i = 0
    while (i < params.size) {
        when (val p = params[i]) {
            is Vec3 -> { out += p; i++ }
            is Vec2 -> { out += Vec3(p.x, p.y, 0f); i++ }
            is Number -> {
                // Collect a contiguous run of numbers and split into points.
                val nums = mutableListOf<Float>()
                var j = i
                while (j < params.size && params[j] is Number) {
                    nums += (params[j] as Number).toFloat()
                    j++
                }
                var k = 0
                while (k < nums.size) {
                    val left = nums.size - k
                    when {
                        // Prefer (x,y,z) triples when the run length is odd to avoid a single leftover.
                        left >= 3 && left % 2 == 1 -> {
                            out += Vec3(nums[k], nums[k + 1], nums[k + 2]); k += 3
                        }
                        left >= 2 -> {
                            out += Vec3(nums[k], nums[k + 1], 0f); k += 2
                        }
                        else -> {
                            throw IllegalArgumentException(
                                "Found a run of scalar numbers with a single leftover value (${nums[k]}). " +
                                        "Wrap as Vec2/Vec3 or add a trailing 0 for z."
                            )
                        }
                    }
                }
                i = j
            }
            else -> {
                throw IllegalArgumentException(
                    "Unsupported parameter type at index $i: ${p::class.qualifiedName}. " +
                            "Expected Number, Vec2, or Vec3."
                )
            }
        }
    }
    return out
}

// --- tiny parser / AST -------------------------------------------------------

private sealed interface Node
private data class Cmd(val kind: Char): Node // 'm','l','c','z'
private data class Seq(val items: List<Node>): Node
private data class Rep(val body: Node): Node // postfix '+'

private class Parser(private val s: String) {
    private var i = 0
    fun parse(): Node {
        val n = parseSeq()
        skip()
        if (i != s.length) error("Unexpected trailing input at ${here()}")
        return n
    }
    private fun parseSeq(stop: Char? = null): Node {
        val items = mutableListOf<Node>()
        while (true) {
            skip()
            if (i >= s.length) break
            if (stop != null && s[i] == stop) { i++; break }
            val atom = when (s[i]) {
                'm','l','c','z' -> { i++; Cmd(s[i - 1]) }
                '(' -> { i++; parseSeq(')') }
                else -> error("Unexpected '${s[i]}' at ${here()}; expected m,l,c,z or '('")
            }
            skip()
            val node = if (i < s.length && s[i] == '+') { i++; Rep(atom) } else atom
            items += node
        }
        return when (items.size) {
            0 -> Seq(emptyList())
            1 -> items[0]
            else -> Seq(items)
        }
    }
    private fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
    private fun here(): String = "pos $i in \"$s\""
}

// --- executor with greedy-but-safe '+' handling ------------------------------

private class Executor(private val f: FLine, private val pts: MutableList<Vec3>) {
    private var pos = 0

    fun remainingPoints(): Int = pts.size - pos

    fun exec(n: Node) {
        when (n) {
            is Cmd -> execCmd(n.kind)
            is Seq -> execSeq(n)
            is Rep -> execRep(n, suffixMin = 0) // top-level '+' has no suffix
        }
    }

    private fun execSeq(seq: Seq) {
        val items = seq.items
        // Precompute minimal suffix points needed from each position.
        val suffixMin = IntArray(items.size + 1)
        suffixMin[items.size] = 0
        for (i in items.size - 1 downTo 0) {
            suffixMin[i] = minPoints(items[i]) + suffixMin[i + 1]
        }
        for (i in items.indices) {
            when (val it = items[i]) {
                is Rep -> execRep(it, suffixMin = suffixMin[i + 1])
                else -> exec(it)
            }
        }
    }

    private fun execRep(rep: Rep, suffixMin: Int) {
        val minBody = minPoints(rep.body)
        if (minBody == 0) return // avoid infinite loop on e.g. 'z+'
        while (remainingPoints() - suffixMin >= minBody) {
            val before = pos
            exec(rep.body)
            if (pos == before) break // paranoia guard
        }
    }

    private fun execCmd(k: Char) {
        when (k) {
            'm' -> {
                ensure(1, "moveTo")
                val a = take()
                f.moveTo(a)          // uses your Vec3 overload
            }
            'l' -> {
                ensure(1, "lineTo")
                val a = take()
                f.lineTo(a)          // uses your Vec3 overload
            }
            'c' -> {
                ensure(3, "cubicTo")
                val a = take(); val b = take(); val c = take()
                f.cubicTo(a, b, c)   // uses your Vec3 overload
            }
            'z' -> f.close()
            else -> error("Unknown command: $k")
        }
    }

    private fun minPoints(n: Node): Int = when (n) {
        is Cmd -> when (n.kind) { 'm' -> 1; 'l' -> 1; 'c' -> 3; 'z' -> 0; else -> 0 }
        is Seq -> n.items.sumOf { minPoints(it) }
        is Rep -> 0 // zero-or-more
    }

    private fun take(): Vec3 {
        if (pos >= pts.size) throw IllegalArgumentException("Not enough points for the requested command(s).")
        return pts[pos++]
    }
    private fun ensure(n: Int, what: String) {
        if (remainingPoints() < n) {
            throw IllegalArgumentException("Need $n point(s) for $what, but only ${remainingPoints()} left.")
        }
    }
}
