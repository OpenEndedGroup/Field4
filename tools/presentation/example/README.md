# Field / Present starter

This folder is both a Motion Canvas project and a Field workspace. `src/` contains the scenes, `presentation.json` maps their cues to native live views, `media/` holds shared assets, and `field-demo/` is a real Field document with a Markdown guide and saved macro.

Run `npm ci` here, open `field-demo` from this folder as Field's workspace, and choose **Presentation → Open Presentation…** in the Electron app. The separate controller moves between cues, toggles audience fullscreen, opens the Field guide, and runs the demo macro. **Attach source files to Field** links the TypeScript scenes and manifest to the current document. Editing those links writes the same Git-tracked files and refreshes the presentation. `.tsx` and `.ts` links use this project's TypeScript configuration for diagnostics and completion, and join Field's semantic search and AI4 context.

See the public [Presenting with Field](https://github.com/OpenEndedGroup/Field4/blob/main/public/docs/presentation_studio.md) guide for the full setup and cue format. Run `npm run check` to type-check the talk. The YouTube ID in the starter is a public embed test; replace it with an embeddable video of your choice.
