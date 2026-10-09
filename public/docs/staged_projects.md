# Start a staged project

A staged project is a way of working with an agent on something that is built in steps, where each
step takes a while and a mistake in an early one spoils everything after it: a building, a level, a
machine part. The starting point on this page is a level for a puzzle shooter-looter, small enough to
build in seconds.

The strategy, in one paragraph:

- The document holds one editable source of truth, changed only through guarded tools: a tool
  refuses an edit made against a revision that is no longer current.
- Everything else is a derived build with recorded inputs, so a result is either current or visibly
  stale. Every build reports what it measured, in notes the agent reads.
- A measurement becomes a gate, a step the next stage waits behind, once it is trusted.
- Rules the builds follow change only after a replay on real cases, with a case that must fail, and
  only a person approves a change to a case's recorded outcome.
- A corpus runs the same machine on many variants without a model, side by side.
- The agent is asked to evaluate the results themselves, not only the checks.

## Open the starting point

Open **staged_project** from the Welcome screen's starting points; it opens as your own copy, and
its boxes run as it opens. It has five boxes:

| box | what it holds |
| --- | --- |
| Start here | the brief for you and for the agent, also kept as `guides/start-here.md` |
| Level | the level, as rooms and doors on a grid, and the plan view |
| Loot rule | the rule that places loot, as a [qualified source](qualified_sources.md) |
| Builds | the blockout, traversal and placement stages, and the paths view |
| Corpus | seeded variants of the level, built by the same stages |

[Open ↗](offline.html?file=assets/agent_transcripts/staged_project/metadata.edn)

This is the document as it opens, before anything is built:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="2240x1180" clip="0,0,2240,1180" hide-toasts=true screenshot="assets/staged_projects/overview.png" transcript="dev_docs/agents/transcripts/staged_project.yaml" padding="0" wait="0" timeout="300" embed=true hidden=true name="staged-projects-overview" alt="The whole staged_project document. On the left, the Start here box with its brief, and below it the Level box with the plan. In the middle, the Builds box, its three stages not built yet. On the right, the Loot rule card with its rule revision and three fixtures, and below it the Corpus box's gallery of six small plans in three columns, each with four lines of measurements."}
ui.waitForSelector("text=Ready to build", 120000.0)
ui.waitForSelector("text=seed 5", 180000.0)
ui.pause(2000)
```

The Level box keeps the level as JSON in its `level` property. A room is `[x, y, w, h]` in grid
cells; a door is `[x, y, side]`, an opening from cell (x, y) to its neighbour on the east (`e`) or
south (`s`); loot is `[x, y, path budget]`. The plan view draws the rooms in grey, doors in orange,
the spawn in green and loot in yellow:

```diffstatic_runner {data-lang=kotlin capture="ui" region="box" capture-box="staged_level" screenshot="assets/staged_projects/plan.png" transcript="dev_docs/agents/transcripts/staged_project.yaml" padding="0" wait="0" timeout="300" embed=true hidden=true name="staged-projects-plan" alt="The Level box's plan output: six rooms in two rows of three, drawn as grey rectangles, with four orange door marks between them, a green spawn in the top-left room and three yellow loot diamonds, one in the top-right room, one in the bottom-left room and one in the bottom-right room, which has no door."}
ui.waitForSelector("text=Ready to build", 120000.0)
ui.pause(1500)
```

The bottom-right room, the well, has no door yet. That is on purpose.

## Change the level only through its tools

An agent reads the level with `level.inspect`, which returns it with its revision, and changes it
with `level.edit`, which quotes that revision and applies a list of operations together:

```diffstatic {data-lang=json}
{"expectedRevision": "r1-…", "description": "A door from the vault into the well",
 "operations": [{"op": "door", "id": "d5", "at": [11, 7, "e"]}]}
```

The operations are `room`, `door` and `loot`, which add or replace one, `removeRoom`, `removeDoor` and
`removeLoot`, and `spawn`. An edit against an older revision is refused. So is one that would leave
rooms overlapping, a door that does not open between two rooms, or the spawn or loot outside a room,
and it changes nothing. Every accepted edit is a new revision of the level, kept in the document's
`studies` folder.

## Build the stages

The Builds box has three stages, each with a **Build** button on its card:

- **Blockout** makes a floor slab for each room and a wall on every cell edge that is not a door,
  writes them as `blockout.obj`, and, when [Manifold](manifold.md) is installed, joins them into one
  solid and reports its volume.
- **Traversal** finds the shortest walk on the grid from the spawn to each loot point, then pulls it
  tight into a relaxed path that crosses walls only at doors. It reports each path's length against
  the loot point's budget, how far it turns against the rough walk it came from, the doors on the
  way, and how many other points can see the loot point.
- **Placement** gives each loot point a placement from the loot rule, from its path length and the
  doors on the way.

Placement waits behind a gate, **reachable loot**, until every loot point can be reached within its
path budget. Here is the card after building the blockout and the traversal:

```diffstatic_runner {data-lang=kotlin capture="ui" region="box" capture-box="staged_builds" screenshot="assets/staged_projects/gate.png" transcript="dev_docs/agents/transcripts/staged_project.yaml" padding="0" wait="0" timeout="300" embed=true hidden=true name="staged-projects-gate" alt="The Builds card. Blockout is Current, with the note 6 rooms, 153 cells of floor, 83 wall pieces, 4 doors, joined into one solid. Traversal is Current, Needs correction: L1 and L3 have notes with their relaxed path length, rough walk, budget, turning and doors, and a red note says L2 cannot be reached from the spawn. Placement is Waiting for reachable loot: Out of reach within its path budget: L2 (no path). Below the card, the paths output draws the relaxed paths from the spawn to L1 and L3 over the plan."}
ui.waitForSelector("text=Ready to build", 120000.0)
ui.clickSelector("button[id$='-blockout-build']")
ui.clickSelector("button[id$='-traversal-build']")
ui.waitForSelector("text=Waiting for reachable loot", 120000.0)
ui.pause(1500)
```

The measurements are the notes the agent reads with `InspectStudyBuildsRequest`. A relaxed path that
came out longer, or turning more, than its rough walk would get a warning of its own: pulling a path
tight should never make it worse, and when a change to the traversal makes it worse, this is where it
shows.

## One edit through every stage

The agent adds the missing door with the `level.edit` call above. The blockout and traversal results
now describe an earlier level, so their cards ask to be rebuilt. After rebuilding them the gate opens,
and placement builds:

```diffstatic_runner {data-lang=kotlin capture="ui" region="box" capture-box="staged_builds" screenshot="assets/staged_projects/edited.png" transcript="dev_docs/agents/transcripts/staged_project.yaml" padding="0" wait="0" timeout="300" embed=true hidden=true name="staged-projects-edited" alt="The Builds card after the edit. Blockout is Current with 5 doors. Traversal is Current, with a note for each of L1, L2 and L3: L2 is 16.3 along its relaxed path against a budget of 20, through 3 doors. Placement is Current with the note L1 rare, L2 epic guarded, L3 common; 1 guarded. Below, the paths output draws all three relaxed paths, the one to L2 passing through the new door into the bottom-right room."}
ui.waitForSelector("text=Ready to build", 120000.0)
ui.clickSelector("button[id$='-blockout-build']")
ui.clickSelector("button[id$='-traversal-build']")
ui.waitForSelector("text=Waiting for reachable loot", 120000.0)
val plan = thisBox.named("Level").properties["levelPlan"] as SourceStudy<String>
val edit = thisBox.named("Level").properties["editLevel"] as (String, List<Map<String, Any?>>, String) -> String
edit(plan.currentId(), listOf(mapOf("op" to "door", "id" to "d5", "at" to listOf(11, 7, "e"))), "A door from the vault into the well")
ui.waitForSelector("text=Needs rebuilding", 60000.0)
ui.clickSelector("button[id$='-blockout-build']")
ui.clickSelector("button[id$='-traversal-build']")
ui.waitForSelector("button[id$='-placement-build']", 120000.0)
ui.clickSelector("button[id$='-placement-build']")
ui.waitForSelector("text=epic guarded", 120000.0)
ui.pause(1500)
```

The placement follows the loot rule in the Loot rule box: a loot point three doors in is epic and
guarded, two doors in or more than 16 steps away is rare, anything nearer is common. The rule's last
line checks that every level has an epic point. To change the rule, the agent goes through
`loot.candidate`, `loot.test` and `loot.apply`, which replay a candidate on the rule's fixtures and on
a level that must fail, as [Change a rule without breaking what already works](qualified_sources.md)
describes. Each placement build keeps its input in `rule-input.txt`, so `loot.retainFixture` can turn
a real placement into a new fixture.

## Run the corpus

The Corpus box makes seeded variants of the level: seed 0 is the level as it is, and the others shift
each door along its wall and sometimes drop a room with one door. Running the box builds every
variant with the same stage objects as the Builds box, gate included, in runners of its own, and
shows them side by side with their measurements:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" viewport="2240x1300" clip="1420,628,780,500" hide-toasts=true screenshot="assets/staged_projects/corpus.png" transcript="dev_docs/agents/transcripts/staged_project.yaml" padding="0" wait="0" timeout="300" embed=true hidden=true name="staged-projects-corpus" alt="The Corpus box's gallery after the edit: six small plans in three columns, each with its relaxed paths drawn in blue and four lines beneath: which seed it is and how many rooms it has, how many loot points are within budget and the sightline count, the largest ratio of relaxed to rough path length, and the placement. Five variants are placed; under seed 3 the last line reads placement failed: no loot point is epic."}
ui.waitForSelector("text=seed 5", 180000.0)
val plan = thisBox.named("Level").properties["levelPlan"] as SourceStudy<String>
val edit = thisBox.named("Level").properties["editLevel"] as (String, List<Map<String, Any?>>, String) -> String
edit(plan.currentId(), listOf(mapOf("op" to "door", "id" to "d5", "at" to listOf(11, 7, "e"))), "A door from the vault into the well")
thisBox.named("Corpus").begin()
ui.waitForSelector("text=placed: L1", 180000.0)
ui.waitForSelector("text=seed 5", 180000.0)
ui.pause(1500)
```

Seed 3 is the one to look at. Its doors moved so that the deepest loot point is two doors in, the
rule gives nothing an epic placement, and the rule's own check refuses the level. Nothing about the
level as it is says so; only a variant does. A variant whose placement waits behind the gate is worth
the same look, since the rule never sees that level at all. Run the corpus before calling a change
done. It asks no model, so it costs only the time the stages take.

## Work with the agent

Ask the agent in any box's chat. It can read the brief in `guides/start-here.md` with
`DocumentGuideReadRequest`, and it works through the same tools a person's buttons use:
`level.inspect` and `level.edit`, `InspectStudyBuildsRequest` and `StartStudyBuildRequest`, and the
`loot.*` tools. When a stage takes minutes rather than seconds, it can end its turn and let Field
wait for the build, as [Let AI4 Wait for Long Builds](ai4_long_builds.md) describes.

The brief ends with the sentence that matters most: evaluate the results, not only the checks. An
agent shown only checks works until the checks pass. Ask it what it sees in the paths and the
placement, and look at them yourself.

## Make it yours

Each stage is an ordinary `StudyBuildStage` in the Builds box, so you can change what a stage does,
add a stage, or add a gate over any measurement you come to trust; see
[Studies](studies.md#build-derived-results-without-blocking-edits). The level format is the Level
box's own, and the tools check whatever its `problems` function checks. To give many documents the
same machine and upgrade them together, make it a [kit](kits.md).

See also: [Studies](studies.md), [Change a rule without breaking what already works](qualified_sources.md),
[Let AI4 Wait for Long Builds](ai4_long_builds.md), [Kits](kits.md).
