# How the popup decides

Press `ctrl-space` in a Field editor and something has to choose which of a few hundred candidates
you see first. This page is about that choice: where it is made, what it is made from, and how you —
writing a class in a Field box or in Field's own source — get a say in it.

There is one fact to get out of the way first, because it is where almost everyone starts looking in
the wrong place:

!!! important "The server sends everything. The browser does the ranking."

    Field's completion server does not sort, filter or truncate. It answers a caret with *every*
    candidate it can find, and it says so in as many words — `CompletionCorpusProbe` in the Kotlin
    source is annotated "Ranking is client-side". The order you see is computed in JavaScript, in
    `src/commonMain/resources/fieldSuggest.js`, by a function called `rankItems`.

    So if you are reading Kotlin looking for the code that decides what comes first, you will not
    find it. What Kotlin decides is what each row *carries* — its category, where it was declared,
    and its receiver type's own prior. The browser turns those into an order.

That division is deliberate. It means the popup can re-rank on every keystroke without another
round trip, and it means the things a person does — which completion they accepted, and when — can
weigh on the order without ever leaving their machine.

## One caret, end to end

Take a concrete caret. You have an `FLine` in hand, you type a `.`, and you press nothing else —
there is no prefix, no letters to match against. Six things happen.

### 1. The server answers with everything

For `f.` on an `FLine` that is on the order of 250 rows: the class's own members, extension functions
written for `FLine`, and the extensions that apply to literally every value in Kotlin — `also`,
`apply`, `let`, `takeIf`. Nothing is dropped. In `rankItems` the filter step is
`!query || Number.isFinite(entry.fuzzy)`, and with an empty query the left half short-circuits, so at
a bare dot *no row is filtered out at all*.

### 2. Each row says where it was declared

Every candidate carries a `declaredIn`: a class's fully-qualified name for a member
(`afield.graphics.FLine`), or a package for a top-level extension. This is the field that decides the
row's **category**, which is the field that decides which **section** it renders under.

It has to be `declaredIn`, and until recently it was not. The categoriser,
`KotlinSourceIndex.getCompletionCategoryForSymbol`, used to be keyed on the bare simple *name* — and
in Field's source tree `scale` is declared in 42 categorised files, `close` in 21, `copy` in 16. On a
collision it picked the first. That is how `FLine.close` came to be filed under `Solvers` and
`FLine.scale` under `Synthesis`: not because anything knew anything about `FLine`, but because some
other file that happens to declare a `close` was reached first.

Now the question is asked the way the index can actually answer it, in three steps, and the third
step is the interesting one:

| step | question asked | answer if it hits |
|---|---|---|
| 1 | is `Owner.member` in the index? | that declaration's category — including *no category*, which does not fall through |
| 2 | does `declaredIn`'s package contain a match? | the vote, scoped to one package instead of 42 files |
| 3 | `declaredIn` names something outside this tree | **decline** — a namesake here says nothing about `java.lang.Math` |

The third row is a behaviour change worth internalising, because its consequence looks like a
regression and is not. A receiver you did not write and cannot annotate — `java.lang.Math`,
`kotlin.collections.List` — now gets an honest, uniform "no category" for every one of its members
rather than a scatter of borrowed ones. Uniform means they all tie, and when everything ties the
popup falls back to the order the server sent, which for a foreign type is alphabetical. So a bare
dot on a foreign receiver is alphabetical. That is the truth about how much Field knows about
`java.lang.Math`, stated plainly, where it used to be hidden behind noise.

### 3. The receiver's type states its own prior

Here is the part you can control. A class can name the members that answer *"what do you actually do
with one of these"*, in the order a person reaches for them, with `@CommonMembers`. `FLine` is the
first type in Field to do it:

```kotlin
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
K1 fallback did, no candidate carries one, and the server resolves the caret's receiver itself instead —
the live value's own class, else the declared type, else the constructor called to make it. Without that
the prior would be silently absent for the first `ctrl-space` after launch, which is the popup most
people meet first; [what the figure used to show](#what-the-figure-used-to-show-and-why) is that story.

The position rides across the wire as `Metadata.commonMemberRank`.

### 4. The browser scores every row

`rankItems` adds up, per row: how well the text fuzzy-matches what you typed, the category weight,
**recency** (have you accepted this completion before, and how recently), a nudge for AI suggestions,
a penalty for `equals`/`hashCode`/`toString` in member position, a penalty for declarations that are
*too far* from the receiver (a name declared on `Any` is true of everything and therefore says
nothing about the thing in your hand), a server-supplied context boost, **the receiver's prior**, and
finally a hair of the server's original index so that ties break in the order the server chose.

### 5. It keeps the top 80

`MAX_VISIBLE_ITEMS` is 80. This is why "is the right answer in the response" and "can a person see
it" are two different questions, and only the second one is yours. A completion at index 159 of 249
is in the payload and is nowhere on screen.

### 6. Then — and this is the step that surprises people — it regroups into sections

The rendering step is one line:

```javascript
orderItemsForGroupedDisplay(rankItems(...).slice(0, MAX_VISIBLE_ITEMS))
```

Read it right to left. Ranking picks the 80 rows. *Then* `orderItemsForGroupedDisplay` walks that
ranked list and buckets each row under its category's section title, stably, in first-appearance
order — and returns the buckets concatenated.

So ranking decides three things: **which** rows survive the cut, the **order of the sections** (a
section appears where its best-ranked member ranked), and the **order within** a section. It does not
decide anything across a section boundary. A row cannot be lifted past a row in an earlier section,
no matter what it scored.

!!! tip "The rule of thumb"

    Where a row lands on screen is *(which section, then rank within that section)*. If a completion
    is much further down than its score should put it, the thing to check is not the ranking — it is
    which **section header** it ended up under.

This is not a hypothetical. Before `declaredIn` fixed the categoriser, five of `FLine`'s eight common
members were filed under someone else's category. Their prior was working perfectly; it lifted each
of them to the top of a section nobody was looking at, seventy-odd rows down the list. And the three
that were *correct* — `lineTo`, `cubicTo`, `here` — were correct precisely because they collide with
nothing, so they fell through to the fallback and got `Graphics`. The name lookup was worse than its
own fallback. One classification change, same payload and same ranker, moved `moveTo` from off-screen
to first.

## Seeing it

Both figures below are generated by the docs build against a live Field, on the machine that built
this page. Neither is a drawing, and neither has been arranged to agree with the prose. The first one
spent its first days *contradicting* this page, which turned out to be the most useful thing it ever
did: two real defects were hiding behind it, and [what the figure used to show](#what-the-figure-used-to-show-and-why)
is now the best-documented part of this page.

*For anyone re-running the build: measured 2026-08-18, `docs_capture_audit --repeat 2` reproduced the
popup byte-for-byte across both runs, and graded the prior table `jitter` at maxΔ9 on 0.0056% of pixels
— antialiasing, not content. Both are otherwise deterministic, so if either changes materially,
something real changed. The popup's clip has also been seen to pick up a few dozen intermittent pixels
at its extreme top-left, inside the padding and outside the panel; the rows, the section header and the
doc panel have been identical every time.*

The first is a real `ctrl-space`: a box containing `val f = FLine()` and a bare `f.`, driven by real
keypresses, with the popup panel photographed while it is open — and served by the same Analysis API
backend an ordinary `./field.sh` session uses, which it was not until 2026-08-18.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector=".afield-suggest" warm-completion=true screenshot="assets/popup-decides-fline-dot.png" padding="6" wait="0" timeout="140" embed=true hidden=true name="popup-decides-fline-dot" alt="A real Field completion popup at a bare dot on an FLine. A single section header reads GRAPHICS. Under it, in this order: moveTo(x: Number, y: Number, z: Number), lineTo(same), cubicTo(x0: Number, y0: Number, z0: Number, x1: Number...), close(), here (FLine.Node), rot(angle: Number, center: Vec2), scale(scale: Number), copy(), then arcLength(). Those are FLine's eight @CommonMembers in declared order, followed by the first member the class says nothing about. The documentation panel on the right reads Category Graphics above a signature card for moveTo and its documentation."}
// Fresh and deterministic: clear the recency store so nothing this machine happens to have accepted
// before outranks the prior, and hide the run-tick overlay — all of it, not just `.tick_text.ok`,
// since `f.` is deliberately incomplete Kotlin and any tick here would be the error-coloured one.
// Known and measured, so that nobody re-derives it: an INTERMITTENT few dozen pixels can appear at
// the very top-left of this clip, inside the padding and outside the panel itself. Seen in three
// captures of four, scored at 0.0049% of pixels, maxΔ214; absent from both runs of the 2026-08-18
// audit, which reproduced this figure byte-for-byte. Purely cosmetic and disclosed rather than papered
// over — the rows, the section header and the doc panel are identical every time, which is everything
// this figure is for.
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
// to actually serve, so this is a photograph of the backend a user gets rather than of the K1 fallback
// (which is what it was until 2026-08-18 — see below). Wait for a row rather than guessing a pause;
// a cold first completion can take seconds.
ui.waitForSelector(".afield-suggest-row", 20000.0)
ui.pause(1400)
```

**That is the prior working.** All eight of `FLine`'s `@CommonMembers` are there, in the order the
annotation lists them — `moveTo`, `lineTo`, `cubicTo`, `close`, `here`, `rot`, `scale`, `copy` — and the
first member the class says nothing about, `arcLength()`, comes ninth. One `GRAPHICS` header, which is
the sectioning claim confirmed in the same picture: every `FLine` member is categorised from where it is
declared, so they all land in one section, and inside one section the prior is free to decide.

### What the figure used to show, and why

This is worth a section rather than a footnote, because for its first days this figure showed the
opposite and *nothing failed*. The rows were alphabetical — `breakNext`, `currentPoint`, `here`, `nodes`,
`properties`, then `breakNext()`, `byFiltering`, `byThickening`, `center()` — with `moveTo` nowhere in the
visible nine. Field's own completion-interaction log for that interaction gave the reason as one number:
all 80 presented rows scored `25.988, 25.976, 25.964, …`, each exactly `0.012` below the one above,
which is the `originalIndex * 0.012` tie-break and nothing else, descending from a constant equal to the
`Graphics` category weight. Fuzzy is 0 at an empty prefix, recency is 0 because the driver clears it —
and the prior was 0 on every row. Category weight plus the server's order, no other term in play.

Two defects, with one cause: `activeCompletionBackend` read `AA` in a process where nothing AA ever
served a completion, and everything downstream believed the config rather than the log.

**The docs build was not exercising the backend a user gets.** It starts Field with `./gradlew runJvm`.
`./field.sh` was the only thing in the tree that starts the Analysis API sidecar and exports its URL,
and `AaCompletionClient` engages only when that URL is configured. So every completion figure in these
docs was a photograph of the K1 fallback, and each capture's own log said so:

```text
[info,completion] completion backend=K1 box='Untitled'(__docs_drive__) 2ms (AA→K1: no-sidecar-url)
```

The docs runner now starts, or reuses, the same sidecar `./field.sh` would; it warms that sidecar before
a completion capture, because a fresh one answers *"session building"* for the first seconds of its life
and falls back to K1 just as legitimately; and it **fails the fence** when the log for that capture says
the completion fell back. A figure of the wrong backend is a wrong figure, and it should not be able to
build green.

**And the prior was dropped whenever K1 served — which is not only the harness.** `CommonMemberPrior`
keys on the receiver's *qualified* name, and only the sidecar's reply carries one; a K1 result names no
receiver at all, so `rankOf(null, "moveTo")` returned 0. The server now resolves the caret's own
receiver when the backend named none, so the prior applies on both paths. That matters to a person and
not just to a build, because K1 serves every completion until the sidecar's session exists — including
the first `ctrl-space` after launch. Two consecutive completions on one caret, from an ordinary session's
log:

```text
completion backend=K1 … (AA→K1: empty: session building (warming up; using K1 meanwhile))
completion backend=AA … 1616ms (44 candidates)
```

Measured after the fix on a K1-served `f.`: the server logs
`completion prior-receiver resolved='afield.graphics.FLine'`, and the popup renders `moveTo`, `lineTo`,
`cubicTo`, `close`, `here`, `scale`, `copy` — the declared order, out of the fallback backend.

### What the sidecar returns for this caret

Measured directly, since a figure shows nine rows and the panel has rather more than nine. Asking a live
sidecar's `/complete` for `val f = FLine()` then `f.`, with the running JVM's own classpath, returns
**254 candidates, 248 of them carrying `receiverQualifiedName = afield.graphics.FLine`** — the exact key
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

Thirty-seven rows over eight names — which is incidentally the clearest possible demonstration of why
the annotation takes *names* rather than callable references, since one entry covers `scale`'s eleven
overloads.

**What still differs between the two backends, now that the prior does not.** The first `ctrl-space`
after launch is still K1-served, and a K1 result carries less than a sidecar result does: no
`declaredIn`, so the section a row lands in is inferred from its name and its type rather than read off
its declaration; no `declarationProximity`, so nothing pushes `also`/`apply`/`let` below the names that
are about *this* type; and no expected-type match. The rows are the compiler's own, and the prior now
applies — but the *sections* can be less trustworthy for a second or two. Your Field's log names the
backend and the reason on every request, so this is never guesswork.

!!! warning "A discriminator that did not discriminate"

    An earlier draft of this page argued the capture was K1 because every logged row had an empty
    `declarationProximity`, a field only the sidecar populates. **That inference was wrong and is
    retracted here.** A sidecar-served response was subsequently measured with that field blank on all
    44 of its rows: it is populated for *member* completions, so blank means "not a dot completion" at
    least as often as it means K1. Everything asserted above rests instead on the
    `completion backend=…` line each session logs for itself, which names the backend and the fallback
    reason directly. Written down because a plausible discriminator that quietly fails is worse than
    none at all.

The second figure asks Field for the prior in numbers. It is not a transcription: the box calls
`afield.CommonMemberPrior` — the very object the completion server calls, once per candidate, on every
keystroke — so the table is the popup's own arithmetic. That also means it cannot go quietly stale: if
the band moves, the figure moves with it on the next docs build.

```diffstatic_runner {data-lang=kotlin screenshot="assets/popup-decides-prior-table.png" capture="box" w="700" h="190" wait="1.2" timeout="120" embed=true hidden=true name="popup-decides-prior-table" alt="A Field box output panel labelled main. A heading reads: @CommonMembers on afield.graphics.FLine — 8 members, first scores 40, less 2 per position. Below it a three-column table headed position, member, prior score, with eight rows: 1 moveTo 40, 2 lineTo 38, 3 cubicTo 36, 4 close 34, 5 here 32, 6 rot 30, 7 scale 28, 8 copy 26."}
:::static
// Ask Field what prior the completion popup has, instead of trusting a number typed into a
// page. `CommonMemberPrior` is the object the completion server itself calls, once per
// candidate, on every keystroke — so this table is the popup's own arithmetic rather than a
// description of it. Change `receiver` to any qualified type name to ask about your own class.
val receiver = "afield.graphics.FLine"
val ranks = afield.CommonMemberPrior.ranksFor(receiver)

val cell = "padding:2px 20px 2px 0"
val rows = ranks.entries.joinToString("") { (member, position) ->
    val score = afield.CommonMemberPrior.scoreOf(position)
    "<tr><td style=\"$cell\">$position</td>" +
        "<td style=\"$cell\"><b>$member</b></td>" +
        "<td style=\"padding:2px 0\">$score</td></tr>"
}

display("main", """
  <div style="font:12px/1.7 ui-monospace,monospace;color:#cfd8e3;padding:10px 14px">
    <div style="opacity:0.72">@CommonMembers on $receiver — ${ranks.size} members, first scores
      ${afield.CommonMemberPrior.BASE}, less ${afield.CommonMemberPrior.STEP} per position</div>
    <table style="margin-top:9px;border-collapse:collapse">
      <tr style="opacity:0.62;text-align:left">
        <th style="$cell">position</th>
        <th style="$cell">member</th>
        <th style="padding:2px 0">prior score</th>
      </tr>
      $rows
    </table>
  </div>
""")
```

The two calls that table is built from are the whole public surface, and you can point them at your
own class:

```kotlin
val ranks = afield.CommonMemberPrior.ranksFor("afield.graphics.FLine")  // name -> 1-based position
val score = afield.CommonMemberPrior.scoreOf(ranks["moveTo"] ?: 0)      // position -> points
```

The same box ships as a replayable transcript — `popup_prior_inspect` on the
[agent transcripts](agent_transcripts.md) page — so you can open it, change the one string, and ask
about a type of your own.

## The authority order

Four things can decide an order, and they are deliberately ranked against each other. In one line:

> **recency > the author's prior > the category bucket > alphabetical.**


| | decided by | beats | because |
|---|---|---|---|
| 1 | **recency** — what you have actually accepted | everything below | what a person demonstrably does beats anybody's guess about what they will do |
| 2 | **the author's prior** — `@CommonMembers` | bucket, alphabetical | the person who wrote the class knows its first page |
| 3 | **the category bucket** | alphabetical | a coarse grouping is better than none |
| 4 | **the server's order** — alphabetical for a foreign type | — | the last resort, and honest about being one |

The prior's band is chosen to sit exactly in that gap: **above** the highest category weight, so a
called-out member clears a page of uncategorised rows, and **below** what recency can reach, so what
you use still wins over what the author guessed. As of `ee2d0655b` those are `COMMON_MEMBER_BASE = 40`
decaying `2` per position, against a category ceiling of `28` and a recency ceiling near `62`.

Two things keep that arrangement honest rather than aspirational. The magnitude lives on **both**
sides of the wire — `CommonMemberPrior.BASE`/`STEP` in Kotlin and `COMMON_MEMBER_BASE`/
`COMMON_MEMBER_STEP` in JavaScript — so `FieldSuggestPresentationTest` asserts the two agree, because
a server that ranks members the client scores differently is worse than no prior at all. And the
weights themselves are under active revision; treat the four literals above as a snapshot and the
*ordering* as the contract.

There is one more reason the prior exists at all, and it is the sharpest argument for it: **recency is
`localStorage`.** It is empty for a new user, empty in a fresh browser profile, and empty in every
test, because the test harness clears it. So the term with the most authority is exactly the term that
is unavailable when a person meets Field for the first time — and unavailable to any test that wants
to assert what the popup should do. A list written in the source is the same answer for all four
cases.

## Writing `@CommonMembers` on your own class

If you write a class in a Field box, or anywhere on Field's classpath, you can give the popup its
first page yourself.

```kotlin
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

**Names, not callable references.** `"moveTo"` rather than `FLine::moveTo`. This is on purpose: one
entry covers every overload of a name — `FLine.moveTo` has four — and a property reads exactly the
same as a function. Only the bare name is matched, so a display string like `moveTo(v: Vec3)` still
resolves.

**A typo is a build failure, not a silent no-op.** A name that does not resolve to a member would
simply stop ranking, invisibly, and nothing would ever tell you. So `CommonMembersAnnotationTest`
fails the build if any listed name is not a member of the annotated class. This is the whole reason
the annotation can be trusted: the failure mode it would otherwise have is undetectable by
inspection.

**Order is the claim.** Position 1 scores most; each later position sheds a little. Write them in the
order a person reaches for them, not alphabetically. `FLine`'s order follows its own KDoc — build the
path, then move it around.

**Keep it short.** This is the first page of the manual, not an index. Past roughly ten entries the
order stops being a real claim about what people reach for, and since scoring decays by position a
long tail earns almost nothing anyway.

**Inheritance is one-directional.** A subclass inherits its parent's list, and may re-declare a name
to move it — but `CommonMemberPrior.declaredNames`, which the guard test checks against the class's
actual members, deliberately reads *only* the class's own annotation. A class is answerable for the
list it writes, not for one it inherited.

## Asking the popup to show its reasoning

A rank on its own tells you the answer moved without telling you what moved above it, which is not
enough to tell a ranking bug from a ranking decision. So the presentation probe can dump the rows a
person would actually see, with the two inputs that placed them:

```
FIELD_RANK_DUMP=14 FIELD_RANK_DUMP_CASE='FLine bare dot' \
  ./gradlew jvmTest --tests "afield.FieldSuggestPresentationTest"
```

`FIELD_RANK_DUMP=<n>` prints the first *n* visible rows; `FIELD_RANK_DUMP_CASE` narrows it to one
recorded case (omit it for all of them). Each line gives the visible rank, the label, and the row's
`category` and `commonMemberRank` — which is to say, the section it will land in and the prior that
placed it inside that section. Those are the two fields that explain essentially every "why is that
so far down" question.

Run at `ee2d0655b`, that command says this — 249 candidates sent, 80 rendered, and the first eight of
them are `FLine`'s eight `@CommonMembers` in declared order:

```text
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

That is the whole mechanism in one screenful. Ranks 1–8 carry a non-zero `commonMemberRank` and are in
the annotation's order; rank 9 onwards is `commonMemberRank=0` and falls back to the alphabet within
the same section. Every row reads `category="Graphics"`, which is why the prior is *visible* at all —
put five of those eight in someone else's category and the same prior would put them at the top of
five different sections, seventy rows down.

The cases it replays are real recorded `/complete` payloads, ranked by the verbatim `fieldSuggest.js`,
so the numbers are the popup's own and not a transcription of its formula. Parameter lists are elided
above; ranks, names, categories and ranks are verbatim — which is why `breakNext` occupies two rows.
A function and a property of the same name are two separate completions, and the popup shows both.

There is a joke buried in that elision, and it is this page's own subject biting back. Field's
codebase reference decides "which docs pages use this source file" by matching call-shaped tokens that
only one documented file declares. `PatComposition.kt` — Field's *pattern* algebra — declares a `rot`,
and so does `FLine`. Quoting the member *with its parameter list* was enough, with one other
coincidence, to publish this page on `patterns.md` as a worked example of a file it has never
mentioned. The parameters came off, and the claim went away. It is exactly the failure `declaredIn`
was introduced to fix, in a different subsystem, found by writing about it — and the reason the
"Where this lives" table below cites symbols rather than call syntax.

## When a row is not where you expect

In rough order of how often each turns out to be the answer:

1. **It is in another section.** Check the header above it. Ranking cannot cross a section boundary.
2. **The section it landed in was inferred, not read.** On a freshly launched Field the first request
   or two are served by K1 while the sidecar builds its session. The prior applies on that path too
   (it did not until 2026-08-18), but a K1 result carries no `declaredIn`, so its category is guessed
   from the row's name and type — and a row in the wrong section cannot be lifted out of it. Ask
   again a second later. Your Field's own log names the backend and the reason on every request —
   `completion backend=AA … (N candidates)` or `backend=K1 … (AA→K1: <why>)`.
3. **Its receiver is a foreign type.** A type you did not write has no `@CommonMembers` and now gets
   no borrowed category, so a bare dot on it is alphabetical. Type a letter or two; with a prefix,
   fuzzy matching dominates everything else on this page.
4. **You are past row 80.** The popup renders at most 80 rows of however many the server sent.
5. **Recency is outvoting the prior.** By design. It is also per-browser-profile, which is why a
   colleague's popup and yours can legitimately disagree.
6. **The category weights predate the server's category vocabulary.** Most of the categories the
   Kotlin side sends score nothing at all, which is a known defect rather than a subtlety — there is
   a characterization test that pins the current behaviour precisely so that fixing it is a visible
   change.

## Where this lives

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

Symbol names rather than line numbers, on purpose: a line number in a page like this one is the most
perishable thing it could contain, and a name survives every edit that does not rename it.
