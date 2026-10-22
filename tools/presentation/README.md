# Field presentation studio

The public [Presenting with Field](../../public/docs/presentation_studio.md) guide is the starting point. Create a repository-sized talk with:

```sh
node tools/presentation/new-talk.mjs ~/my-talk
cd ~/my-talk
npm ci
git init
git add .
git commit -m "Start my Field talk"
```

Launch Field with `~/my-talk` as its workspace and `field-demo` as the first document, then choose **Presentation → Open Presentation…** in the Field Electron app. The talk contains editable Motion Canvas scenes, a live Field document, its guide and macro, a webpage cue, and a YouTube cue. Its `presentation.json` maps named Motion Canvas slide cues to live content and sets the cross-fade duration.

The runtime can also be started with `node tools/presentation/server.mjs <project-folder>` from this checkout. Its URL provides a Motion Canvas preview. Native website and Field-document views, transitions, and audience controls require the Electron app.

The example uses a public YouTube embed test ID; replace it with a video that permits embedding. The project package is installed separately from Field so each talk can pin its own Motion Canvas and TypeScript dependencies.

## Verify the studio

Use Node.js 22 or newer. `npm ci --prefix tools/presentation` installs the development test dependencies; `npm test --prefix tools/presentation` checks the project language service, cue boundaries, macro scopes, and starter creation. `npm run check` inside a created talk checks its TypeScript scenes.

For the live rehearsal, launch the Electron wrapper with `--remote-debugging-port=29482`, open a disposable starter workspace and its presentation, then run:

```sh
node tools/presentation/rehearse.mjs http://127.0.0.1:29482
```

The rehearsal drives the real presenter controls. It checks same-cue navigation, decoded transition frames, the native webpage, the Field document, interactive guide selection and navigation, visible macro output, and Markdown notes. It changes the disposable Field document by running the demo macro.
