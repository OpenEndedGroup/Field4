# A Field presentation

Open this starting point from Field's Welcome screen, or choose **Presentation → New Presentation…**.
The Presentation box is the editable talk. Run it to open the audience and presenter windows.
Edit it and run again to keep presenting from the current step.

There is no per-talk Node project or dependency installation. Keep this folder in Git if you want history and collaboration.
Media belongs in `files/`, beside the shared presentation style.

Select the **presentation.css** box to edit the stylesheet in Field. **Refresh slide** applies its saved edits;
changing CSS does not require recompiling the talk. The image examples show fullscreen credits and a figure beside Markdown.

[Building this presentation](guides/HANDOFF.md) explains the slide API, live documents, macros and guides.
AI4 discovers it in the document's guide catalogue and can read it directly with `DocumentGuideReadRequest`.

The teaching-guide helper and the shared style are a small Field kit (`kit.json`, `roles/` and `files/kit.json`).
The authored Presentation box and live example are ordinary document content, outside the kit roles.
Run `showKit()` in a box to inspect the helper and style. Explicit kit migrations preserve your authored talk.

## Organize a long talk

Wrap related steps in `section("Title") { ... }`; sections may nest. Playback stays in source order. The presenter outline has collapsible groups, slide numbers and **Find slide** for titles, section names or numbers. Enter opens the first search result; Escape clears the search. Current-slide ancestors open when you navigate, while other collapsed groups stay collapsed.
