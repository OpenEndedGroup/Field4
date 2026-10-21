# Field / Present example

The `src` directory contains ordinary Motion Canvas TypeScript scenes. `presentation.json` assigns an optional live website or YouTube player to a named cue. Assets in `media/` can be referenced by relative URL; nothing is copied into a slide file.

Run `npm install` in this folder, then open it from Field4's **Presentation → Open Presentation…** menu. Field serves a Motion Canvas preview and opens a separate audience window. In the controller, **Attach source files to Field** links the scene and manifest files to the current Field document as live file-proxy boxes. Editing them in Field writes the ordinary source files and refreshes the presentation. Linked `.tsx` and `.ts` files use this project's TypeScript configuration and installed packages for diagnostics and completion; they join Field's semantic search corpus and can be used with AI4.

The example uses a public YouTube embed test ID. Replace it with an embeddable video of your choice.
