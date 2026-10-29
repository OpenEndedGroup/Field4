# Presenting from this document

For authoring and AI4 assistance, read [Building this presentation](HANDOFF.md).

The **Presentation** box contains your talk. Edit its Kotlin and run the box to present it.
Running it again keeps your place in the talk.

Select **presentation.css** to edit the shared stylesheet. Press **Refresh slide** in the controller to apply saved CSS changes.

Use **Next**, **Previous** and the named steps in the controller. The audience has a separate window.
A Field-document step shows this real document. **Run reveal_wave** runs the saved macro;
**Start document guide** opens the interactive guide built by the **Teaching guide** box.

The Presentation box is your lesson. The teaching helper and `files/presentation.css` come from the kit.
They can be inspected and upgraded separately from your talk.

## Organize a long talk

Wrap related steps in `section("Title") { ... }`; sections may nest. Playback stays in source order. The presenter outline has collapsible groups, slide numbers and **Find slide** for titles, section names or numbers. Enter opens the first search result; Escape clears the search. Current-slide ancestors open when you navigate, while other collapsed groups stay collapsed.
