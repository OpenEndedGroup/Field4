# Landing on `main`

Field is developed by several people and many agents at once, each working in a separate
worktree on a topic branch. None of them merge into `main` themselves. Instead they hand the
branch to `tools/land.py`, which keeps one queue per clone and has a single process, the
*lander*, put branches onto `main` one at a time.

```diffstatic {data-lang=bash}
:::static
uv run python tools/land.py enqueue
```

That queues the current branch at its current commit and returns immediately. You can go back to
work; the lander will merge it, regenerate the generated artifacts, run the full guard once, and
fast-forward `main`, or bounce it back with a reason.

## Why a queue

Before the queue, every agent landed its own work: merge `main`, run about 45 minutes of guards,
discover that `main` had moved in the meantime, merge again, run the guards again. With a dozen
sessions doing this at once they mostly collided with each other. Now only the lander writes to
`main`, so there is nothing to race against and nobody has to wait around for their own landing.

## Watching it

```diffstatic {data-lang=bash}
:::static
uv run python tools/land.py status --watch
```

![The landing queue in `status --watch`](assets/landing_queue/status_watch.webp)

The top line says whether the lander is running (here under `launchd`, up for 25 hours). Below
it are three sections:

- **landing**: the item in progress, with its branch, commit, who queued it, and its commit
  subject. The bar compares elapsed time with how long landings typically take, so a branch that
  goes past that is easy to spot ("32s over typical"). `step` is what the lander is doing now and
  `last` is the newest line in its log, whose path is printed below.
- **queued**: what is waiting, oldest first, with an estimate of when each starts.
- **recent**: what has landed (✓) or bounced, with the `Land ...` commit on `main`, how long it
  took, and which guard ran: the full Gradle `guard`, or `no gradle` when nothing the guard cares
  about changed.

Press `h` to ask what is going on, which sends the queue's state and logs to `claude -p` and
prints the answer (`land.py huh` does the same from the command line, and `huh --context` shows
what it would send without asking). Press `q` to quit.

## The commands

| command | what it does |
| --- | --- |
| `enqueue [branch] [--note "..."] [--push]` | queue a branch at its current commit; `--push` pushes `main` to `origin` once it lands |
| `status [id] [--watch]` | the queue, or one item (exit 0 landed, 1 bounced, 3 pending) |
| `wait <id>` | block until the item lands (exit 0) or bounces (exit 1) |
| `mine [branch]` | every attempt to land this branch, newest first |
| `sync [--push]` | fetch `origin/main` and land it through the queue |
| `huh [--context]` | ask `claude -p` what the queue is doing |
| `run [--once]` | the lander itself; one per clone |

An agent that needs the outcome runs `wait` as a background command rather than polling
`status`. Someone returning to a branch after a while runs `mine` to see what happened to it.

## What the lander does

For each round the lander:

1. resets its own worktree to `main` (it never touches yours);
2. skips any item whose commit is already on `main`;
3. runs `tools/merge_regen.py` on the commit, which merges it, regenerates the committed
   generated artifacts on the merged tree (see
   [Building & Testing](building_and_testing.md#running-the-tests)), runs the guard once, and
   commits `Land <branch> (<who>)`;
4. fast-forwards `main` to that commit, starting over if `main` moved in the meantime;
5. on any failure, bounces the item with the reason and log path and leaves `main` alone.

It takes up to eight of the oldest items per round, merges each as its own commit, and runs one
regeneration and one guard for all of them. If the round fails, it splits the batch in half and
retries from the oldest until the branch responsible bounces on its own. A branch that conflicts
only with another branch in the same batch waits for a later round instead of bouncing.

The commit is fixed when you enqueue. Later commits on the branch are not landed until you
enqueue again, and re-enqueueing keeps the branch's place in the queue.

To fix a bounce, fix the branch, commit, and enqueue again. To bring `main` into your branch while
you work, use `tools/merge_regen.py main`, which regenerates generated files instead of making you
hand-merge them.

??? aside "Where the queue lives"
    The queue is plain JSON files in the clone's common git directory
    (`.git/field-land-queue/`), so every worktree of the clone sees the same queue, and the batch
    logs live next to it under `logs/`. The full reference is `uv run python tools/land.py --help`.
