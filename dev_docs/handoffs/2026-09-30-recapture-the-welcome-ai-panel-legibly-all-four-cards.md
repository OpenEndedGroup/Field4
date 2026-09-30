# Recapture the welcome AI panel legibly, all four cards

- **Handoff:** `8e78f9d9-0f8e-4613-bd5d-ca9bfb458873` (project)
- **Filed from:** https://openendedgroup.com/field4/history_overview/
- **Filed by:** agent:claude, 2026-09-30 20:44
- **Close with:** `python tools/docs_notes.py handoff-close 8e78f9d9 "what happened"`

## The notes that led here

- `3ce7033f` (public/docs/history_overview.md:39): the image above is too small to read
  > Each

## What was asked

Note `3ce7033f` on `public/docs/history_overview.md` (`## The /welcome dashboard`) says the AI-panel
image is "too small to read".

## Why this is bigger than a docs edit

`public/docs/assets/welcome-ai-panel.png` is a hand-taken, committed screenshot, not a runner
capture. It is 370 px wide at 1× device pixel ratio, so the text is about 11 px tall, and making it
display larger would only blur it. It has other problems as well:

- It is cut off at the bottom. The **Semantic search** card, the fourth of the four cards the page
  lists, is not in the frame. (I changed the alt text to name the three cards it does show.)
- It shows one developer's provider state (`openai configured`, particular model names, "15598
  snippets / 7117 lifecycles"), and that state will go stale.
- The docs runner has no capture kind for a server page such as `/welcome`. AGENTS.md §3 requires
  a capture to exercise the real code, so the fix can't be a hand-made crop. The existing handoff
  `1691c187` ("Capture the /welcome page as a new user sees it") proposes the same capture path
  for the full dashboard, and its question 3 already asks whether `history_overview.md` should get
  its own framing with the AI panel open.

## Proposed approach

Once `1691c187` adds a `/welcome` capture path (a Playwright page capture, or a `drive` script), use
it to capture the AI panel on its own: `<details>` open, at devicePixelRatio 2, clipped to the panel
from the Provider row down through the Semantic search card. At the text column's width it should
render near 1:1, so it can be read.

Alternatives:
- Capture only the four task cards and leave out the provider/key rows. The page is about the
  tasks, and a smaller frame displays larger.
- Drop the image and rely on the full-dashboard capture plus the bullet list.

## Questions for a human

1. Should the provider/key rows be in the frame, or only the task cards?
2. Which provider state should the capture show: unconfigured (what a new user sees, and possibly
   empty badges) or configured?
3. Should this be folded into `1691c187` or kept as a separate capture?

## What the page needs afterwards

Point the image at the new capture and update the alt text to name all four cards.
