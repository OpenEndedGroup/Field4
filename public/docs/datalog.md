# Datalog in a box

A Field document is a graph. Boxes have parents and children, they nest, they carry properties,
and the questions you end up asking about one are recursive: what runs downstream of this, what
does nothing feed into, which two boxes both write the same property. `graphPaths`
([Querying Object Graphs](graph_query.md)) can address a slot by pattern, but it cannot follow an
edge and then follow it again.

`afield.datalog` can. It is a small deductive database: you state facts, you write rules that
derive more facts from them, and the engine runs the rules to exhaustion. Recursion is the normal
case rather than a special one, so transitive closure is two lines. Facts can come from Datalog
text, from Kotlin collections, or from the document you have open.

Everything on this page is real output from a running box.

## Facts, rules, and a question

A fact is a row. A rule reads `head :- body`, which you can say out loud as *head if body*.
Capitalised names are variables; lowercase names are constants, so `imports(app, ui)` and
`imports("app", "ui")` are the same fact.

<!--
FIELD_TEST
id: docs.datalog.modules
runner: backdoor-box
tags: [docs, datalog, inline-output]
timeoutSec: 90
waitSec: 2.0
expect:
  outputContains:
    - "app needs log, store, ui, util"
    - "does app need log? true"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/datalog/modules.png" capture="editor" wait="4" padding="10" timeout="180" embed=true name="datalog-modules" w="900" h="420"}
:::static
val d = Datalog()
d.add("""
    #datalog
    % which module pulls in which
    imports(app, ui).    imports(app, store).
    imports(ui, util).   imports(store, util).  imports(util, log).

    needs(A, B) :- imports(A, B).
    needs(A, C) :- needs(A, B), imports(B, C).
""")

println("app needs " + d.query("needs(app, X)").column("X").joinToString(", "))
println("does app need log? " + d.ask("needs(app, log)"))
println("modules that need log: " + d.query("needs(A, log)").size)
```

Answers land beside the line that asked, which is the shortest loop there is: edit a rule, run, read.
Two rules for `needs`, read as alternatives: `app` needs whatever it imports, and it needs whatever
*that* needs. Nothing says how far to follow the chain, and nothing has to.

The `#datalog` on the first line is a marker the editor looks for; the parser ignores it. Leave it
out and the program still runs. Put it in and the region lights up, which the last section covers.

## The answer is a fixpoint

`query` runs one goal. `solve` hands back the whole model: every relation, saturated. It is worth
looking at once, because it shows what the engine actually did.

<!--
FIELD_TEST
id: docs.datalog.fixpoint
runner: backdoor-box
tags: [docs, datalog, tohtml]
timeoutSec: 60
waitSec: 1.5
expect:
  outputContains:
    - "facts in 2 relations"
    - "path"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/datalog/fixpoint.png" wait="1.5" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true name="datalog-fixpoint" w="760" h="260"}
:::static
val d = Datalog()
d.add("""
    #datalog
    edge(a, b). edge(b, c). edge(c, d). edge(d, b).
    path(X, Y) :- edge(X, Y).
    path(X, Z) :- path(X, Y), edge(Y, Z).
""")

display("main", d.solve())
```

Four stated edges produce twelve paths. The cycle `b → c → d → b` makes those three nodes mutually
reachable, including each from itself, and the rules terminate anyway: a rule can only ever add
rows that are already in the (finite) product of what it reads, so there is nothing left to add
after a few rounds.

## Why is that true?

A query engine that answers `yes` and stops is hard to trust on a graph you cannot see. Ask for the
derivation instead.

<!--
FIELD_TEST
id: docs.datalog.why
runner: backdoor-box
tags: [docs, datalog, tohtml]
timeoutSec: 60
waitSec: 1.5
expect:
  outputContains:
    - "why path(a, d)"
    - "edge(c, d)"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/datalog/why.png" wait="1.5" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true name="datalog-why" w="760" h="260"}
:::static
val d = Datalog()
d.add("""
    #datalog
    edge(a, b). edge(b, c). edge(c, d). edge(d, b).
    path(X, Y) :- edge(X, Y).
    path(X, Z) :- path(X, Y), edge(Y, Z).
""")

display("main", d.why("path(a, d)"))
```

Each line names the rule that produced it, and the leaves are facts you stated. The search
terminates on cyclic input because the engine records which round each fact first appeared in, and
a proof may only descend to strictly earlier rounds. `path(a, a)` in the graph above gets a proof
too, rather than an infinite regress.

A proof runs through an aggregate as well, and that is the case worth having: `count` and `sum` are
exactly the numbers you cannot check by reading. A fact derived through one is justified by the
tuples that contributed to it, plus the arithmetic that combined them.

```diffstatic {data-lang=text}
payroll(sales, 300)   [payroll(D, S) :- dept(D), S = sum P : { emp(_1, D, P) }]
    dept(sales)   [fact]
    S = sum P : { emp(_1, sales, P) }   [aggregate: sum over 2 tuples = 300]
        emp(ada, sales, 100)   [fact; contributes P = 100]
        emp(bob, sales, 200)   [fact; contributes P = 200]
```

`min` and `max` label the tuple that was the extreme, and keep the rest of the group in the proof
too, because "nothing bigger exists" is half of what the answer means. A group is capped at 24 lines
of display; the value is always computed over the whole of it. A negated body literal appears as its
own step, since "no such fact" is often the more surprising half of why a rule fired.

The number in the proof is recomputed from the group and checked against the model. If the two ever
disagreed you would get the old answer — a note saying no proof was reconstructed — rather than a
plausible wrong tree.

## Why *not*

The question a query asks more often. An empty result gives no reason, so `whyNot` walks the
conjunction and names the literal that took the answer set to zero.

```diffstatic {data-lang=text}
whynot cell(C, V), V > 5, colour(C, red)   no answers
     1  cell(C, V)      3 answers
     2  V > 5           1 answer
     3  colour(C, red)  0 answers   <- nothing survives here
    a binding that got this far: C = a3, V = 10
```

Ask about a single derived fact and it does the same walk once per rule that could have produced it,
with the head's constants filled in, and puts the attempt that got furthest first:

```diffstatic {data-lang=text}
whynot path(a, e)   not in the model; 2 rule(s) could have produced it
  via path(X, Z) :- path(X, Y), edge(Y, Z)
       1  path(a, Y)  3 answers
       2  edge(Y, e)  0 answers   <- nothing survives here
      a binding that got this far: Y = b
  via path(X, Y) :- edge(X, Y)
       1  edge(a, e)  0 answers   <- nothing survives here
```

The counts come from the real engine — each prefix of the conjunction is run as a rule — so they
cannot disagree with the query they are explaining. Three answers are kept distinct, because in an
empty result they look identical: a literal with no matching rows, a predicate nothing defines at
all, and a test written before the atom that binds its variable. The last of those is reported as
"not evaluable this early" rather than as zero, which is usually the whole answer.

## Your own data, not Datalog's

`facts` takes Kotlin objects and a projection, so the rules can run over whatever you already have
in the box. Negation and aggregation are both available, with the usual condition: they need the
relations they read to be finished first, so a rule may not negate or aggregate over something that
depends on itself. The engine checks and says so by name if you try.

<!--
FIELD_TEST
id: docs.datalog.takes
runner: backdoor-box
tags: [docs, datalog, tohtml]
timeoutSec: 60
waitSec: 1.5
expect:
  outputContains:
    - "footage(S, Total, Best)"
    - "insert"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/datalog/takes.png" wait="1.5" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true name="datalog-takes" w="760" h="330"}
:::static
data class Take(val clip: String, val shot: String, val seconds: Int, val rating: Int)

val takes = listOf(
    Take("a017", "wide",   42, 5), Take("a018", "wide",   38, 2),
    Take("a021", "close",  19, 4), Take("a022", "close",  22, 3),
    Take("a030", "insert",  8, 2), Take("a031", "insert", 11, 1),
)

val d = Datalog()
d.facts("take", takes) { listOf(it.clip, it.shot, it.seconds, it.rating) }
d.facts("shot", listOf(listOf("wide"), listOf("close"), listOf("insert")))
d.add("""
    #datalog
    covered(S) :- take(_, S, _, R), R >= 4.
    reshoot(S) :- shot(S), !covered(S).
    footage(S, Total, Best) :- shot(S),
        Total = sum Sec : { take(_, S, Sec, _) },
        Best  = max R   : { take(_, S, _, R) }.
""")

display("main", d.query("footage(S, Total, Best)"))
display("reshoot", d.query("reshoot(S)"))
```

An aggregate groups by whichever of its variables the enclosing rule has already bound. `shot(S)`
binds `S` before the aggregate runs, so `sum Sec : { take(_, S, Sec, _) }` totals one shot at a
time. Take `shot(S)` out and you get one number for the whole table.

`_` is a hole, and each one is separate: `take(_, S, Sec, _)` means *some* take of this shot, not a
take whose clip and rating are equal. Under `!` a hole is existential, so `!covered(S)` and
`!child(_, X)` both mean "no row matches".

## The document is a database

`Box.datalog(...)` loads the graph you are looking at. Boxes, names, edges, nesting, frames and
scalar properties all arrive as relations:

<!--
FIELD_TEST
id: docs.datalog.schema
runner: backdoor-box
tags: [docs, datalog, tohtml]
timeoutSec: 60
waitSec: 1.5
expect:
  outputContains:
    - "child(Parent, Child)"
    - "prop(Id, Key, Value)"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/datalog/schema.png" wait="1.5" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true name="datalog-schema" w="760" h="290"}
:::static
display("main", dlBoxRelationTable())
```

Which makes the recursive questions about a document short. Here the graph is built in the box so
the answer is reproducible, but `thisBox.datalog(program)` runs the identical rules against the
document you have open:

<!--
FIELD_TEST
id: docs.datalog.document
runner: backdoor-box
tags: [docs, datalog, tohtml]
timeoutSec: 60
waitSec: 1.5
expect:
  outputContains:
    - "downstream(loader, X)"
    - "shader"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/datalog/document.png" wait="1.5" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true name="datalog-document" w="760" h="380"}
:::static
fun boxNamed(id: String, name: String, parent: Box?): Box {
    val b = Box(id)
    b.name = name
    if (parent != null) { parent.children.add(b); b.parents.add(parent) }
    return b
}

val root   = boxNamed("root", "document", null)
val loader = boxNamed("loader", "loader", root)
val mesh   = boxNamed("mesh", "mesh", loader)
val shader = boxNamed("shader", "shader", loader)
val draw   = boxNamed("draw", "draw", mesh)
val notes  = boxNamed("notes", "notes", root)

val d = Datalog()
d.addBoxGraph(root)
d.add("""
    #datalog
    downstream(A, B) :- child(A, B).
    downstream(A, C) :- downstream(A, B), child(B, C).
    leaf(X) :- box(X), !child(X, _).
""")

display("main", d.query("downstream(loader, X), name(X, N)"))
display("leaves", d.query("leaf(X), name(X, N)"))
```

The graph is read once, as a snapshot, so an answer describes the document as it was when you
asked. Build a new `Datalog` to refresh it.

## Any object graph, not just the document

The document bridge is a schema someone wrote by hand. The general one comes from
[`graphPaths`](graph_query.md): walk any live object, and turn every slot it addressed into facts.

The two layers cover each other's gaps. A glob addresses slots but cannot follow an edge and then
follow it again, and it has nothing to say about two addresses that turn out to be the same object.
Datalog has the recursion and no idea what an object is. `addObjectGraph` costs one walk and gives
`slot`, `link`, `segment`, `depth`, `at`, `value`, `type` and `writable`; `dlObjectGraphRules` adds
the four rules worth having on top.

`at(Path, ObjectId)` is the one that pays for the trip. Every non-scalar value gets a number *by
identity*, so aliasing needs no special relation — it is `at` joined with itself:

<!--
FIELD_TEST
id: docs.datalog.object_graph
runner: backdoor-box
tags: [docs, datalog, graphquery, tohtml]
timeoutSec: 90
waitSec: 2.0
expect:
  outputContains:
    - "alias(A, B)"
    - "spare"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/datalog/object_graph.png" wait="1.5" wait-output=true wait-output-kind=display wait-output-channel="main" capture="box" embed=true name="datalog-object-graph" w="760" h="420"}
:::static
class Osc(var freq: Float, var gain: Float, val label: String)
class Rig(val carrier: Osc, val modulator: Osc, val spare: Osc, var title: String)

// carrier and spare are one oscillator under two names. Nothing in the type says so.
val shared = Osc(220f, 0.8f, "shared")
val rig = Rig(shared, Osc(440f, 0.2f, "mod"), shared, "rig")

val d = Datalog()
d.addObjectGraph(rig)
d.add(dlObjectGraphRules)

display("main", d.query("alias(A, B)"))
display("settable", d.query("reaches(\"carrier\", P), writable(P)"))
```

Two names for one oscillator, found without being told to look. Equality would have called all three
the same, since two `Osc(220f, 0.8f, "shared")` are equal and only one is *shared*; the walk already
visits a shared node under every alias, so the facts were there to join.

The right-hand answer is the other half: every settable slot below `carrier`, which needs the
transitive step a glob has no way to write.

Scope the walk with the same pattern `graphPaths` takes — `addObjectGraph(rig, "carrier/*")` loads
one level, and a literal prefix costs O(depth) where `**` walks everything.

`addBoxGraph` stays its own implementation rather than becoming a call to this. A document's useful
schema is `child`, `nested`, `frame` and `prop`, not the several hundred reflected fields a `Box`
actually has.

## Drawing what it derived

Query results are rows of ordinary values, so anything that eats values can eat them. Below, the
rules separate stated edges from the ones that follow, and the drawing gives each set a weight:

<!--
FIELD_TEST
id: docs.datalog.reachability_figure
runner: backdoor-box
tags: [docs, datalog, fline]
timeoutSec: 90
waitSec: 1.5
expect:
  outputContains:
    - "8 stated edges, 34 implied by them"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/datalog/reachability.png" wait="1.5" capture="box" open-outputs="true" embed=true name="datalog-reachability" w="760" h="430"}
:::static
val links = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 0, 1 to 5, 5 to 6, 6 to 2)

val d = Datalog()
d.facts("edge", links.map { listOf(it.first, it.second) })
d.add("""
    #datalog
    path(X, Y) :- edge(X, Y).
    path(X, Z) :- path(X, Y), edge(Y, Z).
    implied(X, Y) :- path(X, Y), !edge(X, Y), X != Y.
""")

val n = 7
fun at(i: Int): Vec2 {
    val a = i * 2.0 * PI / n - PI / 2
    return vec(340.0 + 300.0 * cos(a), 170.0 + 135.0 * sin(a))
}
fun link(a: Int, b: Int, c: Vec4, t: Double) = fline {
    val p = at(a); val q = at(b)
    moveTo(p.x, p.y)
    lineTo(q.x, q.y)
    color = c
    thickness = t
}
fun dot(i: Int) = fline {
    val p = at(i); val r = 5.0
    moveTo(p.x - r, p.y); lineTo(p.x, p.y - r); lineTo(p.x + r, p.y); lineTo(p.x, p.y + r)
    close()
    filled = true
    color = vec(0.98, 0.86, 0.30, 1.0)
}
// Node numbers, set just outside the ring so an edge never runs through one. Glyphs move with
// FLine.transform, which carries the cubic handles; nudging a node's point alone smears the letter.
fun label(i: Int): List<FLine> = runCatching {
    val a = i * 2.0 * PI / n - PI / 2
    val p = at(i)
    val layout = layoutText("$i", typeface("Monospaced", 22.0))
    val dx = p.x + 26.0 * cos(a) - layout.advanceWidth / 2.0
    val dy = p.y + 26.0 * sin(a) + 8.0
    layout.toFLines(color = vec(0.72, 0.78, 0.86, 1.0), thickness = 0.0, filled = true, stroked = false)
        .map { line -> line.transform { q -> vec(q.x + dx, q.y + dy, q.z) } }
}.getOrElse { emptyList() }

val implied = d.query("implied(X, Y)").rows
    .map { link((it[0] as Long).toInt(), (it[1] as Long).toInt(), vec(0.45, 0.70, 0.98, 0.20), 1.0) }
val direct = links.map { link(it.first, it.second, vec(0.98, 0.86, 0.30, 1.0), 2.0) }

println("${links.size} stated edges, ${implied.size} implied by them")
val labels = (0 until n).flatMap { label(it) }
lines(implied + direct + (0 until n).map { dot(it) } + labels, w = 700.0, h = 350.0, fitToSize = true)
```

Eight edges, thirty-four consequences. The faint lines are what `implied` derived, and the rule
that made them is the third line of the program. Its `X != Y` drops self-reachability, which the
cycle `0 → 1 → 2 → 3 → 4 → 0` would otherwise contribute for every node on it.

## A box that is Datalog

Set a box's main language to Datalog (`Set Main Language: Datalog` in the command palette) and its
children hold programs instead of Kotlin. A Datalog box accumulates: running a fragment merges it
into that box's program and prints what the fragment asked for, so you can write a rule, run it,
write a query underneath, and run just that line. Re-running the whole box is safe, because
identical rules are merged rather than appended.

A bare non-ground atom on its own is read as a question, so `over(C).` asks rather than failing as
a fact with a variable in it. Lines beginning with `#` are session commands.

No Kotlin at all in one of these. The box below is a Datalog box, and this is its whole contents:

```diffstatic_runner {data-lang=datalog screenshot="assets/datalog/box.png" capture="editor" wait="4" wait-output=true wait-output-kind="all" padding="10" timeout="240" embed=true name="datalog-box" w="900" h="360"}
:::static
% a spreadsheet's worth of cells, and one rule about them
cell(a1, 3). cell(a2, 4). cell(a3, 10).

over(C) :- cell(C, V), V > 5.

?- over(C).
```

Run a `#why` next to it and the box answers with the derivation rather than the row:

```diffstatic_runner {data-lang=datalog screenshot="assets/datalog/box_why.png" capture="editor" wait="4" wait-output=true wait-output-kind="all" padding="10" timeout="240" embed=true name="datalog-box-why" w="900" h="300"}
:::static
cell(a1, 3). cell(a3, 10).
over(C) :- cell(C, V), V > 5.

#why over(a3)
```

The session commands are `#help`, `#boxes` (load the live document into the base relations),
`#relations`, `#facts <relation>`, `#program`, `#why <goal>`, `#whynot <goal>` and `#reset`. A `#why`
about a fact that is not there points you at `#whynot`, which is where you wanted to be.

## The editor knows the language

A Kotlin string whose first line is `#datalog` is an embedded region, in the same way `#stacksl`
and `#petri` regions work ([Language Extensions](language_extensions.md)). Rule heads are coloured
differently from the same predicate used in a body, variables differently from constants, and
hovering a predicate reports its arity and how many clauses define it. Completion offers the
program's own predicates first, then the builtins and the box-graph relations.

```diffstatic_runner {data-lang=kotlin capture="ui" region="editor" screenshot="assets/datalog/region.png" wait="0" timeout="120" embed=true hidden=true name="datalog-region"}
ui.eval("var s=document.createElement('style');s.textContent='.tick_text.ok{display:none !important}';document.head.appendChild(s);return 'setup';")
ui.openEditor(id)
ui.pause(700)
// Set the model rather than typing it: the region only exists once its closing triple quote does,
// and a half-typed literal has nothing to highlight.
ui.eval(
    "var q = String.fromCharCode(34).repeat(3);" +
    "var code = ['val d = Datalog()', 'd.add(' + q, '#datalog'," +
    " '% which module pulls in which', 'imports(app, ui).  imports(ui, util).'," +
    " 'needs(A, B) :- imports(A, B).', 'needs(A, C) :- needs(A, B), imports(B, C).'," +
    " 'busy(M, N) :- imports(M, _), N = count : { needs(M, _) }.'," +
    " 'orphan(M) :- imports(_, M), !imports(M, _).', q + ')'," +
    " 'display(' + q.slice(0,1) + 'main' + q.slice(0,1) + ', d.query(' + q.slice(0,1) + 'needs(app, X)' + q.slice(0,1) + '))'].join('\\n');" +
    "window.globalEditor.getModel().setValue(code); return 'set';"
)
ui.pause(1500)
```

Diagnostics inside a region are lexical only: unbalanced brackets, an unterminated string, a clause
with no `.`. Whether a rule is safe or stratified is a property of the whole program, which the
engine decides when it runs; underlining a half-typed buffer for it would flag correct code.

## Reference

**Syntax.** `%` and `//` start a line comment, `/* */` a block. A clause ends with `.`. A variable
starts with a capital letter or `_`; everything else lowercase is a constant string. Numbers,
`"strings"`, `true`, `false` and `null` are literals. `!goal` or `not goal` negates. `?- goal.`
records a query.

**Arithmetic.** `+ - * /`, `mod`, `div`, and comparisons `= != < <= > >=`. `X = expr` binds `X`
when nothing has bound it yet and compares when something has. `+` concatenates if either side is a
string. Every integral number is stored as a `Long`, including a computed `4.0 / 2.0`, so
arithmetic results join with stated facts.

**Functions.** `abs sign sqrt floor ceil round pow exp log sin cos tan atan2 hypot min max len
upper lower trim cat substr indexOf contains startsWith endsWith split num str int not`. A call to
one of these on its own is a test, so `contains(N, "shader")` filters.

**Aggregates.** `count sum min max mean collect`, written `X = op Expr : { body }` (`count` takes
no expression). `min`, `max` and `mean` of an empty group have no value, so the rule does not fire
for that group; `count` and `sum` give 0. The target has to be a variable nothing has bound yet, so
"the maximum, and who earned it" is two rules rather than one.

**Explaining.** `why(goal)` returns a derivation tree, through aggregates and negation as well as
rules, or null when the fact is not in the model. `whyNot(goal)` returns the narrowing walk instead.
Both implement `ToHTML`, so `display(…)` of either from a Kotlin box draws an indented table; the
`#why` and `#whynot` directives inside a Datalog box print `render()`'s plain text instead.

**What it refuses.** A rule whose head has a variable the body never binds. A negated goal whose
variables are bound nowhere else. A predicate used at two different arities. Negation or
aggregation inside a recursive cycle, which it reports with the cycle named rather than picking an
evaluation order.

**Where it lives.** `src/jvmMain/kotlin/afield/datalog/`, and `dev_docs/DATALOG.md` for the
implementation notes.
