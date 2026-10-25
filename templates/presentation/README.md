# A Field presentation

In Field's Electron app, open Welcome and expand **Kits**. Press **Add presentation starter kit**,
then **Restart Field**. Once it reconnects, expand **Kits → Presentations** and press **Create document**
beside **Teaching with Field**. The new document starts its guide, live example and talk in order.

The Presentation box is the editable talk. Run it to open the audience and presenter windows.
Edit it and run again to keep presenting from the current step.

There is no per-talk Node project or dependency installation. Keep this folder in Git if you want history and collaboration.
Media belongs in `files/`, beside the shared presentation style.

Select the **presentation.css** box to edit the stylesheet in Field. **Refresh slide** applies its saved edits;
changing CSS does not require recompiling the talk. The image examples show fullscreen credits and a figure beside Markdown.

[Building this presentation](guides/HANDOFF.md) explains the slide API, live documents, macros and guides.
AI4 discovers it in the document's guide catalogue and can read it directly with `DocumentGuideReadRequest`.

The source package's `field-kit.json` declares this starting point as the Presentations package. Documents made through Kits
record their revision in `.field-kit-instance.json` and join the `kit-presentation` Project. Use the
Welcome card's **Your documents → Inspect update** for package updates. Close a talk before applying
an update; conflicts with local edits are refused. Preserve the instance record in Git and install
its recorded package revision when moving the talk to another machine.

The teaching-guide helper and shared style also form an inner role kit (`kit.json`, `roles/` and
`files/kit.json`). The authored Presentation box and live example are outside those roles.
`showKit()` inspects this helper/style kit, rather than the outer package's installation status.

Welcome's **Templates → presentation** remains available for a standalone copy without managed
package registration. Ordinary slides require no Motion Canvas project or per-talk Node setup.

## Organize a long talk

Wrap related steps in `section("Title") { ... }`; sections may nest. Playback stays in source order. The presenter outline has collapsible groups, slide numbers and **Find slide** for titles, section names or numbers. Enter opens the first search result; Escape clears the search. Current-slide ancestors open when you navigate, while other collapsed groups stay collapsed.
