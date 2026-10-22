# A Field presentation

Open this starting point from Field's Welcome screen, or choose **Presentation → New Presentation…**.
The Presentation box is the editable talk. Run it to open the audience and presenter windows.
Edit it and run again to keep presenting from the current step.

There is no per-talk Node project or dependency installation. Keep this folder in Git if you want history and collaboration.
Media belongs in `files/`, beside the shared presentation style.

[Building this presentation](guides/HANDOFF.md) explains the slide API, live documents, macros and guides.
AI4 discovers it in the document's guide catalogue and can read it directly with `DocumentGuideReadRequest`.

The teaching-guide helper and the shared style are a small Field kit (`kit.json`, `roles/` and `files/kit.json`).
The authored Presentation box and live example are ordinary document content, outside the kit roles.
Run `showKit()` in a box to inspect the helper and style. Explicit kit migrations preserve your authored talk.
