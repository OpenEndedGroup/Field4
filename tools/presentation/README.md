# Field presentations

The default starting point is **Presentation → New Presentation…** in the Field Electron app, or the bundled **presentation** template. It creates an ordinary Field document with Kotlin guide steps and standard `kotlinx.html` audience slides. No per-talk package installation is required.

The [public presenting guide](../../public/docs/presentation_studio.md) covers slides, live documents, guide controls, macros, crossfades and the portable starter kit. The native starter is in [templates/presentation](../../templates/presentation).

## Optional Motion Canvas projects

`new-talk.mjs` creates the optional Motion Canvas starter. These projects need Node.js 22 or newer and their own dependencies:

```sh
node tools/presentation/new-talk.mjs ~/animated-talk
cd ~/animated-talk
npm ci
```

Open the folder with **Presentation → Open Motion Canvas Project…**. `server.mjs` runs its local Motion Canvas/Vite preview, and `typescript-service.mjs` provides project-aware TSX diagnostics and completion. These are not involved in the Kotlin presentation path.

## Verify

With Node.js 22 or newer, `npm ci --prefix tools/presentation` installs development test dependencies. `npm test --prefix tools/presentation` checks cue validation, the native starter, Kotlin wire defaults and the project TypeScript service. JVM `PresentationTest`, `SlideShowTest` and `KitsTest` cover guide lifecycle, HTML generation, validation and portable kit inspection.

`node tools/presentation/rehearse.mjs http://127.0.0.1:29482 --native` rehearses the Kotlin starter in the real Electron windows. Omit `--native` for the optional Motion Canvas starter. Use a disposable workspace because the rehearsal runs its saved macro.
