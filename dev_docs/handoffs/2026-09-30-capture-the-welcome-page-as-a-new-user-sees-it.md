# Capture the /welcome page as a new user sees it

- **Handoff:** `1691c187-00aa-4199-8344-8ca9cc13655e` (project)
- **Filed from:** https://openendedgroup.com/field4/installation/; also affects funFeatures.md, history_overview.md
- **Filed by:** agent:claude, 2026-09-30 20:36
- **Close with:** `python tools/docs_notes.py handoff-close 1691c187 "what happened"`

## The notes that led here

- `695d7ffa` (public/docs/installation.md:33): we should show a nice screenshot of the /welcome page and a brief tour of the tabs in the welcome page, going over the starting points and linking to things like python, the graphics system, the ai improvement stuff. There's a missing section here, and we need a screenshot of the thing that the user really sees.
  > That URL is a real web server, running inside Field and listening on your machine. If you share a network with anyone else, read What Field Exposes, and What Stops It for what it exposes and what does, and does not, protect it.

## What was asked

Note 695d7ffa on `public/docs/installation.md` asks for "a nice screenshot of the /welcome page and a brief tour of the tabs … we need a screenshot of the thing that the user really sees."

The prose tour is done: a new `## The welcome page` section in `public/docs/installation.md` walks through the documents side, Starting points / Templates, and each right-hand panel, and links Python, graphics and the AI pages. The screenshot is still missing, and fixing that is more than a docs edit.

## Why this is bigger than a docs edit

The only welcome-page image in the repo is `public/docs/assets/welcome-dashboard.png`, committed in 36e61332d9 (2026-07-11). It is a hand-taken screenshot of a developer's own workspace. It shows 341 documents named things like `codex_macro_coalesce_smoke_20260703`, a changelog of internal commits, and no Starting points dock (the list fills the whole left column). It also predates the Vulkan panel (`src/commonMain/resources/welcome.html`, `<details id="vulkan-details">`). A new user sees something quite different: an almost empty list with the Starting points dock (`#starting-points-dock`) pinned below it.

The docs runner has no capture kind for a server page like `/welcome`. Its captures are box outputs, editors and windows. AGENTS.md §3 says a capture has to exercise the real code, so the image can't be staged by hand. It needs a real capture path.

## Proposed approach

- Add a docs-runner capture kind (or a `drive` script) that loads `/welcome` in the runner's Playwright against a fresh, checkout-local workspace that has the shipped starting points. Frame it so the Starting points dock and the collapsed panel stack both show.
- Alternative: an `OperationTranscript` / backdoor recipe that screenshots `/welcome` through `--with-playwright`, committed as a retained asset with the recipe that produced it.
- Either way, `funFeatures.md` and `history_overview.md#the-welcome-dashboard` use the same stale PNG and would benefit too.

## Questions for a human

1. Should the capture show a fresh workspace (what a new user sees) or a lived-in one?
2. Should the Changelog panel be open (it is `open` by default and is the busiest part of the frame) or collapsed?
3. Should the three pages share one asset, or should each page get its own framing (e.g. the AI panel open for `history_overview.md`)?

## What the page needs afterwards

Point the image in `installation.md` → `## The welcome page` at the new capture, and update its alt text so it mentions the Starting points dock.
