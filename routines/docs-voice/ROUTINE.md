---
name: docs-voice
title: Documentation voice catchup
summary: Reviews new and extended public documentation for Marc's voice and a clear journey for the reader, then lands the prose edits.
kind: agent
schedule: 0 14 * * 6
runtime-hours: 3
disk-gib: 8
quiet: no
needs: the landing queue running (tools/land.py); an agent with enough time to read the changed pages
cost: one agent, a seeded worktree, and documentation checks and a queued site build
reports: runs
---

# Documentation voice catchup

Recommended model: **GPT-6.1 Terra**, when the scheduler offers it. This is a recommendation,
not a model setting: the routine runner uses the agent configured for that scheduler.

Give new public documentation the same close, reader-oriented language review as the long Field 4
docs voice pass. Marc's instruction is to make it "just a bit better": speak to users, show them
what they can do, feed their ambition and teach them how. Preserve the writer's personality and
technical precision. The goal is good prose, not a mechanical purge of words or a uniform style.
Read `dev_docs/agents/DOCS_WRITING_RULES.md`, `public/docs/index.md` and
`public/docs/funFeatures.md` for the house rules and examples of Marc's voice before editing.

The scheduler supplies `$REPO` and `$WORKTREE_BASE`. Never edit the primary checkout, another
agent's worktree, generated reference pages, generated transcript documents or assets. Do not
change executable code, capture fences or their attributes in a language pass. When a factual or
API question cannot be resolved from source and examples, preserve the claim and report it for
follow-up rather than guessing.

1. In `$REPO`, identify the latest landed commit on local `main` with subject beginning
   `docs(voice): review through ` and a `Docs-Voice-Reviewed-Through: <full SHA>` trailer. Use
   its trailer as the baseline, after checking that it is an ancestor of `main`. If there is no
   valid landed marker, use `f717972085` (the November 17 full pass) as the baseline. The November
   26 catchup at `34eabd346` was selective, so it is not a whole-site checkpoint. Set the target
   to the current `main` SHA before starting; later landings belong to the next pass. Inspect the
   history and diff from baseline to target under `public/docs/`, excluding `reference/` and
   `assets/`. Include newly authored pages, added sections at the ends of previously polished
   pages, and relevant prose changed alongside code. If a page was deleted, inspect its replacement
   only if it exists in the target. Do not treat a filename list or a keyword scan as a review.
2. Create a seeded worktree from that target, using a unique branch and path:
   `python3 $REPO/tools/worktree_seed.py add -C $REPO -b docs/voice-<unique-id> $WORKTREE_BASE/docs-voice-<unique-id> <target>`.
   Work there. Review the changed prose paragraph by paragraph in the context of each page, including
   introductions, transitions, instructions, captions and lists. On a new page, read the whole page.
   Keep an explicit list of pages and sections reviewed and those left unfinished. If the diff is
   too large to complete, land a clearly described partial batch without a review-through marker;
   the next run must revisit the uncheckpointed range. Do not claim full coverage for a partial run.
3. Edit with a light hand. Prefer a concrete invitation or example to an abstract opening. Make it
   clear who is speaking and what the reader can do next. Repair awkward transitions and sentence
   rhythm. Look for repeated formulae such as `X, not Y`, `X, that is Y`, forced sets of three, and
   promotional filler like delve, elevate, harness, navigate, enhance, foster, uncover, unlock,
   underscore and reimagine. Judge each occurrence in context: real technical terms and necessary
   distinctions stay. Preserve useful detail, links, heading anchors, code and capture blocks.
   Check facts against the source or working examples before changing their meaning. Do not rewrite
   passages that already sound like Marc simply to leave a mark.
4. Review the diff. Verify code and capture fences are unchanged, check `git diff --check`, and run
   the relevant checks described in `AGENTS.md`; run `uv run python tools/land.py precheck` before
   enqueueing. If there are no edits, report the reviewed range and leave the checkpoint unchanged;
   a future pass may revisit it. If edits exist, commit only your authored prose paths. For a fully
   reviewed range, use subject `docs(voice): review through <short target SHA>` and put
   `Docs-Voice-Reviewed-Through: <full target SHA>` in the commit message. Add that trailer only
   after the entire selected range has actually been reviewed. For a partial batch use subject
   `docs(voice): partial catchup` with no trailer. Never use `--no-verify`.
5. Enqueue the committed branch with `uv run python tools/land.py enqueue`, then wait for its printed
   `land.py wait <id>` result. If it bounces, inspect the reason, fix what belongs to this pass and
   recheck before re-enqueueing; report an external blocker if it cannot be resolved. Once the
   landing succeeds, queue `uv run python tools/land.py doc` and wait for the build result. Do not
   push `main`. Preserve concurrent work and use the repository's merge-regeneration workflow if
   integration is needed.
6. Report the baseline and target SHAs, pages and sections reviewed, edits made, any unreviewed
   material or technical uncertainties, checks and skips, and the actual landing and build results.
   Give Marc your ten favourite edits as short before-and-after examples, with page names. If fewer
   than ten edits were made, show the available examples without inventing more. Say explicitly if
   the run was partial or if a build or landing did not succeed. The run log is the handoff; the
   landed trailer is the durable checkpoint for a completed pass.
