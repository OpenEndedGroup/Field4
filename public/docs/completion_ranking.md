# How completions are ranked

Press `ctrl-space` in a Field editor and something has to choose which of a few hundred candidates
you see first. This page is about that choice: where it is made, what it is made from, and how a
class you write, in a Field box or in Field's own source, gets a say in it.

!!! important "The server sends everything. The browser does the ranking."

    Field's completion server does not sort, filter or truncate. It answers a caret with *every*
    candidate it can find; a comment in the Kotlin reads "Ranking is client-side". The order you see is
    computed in JavaScript, in `src/commonMain/resources/fieldSuggest.js`, by a function called
    `rankItems`.

    So if you are reading Kotlin looking for the code that decides what comes first, you will not
    find it. What Kotlin decides is what each row *carries*: its category, where it was declared,
    and its receiver type's own prior. The browser turns those into an order.

The split buys two things. The popup can re-rank on every keystroke without another round trip,
and which completions a person has accepted before can weigh on the order without leaving their
machine.

## One caret, end to end

Take a concrete caret. You have an `FLine` in hand, you type a `.`, and you press nothing else —
there is no prefix, no letters to match against. Six steps, in order.

### The server answers with everything

For `f.` on an `FLine` that is on the order of 250 rows: the class's own members, extension functions
written for `FLine`, and the extensions that apply to literally every value in Kotlin (`also`,
`apply`, `let`, `takeIf`). Nothing is dropped. In `rankItems` the filter step is
`!query || Number.isFinite(entry.fuzzy)`, and with an empty query the left half short-circuits, so at
a bare dot *no row is filtered out at all*.

### Each row says where it was declared

Every candidate carries a `declaredIn`: a class's fully-qualified name for a member
(`afield.graphics.FLine`), or a package for a top-level extension. This is the field that decides the
row's **category**, which is the field that decides which **section** it renders under.

It has to be `declaredIn` rather than the member's bare name, because bare names collide: in
Field's source tree `scale` is declared in 42 categorised files, `close` in 21, `copy` in 16. The
categoriser, `KotlinSourceIndex.getCompletionCategoryForSymbol`, asks in three steps:

<div class="field-data-table" markdown>

| step | question asked | answer if it hits |
|---|---|---|
| 1 | is `Owner.member` in the index? | that declaration's category — including *no category*, which does not fall through |
| 2 | does `declaredIn`'s package contain a match? | the vote, scoped to one package instead of 42 files |
| 3 | `declaredIn` names something outside this tree | **decline** — a namesake here says nothing about `java.lang.Math` |

</div>

The third row has a consequence worth spelling out. A receiver you did not write and cannot
annotate (`java.lang.Math`, `kotlin.collections.List`) gets a uniform "no category" for every one
of its members rather than a scatter of borrowed ones. Uniform means they all tie, and when
everything ties the popup falls back to the order the server sent, which for a foreign type is
alphabetical. So a bare dot on a foreign receiver is alphabetical, which is about as much as Field
knows about `java.lang.Math`.

### The receiver's type states its own prior

Here is the part you can control. A class can name the members that answer *"what do you actually do
with one of these"*, in the order a person reaches for them, with `@CommonMembers`. `FLine` uses
it:

```diffstatic {data-lang=kotlin}
@CommonMembers("moveTo", "lineTo", "cubicTo", "close", "here", "rot", "scale", "copy")
class FLine : HasProperties {
```

On the completion path, `CommonMemberPrior.rankOf(receiverQualifiedName, candidateName)` turns that
into a **1-based position**, or `0` for "this type said nothing". Resolution:

- by the receiver's qualified name, loaded with `Class.forName`;
- then breadth-first up superclasses **and interfaces**, self first — because a receiver is very
  often typed as an interface, and because a subclass should be able to re-order its parent's list;
- nearest declaration wins, and the first writer of a name keeps its position;
- **hits and misses are both cached.** This runs per keystroke, and the overwhelmingly common answer
  is "this type has no annotation".

The qualified name comes from the candidate itself when the Analysis API served the completion. When the
K1 fallback did, no candidate carries one, and the server resolves the caret's receiver itself instead:
the live value's own class, else the declared type, else the constructor called to make it. Otherwise
the prior would be absent for the first `ctrl-space` after launch (see
[the K1 fallback](#the-k1-fallback)).

The position rides across the wire as `Metadata.commonMemberRank`.

### The browser scores every row

`rankItems` adds up, per row: how well the text fuzzy-matches what you typed, the category weight,
**recency** (have you accepted this completion before, and how recently), a nudge for AI suggestions,
a penalty for `equals`/`hashCode`/`toString` in member position, a penalty for declarations that are
*too far* from the receiver (a name declared on `Any` is true of everything and therefore says
nothing about the thing in your hand), a server-supplied context boost, **the receiver's prior**, and
finally a hair of the server's original index so that ties break in the order the server chose.

### It keeps the top 80

`MAX_VISIBLE_ITEMS` is 80. This is why "is the right answer in the response" and "can a person see
it" are two different questions, and only the second one is yours. A completion at index 159 of 249
is in the payload and is nowhere on screen.

### Last, it regroups into sections

The rendering step is one line:

```diffstatic {data-lang=javascript}
orderItemsForGroupedDisplay(rankItems(...).slice(0, MAX_VISIBLE_ITEMS))
```

Read it right to left. Ranking picks the 80 rows. *Then* `orderItemsForGroupedDisplay` walks that
ranked list and buckets each row under its category's section title, stably, in first-appearance
order, then returns the buckets concatenated.

So ranking decides three things: **which** rows survive the cut, the **order of the sections** (a
section appears where its best-ranked member ranked), and the **order within** a section. It does not
decide anything across a section boundary. A row cannot be lifted past a row in an earlier section,
no matter what it scored.

!!! tip "Where a row lands"

    Where a row lands on screen is *(which section, then rank within that section)*. If a completion
    is much further down than its score should put it, check which **section header** it ended up
    under before suspecting the ranking.

That is easy to underestimate. Put five of `FLine`'s eight common members into someone else's
category and their prior still works perfectly: it lifts each of them to the top of a section nobody
is looking at, seventy-odd rows down. Same payload, same ranker, and one classification change moves
`moveTo` from off-screen to first.

## Seeing it

Both figures below are photographs of a live Field rather than drawings.

The first is a `ctrl-space` on a box containing `val f = FLine()` and a bare `f.`, driven by real
keypresses, with the popup captured while it is open, served by the same Analysis API backend an
ordinary `./field.sh` session uses.

??? aside "Provenance"
    `docs_capture_audit --repeat 2` reproduces the popup byte-for-byte, and grades the prior table's
    jitter at maxΔ9 on 0.0056 % of pixels. Both figures are otherwise deterministic, so if either
    changes materially, something real changed.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector=".afield-suggest" warm-completion=true screenshot="assets/popup-decides-fline-dot.png" padding="6" wait="0" timeout="140" embed=true hidden=true name="popup-decides-fline-dot" alt="A real Field completion popup at a bare dot on an FLine. A single section header reads GRAPHICS. Under it, in this order: moveTo(x: Number, y: Number, z: Number), lineTo(same), cubicTo(x0: Number, y0: Number, z0: Number, x1: Number...), close(), here (FLine.Node), rot(angle: Number, center: Vec2), scale(scale: Number), copy(), then arcLength(). Those are FLine's eight @CommonMembers in declared order, followed by the first member the class says nothing about. The documentation panel on the right reads Category Graphics above a signature card for moveTo and its documentation."}
// Fresh and deterministic: clear the recency store so nothing this machine happens to have accepted
// before outranks the prior, and hide the run-tick overlay — all of it, not just `.tick_text.ok`,
// since `f.` is deliberately incomplete Kotlin and any tick here would be the error-coloured one.
ui.eval("window.localStorage.removeItem('field.suggest.recency.v1'); var s=document.createElement('style');s.textContent='.tick_text{display:none !important}';document.head.appendChild(s);return 'setup';")
ui.openEditor(id)
ui.pause(700)
ui.clickSelector(".monaco-editor")
ui.pause(300)
ui.press("Control+A")
ui.type("val f = FLine()\nf.")
ui.pause(400)
ui.press("Control+Space")
// warm-completion=true builds the completion engine up front AND waits for the Analysis API session
// to actually serve, so this is a photograph of the backend a user gets rather than of the K1
// fallback. Wait for a row rather than guessing a pause; a cold first completion can take seconds.
ui.waitForSelector(".afield-suggest-row", 20000.0)
ui.pause(1400)
```

All eight of `FLine`'s `@CommonMembers` are there, in the order the annotation lists them
(`moveTo`, `lineTo`, `cubicTo`, `close`, `here`, `rot`, `scale`, `copy`), and the first member the
class says nothing about, `arcLength()`, comes ninth. One `GRAPHICS` header, which is the sectioning
claim confirmed in the same picture: every `FLine` member is categorised from where it is declared,
so they all land in one section, and inside one section the prior is free to decide.

### The K1 fallback { #the-k1-fallback }

K1 serves **every** completion until the sidecar's Analysis API session exists, including the first
`ctrl-space` after launch. `CommonMemberPrior` keys on the receiver's *qualified* name and only the
sidecar's reply carries one; a K1 result names no receiver at all, so `rankOf(null, "moveTo")` would
be 0. The server resolves the caret's own receiver when the backend names none, so the prior applies
on both paths. Two consecutive completions on one caret, from an ordinary session's log:

```diffstatic {data-lang=text}
completion backend=K1 … (AA→K1: empty: session building (warming up; using K1 meanwhile))
completion backend=AA … 1616ms (44 candidates)
```

On a K1-served `f.` the server logs `completion prior-receiver resolved='afield.graphics.FLine'`,
and the popup renders `moveTo`, `lineTo`, `cubicTo`, `close`, `here`, `scale`, `copy` — the declared
order, out of the fallback backend.

### What the sidecar returns for this caret

A figure shows nine rows and the panel has rather more. Asking a live sidecar's `/complete` for `val
f = FLine()` then `f.`, with the running JVM's own classpath, returns **254 candidates, 248 of them
carrying `receiverQualifiedName = afield.graphics.FLine`**, the exact key
`CommonMemberPrior.rankOf` looks up. All eight common members are among them, across every overload:

| member | rows returned | all carry the receiver | prior position |
|---|---|---|---|
| `moveTo` | 5 | yes | 1 |
| `lineTo` | 5 | yes | 2 |
| `cubicTo` | 4 | yes | 3 |
| `close` | 1 | yes | 4 |
| `here` | 1 | yes | 5 |
| `rot` | 8 | yes | 6 |
| `scale` | 11 | yes | 7 |
| `copy` | 2 | yes | 8 |

Thirty-seven rows over eight names, which is why the annotation takes *names* rather than callable
references: one entry covers `scale`'s eleven overloads.

**What still differs between the two backends.** The first `ctrl-space` after launch is still
K1-served, and a K1 result carries less than a sidecar result does: no `declaredIn`, so the section
a row lands in is inferred from its name and its type rather than read off its declaration; no
`declarationProximity`, so nothing pushes `also`/`apply`/`let` below the names that are about *this*
type; and no expected-type match. The rows are the compiler's own and the prior applies, but the
*sections* can be less trustworthy for a second or two. Your Field's log names the backend and the
reason on every request.

The second figure asks Field for the prior in numbers. The box calls `afield.CommonMemberPrior`, the
object the completion server calls once per candidate on every keystroke, so the table is the popup's
own arithmetic and moves when the band moves.

```diffstatic_runner {data-lang=kotlin screenshot="assets/popup-decides-prior-table.png" capture="box" w="700" h="190" wait="1.2" timeout="120" embed=true hidden=true name="popup-decides-prior-table" alt="A Field box output panel labelled main. A heading reads: @CommonMembers on afield.graphics.FLine — 8 members, first scores 40, less 2 per position. Below it a three-column table headed position, member, prior score, with eight rows: 1 moveTo 40, 2 lineTo 38, 3 cubicTo 36, 4 close 34, 5 here 32, 6 rot 30, 7 scale 28, 8 copy 26."}
:::static
// Ask Field what prior the completion popup has, instead of trusting a number typed into a
// page. `CommonMemberPrior` is the object the completion server itself calls, once per
// candidate, on every keystroke — so this table is the popup's own arithmetic rather than a
// description of it. Change `receiver` to any qualified type name to ask about your own class.
val receiver = "afield.graphics.FLine"
val ranks = afield.CommonMemberPrior.ranksFor(receiver)

// Sorted by position rather than trusting the map's iteration order, so the figure is the same
// every time it is captured.
val prior = Table(listOf("position", "member", "prior score"))
    .rows(ranks.entries.sortedBy { it.value }.map { (member, position) ->
        listOf(position, member, afield.CommonMemberPrior.scoreOf(position))
    })
    .role("member", CellRole.ACCENT)

// The arithmetic the heading claims, checked rather than described.
check(ranks.all { (_, position) ->
    afield.CommonMemberPrior.scoreOf(position) ==
        afield.CommonMemberPrior.BASE - afield.CommonMemberPrior.STEP * (position - 1)
}) { "the prior is no longer BASE less STEP per position" }

markdown("main", """
    {muted:@CommonMembers on ${receiver.md} — ${ranks.size} members, first scores ${afield.CommonMemberPrior.BASE}, less ${afield.CommonMemberPrior.STEP} per position}

    ${prior.h}
""")
```

The two calls that table is built from are the whole public surface, and you can point them at your
own class:

```diffstatic {data-lang=kotlin}
val ranks = afield.CommonMemberPrior.ranksFor("afield.graphics.FLine")  // name -> 1-based position
val score = afield.CommonMemberPrior.scoreOf(ranks["moveTo"] ?: 0)      // position -> points
```

The same box ships as a replayable transcript (`popup_prior_inspect` on the
[agent transcripts](agent_transcripts.md) page), so you can open it, change the one string, and ask
about a type of your own.

## The authority order

Four things can decide an order, and they rank against each other in a fixed precedence:

> **recency > the author's prior > the category bucket > alphabetical.**


| | decided by | beats | because |
|---|---|---|---|
| 1 | **recency** — what you have actually accepted | everything below | what a person demonstrably does beats anybody's guess about what they will do |
| 2 | **the author's prior** — `@CommonMembers` | bucket, alphabetical | the person who wrote the class knows its first page |
| 3 | **the category bucket** | alphabetical | a coarse grouping is better than none |
| 4 | **the server's order** — alphabetical for a foreign type | — | the last resort |

The prior's band is chosen to sit exactly in that gap: **above** the highest category weight, so a
called-out member clears a page of uncategorised rows, and **below** what recency can reach, so what
you use still wins over what the author guessed. The numbers are `COMMON_MEMBER_BASE = 40` decaying
`2` per position, against a category ceiling of `28` and a recency ceiling near `62`.

The magnitude lives on **both** sides of the wire (`CommonMemberPrior.BASE`/`STEP` in Kotlin and
`COMMON_MEMBER_BASE`/`COMMON_MEMBER_STEP` in JavaScript), and the build asserts the two agree, since a
server that ranks members the client scores differently would be worse than no prior. The weights are
under revision, so treat the four literals above as a snapshot and the *ordering* as the contract.

The prior also covers a gap recency cannot: **recency is `localStorage`.** It is empty for a new user,
empty in a fresh browser profile, and empty in every test, because the harness clears it. So the term
with the most authority is unavailable when a person meets Field for the first time, and unavailable
to any test that wants to assert what the popup should do. A list written in the source is the same
answer in all four cases.

## Writing `@CommonMembers` on your own class

If you write a class in a Field box, or anywhere on Field's classpath, you can give the popup its
first page yourself.

```diffstatic {data-lang=kotlin}
import afield.CommonMembers

@CommonMembers("advance", "reset", "value")
class Ramp {
    fun advance(dt: Double) { … }
    fun advance() { … }
    fun reset() { … }
    val value: Double get() = …
}
```

Four things to know about it:

**Names, not callable references.** `"moveTo"` rather than `FLine::moveTo`, so that one
entry covers every overload of a name (`FLine.moveTo` has four), and a property reads exactly the
same as a function. Only the bare name is matched, so a display string like `moveTo(v: Vec3)` still
resolves.

**A typo is a build failure.** A name that does not resolve to a member of the annotated class fails
the build. The alternative failure is that the name simply stops ranking, with nothing said about
it, which is undetectable by inspection.

**Order is the claim.** Position 1 scores most; each later position sheds a little. Write them in the
order a person reaches for them, not alphabetically. `FLine`'s order follows its own KDoc — build the
path, then move it around.

**Keep it short.** It is the first page of the manual rather than an index. Past roughly ten entries
the order stops being a real claim about what people reach for, and since scoring decays by position
a long tail earns almost nothing anyway.

**Inheritance is one-directional.** A subclass inherits its parent's list, and may re-declare a name
to move it, but `CommonMemberPrior.declaredNames`, which the guard test checks against the class's
actual members, reads *only* the class's own annotation. A class is checked against the list it
writes.

## Asking the popup to show its reasoning

A rank on its own says the answer moved without saying what moved above it, which is not enough to
tell a ranking bug from a ranking decision. The presentation probe dumps the rows a person would
actually see, with the two inputs that placed them:

```diffstatic {data-lang=text}
FIELD_RANK_DUMP=14 FIELD_RANK_DUMP_CASE='FLine bare dot' \
  ./gradlew jvmTest --tests "afield.FieldSuggestPresentationTest"
```

`FIELD_RANK_DUMP=<n>` prints the first *n* visible rows; `FIELD_RANK_DUMP_CASE` narrows it to one
recorded case (omit it for all of them). Each line gives the visible rank, the label, and the row's
`category` and `commonMemberRank`, which is to say the section it will land in and the prior that
placed it inside that section. Those are the two fields that explain essentially every "why is that
so far down" question.

On the `FLine` case above, that command reports 249 candidates sent and 80 rendered, with the first
eight of them `FLine`'s eight `@CommonMembers` in declared order:

```diffstatic {data-lang=text}
  case                    sent  visible      rank       was     ms
  FLine bare dot           249       80         1         1   16.1
    -- FLine bare dot: first 14 of 80 visible
       1  moveTo      category="Graphics" commonMemberRank=1
       2  lineTo      category="Graphics" commonMemberRank=2
       3  cubicTo     category="Graphics" commonMemberRank=3
       4  close       category="Graphics" commonMemberRank=4
       5  here        category="Graphics" commonMemberRank=5
       6  rot         category="Graphics" commonMemberRank=6
       7  scale       category="Graphics" commonMemberRank=7
       8  copy        category="Graphics" commonMemberRank=8
       9  breakNext   category="Graphics" commonMemberRank=0
      10  breakNext   category="Graphics" commonMemberRank=0
```

Ranks 1–8 carry a non-zero `commonMemberRank` and are in the annotation's order; rank 9 onwards is
`commonMemberRank=0` and falls back to the alphabet within the same section. Every row reads
`category="Graphics"`, which is why the prior is *visible* at all — put five of those eight in
someone else's category and the same prior would put them at the top of five different sections,
seventy rows down.

The cases it replays are real recorded `/complete` payloads, ranked by the verbatim
`fieldSuggest.js`, so the numbers are the popup's own rather than a transcription of its formula.
Parameter lists are elided above; ranks, names and categories are verbatim, which is why `breakNext`
occupies two rows. A function and a property of the same name are two separate completions, and the
popup shows both.

## When a row is not where you expect

In rough order of how often each turns out to be the answer:

1. **It is in another section.** Check the header above it. Ranking cannot cross a section boundary.
2. **The section it landed in was inferred rather than read.** On a freshly launched Field the first
   request or two are served by K1 while the sidecar builds its session. The prior applies on that
   path too, but a K1 result carries no `declaredIn`, so its category is guessed from the row's name
   and type, and a row in the wrong section cannot be lifted out of it. Ask again a second later.
   Your Field's own log names the backend and the reason on every request:
   `completion backend=AA … (N candidates)` or `backend=K1 … (AA→K1: <why>)`.
3. **Its receiver is a foreign type.** A type you did not write has no `@CommonMembers` and gets no
   borrowed category, so a bare dot on it is alphabetical. Type a letter or two; with a prefix,
   fuzzy matching dominates everything else on this page.
4. **You are past row 80.** The popup renders at most 80 rows of however many the server sent.
5. **Recency is outvoting the prior.** That is the intended order. It is also per-browser-profile,
   which is why a colleague's popup and yours can legitimately disagree.
6. **The category weights predate the server's category vocabulary.** Most of the categories the
   Kotlin side sends score nothing at all. That is a known defect; a characterization test pins the
   current behaviour so that fixing it shows up as a change.

## Where this lives

<div class="field-data-table" markdown>

| what | where |
|---|---|
| the annotation | `CommonMembers` in `src/jvmMain/kotlin/afield/util.kt` |
| resolving it for a receiver | `src/jvmMain/kotlin/afield/CommonMemberPrior.kt` |
| naming the receiver when the backend did not | `receiverQualifiedNameAtCompletion` in `KotlinCompletionSupport.kt` |
| putting it on the wire | `Metadata.commonMemberRank` in `src/commonMain/kotlin/rpc.kt` |
| categorising a candidate | `getCompletionCategoryForSymbol` in `KotlinSourceIndex.kt` |
| scoring, sorting, sectioning, rendering | `src/commonMain/resources/fieldSuggest.js` |
| the guard on listed names | `CommonMembersAnnotationTest` |
| the guard on rank and presentation | `FieldSuggestPresentationTest` + `src/jvmTest/resources/fieldSuggest/probe.mjs` |
| the guard on the prior surviving a K1 result | `CompletionPriorReceiverTest` |
| the guard on which backend a figure captured | `tools/test_docs_runner_completion_backend.py` |
| the internals, at length | `dev_docs/completion_aa_sidecar.md` |

</div>

Symbol names rather than line numbers: a line number in a page like this one is the first thing to
rot, and a name survives every edit that does not rename it.
