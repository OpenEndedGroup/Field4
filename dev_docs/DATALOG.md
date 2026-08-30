# Datalog in Field·4

`afield.datalog` is a small deductive database: facts, rules, recursion, stratified negation and
aggregation, with the live box graph available as base relations. It sits next to `afield.graphquery`
and answers the questions that one cannot — anything recursive, or anything that needs a join.

Sources live in `src/jvmMain/kotlin/afield/datalog/`:

| file | what it is |
| --- | --- |
| `DatalogAst.kt` | terms, literals, rules, value normalisation |
| `DatalogParser.kt` | lexer + recursive-descent parser for the surface syntax |
| `DatalogEngine.kt` | indexed relations, body planner, stratification, semi-naive fixpoint |
| `Datalog.kt` | the facade: `add` / `fact` / `query` / `why`, and `DlRows` rendering |
| `DatalogBoxes.kt` | the box-graph bridge (`Datalog.addBoxGraph`, `Box.datalog`) |
| `DatalogObjects.kt` | the `graphPaths` bridge — any live object graph as facts |
| `DatalogInterface.kt` | the box language (`DatalogExecution`) and its `#directives` |
| `DatalogEditorExtension.kt` | serves `src/commonMain/resources/datalog.js` to the browser and the node probe |

## From Kotlin, in any box

`afield.datalog.*` is in `intro`, so nothing needs importing.

```kotlin
val d = Datalog()
d.add("""
    edge(a, b). edge(b, c). edge(c, d).
    path(X, Y) :- edge(X, Y).
    path(X, Z) :- path(X, Y), edge(Y, Z).
""")
d.query("path(a, Z)").table()
d.why("path(a, d)")
```

Facts can come from Kotlin objects, which is the point of it being in-process:

```kotlin
d.facts("emp", staff) { listOf(it.name, it.department, it.salary) }
d.query("dept(D), S = sum P : { emp(_, D, P) }, S > 1000")
```

## Against the document

`Box.datalog(program)` preloads the live box graph. The relations are listed in
`dlBoxRelationSchema`: `box/1`, `name/2`, `child/2`, `nested/2`, `frame/5`, `prop/3`, `running/2`.

```kotlin
datalog("""
    reaches(A, B) :- child(A, B).
    reaches(A, C) :- reaches(A, B), child(B, C).
""").query("reaches(root, X), name(X, N)").table()
```

Box ids contain hyphens, so a literal id has to be quoted (`child(root, "ad4e-…")`) — the lexer reads
a bare `ad4e` followed by a minus. Joining through `name/2` is usually what you want anyway.

## Any object graph

`Datalog.addObjectGraph(root, pattern)` walks with `afield.graphquery` and emits `slot/1`,
`link/3`, `segment/2`, `depth/2`, `at/2`, `value/2`, `type/2`, `writable/1`. `dlObjectGraphRules`
carries `alias`, `reaches`, `unreached` and `leaf` as source you can `add`.

`at(Path, ObjectId)` is the reason the bridge exists. Ids come from an `IdentityHashMap`, so
aliasing is a join of `at` with itself and needs no special relation — and the walk already visits
a shared node under every alias (its cycle detection is path-scoped, not visit-once), so the facts
are there. Equality would defeat it: two equal `Osc`s are not one `Osc`.

Only non-scalars get an id. Two equal `Int`s being "the same object" is a fact about boxing.

`addBoxGraph` deliberately did **not** become a call to this. A document's useful schema is `child`,
`nested`, `frame`, `prop`; reflecting a `Box` gives several hundred fields nobody wants to join on.

Scope the walk with `pattern` — a literal prefix is O(depth), `**` walks everything.

## As a box language

Set a box's Main Language to Datalog (action `set-main-language-datalog`); its children then hold
Datalog rather than Kotlin. A box **accumulates**: running a fragment merges it and prints only what
that fragment asked for, so rules can be built up and queried line by line. Rules are de-duplicated,
so re-running the whole box is safe.

Directives: `#help`, `#boxes` (load the live document), `#relations`, `#facts <p>`, `#program`,
`#why <goal>`, `#whynot <goal>`, `#reset`. A bare non-ground atom on its own line (`path(a, Z).`) is
treated as a query, not as a malformed fact.

Adding a directive means editing **two** vocabularies: `DatalogExecution.directiveHelp` and
`directiveWords` / `directiveDocs` in `src/commonMain/resources/datalog.js`.
`datalog.editor-extension.workout` asserts the two are equal, and a directive the highlighter does
not know about gets underlined as a clause with no `.`.

## `#datalog` — the embedded region

A program whose first non-blank line is `#datalog` is self-identifying, the same way `#stacksl`,
`#rectsl` and `#petri` are. The parser accepts and drops the tag, and `datalog.js` looks for the
same word to decide which part of a Kotlin file to highlight, so one piece of text can be pasted
into a Datalog box, a Kotlin `"""…"""` string or a docs fence without editing.

```kotlin
val d = Datalog()
d.add("""
#datalog
edge(a, b). edge(b, c).
path(X, Y) :- edge(X, Y).
path(X, Z) :- path(X, Y), edge(Y, Z).
""")
```

Inside such a region the editor gives syntax highlighting (rule heads read differently from the
same predicate used in a body), lexical diagnostics, a context badge reporting the predicate's
arity and clause count, and completion over the program's own predicates plus the builtins and the
box-graph relations. Loose `.datalog` files get the same treatment as whole documents.

**There is no Monarch grammar.** Colouring is decoration-based, exactly as in
`rectsl.js` and `stacksl.js`: one tokenizer serves both the standalone document and the embedded
region, and there is no grammar that can fail to compile and take `initMonaco` down with it. The
workout asserts `setMonarchTokensProvider` never appears in the file.

Diagnostics are lexical only — unbalanced brackets, unterminated strings and comments, a clause
with no `.`. Safety and stratification are whole-program properties the Kotlin engine decides;
re-deriving them from a half-typed buffer would underline correct code.

The vocabulary (aggregate operators, builtin functions, `#directives`, box-graph relation names) is
written down twice, once in Kotlin and once in `datalog.js`, because the editor cannot call the
server. `DatalogEditorExtensionWorkout.checkVocabularyAgrees` compares the two lists, so adding a
builtin on one side and forgetting the other fails a test rather than silently mis-colouring.

## Semantics worth knowing

- A bare lowercase identifier is a **string**: `edge(a, b)` and `edge("a", "b")` are one fact.
- Every integral number normalises to `Long`, including a computed `4.0 / 2.0`. Without that,
  arithmetic results would silently fail to join with stated facts. The cost is that `2.0` prints
  as `2`.
- `X = expr` **binds** when `X` is not yet bound at that point and **compares** when it is. The
  planner decides, after it has chosen an order for the rest of the body.
- Negation and aggregation are stratified. A negative edge inside a recursive cycle is rejected up
  front, with the cycle named, rather than resolved by evaluation order.
- `!path(X, _)` is allowed: a variable that appears exactly once in the rule is an existential
  wildcard under negation.
- `min` / `max` / `mean` of an empty group have no value, so the enclosing rule does not fire there;
  `count` and `sum` give 0.
- Query rows come back sorted. Set semantics leave the order arbitrary, and an arbitrary order that
  changes between runs is a bad thing to print next to a box.
- Rounds are numbered **globally**, not per stratum. `DlRelation.roundOf` is what makes `why`
  terminate — a proof may only descend to a strictly earlier round — and a per-stratum counter broke
  that across a stratum boundary, so any rule with negation or an aggregate in its body was
  inexplicable. Strata run in topological order, so a global counter makes "older stratum implies
  lower round" true by construction. `MAX_ROUNDS` is measured from each stratum's first round.
- An aggregate's **target must be a variable nothing has bound yet**, which is why "the maximum, and
  who earned it" is two rules: `peak(D, M) :- dept(D), M = max P : { emp(_, D, P) }.` and then
  `topEarner(D, W) :- peak(D, M), emp(W, D, M).`

## Explaining a fact, and explaining its absence

`Datalog.why(goal)` is a top-down search over the finished model, bounded by round numbers rather
than by an occurs-check. Three things about it are easy to get wrong:

- **Literal ordering is where the bugs live.** `chooseLiteral` mirrors the planner: evaluable tests
  first (0), bindings (1), anti-joins (0 when ready), positive scans (2), aggregates last (3, or 4
  when one aggregate's subgoal reads another's target). A literal that cannot be evaluated yet must
  score 99. Scoring an unevaluable comparison *below* a positive atom once made the search pick
  `V > 5` before the `cell(C, V)` that binds `V`, fail, and report a real fact as unproven.
- **An aggregate proof is the group plus the arithmetic.** `aggregateGroup` enumerates the subgoal
  under the same `roundOf < beforeRound` bound as any other premise, recomputes the value with
  `dlAggregateValue` — the same function the fixpoint uses, which is why it is top-level — and the
  caller checks it against the model. A group that does not add up is refused, and the answer falls
  back to the honest "no proof reconstructed". Two details are load-bearing: an aggregate's
  dependencies are forced negative during stratification, so they always sit in a strictly earlier
  stratum and every contributing tuple passes the round bound; and the target is *dropped* from the
  bindings first, because the engine assigns it after the subgoal runs and leaving it in would
  restrict the group to the tuples that happen to equal the answer.
- **`DlDerivation.kind` says what a node is to its parent** — `DERIVED`, `AGGREGATE`, `GROUP`,
  `ABSENCE`. A proof tree is not homogeneous, and a reader who cannot tell a rule body from an
  aggregate's group is reading a tree that means several different things in one shape.
  `contribution` carries what a group member gave the aggregate, so `min`/`max` can name their
  witness. The shown group is capped at `Datalog.MAX_GROUP_MEMBERS`; the value is always computed
  over all of it, and for `min`/`max` the extreme tuple is kept even when it falls outside the cap.

`Datalog.whyNot(goal)` (`DatalogWhyNot.kt`, an extension so the fixpoint's file does not grow a
second personality) walks prefixes of the conjunction, **running each one through a real
`DatalogEngine`**, and names the literal whose prefix has zero answers. One solve per prefix, paid
because a why-not that disagreed with the query it explains would be worse than none. The walk is in
the order the goal was written, not the planner's order, because the reader is looking at their own
text — which is also why "not evaluable this early" is reported as distinct from "0 answers". For a
single derived atom it walks each candidate rule's body with the head's constants substituted, and
sorts by how far each got.

A comparison still contributes no proof node, deliberately: `#why over(a3)` on the published page is
a committed capture whose proof contains `V > 5`, and adding a node there would churn the image.

## Documenting it

`public/docs/datalog.md`. Two things make its captures real rather than staged:

- **`data-lang=datalog` fences produce an actual Datalog box.** `datalog` is in
  `MAIN_LANGUAGE_ROOT_LANGUAGES` (public/docs_hooks.py), so the runner creates a `-root` box, sets
  `mainLanguage=datalog` on it, hangs the fence body off it as a child and runs that. The first
  version of the page documented a Datalog box with *Kotlin* that instantiated `DatalogExecution`
  and printed a transcript, which taught the reader nothing about the language.
- **`capture="editor"` shows code and answers together**, which is how a box actually reads:
  `println` output lands inline beside the line that produced it.

The Datalog inside a Kotlin `"""…"""` is indented, `#datalog` header included. The lexer skips
the leading whitespace and `datalog.js`'s header regex already allowed it, so the indentation is
free and it makes the nesting obvious at a glance.

Two bugs the captures found, which no unit test had:

- `#why` in a fragment that also stated the rules answered "not in the model", because
  `runProgram` ran every directive *before* merging the fragment. Now `#reset` runs before the
  merge and everything else after.
- The editor underlined `#why over(a3)` as a clause with no `.`. `analyzeLexical` now skips a `#`
  line the way it skips a `%` one.

## Verification

| what | command |
| --- | --- |
| engine semantics, incl. semi-naive vs BFS | `uv run --project . --group backdoor python tools/run_field_tests.py --id datalog.core.smoke` |
| box graph + box language | `./gradlew --no-daemon jvmTest --tests "afield.datalog.*"` |
| the editor extension, under node, against a mock Monaco | `uv run --project . --group backdoor python tools/run_field_tests.py --id datalog.editor-extension.workout` |

`datalog.core.smoke` is a `jvm-main` FIELD_TEST, which it can only be because the engine touches no
`Runloop` and starts no threads — the JVM exits naturally. Everything that needs a `Box` is in
`jvmTest` instead.

A replayable box that exercises the aggregate proofs and `#whynot` end to end lives at
`dev_docs/agents/artifacts/datalog_aggregate_proofs/` — a transcript, not a saved document, because
a `save` op inside a committed transcript writes into the repository every time somebody replays it.

## Documentation

`public/docs/datalog.md` ("Datalog in a Box", staging → Small Languages). Eight captures under
`public/docs/assets/datalog/`, each one also a `docs.datalog.*` FIELD_TEST, so a change that breaks
an example fails a test rather than shipping a stale PNG.

Regenerate the captures for this page alone — a bare incremental build re-runs the whole corpus:

```bash
FIELD_DOCS_RUNNER_MODE=always FIELD_DOCS_RUNNER_PORT=9477 uv run --project . --group docs python tools/build_zensical_docs.py --skip-build --page datalog.md
```

`incremental` keys on fence content, not on Kotlin, so a change to (say) `dlBoxRelationTable`
reports `cache_hits` and leaves the old image in place. Use `always` with `--page`.

`DlRows` and `DlSolution` implement `ToHTML` via the repo-wide `Table`, which is what makes
`display("main", d.query(…))` render as a table. `DlDerivation` and `DlWhyNot` render their own
indented trees instead — the shape *is* the answer in a proof, and a table would flatten the one
thing worth looking at.

Adding the aggregate-proof and why-not prose did **not** need a recapture. The two `#why` captures on
the page (`path(a, d)` and `#why over(a3)`) contain no aggregate, no negation and no new node kind,
so their images are unchanged; the new examples are `data-lang=text` fences holding output that was
actually observed in a live box, not runner fences.

## Not done yet

- `whyNot` is one rule deep. It walks the bodies of the rules that could have produced a missing
  fact, but does not then recurse into the literal that failed. Asking again about that literal is
  the manual version and works; the automatic version is a research project.
- A Datalog *box* gets its language id from `code_language`, which is the display label
  (`"Datalog"`); the extension compares language ids case-insensitively so that still highlights,
  but the mismatch is shared with every other box language here and was not fixed.
