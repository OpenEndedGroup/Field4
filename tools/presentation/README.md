# Presentation runtime

The Electron **Presentation → Open Presentation…** menu opens a presentation folder containing `src/project.ts`, `src/scenes/`, `tsconfig.json`, `presentation.json`, and the two Field presentation pages. Field runs Motion Canvas on a local Vite server. `example/` is the starting point. Copy it into its own Git repository, run `npm install` in that folder, then edit its scenes and manifest. The talk owns its source, media, and dependencies.

Run the runtime independently with `node tools/presentation/server.mjs <project-folder>` from this repository; its URL opens Motion Canvas's preview. Native website embedding and audience windows require the Field Electron wrapper.
