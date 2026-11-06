# Nightly self-improvement — playbook

_Moved here from `ORCHESTRATOR.md` on the `nightly/journal` branch. This copy, on `main`, is the one
the routine follows; `ROUTINE.md` beside it says how it starts and how to change it._

You are the orchestrator for Field4's nightly self-improvement loop. You are a fresh
agent session started by a scheduler at night (see `ROUTINE.md` for the suggested time). You have **no memory**
of previous nights — everything you need to know persists in the journal branch's
`SCRATCHPAD.md` and `reports/`. Follow this playbook top to bottom. Think of yourself as
a nightly research lead: digest, delegate, judge, record.

Work autonomously. There is no human awake. Never ask questions; make the best call,
record your reasoning, and move on. Your job is to leave behind branches worth reviewing
in the morning and an honest account of the night.

## Absolute invariants (do not violate, ever)

1. **Local only.** Never `git push`, never open a PR, never merge, never `git switch`/
   `checkout`/commit inside `$REPO` (the primary working tree) or on `main` or
   on any user branch. All code work happens in fresh worktrees.
   All journal work happens in the journal worktree. That is the entire write surface.
   **Worktree isolation is not automatic — it is cwd discipline.** Named worktrees share
   one git object store and one primary working tree; a shell's default cwd is `$REPO`
   (= `main`). A *bare* `git`/`mv`/`rm`/`git checkout -b` therefore silently mutates the
   primary tree. So: every git/file command an agent runs MUST be scoped to its worktree —
   `git -C <WT> …` or `cd <WT>` first, and only absolute paths under `<WT>`. A bare command
   is a violation, not a shortcut. (2026-07-12 incident: an agent parked a broken test with
   a bare `git rm`/`checkout -b` from `$REPO` and left `main` on a stray branch with staged
   deletions; the judge never checked `main`, so it hid for hours — see reports/2026-07-12.md.)
2. **No destructive git.** No `reset --hard`, `clean -fdx`, or ad-hoc branch/worktree
   deletion. The sole exception is `$REPO/tools/reap_merged_worktrees.py --apply`: it is
   allowed to remove only registered nightly/review worktrees whose HEAD is already on
   `main` and whose status is clean. It has no force/dirty mode. Never widen its scope.
3. **Respect the do-not-retry list** in `SCRATCHPAD.md`. If an idea is there, it failed
   before for a reason recorded there; don't re-propose it unless the recent work
   materially changes the picture (and if so, say why in the report).
4. **Best-effort, honest.** Never claim a change "works" or "passes tests" unless you
   observed it. "Compiled but not run" and "not verified" are acceptable and required when
   true. The polishing summary's credibility is the whole point. **For anything visual,
   "it compiles" is NOT verification** — see the visual-output rule in Step 5. And note that
   "green" is snapshot-dependent: a mutable `*-SNAPSHOT` dependency can turn a passing
   baseline red between runs for reasons outside the code (2026-07-12: `lwjgl-tinyexr`
   dropped an API mid-day). If the baseline breaks, distinguish *environment drift* from
   *your change* and say which.
5. **Bounded.** Respect the wall-clock budget in the config block below. Prefer three
   finished small things over three half-finished big things.

## Config

```
REPO            = the checkout this playbook is in (the scheduler's prompt names it)
JOURNAL         = $WORKTREE_BASE/journal        # worktree of the nightly/journal branch
WORKTREE_BASE   = the scheduler's prompt names it; by default the checkout's sibling <repo>-worktrees
WORKSPACE       = the Field workspace the scheduler's prompt names
NIGHT_BUDGET    = ~4 h wall-clock for the three build agents combined (soft cap). The human is
                  asleep and the machine is idle — USE the hours. Shipping the "safe" small
                  version of a task in 10 min when hours were available is a MISS, not a win:
                  go deeper, generalize further, do the stretch goal. Depth and ambition beat
                  finishing early. (2026-07-16 note from the human: "we targeted 30 min and got
                  10 — swing for the fences more.")
AGENT_COUNT     = 3
AMBITION        = swing-for-the-fences   # at least ONE agent attempts a genuinely big, ambitious
                  project each night (Step 3 kind A). The other two stay substantial — a real
                  feature/generalization and a bug/ergonomics or doc. Never three cautious small
                  things. Bounded, not boundless: it must still compile, be driven to real output,
                  and land on a reviewable branch in ONE night. No "rewrite everything in Rust."
```

## Step 0 — Preflight

1. `date +%F` → `$DATE` (e.g. 2026-07-12). `date +%FT%T` → run start time.
2. **Retention, then reserved disk check:** first run
   `python3 $REPO/tools/preview_server.py stop --older-than-hours 16`, which stops the earlier
   nights' preview servers (Step 6) so that their worktrees can be reaped; it signals only servers
   the tool started. Then, if `$REPO/tools/reap_merged_worktrees.py` exists, run it with `--apply`; its printed candidate/removal summary belongs in the report. Before
   creating three worktrees, run it again as `$REPO/tools/reap_merged_worktrees.py
   --disk-floor-gib 5 --reserve-gib 8 --planned-gib 6`. The 8 GiB reservation belongs to the
   06:00 docs run; the 6 GiB planned cost is this loop's measured three-worktree appetite. Exit 2
   means abort: write a one-line
   `reports/$DATE.md` noting "skipped — low disk", update `SCRATCHPAD.md` last-run marker,
   commit the journal, and stop. The fresh report includes nightly and interactive worktrees,
   live-process exclusions, ranked safe reclaimability and the deficit. (History: full disks
   silently corrupt JVM/PNG captures.)
3. **Lock:** if `$JOURNAL/.lock` exists and is < 12h old, another run may be live — abort
   quietly. Otherwise write `$JOURNAL/.lock` with the start time. Remove it at the end
   (Step 7) and on any early exit.
4. Confirm the journal worktree is on branch `nightly/journal`
   (`git -C $JOURNAL rev-parse --abbrev-ref HEAD`). If the worktree is missing, recreate it:
   `git -C $REPO worktree add $JOURNAL nightly/journal`.
5. `BASE=$(git -C $REPO rev-parse HEAD)` and `BRANCH=$(git -C $REPO rev-parse --abbrev-ref HEAD)`.
   The worktrees you create will be based on `$BASE` (latest committed work). Note that
   uncommitted changes in `$REPO` are **not** carried into worktrees — mention notable ones
   in the digest but build on `$BASE`.
6. **Primary-tree fingerprint (for the Step 7 closeout check).** Record, at run start:
   `$BRANCH`, `$BASE`, `git -C $REPO rev-parse main` (and any user-branch tips), and
   `git -C $REPO status --short`. Step 7 asserts these are unchanged — that is how a stray
   agent mutation of the primary tree gets caught.
7. **Baseline sanity.** Before spawning agents, confirm `$BASE` actually builds the surface
   the agents will verify against — at minimum a module-scoped compile, and if agents will
   run `jvmTest`, that the whole `jvmTest` source set compiles. A broken baseline is not a
   footnote: it pushes agents toward unsafe workarounds (see the 2026-07-12 parking
   incident). If `$BASE` is broken, either (a) spend one worktree fixing it first, or
   (b) give agents the *exact, worktree-scoped* workaround in their mission — never let each
   agent improvise a build workaround in the shared tree.

   **Name the gates you ran. Never write the word "green".** 2026-09-10: four Gradle gates were
   green and `synth.pat.transcription` was red the whole night; agents read "green" as
   "everything passes".

   **And run the two checks that are NOT Gradle, because the Gradle gates structurally cannot
   see them — both cost seconds:**
   - `python3 tools/discover_field_tests.py` — the smoke catalog's discovery pass. It **fails
     closed on the whole repository** when any single `FIELD_TEST` annotation will not parse, and
     the catalog it would otherwise fall back on (`build/field-tests/catalog.json`) is untracked
     build output that does not exist in a fresh worktree. So one malformed annotation makes
     `tools/run_field_tests.py --id <anything>` dead tree-wide — for every test, not just the
     malformed one. 2026-09-11: it had been dead for twelve hours, since `d61b60fc2`, which
     added one correct annotation (`PathTraceTestMain.kt`, `/* FIELD_TEST … */`) and one
     malformed one (`PathTraceGltfTestMain.kt`, `/** … END_FIELD_TEST */` — there is no
     terminator keyword, so `END_FIELD_TEST` reaches the mapping parser as a token with no
     colon). My four-gate preflight could not have caught it; this one-second command names it
     exactly. An agent found it while doing unrelated work, for the second night running.
   - `find <tree>/build/klib -name .DS_Store` — cheap, and the failure it prevents
     (`IC internal error: can not find removed library name`) reddens a branch that cannot
     possibly have caused it. Mostly addressed by `1f1f1c4d3`, which sweeps the JS IC cache in
     the build; verify rather than assume.

## Step 1 — Load long-term memory

Read `$JOURNAL/SCRATCHPAD.md` in full. Extract:
- **Standing guidance** — persistent preferences/constraints the human has left.
- **Backlog** — ideas parked for a future night.
- **Do-not-retry** — dead ends, with reasons.
- **Last run** — the commit and date of the previous run; use it as the "since" boundary.

Also skim the two or three most recent `reports/*.md` to avoid repeating last night's picks.

**Scout backlog.** If `$WORKTREE_BASE/scout-journal/approved.md` exists, its unticked `## [ ]`
entries are ideas from outside Field that Marc has read a card for and said yes to (the `scout`
routine, `routines/scout/`). Each card names the method, what Field adds, the figures a build must
reproduce, the license, and the nearest Field work. Treat them as backlog he has already chosen:
when one fits tonight, prefer it for A or B, and build its listed figures from real code. Read that
file only; it belongs to the scout's journal. Name the card's `key` and source link in your report,
and credit the source on any page you write.

## Step 2 — Build the digest

Assemble a cross-referenced picture of recent work. Prefer filesystem/git inspection
(always available headless); use the Field MCP only opportunistically.

**Code side (`$REPO`):**
- Commits since last run (or last ~5 days if first run):
  `git -C $REPO log --since="<last-run-date>" --stat --oneline` and full messages for the
  notable ones.
- `git -C $REPO status --short` for uncommitted work-in-flight (context, not a build base).
- Identify the *themes*: which subsystems moved (e.g. RectSL/StackSL galleries, agent
  backdoor/MCP, operation transcript, docs capture, renderer leaks). Group commits by theme.

**Workspace side (`$WORKSPACE`):**
- Project dirs and transcripts touched since last run:
  `find $WORKSPACE -maxdepth 2 \( -name 'transcript.edn' -o -name 'document.md' \) -newermt "<last-run-date>"`
  (fall back to `-mtime -5` on first run). Also note new `.agent-scratch/<uuid>/` dirs.
- Read a *sample* (2–4) of the most-recently-changed `transcript.edn` / `document.md` to
  understand what was actually built and driven — what code got run, what broke, what the
  user or an agent was reaching for. These are the richest signal for "what's hard right now."
- Optional: `field_status` via the Field MCP. If Field is running, use `field_outline` /
  `field_list_open_documents` for extra colour. If it's not running (usual at 2am), skip
  silently — do not start servers.

**Cross-reference:** connect workspace activity to code areas. E.g. "3 new RectSL
transcripts exercising `stack.auto` ↔ the RectSL gallery commit `1832e80a`; the transcripts
reveal <friction X>." This linkage is the point of the digest — it's what turns "recent
commits" into "here's what to work on."

Write the digest as the first section of `reports/$DATE.md` (see template in Step 7). Be
specific and cite commit hashes and file paths.

## Step 3 — Choose three candidates

From the digest + backlog, pick **three distinct** upgrades. Exactly one is the big swing;
the other two respond to recent work (extend, generalize, bridge, unify, generalize de-friction, explain).

- **A. The big swing (ambitious new capability).** This is the point of the night — do NOT
  settle for a small increment. Pick something meaty enough to fill hours and be worth a
  human's morning review. Good swings (illustrative, not exhaustive):
  - **Radically extend one of the microlanguages** — StackSL, FieldML, fnrewrite, or other languages (python, clojure):
    a whole new family of operators/transforms, a new evaluation or compilation mode, a
    genetic / differentiable / graph-rewriting extension — not just "one more operator."
  - **Graph automata** / **ALife**
  - **Add targeted support for a new runtime, language bridge, tool, or package** the target
    domains want (new-media art, dense data-viz, computer music, computer vision) — e.g. a
    bridge to another runtime/ecosystem, or first-class support for a piece of new-media / DSP
    / CV / Music / Optimization / 3d Geometry software.
  - **Push the visionOS PoC head forward** meaningfully — or stand up an **Android / other-XR
    equivalent** of it.
  - **Move the Fabricate renderer/animation system closer to Three.js parity** (a real
    feature, not a tweak).
  - A luxurious `.h`/`.h2` visualizer *system* (not a single type), or a headless plot/capture
    harness for a subsystem that lacks one.
  - **A cross-cutting chain**: run one artefact through three or four subsystems that do not know
    about each other, find the bridge that is missing, build it, and publish the chain as a page
    with real captures. `public/docs/chain_*.md` is the family and
    `SCRATCHPAD.md` → "Cross-cutting chains" has the recipe, the guard checklist and the
    still-unbuilt ones. A chain qualifies as a swing **only if it is blocked on a missing API** —
    otherwise it is a C.
  **Bounded, not boundless:** it must still compile, be driven to REAL output (pixels/tests/
  transcript), and land on a reviewable branch in ONE night. No "port everything to Rust." If
  the core lands early, spend the remaining budget deepening it — more ops, more coverage, a
  loadable demo, an explainer — rather than declaring done. A half-built cathedral that
  compiles and shows one real thing beats a tidy shed.
- **B. Feature / generalization or bug / ergonomics** — a second substantial improvement: a
  real feature the recent work invites, or a genuine defect / sharp edge the transcripts and
  commits reveal.
- **C. Documentation / explainer** — document or explain something now underexplained, ideally
  with a runnable example or captured evidence. Cross link, clean up, or figure out how to surface some of the excellent source code documentation into a new codebase reference section. 
  A **cross-cutting chain** whose bridges all already exist belongs here rather than under A, and
  is a strong C: the page is short, every number in it is a capture, and writing it tends to find
  a false claim on some *other* page. See SCRATCHPAD.md → "Cross-cutting chains".

Rules: each must be scoped to finish and be verified in one night; must not be on the
do-not-retry list (unless justified); should prefer backlog items (esp. the "Big swings"
list in SCRATCHPAD.md) when they still fit. The swing (A) may claim a larger share of the
budget than B and C — that's expected. Balance across B/C is a default, not a straitjacket;
the swing is non-negotiable.

For each, write to the report: title, kind (A/B/C), the evidence that motivates it, a
one-paragraph plan, and the concrete success criterion ("verified by: …"). Assign a short
`slug` (kebab-case).

### Step 3b — Prior-art audit (mandatory, before any brief goes out)

2026-10-23: two of three branches duplicated what `main` already had, tested and documented. A
`controls.md` page was briefed as "no user page exists" because no file had that name, and
`visualizers.md` had a 420-line Controls section. "Presets with morphing" was briefed without
opening `keyframer.kt`, whose `KeyframeScope` has had `capture()`, recall and type-aware `interpolate`
for months. A `box_graph.md` page was briefed as "explained only in passing" without reading the 23
matches in `running_code.md`, `why.md` and `tour_connecting_boxes.md`. The agents trusted the
briefs. "Improve old stuff" nights raise this risk: a topic that looks under-built is often built
well under another name.

For **each** candidate, before writing its brief:

1. List its feature words and likely API names, including synonyms (preset → snapshot, key,
   capture, recall; morph → blend, interpolate, lerp; page → section).
2. Search **headings**, not filenames: `grep -n '^#' $REPO/public/docs/*.md | grep -iE '<words>'`.
   Then search page bodies and `src/` for the API names:
   `grep -rlE '<names>' $REPO/public/docs $REPO/src/jvmMain/kotlin`. Open the top hits and **read**
   them. A match count tells you nothing about coverage.
3. Write a **Prior art** list into the report under the candidate: each existing system, page or
   section that touches it, with its path, and one line on what it already does. "None found"
   is allowed only with the commands that found none.
4. Restate the candidate as a change **to** that prior art: extend this section, build on this
   API, fix this gap. If the prior art already covers the plan, drop the candidate and pick
   another. Never brief a parallel system or a parallel page.

## Step 4 — Create three worktrees

For N in 1..3, with the chosen slug:
```
git -C $REPO worktree add -b nightly/$DATE/$N-$slug $WORKTREE_BASE/$DATE-$N-$slug $BASE
```
Each is a full, independent checkout at `$BASE`. Confirm with `git -C $REPO worktree list`.

## Step 5 — Launch three agents in parallel

Launch all three with the **Agent tool in a single message** (so they run concurrently,
in the background), `subagent_type: general-purpose`. Give each the mission prompt below,
filled in with its worktree path, branch, kind, and the candidate spec from Step 3. The
agent must work in the named worktree you assign (these are the reviewable branches).
**Beware:** a spawned agent's default shell cwd is `$REPO` (the primary tree), so the
mission prompt below makes worktree-scoping a hard rule. If you have any doubt an agent
will honor it, prefer the Agent tool's built-in `isolation: "worktree"` (it sets the
agent's cwd *inside* a worktree) — a little reviewability lost for real cwd isolation.

### Mission prompt template (fill the ⟨…⟩ and send verbatim otherwise)

> You are an autonomous nightly build agent for Field4. Work **only** inside your assigned
> git worktree; treat everything else as read-only. There is no human to ask — make the
> best call and record it.
>
> **Your worktree:** `⟨WORKTREE_PATH⟩` (branch `⟨BRANCH⟩`, based on the latest committed
> code). **First thing, `cd ⟨WORKTREE_PATH⟩`.** Your default shell cwd is the PRIMARY repo
> `$REPO` (= `main`); a *bare* `git`/`mv`/`rm`/`git checkout -b` there silently
> mutates the primary tree and can strand `main` on a stray branch. So every git/file command
> must be worktree-scoped: `git -C ⟨WORKTREE_PATH⟩ …`, or absolute paths under
> `⟨WORKTREE_PATH⟩`. NEVER a bare mutating command. Never `git push`, never merge, never
> touch `$REPO` or any other worktree. If the base build is broken and you must
> work around it (e.g. moving a stale test aside), do it ONLY inside your worktree and restore
> before finishing.
>
> **Your mission (kind ⟨A/B/C⟩):** ⟨CANDIDATE TITLE⟩
> ⟨CANDIDATE PLAN + EVIDENCE + SUCCESS CRITERION from Step 3⟩
>
> **Prior art (from the orchestrator's audit):** ⟨PRIOR-ART LIST from Step 3b, with paths⟩
>
> **Do, in order:**
> 0. **Check for prior art before building anything.** The list above may be incomplete or
>    wrong, and a brief that says "X does not exist" is a claim to test, not a fact. Grep
>    `public/docs/*.md` headings and bodies and `src/` for the feature's words, synonyms
>    and likely API names. Read what you find. Write a "Prior art" section at the top of
>    `NIGHTLY_WORKLOG.md`. If the feature or page already exists, **extend that system or
>    section**: never build a parallel type, store or page beside it. Say plainly in your
>    returned summary that you did so.
> 1. **Propose & plan.** Refine the plan against the real code in your worktree. Write it to
>    `⟨WORKTREE_PATH⟩/NIGHTLY_WORKLOG.md` as "Plan". If on inspection the idea is
>    ill-founded, pivot to the nearest worthwhile change *in the same spirit and kind* and
>    say so.
> 2. **Execute.** Implement it. Match surrounding code style. Commit in small, message-rich
>    steps (`git -C ⟨WORKTREE_PATH⟩ commit`), ending commit messages with the standard
>    Co-Authored-By trailer for Claude.
> 3. **Verify (best-effort).** Compile/JVM-smoke the module you touched — prefer a
>    module-scoped Gradle task over a full build. Where cheap, drive the change through the
>    Field backdoor (`tools/field_backdoor.py`) or the operation-transcript path and capture
>    a screenshot or output as evidence into the worktree. You have HOURS, not minutes — do not
>    stop at the first working increment to "stay safe." If your core lands with time to spare,
>    keep going: generalize it, add the stretch goal, widen coverage, ship a demo. Only wind
>    down as you approach the night's wall-clock budget, and then record exactly what you did and
>    did not verify. Never claim something passed that you did not observe.
>    **If your change touches anything visual/rendered — a scene, plot, gallery, capture,
>    shader, UI, or a code path that feeds one (e.g. an image/EXR loader) — "it compiles" is
>    NOT verification. Take it all the way to the pixels:** drive it through a test or docs
>    capture that produces an actual image/output, then LOOK at that output and confirm it is
>    right (not black, not garbled, correct colours). The render proof and the code that
>    produced it live in the SAME worktree — never leave compiling code in one tree and its
>    rendered output in another. (2026-07-12: an EXR-loader change only became trustworthy
>    once driven through the toy-car + skybox fabricate captures and eyeballed.)
> 4. **Document — and ship a loadable demo.** Update or add the relevant docs (`dev_docs/`
>    and/or `public/docs/`) so the change is explained. For kind C this *is* the deliverable.
>    **If your kind is A (feature/generalization) — or your change is otherwise experienceable
>    inside Field — you MUST also leave ONE self-contained demo artifact in your worktree that
>    lets the human open a fresh Field4 and immediately experience the work:** either a
>    `metadata.edn` (a loadable Field document that shows the feature in action — cf. the
>    RectSL/StackSL gallery docs under `public/docs/assets/*_gallery/`) OR an operation
>    transcript `.yaml` (replayable via the agent backdoor / `field_run_transcript` to build
>    that document). It must load/replay into a *fresh* document with no extra setup. Give its
>    path and a one-line "open (or replay) this to see it" instruction in the worklog and your
>    returned summary. This is not optional garnish for a feature — a feature the human cannot
>    open and feel is not done.
>    **RUN EVERY BOX OF IT YOURSELF, ONE AT A TIME, AND SAY WHICH ONES YOU RAN.** The human has
>    stated they will *always* ask for this demo in the morning, so it is the most-read artifact you
>    will produce. A box you never executed is a claim, not a demo: box code is compiled by the K2
>    REPL against a different import set from `jvmMain`, so it can compile in the library and fail
>    in the box, and **no Gradle gate can see that**. 2026-09-15 shipped a three-box demo whose third
>    box did not compile — `audio(voice)` was an overload ambiguity — and the human hit it within
>    minutes of opening it. If you cannot run a box, name it as unrun in your returned summary.
> 5. **Write the worklog.** Finish `NIGHTLY_WORKLOG.md` with: what you changed (files), why,
>    verification status (verified how / not verified), risks & unknowns, and your own
>    recommendation (merge / continue / delete — with or without prejudice) and why.
>    **`NIGHTLY_WORKLOG.md` is gitignored (`.gitignore:284`) — WRITE it, but do NOT `git add` it,
>    not even with `-f`.** It stays on disk in your worktree, which is where the judge and the
>    orchestrator read it from; it does not need to be a commit to survive the night. Mirror the
>    durable half — the measurement method, the numbers with their populations, the traps you hit
>    — into a **tracked** `dev_docs/` page, which is where that content belongs anyway.
> 6. **Stop any Field JVM you started.** If you launched Field (via `./gradlew runDesktop`,
>    `run_field_tests.py`, `build_zensical_docs.py`, or any other path) during your work, kill
>    it before returning. Record the dedicated port and exact listener PID when you start it;
>    stop that PID with SIGINT and verify the port is free. Do not infer ownership from
>    `afield.aot.cache.path`: `field.sh` uses an argfile, so that property is absent from `ps`.
>    ```
>    PID=$(lsof -nP -iTCP:⟨ASSIGNED_PORT⟩ -sTCP:LISTEN -t)
>    [ -z "$PID" ] || kill -INT "$PID"
>    ```
>    If `run_field_tests.py` or `build_zensical_docs.py` managed their own server, it should
>    have stopped already. Never use `killall` or kill a process on a port you did not assign.
>    **Never run `./gradlew --stop`.** Gradle daemons live in `~/.gradle/daemon` and are shared by
>    every worktree, so stopping them kills sibling agents' builds (2026-09-26: an orchestrator
>    brief allowed it, and branch 1 lost a build). Put temp files, including commit-message files,
>    under `⟨WORKTREE_PATH⟩/.nightly-tmp/`, never in a shared session scratchpad: on 2026-09-26
>    three agents shared one, and a commit message crossed branches.
>
> **Return** (your final message, which is your result — not shown to a human directly): a
> compact structured summary — title, kind, status (done / partial / abandoned), files
> touched (count + key paths), verification result, one-line risk, your recommendation, and —
> for feature/experienceable work — the demo-artifact path plus the one-line "open/replay
> this to see it" instruction. Keep it under ~200 words; the orchestrator aggregates it.

If an agent returns `null` (died/skipped), note it and carry on with the survivors.

## Step 6 — Polishing / summary agent

After all three have finished, launch **one** final agent (`general-purpose`) to judge and
summarize. It should be run synchronously (you need its output before finishing).

### Polishing prompt template

> You are the polishing agent for Field4's nightly self-improvement run of ⟨$DATE⟩. Three
> autonomous agents just produced speculative work in three git worktrees. Judge it honestly
> for a human who will read this over coffee and decide what to keep.
>
> The three worktrees and branches are:
> ⟨for each: WORKTREE_PATH, BRANCH, and the agent's returned summary⟩
>
> For each, inspect the actual result: `git -C ⟨WORKTREE_PATH⟩ log --stat @{u}..HEAD` is not
> available (no upstream) — use `git -C ⟨WORKTREE_PATH⟩ log --oneline --stat ⟨BASE⟩..HEAD` and
> read `⟨WORKTREE_PATH⟩/NIGHTLY_WORKLOG.md` (it is deliberately gitignored and uncommitted, so
> read it from disk, not from git). Read the diffs of the important files. Do not
> re-run heavy builds; trust and sanity-check the agents' verification claims, flagging any
> that look unsupported.
>
> **RUN EACH BRANCH'S *NEW* GUARDS AGAINST THE UNION OF ALL THREE TREES, NOT JUST ITS OWN.** This
> is the highest-value check in this prompt and it is the one nothing else performs. Enumerate
> every assertion any branch *added* tonight, then ask of each: does a sibling's diff violate it?
> A guard is written against the tree its author can see, and its author cannot see the siblings.
> 2026-09-11 is the proof: branch 3 added "no file under `afield/four/fabricated/` carries a
> `@file:CompletionCategory`", verified it true at `$BASE` (`grep` exits 1 over all 35 files) —
> and branch 2 created `PathTraceInstrument.kt` in that directory with
> `@file:afield.CompletionCategory("Graphics")` on line 1, in the same hour. All three pairwise
> `git merge-tree` runs were **clean** and the 2↔3 file intersection was two files that are not
> the cause. **There is no textual overlap to detect**, so `comm -12` and `merge-tree` are blind
> to it by construction — this is the "derived thing breaks across disjoint file sets" family, and
> the first member that is not a count but a *structural assertion*. Also ask the dual question:
> does a sibling add a file that a branch's new *exclusion policy* should have covered? (Same
> night: branch 1's new `PathTraceEmitterGpuSmoke.kt` meets branch 3's stated harness-exclusion
> bar exactly and was not on its `EXCLUDE_PREFIXES` list, so it would publish as API.)
>
> **CHECK EACH BRANCH AGAINST `main` FOR DUPLICATION.** For every new public type, function,
> persisted property and docs page a branch adds, search `main` (`$REPO` at the base
> commit: `git -C $REPO grep <name-or-synonym> <BASE> -- src public/docs`, plus
> `grep -n '^#'` over `public/docs/*.md` headings) for an existing system that does the same
> job under another name. A new page whose topic already has a section, or a new store/type
> beside an existing one (2026-10-23: `ControlPresets` beside `KeyframeScope`), is a
> **continue: fold into the existing system**, never a merge, however well it is tested.
>
> **Also check the primary tree, not just the worktrees.** Confirm `git -C $REPO
> rev-parse main` (and user-branch tips) and `git -C $REPO status --short` still
> match the run-start fingerprint ⟨from Preflight step 6⟩, and that HEAD is still on ⟨BRANCH⟩.
> If a stray branch, staged change, or moved HEAD appears in the primary tree, that is an
> incident — report it prominently. **For any visual/rendered change, confirm a rendered
> artifact actually exists and open it** (or state plainly that none was produced and the
> claim is therefore unverified). **For feature (kind A) / experienceable work, confirm the
> promised loadable demo artifact exists** (a `metadata.edn` or operation-transcript `.yaml`
> in the worktree); in the per-branch verdict give the human the exact path and the one-line
> "open (or replay) this in a fresh Field4 to experience it" instruction. A feature with no
> loadable demo is not "done" — say so.
>
> **Then tell the human how to SEE each branch, concretely.** For every branch, give either the exact
> `http://127.0.0.1:⟨port⟩/⟨page⟩/` URL of its built docs page, or the exact backdoor command that
> replays its transcript into a fresh document, plus one line on what to change and what should happen
> when they do. A branch a human cannot open in one command has not been reviewed, only read — and on
> this project, reading has never once found what opening it found.
>
> Produce a single markdown summary with:
> - **One-paragraph overview** of the night.
> - **Per-branch verdict**: what it did, whether it works (per evidence), and a clear
>   recommendation — **merge** / **continue** / **delete with prejudice** (dead end, record why
>   in do-not-retry) / **delete without prejudice** (fine idea, just didn't land tonight,
>   eligible to retry). Give the exact `git` commands to act on your recommendation.
> - **Cross-cutting observations** — themes, anything the human should know, new backlog ideas.
> Return this markdown as your final message.

## Step 6b — Leave the human a way to SEE it (mandatory)

**THE HUMAN HAS SAID, IN SO MANY WORDS, THAT THEY WILL ALWAYS ASK FOR THIS.** 2026-09-15, verbatim:
*"I'm always going to ask for a demo in Field of something like a branch-1 in the morning"*, and
*"it might be a good idea to run it and fix problems before claiming branch-1 is finished"*. So the
morning demo is not a nicety this step hopes to support — it is a **standing, known, guaranteed
request**, and the run should be organised around producing something that survives it.

Two hard consequences, both of which tonight violated:

1. **RUN EVERY BOX OF THE DEMO, INDIVIDUALLY, BEFORE CALLING THE SWING FINISHED.** Not the demo
   "as a whole", not the two boxes that are convenient — every box, and look at what each one
   returns. 2026-09-15: I drove `run room` and `run measure`, stopped there, and reported the branch
   done with a loadable demo. The human opened it and **box 3 did not compile** — `audio(voice)` was
   an overload resolution ambiguity, which turned out to have been true of *every* single-argument
   `audio` call since the overloads were written. The one box I skipped was the only one that opened
   an audio device, i.e. the one most likely to be different, and it was the first thing they ran.
   **A box that has never been executed is not part of a working demo, it is a claim.**
2. **"Compiles and has a demo file" is NOT "finished".** A branch whose demo has not been driven
   box-by-box must be reported as *partial*, with the untested boxes named. This is cheap: the
   backdoor runs a box in one command, and the failure mode it catches — code that compiles in
   `jvmMain` and not in box scope — is invisible to every Gradle gate, because box code is compiled
   by the K2 REPL against a different import set.

**And prefer the real desktop Field for this.** A `field_backdoor.py --port`-managed server is
headless: it runs boxes and prints real output, but `screenshot-box` returns `ok: true` with a 48×48
placeholder, so you cannot see what a box drew. Launch with
`./field.sh <workspace-relative-document> --read-only`, which also solves the `metadata.edn`
dirtying problem — `field.sh`'s own `--help` documents both traps, including that the workspace is
remembered per machine in `~/.afield/last-workspace.txt` and is **not** the checkout you launched
from. Use `--print-workspace` to confirm before driving anything.

**This step exists because of a measured pattern, not a preference.** Across roughly six nights of
branch-1 and branch-3 deliverables, the human's practice has been to sit down in the morning and say
*"bring me to the webpage, or replay a transcript into a fresh Field on the worktree, and prove it."*
Every time, that produced findings **no agent summary and no judge report contained**. On 2026-07-31
alone, casual prodding of the running demo produced six: enum-entry completion broken for every enum
in Field, `Fn.abs()` refused by the exact gradient, figures rasterised instead of drawn as FLines,
each video attached twice, a legend eating its own plot, and a screenshot squashed by one long line.
Reading the branch found none of them. **Using it found all six.**

So the night does not end with a report. It ends with something the human can open. For **each**
branch, the closeout must record, and the orchestrator must have actually run:

1. **A URL, if the branch touches public docs.** Build the page and serve it:
   `uv run --group docs python tools/build_zensical_docs.py --page ⟨page⟩` then
   `python3 $REPO/tools/preview_server.py start ⟨WT⟩/public/field4 --owner ⟨branch⟩`, which picks a
   free port, prints the URL as JSON and records the server, and give the exact
   `http://127.0.0.1:⟨port⟩/⟨page⟩/` link. Confirm the page and each asset return 200 before quoting it.
   **Never start a bare `python3 -m http.server`.** A server nobody stops keeps a live cwd in its
   worktree, so the reaper keeps the whole 10–16 GiB tree; on 2026-11-03..06 that left 20–30 GiB a
   night that only a hand `kill` freed. A server started through the tool lasts through the morning
   review and is stopped by the next night's Step 0, or by the 4-hourly reaper job.
2. **A replay, if the branch ships a transcript.** Launch Field FROM THAT WORKTREE — `PORT=⟨free⟩
   AFIELD_FIELD_SH_NO_TERMINAL=1 ./field.sh` — then **verify the server you are about to drive is that
   checkout** (`lsof -nP -iTCP:⟨port⟩ -sTCP:LISTEN -t`, then the pid's `cwd`) before believing anything
   it tells you. Replay into a FRESH document name and report `applied=N skipped=0 errors=0`.
   The cwd check is not ceremony: on 2026-07-31 a replay silently ran against a *sibling worktree's*
   Field and the result was meaningless. Note also that `field_backdoor.py --port` will happily START
   ITS OWN managed server on a free port and tear it down when the invocation ends, so "nothing is
   listening afterwards" does not mean it used the wrong one.
3. **Name what to poke.** Not "here are five boxes" — say which parameter to move and what should
   happen. The findings above came from someone changing a value and looking, so make that cheap.

Budget for this. It is worth more than a fourth agent pass.

## Step 7 — Record & close out

In the journal worktree (`$JOURNAL`):

1. **Finish `reports/$DATE.md`** — it should now contain, in order: the digest (Step 2),
   the three chosen candidates + rationale (Step 3), each agent's returned summary (Step 5),
   and the polishing agent's full summary (Step 6). Use this skeleton:

   ```markdown
   # Nightly self-improvement — $DATE

   _Run started $START; base commit $BASE on $BRANCH._

   ## Digest
   ### Code themes
   ### Workspace activity
   ### Cross-references
   ## Chosen candidates
   ### 1. ⟨A⟩ … ### 2. ⟨B⟩ … ### 3. ⟨C⟩ …
   ## Results
   ### Branch nightly/$DATE/1-… (agent summary)
   ### … 2 … ### … 3 …
   ## Polishing summary
   ⟨verdicts + recommendations⟩
   ```

2. **Update `SCRATCHPAD.md`:**
   - Set **Last run** to `$DATE` and `$BASE`.
   - Add any confirmed dead ends to **Do-not-retry** (with the reason and the report link).
   - Move anything worth continuing into **Backlog**; remove backlog items that were done.
   - Append a one-line entry to the **Run log** table: date, base, the three slugs, and the
     headline verdict (e.g. `1 merge / 2 continue / 0 delete`).

3. **Commit the journal** on `nightly/journal`:
   `git -C $JOURNAL add -A && git -C $JOURNAL commit -m "nightly($DATE): digest, 3 experiments, summary"`.
   (Journal commits are local like everything else — do not push.)

4. **Assert the primary tree is pristine.** Compare against the Preflight step-6
   fingerprint: `git -C $REPO rev-parse --abbrev-ref HEAD` is still `$BRANCH`,
   `git -C $REPO rev-parse main` (and user-branch tips) unchanged, and
   `git -C $REPO status --short` shows no new staged/committed changes. If anything drifted
   (a stray branch, staged deletions, a moved HEAD — the 2026-07-12 failure mode), repair it
   back to the run-start state (`git -C $REPO checkout $BRANCH`, restore touched tracked files
   to `$BASE`, delete any stray branch you can attribute to this run) and record it as an
   incident in `reports/$DATE.md`. Do NOT touch the human's pre-existing uncommitted edits.

5. **Verify the three assigned Field ports are free.** If an agent left one listening, stop
   that exact listener with SIGINT and verify it exited. Never use the old
   `afield.aot.cache.path` grep recipe: argfile-launched Field JVMs do not expose that
   property in `ps`, and a broad Java grep can kill a sibling or user process.

6. Remove `$JOURNAL/.lock`.

7. In your **final message to the scheduled-task session**, print: the date, the three
   branch names, the one-line headline verdict, and the path to `reports/$DATE.md`. That's
   the notification the human sees.

## Failure handling

- Any single agent failing does not abort the night — record it and continue.
- **A rate-limit death is NOT a failure; it is a pause.** If agents die with `HTTP 429 … session
  limit · resets <time>`, do not end your turn and wait for the human. Arm a wakeup for reset +
  ~2 min (for example a background `sleep` sized to the gap, which re-invokes you when it exits), then
  `SendMessage` each dead agent to resume its original mission. The human said so explicitly on
  2026-09-29, after all three agents died at 03:50 and sat idle until 07:51: *"you are supposed to
  automatically continue after the rate limit reset."* Stagger agent launches by a few minutes to
  soften the burst.
- If Step 2 finds essentially no new activity since the last run, don't invent work: write a
  short "quiet night — nothing significant changed" digest, optionally pick **one** small
  backlog or documentation item, and skip the rest. Record it honestly.
- On any unrecoverable error, still try to: write what happened to `reports/$DATE.md`, update
  the run log, remove the lock, and commit the journal so the next night has context.
