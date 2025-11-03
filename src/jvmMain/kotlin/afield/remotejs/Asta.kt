package afield.remotejs

import afield.notify
import org.openjdk.nashorn.api.tree.*
import java.lang.reflect.Method
import java.util.*
import java.util.function.Function
import kotlin.Pair
import kotlin.to

/*    AST based rewriting
 */
class Asta {

    val disabled = false

    class Options {
        var overloads = true
        var numbers = false
        var functionRewrite = false
        fun on(): Boolean = overloads || numbers || functionRewrite
    }

    var options = Options()

    var currentMapping = mutableMapOf<Pair<Int, Int>, Pair<Int, Int>>()
    var lastReturn = mutableMapOf<Pair<Int, Int>, Double>()
    var currentContents: String? = null
    var baseContents: String? = null

    val accessorMap: Map<Tree.Kind, List<Method>> = Tree.Kind.values().filter { it.asInterface() != null }.map {
        val access = it.asInterface().declaredMethods.filter {
            (it.parameterCount == 0 && Tree::class.java.isAssignableFrom(it.returnType)) || (it.parameterCount == 0 && List::class.java.isAssignableFrom(
                it.returnType
            ))
        }.toMutableList()

        if (it == Tree.Kind.FOR_IN_LOOP) {
            // also do ForOfLoop
            access.addAll(ForOfLoopTree::class.java.declaredMethods.filter {
                (it.parameterCount == 0 && Tree::class.java.isAssignableFrom(it.returnType)) || (it.parameterCount == 0 && List::class.java.isAssignableFrom(
                    it.returnType
                ))
            }.toList())
        }

        it to access
    }.toMap()


    val trapKinds = setOf(Tree.Kind.PLUS, Tree.Kind.MINUS, Tree.Kind.DIVIDE, Tree.Kind.MULTIPLY, Tree.Kind.XOR)

    fun parse(v: String) {
        val p = Parser.create("--language=es6")
        val r = p.parse("<<internal>>", v, {
            print("diagnostic: $it")
        })

        r.sourceElements.forEach {
            //            print("\n\n sourceElement: $it\n\n")
            recurOver(it, 0, v)
        }

    }

    var hash = 0L

    @JvmOverloads
    fun parseAndReconstruct(v: String, d: (Diagnostic) -> Unit = { print("diagnostic: $it") }): String {

        if (!options.on()) return v

        try {
            val (trimmed, suffix) = trimSrcURL(v)


            val p = Parser.create("--language=es6")
            val r = p.parse("<<internal>>", trimmed + "\n", d)

            var actions = mutableListOf<() -> Unit>()

            var replaced = trimmed

            var forbiddenRanges = mutableListOf<Pair<Long, Long>>();

            r.sourceElements.forEach {
                findForbiddenRanges(it, forbiddenRanges);
            }


            hash = 0L

            r.sourceElements.forEach {
                //            print("\n\n sourceElement: $it\n\n")

                var (s, e) = startAndEndPositionFor(it)

                actions.add {
                    replaced = replaced.replaceRange(
                        s,
                        e,
                        reconstructOver(it, 0, v, forbiddenRanges, parent = null, hash = 0L)
                    )
                }
            }

            actions.reversed().forEach { it() }

            return replaced + "\n" + suffix
        }
        catch(a : AssertionError) {
            notify("ASTA parser crashed in code, returning code as-is", "error")
            return v
        }
    }

    private fun findForbiddenRanges(it: Tree, forbiddenRanges: MutableList<Pair<Long, Long>>) {

        if (it is ForLoopTree) {
            val a = it.startPosition
            val b = it.statement.startPosition
            forbiddenRanges.add(a to b)
        }

        childrenFor(it).forEach { findForbiddenRanges(it, forbiddenRanges) }

    }

    private fun recurOver(tree: Tree, i: Int, v: String) {
        val children = childrenFor(tree)

        var (start, end) = startAndEndPositionFor(tree)

        var text = v.substring(start, end)

//        if (debug) println(" node covers ${start} -> ${end} = $text")

        var n = children.size - 1
        for (c in children.asReversed()) {

//            if (debug) println(" replace range ${(c.startPosition - start).toInt()..(c.endPosition - start - 1).toInt() - 1}  : '" + text + "'")

            val (s, e) = startAndEndPositionFor(c)

            text = text.replaceRange((c.startPosition - start).toInt()..(c.endPosition - start - 1).toInt(), "<<$n>>")
            n--
        }


        children.forEach { recurOver(it!!, i + 2, v) }
    }

    private fun reconstructOver(
        tree: Tree,
        i: Int,
        v: String,
        forbiddenRanges: List<Pair<Long, Long>>,
        parent: Tree? = null,
        hash: Long
    ): String {

        val children = childrenFor(tree)

        var (start, end) = startAndEndPositionFor(tree, true)

        var text = v.substring(start, end)


//        if (debug) println("${indent(i)} node covers ${start} -> ${end} = $text")

        if (options.overloads && tree is BinaryTree && tree.kind in trapKinds && !forbidden(
                tree.startPosition,
                tree.endPosition,
                forbiddenRanges
            )
        ) {
//            if (debug) println(" ** binary operator :" + tree)

//            if (!(text.contains("'") || text.contains("\"") || text.contains("`")))
            if (true) {

                // is this inside the initializer of a for loop? if so, replacing anything here will wreck everyone's offsets

                val left = children.get(0)
                val right = children.get(1)

                val (sr, er) = startAndEndPositionFor(right)
                var rightR = reconstructOver(
                    right,
                    i + 5,
                    v,
                    forbiddenRanges,
                    parent,
                    hash = 31 * hash + hashFor(right, "right".hashCode().toLong())
                )//text.replaceRange((sr - start).toInt()..(er - start - 1).toInt(), )


                val (sc, ec) = startAndEndPositionFor(left)
                var leftR = reconstructOver(
                    left,
                    i + 5,
                    v,
                    forbiddenRanges,
                    parent,
                    hash = 31 * hash + hashFor(left, "left".hashCode().toLong())
                )//rightR.replaceRange((sc - start).toInt()..(ec - start - 1).toInt())


                val middle = v.substring(ec, sr)

                // search for missing brackets --- Some parens are part of no element apparently

                var closeBracket = count(middle, ')');
                val openBracket = count(middle, '(');

//                println(" middle text is `$middle` $closeBracket, $openBracket")
//                println("left `$leftR`")
//                println("right `$rightR`")

                if (rightR.trim().endsWith("//"))
                    rightR = rightR.trim().substring(0, rightR.trim().length - 2)

                if (leftR.startsWith("(") && count(leftR, '(') == count(leftR, ')') + 1 && closeBracket > 0) {
                    leftR = leftR.substring(1)
                    closeBracket--
                }
                if (leftR.endsWith(")") && count(leftR, ')') == count(
                        leftR,
                        '('
                    ) + 1 && closeBracket == 0 && openBracket == 0
                ) {
                    leftR = leftR.substring(0, leftR.length - 1)
                    closeBracket++
                }

                return duplicate(openBracket, "(") + "__" + tree.kind + "__(" + leftR + "," + rightR + ")" + duplicate(
                    closeBracket,
                    ")"
                )
            }
        }

        if (options.numbers && tree is LiteralTree && tree.kind.equals(Tree.Kind.NUMBER_LITERAL)) {
            return "__NUMBER_LITERAL__(" + start + ", " + end + ", " + text + ")"
        }


        var n = children.size - 1
        for (c in children.asReversed()) {

            val (s, e) = startAndEndPositionFor(c)

            text = text.replaceRange(
                (s - start).toInt()..(e - start - 1).toInt(),
                reconstructOver(
                    c,
                    i + 5,
                    v,
                    forbiddenRanges,
                    parent = tree,
                    hash = hash * 31 + hashFor(c, n.toLong())
                )
            )
            n--
        }


        return text
    }

    private fun hashFor(c: Tree, salt: Long = 0): Long {
        return c.kind.hashCode().toLong() * 31 + salt
    }


    private fun forbidden(startPosition: Long, endPosition: Long, f: List<Pair<Long, Long>>): Boolean {
        f.forEach {
            if (startPosition < it.second && endPosition > it.first) return true
        }
        return false
    }

    private fun count(middle: String, s: Char): Int {
        var x = 0;
        for (a in middle) {
            if (a == s) x++
        }
        return x;
    }

    private fun duplicate(num: Int, s: String): String {
        var r = ""
        for (i in 0 until num)
            r += s
        return r
    }

    private fun childrenFor(tree: Tree): List<Tree> {
//        if (debug) println(" children for :" + tree + " " + tree.javaClass + " " + tree.kind)
        return accessorMap[tree.kind]!!.flatMap {
            //            if (debug) println("-> $it ($tree)")
            try {
                try {
                    val rr = it.invoke(tree)
                    when (rr) {
                        null -> {
                            Collections.EMPTY_LIST
                        }

                        is Tree -> Collections.singleton(rr)
                        is List<*> -> rr
                        else -> {
                            Collections.EMPTY_LIST
                        }
                    }
                } catch (ee: ClassCastException) {
                    // this happens when we call forloop stuff on a forofloop
//                    ee.printStackTrace()
                    Collections.EMPTY_LIST
                }
            } catch (e: java.lang.IllegalArgumentException) {
//                e.printStackTrace()
                Collections.EMPTY_LIST
            }
        }.filter { it != null }.map { it as Tree }.sortedBy { it!!.startPosition }.toList()
    }

    private fun startAndEndPositionFor(c: Tree, correct: Boolean = true): Pair<Int, Int> {
        var start = c.startPosition.toInt()
        var end = c.endPosition.toInt()

//        println("start/end in $c = $start/$end")
        // Nashorn appears to just lie about the start and end position of some parts of the tree.

        if (c.kind == Tree.Kind.STRING_LITERAL) {
            return start - 1 to end + 1
        }

        if (/*start == end &&*/ correct) {
            val cc = childrenFor(c)
            if (cc.size == 0) return start to end

            val ccSE = cc.map { startAndEndPositionFor(it) }.toList()
            val min = ccSE.minByOrNull { it: Pair<Int, Int> -> it.first }!!.first
            val max = ccSE.maxByOrNull { it: Pair<Int, Int> -> it.second }!!.second
            val r = Math.min(min, start) to Math.max(max, end)

//            println("start/end out $c = $r")
            return r;
        }
        return start to end
    }

    private fun indent(i: Int): String {
        var q = ""
        while (q.length < i) q = q + " "
        return q
    }

    fun options(): Options {
        return options
    }

    // Field source urls look like:
    //  //# sourceURL=bx[Untitled]/_d5b9fda0_5d2b_40a3_96c8_7926a9fb56cb

    // sourceURL=bx[viewport]/viewport/_f46e4d28_e0e1_453b_b698_ae4352804e51
    var srcURL = Regex("(\\/\\/# sourceURL=bx\\[.*\\]\\/_........_...._...._...._............)")

    fun trimSrcURL(src: String): Pair<String, String> {
        val res = srcURL.find(src)
        if (res != null) {
            return srcURL.replace(src, "") to res.groups[0]!!.value
        }
        return src to ""
    }

}
