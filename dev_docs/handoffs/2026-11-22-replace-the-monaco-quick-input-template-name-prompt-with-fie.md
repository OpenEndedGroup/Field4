# Replace the Monaco quick-input template-name prompt with Field-styled UI

- **Handoff:** `b611f3e9-c55e-4c0f-a99e-98667057689a` (project)
- **Filed from:** https://openendedgroup.com/field4/templates/
- **Filed by:** agent:claude, 2026-11-22 10:14
- **Close with:** `python tools/docs_notes.py handoff-close b611f3e9 "what happened"`

## The notes that led here

- `5532db23` (public/docs/templates.md:14): this UI must be out of date, no? surely we have one of our black background things instead of this Monaco left-over?
  > Select the boxes you want to keep: one box, or a group with the connections between them. Open the command palette (f1) with the canvas focused, choose Save selection as template, type a name and press Enter.

## What was asked

On the Templates page, the human looked at the "Save selection as template" capture
(`public/docs/assets/templates/save-name.png`, a Monaco quick-input box) and asked: "this UI must be
out of date, no? surely we have one of our black background things instead of this Monaco
left-over?"

## What the source says

The page is accurate: the UI really is still the Monaco left-over.

- `src/jsMain/kotlin/afield/app/client/commands.kt` (`save-as-template` entry) calls `saveAsTemplate()`.
- That reaches `window._saveAsTemplateHelper` in `src/commonMain/resources/bootstrapMonaco.js`,
  which borrows the Monaco editor (`ensureEditor()`), grabs `IQuickInputService` and calls
  `quickInput.createInputBox()` with prompt "Save selection as template". If no editor exists, it
  logs "No editor available for quick input" and does nothing.
- A search of `src/jsMain/kotlin` turned up no Field-styled text prompt (FieldPanel-based or similar)
  that this could switch to.

So the fix is a code change, not a docs edit.

## Proposed approach

Replace the Monaco input box with a Field-styled name prompt built on `FieldPanel`
(`src/jsMain/kotlin/afield/fieldPanel.kt`), or on whatever dark palette the command palette itself
uses. Keep the existing validation (empty name, `/`, `\`, Windows-forbidden characters) and the
"Template saved: <name>" confirmation. Other commands use the same Monaco quick-input pattern
(for example, Edit hashtag), so one small shared Field text prompt could serve all of them.

Alternative: restyle Monaco's quick-input with CSS to match Field's panels. That is cheaper, but the
command still depends on a Monaco editor existing.

## Questions for the human

1. Which existing "black background" UI should this match: the command palette, a FieldPanel, or
   something else?
2. Should the other Monaco quick-input prompts move over in the same change?

## What the page needs afterwards

Update the capture's `clip-selector` in `public/docs/templates.md` (it currently targets
`.quick-input-widget`) to the new widget, recapture `templates-save-name`, and check the steps in
"Save boxes as a template".
