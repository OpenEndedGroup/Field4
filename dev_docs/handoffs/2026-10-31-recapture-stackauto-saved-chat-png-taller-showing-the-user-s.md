# Recapture stackauto saved-chat.png taller, showing the user's turns

- **Handoff:** `bd65d0f9-efab-41d9-b8da-11c2501d28a9` (project)
- **Filed from:** https://openendedgroup.com/field4/stackauto_conversation/; also affects funFeatures.md, tour_interface.md
- **Filed by:** agent:claude, 2026-10-31 14:11
- **Close with:** `python tools/docs_notes.py handoff-close bd65d0f9 "what happened"`

## The notes that led here

- `42c7cd3a` (public/docs/stackauto_conversation.md:452): this is the hero image --- it actually has some chat in it. It would be better if it was tall enough to see some of the users' orange writing thouhg
  > Launch the renderer, run the polytope, and the finished burst plays. The recorded turns are in the polytope box's AI4 chat. Opening the chat does not call a model.

## What was asked

The human's note on `public/docs/stackauto_conversation.md:452-455` calls `saved-chat.png` the
page's hero image, since it shows real chat. They want it tall enough to show some of the user's
turns, which the chat draws in orange, as well as the model's reply.

## Why this is more than a docs edit

The image comes from `tools/capture_stackauto_conversation.py`, and it has to be re-captured from
a live Field. The page's Markdown can't change what the image shows. Around lines 218-242 the
script sets the chat panel's `.disclosure-content` to 640×900 px. It then calls
`reply.scrollIntoView({block: 'end'})` on the last AI reply ("the live cycle now matches"). That
reply plus its sheet of nine frames fills the visible area, so none of the `.ai-chat-turn.you`
turns are in view.

The same image is also used on `funFeatures.md:297` and `tour_interface.md:429`.

## Proposed approach

- Make the panel taller (about 1400-1600 px). Then scroll so the last `.ai-chat-turn.you` sits
  near the top, with `scrollIntoView({block: 'start'})` on that turn rather than the reply's end.
  The frame then shows the user's last request (orange), the frames and the reply.
- Alternative: keep the height and collapse or shrink the frame sheet. This is worse, because the
  frames are part of what the chat shows.
- Re-run the capture script and `tools/package_stackauto_conversation.py`. Check at column width
  that the orange text is readable.

## Questions for the human

- Should the image show one user turn or several? (The taller it gets, the smaller it looks at
  column width.)

## What the page needs afterwards

Update the alt text at `stackauto_conversation.md:455` (and the copies on the other two pages) to
name the user request that is now visible.
