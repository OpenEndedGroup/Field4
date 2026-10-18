// The brief, for you and for the agent. It is also kept as guides/start-here.md, which every agent in this document can
// read.
val brief =
    """
# A staged level

This document holds one level of a puzzle shooter-looter as rooms and doors on a grid (the **Level** box), and a
machine that builds from it (the **Builds** box): a blockout, the paths from the spawn to every loot point, and the
loot placement, which follows a rule kept in the **Loot rule** box. The **Corpus** box runs the same machine on
seeded variants of the level and shows them side by side.

How to work here:

- The level changes only through `level.inspect` and `level.edit`, which quotes the revision it read. An edit made
  against an older revision is refused.
- Everything else is a build with recorded inputs, so a result is either current or visibly stale. After an edit,
  rebuild the stages in the Builds box.
- Every build reports what it measured in its notes: path lengths against their budgets, how much a relaxed path
  turns against its rough walk, doors on the way, sightlines.
- Placement waits behind a gate until every loot point can be reached within its path budget.
- The loot rule changes only through `loot.candidate`, `loot.test` and `loot.apply`, after a replay on its fixtures
  and a control that must fail. Only the person approves a change to a fixture's recorded outcome.
- Run the corpus before you call a rule change done: a change that suits this level can break a variant.

Evaluate the results, not only the checks. Look at the paths and the placement, and say what you see. The checks
only cover what someone thought to measure.
"""
        .trimIndent()

val guide = "%document/guides/start-here.md".file
if (!guide.exists()) {
    guide.parentFile.mkdirs()
    guide.writeText(brief + "\n")
}
markdown(guide.readText(), width = 520)
