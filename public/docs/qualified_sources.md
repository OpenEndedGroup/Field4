# Change a rule without breaking what already works

Some of a document's source is a rule that other builds follow: how loot is placed in a level, how
a wall is split into panels, which fallback a solver tries next. When an agent improves a rule for
the case in front of it, the danger is the cases it is not looking at. A qualified source keeps the
rule together with the real cases it has already handled and what it did with each one, and lets
the rule change only when a new version handles all of them the same way, or a person has looked at
every difference and approved it.

It has three parts:

- the **rule**, in a box property, with a checked history;
- the **fixtures**: real cases the rule has handled, each with its input and its recorded outcome;
- a **replay**: a build stage that runs a proposed rule on every fixture, and on a *control*, an
  input no correct rule can pass, which must fail.

Changes go through six agent tools: `<name>.inspect`, `.candidate`, `.test`, `.apply`,
`.retainFixture` and `.proposeRebaseline`.

## A rule and its fixtures

This rule picks a loot tier for each room of a level from how many doors the room is from the
spawn. It is written in Kotlin, and has two halves. The first half is the rule itself. The second
half places the loot and checks the result: every tier is one the game has, there is no epic loot
fewer than three doors from the spawn, and every level has at least one epic room. A line in the
source marks where the second half begins, and a change may not touch anything below it.

The box keeps the rule in a property, with three levels it has already placed as fixtures. The
control is a level with no room deep enough for epic loot: no rule can place it without breaking a
check, so its replay has to fail. The replay function evaluates a candidate's source once and
returns the placement function it defines.

```diffstatic {data-lang=kotlin}
val rule =
    """
// Loot for a room, from how many doors it is from the spawn.
fun loot(doors: Int) = when {
    doors >= 4 -> "epic"
    doors >= 2 -> "rare"
    else -> "common"
}

// Checks: everything below this line stays as it is.
fun place(level: String): Map<String, String> {
    val rooms = level.split(", ").associate { it.substringBefore(' ') to it.substringAfter(' ').toInt() }
    val placed = rooms.mapValues { (_, doors) -> loot(doors) }.toSortedMap()
    for ((room, tier) in placed) {
        check(tier in setOf("common", "rare", "epic")) { room + " got " + tier + ", which is not a loot tier" }
        check(tier != "epic" || rooms.getValue(room) >= 3) { room + " has epic loot " + rooms.getValue(room) + " doors from the spawn" }
    }
    check("epic" in placed.values) { "no room has epic loot" }
    return placed
}
::place
"""
        .trimStart()

val loot = qualifiedSource(
    "loot",
    rule,
    label = "Loot rule",
    frozen = [FrozenRegion.after("// Checks: everything below this line stays as it is.")],
    control = QualifiedControl("shallow", "hall 1, store 2", "no room has epic loot"),
    fixtures = mapOf(
        "keep" to
            QualifiedFixture(
                "gate 1, yard 2, keep 4",
                mapOf("gate" to "common", "keep" to "epic", "yard" to "rare"),
            ),
        "crypt" to
            QualifiedFixture(
                "stairs 1, ossuary 3, tomb 5",
                mapOf("ossuary" to "rare", "stairs" to "common", "tomb" to "epic"),
            ),
        "mill" to
            QualifiedFixture(
                "door 1, loft 2, well 4",
                mapOf("door" to "common", "loft" to "rare", "well" to "epic"),
            ),
    ),
    replayVersion = "loot-replay-1",
) { replay ->
        QualifiedSource.evaluateKotlin(replay.source) as (String) -> Map<String, String>
    }
```

The rule lives in the box's `lootRule` property and the fixtures in `lootFixtures`. Builds that use
the rule read `loot.source()`, which is the version last applied. Change `replayVersion` whenever
the replay function changes, as you would a build stage's `recipeVersion`.

## Ask for a change

Ask the agent in the box's chat for a change, for example "the rooms deepest in a level should feel
special". It reads the rule with `loot.inspect`, which returns the rule's revision and the hash of
its live text. It then calls `loot.candidate` with edits to the live text, quoting both. The
candidate is saved, and nothing is published yet. It records what it was made against: the rule's
revision and hash, its own source, the fixtures, the replay's dependencies and the checks below the
marker. Its id is a hash of all of these. Next, `loot.test` queues the replay and returns the
build's job id, and the agent waits for that build with `AwaitStudyBuildsRequest`.

The box below makes the same two calls an agent would, with an edit that adds a legendary tier for
rooms five or more doors in:

<!--
FIELD_TEST
id: docs.qualified-sources.held
runner: backdoor-box
tags: [docs, study, builds, qualified]
timeoutSec: 180
waitOutput:
  channel: loot
  kind: display
  contains: "tomb got legendary"
expect:
  outputContains:
    - "tomb got legendary, which is not a loot tier"
    - "failed, as it must"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/qualified_sources/held.png" alt="The loot output channel: a card titled Loot rule, rule r1, 3 fixtures, must fail: shallow. Candidate 'A legendary tier for the deepest rooms' is marked Held in red. Its table lists crypt as held because tomb got legendary, which is not a loot tier; keep and mill as same; and the control shallow (must fail) as failed, as it must, with the message no room has epic loot." wait="1.0" wait-output=true wait-output-kind=display wait-output-channel="loot" wait-output-contains="tomb got legendary" capture="box" embed=true timeout="180" name="qualified-sources-held" w="760" h="120"}
:::static
:::fold the loot rule and its fixtures, as above
val rule =
    """
// Loot for a room, from how many doors it is from the spawn.
fun loot(doors: Int) = when {
    doors >= 4 -> "epic"
    doors >= 2 -> "rare"
    else -> "common"
}

// Checks: everything below this line stays as it is.
fun place(level: String): Map<String, String> {
    val rooms = level.split(", ").associate { it.substringBefore(' ') to it.substringAfter(' ').toInt() }
    val placed = rooms.mapValues { (_, doors) -> loot(doors) }.toSortedMap()
    for ((room, tier) in placed) {
        check(tier in setOf("common", "rare", "epic")) { room + " got " + tier + ", which is not a loot tier" }
        check(tier != "epic" || rooms.getValue(room) >= 3) { room + " has epic loot " + rooms.getValue(room) + " doors from the spawn" }
    }
    check("epic" in placed.values) { "no room has epic loot" }
    return placed
}
::place
"""
        .trimStart()

val loot = qualifiedSource(
    "loot",
    rule,
    label = "Loot rule",
    frozen = [FrozenRegion.after("// Checks: everything below this line stays as it is.")],
    control = QualifiedControl("shallow", "hall 1, store 2", "no room has epic loot"),
    fixtures = mapOf(
        "keep" to
            QualifiedFixture(
                "gate 1, yard 2, keep 4",
                mapOf("gate" to "common", "keep" to "epic", "yard" to "rare"),
            ),
        "crypt" to
            QualifiedFixture(
                "stairs 1, ossuary 3, tomb 5",
                mapOf("ossuary" to "rare", "stairs" to "common", "tomb" to "epic"),
            ),
        "mill" to
            QualifiedFixture(
                "door 1, loft 2, well 4",
                mapOf("door" to "common", "loft" to "rare", "well" to "epic"),
            ),
    ),
    replayVersion = "loot-replay-1",
) { replay ->
        QualifiedSource.evaluateKotlin(replay.source) as (String) -> Map<String, String>
    }
:::endfold
val legendary = QualifiedEdit("doors >= 4 -> \"epic\"", "doors >= 5 -> \"legendary\"\n    doors >= 4 -> \"epic\"")
val first = loot.candidate(
    loot.rule.currentId(),
    SourceStudy.hash(loot.rule.liveSource()),
    "A legendary tier for the deepest rooms",
    [legendary],
)
loot.test(first.id)
```

The card in the box's `loot` output shows the replay: each fixture came out the **same**,
**changed**, or **held**, which means the rule or its checks refused it. Here the crypt is held:
its tomb is five doors in, and the game has no legendary tier. The control failed with the message
it names, which is what it must do. A control that passes means the checks are not running, and
then no candidate is admitted.

`loot.apply` refuses this candidate and names the held fixture and its reason, so the agent can
revise the change. The live rule is unchanged.

A candidate is admitted only when its latest replay is current, every fixture kept its recorded
outcome, and the control failed as it must. The fixtures, the replay's dependencies and the checks
must also be the ones the candidate was made with. If any of them changes, the agent makes a new
candidate. `loot.apply` then publishes the candidate as the rule's next revision, quoting the
revision and hash it was made against, so an edit made in the meantime is never overwritten.

## When outcomes change

The agent's second try makes rooms three doors in epic. Nothing breaks a check now, but the crypt's
ossuary, three doors from the spawn, used to get rare loot and now gets epic. Whether that is right
is a decision about the game, so the replay fails with the crypt marked **changed**. The agent
calls `loot.proposeRebaseline` with the replay's job id:

<!--
FIELD_TEST
id: docs.qualified-sources.proposal
runner: backdoor-box
tags: [docs, study, builds, qualified]
timeoutSec: 180
waitOutput:
  channel: loot-approval
  kind: display
  contains: "Approve the new outcome of crypt"
expect:
  outputContains:
    - "Approve the new outcome of crypt"
    - "Approve the new outcome of crypt"
-->
```diffstatic_runner {data-lang=kotlin screenshot="assets/qualified_sources/proposal.png" alt="Two output channels. The loot card shows candidate 'Epic loot from three doors in' with the amber status Outcomes changed, needs a person: crypt changed, ossuary rare to epic; keep and mill the same; the control shallow failed, as it must. Below it the loot-approval channel, titled New outcomes for Loot rule, says that every other fixture kept its outcome, lists crypt with a row ossuary, recorded rare, proposed epic, and shows a blue button, Approve the new outcome of crypt." wait="1.0" wait-output=true wait-output-kind=display wait-output-channel="loot-approval" wait-output-contains="Approve the new outcome of crypt" capture="box" embed=true timeout="180" name="qualified-sources-proposal" w="760" h="120"}
:::static
:::fold the loot rule and its fixtures, as above
val rule =
    """
// Loot for a room, from how many doors it is from the spawn.
fun loot(doors: Int) = when {
    doors >= 4 -> "epic"
    doors >= 2 -> "rare"
    else -> "common"
}

// Checks: everything below this line stays as it is.
fun place(level: String): Map<String, String> {
    val rooms = level.split(", ").associate { it.substringBefore(' ') to it.substringAfter(' ').toInt() }
    val placed = rooms.mapValues { (_, doors) -> loot(doors) }.toSortedMap()
    for ((room, tier) in placed) {
        check(tier in setOf("common", "rare", "epic")) { room + " got " + tier + ", which is not a loot tier" }
        check(tier != "epic" || rooms.getValue(room) >= 3) { room + " has epic loot " + rooms.getValue(room) + " doors from the spawn" }
    }
    check("epic" in placed.values) { "no room has epic loot" }
    return placed
}
::place
"""
        .trimStart()

val loot = qualifiedSource(
    "loot",
    rule,
    label = "Loot rule",
    frozen = [FrozenRegion.after("// Checks: everything below this line stays as it is.")],
    control = QualifiedControl("shallow", "hall 1, store 2", "no room has epic loot"),
    fixtures = mapOf(
        "keep" to
            QualifiedFixture(
                "gate 1, yard 2, keep 4",
                mapOf("gate" to "common", "keep" to "epic", "yard" to "rare"),
            ),
        "crypt" to
            QualifiedFixture(
                "stairs 1, ossuary 3, tomb 5",
                mapOf("ossuary" to "rare", "stairs" to "common", "tomb" to "epic"),
            ),
        "mill" to
            QualifiedFixture(
                "door 1, loft 2, well 4",
                mapOf("door" to "common", "loft" to "rare", "well" to "epic"),
            ),
    ),
    replayVersion = "loot-replay-1",
) { replay ->
        QualifiedSource.evaluateKotlin(replay.source) as (String) -> Map<String, String>
    }
:::endfold
val second = loot.candidate(
    loot.rule.currentId(),
    SourceStudy.hash(loot.rule.liveSource()),
    "Epic loot from three doors in",
    [QualifiedEdit("doors >= 4", "doors >= 3")],
)
val job = loot.test(second.id)
kotlin.concurrent.thread {
    loot.replays.await(job.id)
    loot.proposeRebaseline(job.id)
}
```

The proposal opens in the box's `loot-approval` output. For each fixture whose outcome changed it
lists every value that differs, as recorded and as proposed, with one **Approve** button per
fixture. Nothing changes until you press one.

## Approve a new outcome

Only a person changes a fixture's recorded outcome. No tool approves, and an approval attempted
from inside any agent tool call is refused. Pressing **Approve** makes the fixture's outcome the
proposed one, in a new revision of the fixtures, and records who approved it, when, and the
proposal, candidate and replay it came from. The earlier outcome stays in the fixtures' history.

The candidate was made with the old outcome, so it is now out of date. The agent makes a copy of it
against the new fixtures, replays that, and applies it. In box code those three steps are:

```diffstatic {data-lang=kotlin}
val third = loot.candidate(
    loot.rule.currentId(),
    SourceStudy.hash(loot.rule.liveSource()),
    second.description,
    fromCandidate = second.id,
)
loot.replays.await(loot.test(third.id).id)
loot.apply(third.id, third.baseRevision, third.baseSourceHash)
```

The agent makes the same three calls as tools: `loot.candidate` with `fromCandidateId`, then
`loot.test` and `loot.apply`. This is the box after the press:

```diffstatic_runner {data-lang=kotlin capture="ui" region="box" capture-box="qualified_approval" screenshot="assets/qualified_sources/approved.png" transcript="assets/transcripts/qualified_sources_approval.yaml" padding="0" wait="0" timeout="240" embed=true hidden=true name="qualified-sources-approved" alt="The loot card now reads Passed, applied as r2, for candidate 'Epic loot from three doors in': crypt, keep and mill all the same, and the control shallow failed, as it must. Below it the approval view marks crypt as approved, with the name of the person and the time, over its row ossuary, recorded rare, proposed epic, and the line All approved."}
ui.waitForSelector("text=Approve the new outcome of crypt", 120000.0)
ui.pause(500)
ui.clickSelector("button:has-text('Approve the new outcome of crypt')")
ui.waitForSelector("text=applied as", 120000.0)
ui.pause(1200)
```

Every fixture now keeps its outcome, the crypt's because a person recorded the new one. The rule's
new revision records the candidate and the replay that admitted it.

## Keep the checks out of reach

A candidate may not change what judges it. `frozen` says how the checks are kept apart, in one of
three ways, and a candidate that breaks the rule is refused before it is saved.

- `FrozenRegion.after(marker)`: everything after the marker line stays byte for byte. The marker
  must occur exactly once. This is the simplest: the rule and its checks are one source. The two
  halves run in one program, though, so the first half could still shadow a name the second half
  uses. A frozen copy, below, keeps them apart.
- `FrozenRegion.files("placement.kt", "fallback.kt")`: the rule is a set of named files, kept as a
  JSON object of file name to text, and a candidate may edit or add only the files named here. Its
  edits name the file they change.
- `FrozenRegion.baseline { mapOf("audit.kt" to "checks/audit.kt".file.readText()) }`: the checks are
  a separate copy, frozen when the box first runs and handed to the replay as `replay.checks`. The
  replay judges each outcome with that copy, and the candidate never sees it.

A frozen copy goes stale when the checks it was copied from improve. The function you pass says
what the checks should be now. When the copy differs from it, the card says which files differ, and
`loot.inspect` returns the same notice as `checksNotice`. The copy changes only when a person
presses **Accept the current checks** on the card. A migration in box code can also call
`loot.upgradeChecks(by, reason)`. Either way the new checks become a new revision of the copy, and
every candidate made before then is out of date.

The same button appears when the frozen part of the rule itself was edited outside `loot.apply`,
for example by hand in the editor. Until a person accepts that edit, no candidate can be made.

## Keep a real case as a fixture

Fixtures should be cases the rule has really handled. When the rule feeds a build in the same
document, pass a `retain` function that reads a finished attempt of that build and returns its
input and outcome:

```diffstatic {data-lang=kotlin}
retain = { attempt ->
    QualifiedFixture(attempt.file("level.txt").readText(), attempt.record.facts["placed"])
}
```

The agent then calls `loot.retainFixture` with the build runner's name, the attempt's job id, a name
for the fixture, and the fixtures' revision and hash from `loot.inspect`. The new fixture records
which attempt it came from and a hash of its input. Retaining the same case twice changes nothing,
and a fixture's name cannot be reused for a different case. An attempt that failed can be kept too,
with the failure as its outcome. The candidate that fixes it then shows that fixture as changed,
and a person approves the new outcome.

## What the agent sees

| tool | what it does |
| --- | --- |
| `loot.inspect` | the rule's revision and live hash, the frozen checks, the fixtures, the latest replay, and anything waiting for a person |
| `loot.candidate` | saves a candidate from exact edits, a whole source, or a copy of an earlier candidate; runs nothing |
| `loot.test` | queues the replay of a candidate on every fixture and the control, and returns the job to wait for |
| `loot.apply` | publishes an admitted candidate as the rule's next revision, or says why it is not admitted |
| `loot.retainFixture` | keeps a finished build attempt as a new fixture |
| `loot.proposeRebaseline` | shows changed outcomes to the person with an Approve button each |

The replay is an ordinary build runner named after the source, so `InspectStudyBuildsRequest` and
`AwaitStudyBuildsRequest` work on it too. Rules, fixtures, candidates, proposals and replays are
kept in the document's `studies` directory, and re-running the box picks them up again.

??? aside "Who pressed Approve in the last capture"
    The docs runner did, in a real browser on the real page: the capture's script waits for the
    Approve button and clicks it, which sends the same button press a person's click does. The box
    then makes the agent's three calls itself. The approval records the account Field runs under.

See also: [Studies](studies.md), whose build runners the replay is made with, and
[Let AI4 Wait for Long Builds](ai4_long_builds.md).
