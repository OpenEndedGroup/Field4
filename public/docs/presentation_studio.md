# Presenting with Field

Field presentations are folders you can keep in Git. A talk combines editable Motion Canvas scenes with media files, live webpages, YouTube video, and Field documents. The audience gets a separate fullscreen-capable window; the presenter gets cue, guide, and macro controls. Editing a linked `.tsx` scene in Field updates the source file and refreshes the talk. Linked source uses the talk's TypeScript project for completion and diagnostics, and participates in Field's semantic search and AI4 context.

## Make a talk

First [install and run Field](installation.md), and have Node.js 22 or newer with npm available. From your Field checkout, create a copy of the starter:

```diffstatic {data-lang=sh}
node tools/presentation/new-talk.mjs ~/my-talk
cd ~/my-talk
npm ci
git init
git add .
git commit -m "Start my Field talk"
```

The talk folder itself is a Field workspace. Open its included live document, then open the same folder as a presentation:

```diffstatic {data-lang=sh}
cd /path/to/Field4
./field.sh --workspace ~/my-talk --document field-demo
```

In the Field Electron app, choose **Presentation → Open Presentation…** and select `~/my-talk`. The presenter controls open in front, with a **Show audience** button for the audience window. **Fullscreen audience** moves only the audience window fullscreen. **Attach source files to Field** adds live links to the TypeScript scenes and `presentation.json` in the current Field document, so edits in Field and edits from another IDE use the same files.

Run `npm run check` inside the talk to check its TypeScript. The starter's [README](https://github.com/OpenEndedGroup/Field4/tree/main/tools/presentation/example) and `presentation.json` are compact examples to adapt. Commit `src/`, `media/`, `presentation.json`, and any Field document folders; `node_modules/` stays ignored.

## Walk through the starter

Use **Next** in the controller, or the arrow keys, Page Up/Down, and Space in the audience window. The opening Motion Canvas scene animates in. The next cue shows a real website. The video cue opens a native YouTube player. The **field** cue loads `field-demo` from the running Field workspace into the audience window, where its boxes are live and editable. At that cue:

1. Select **Show Field guide**. The document's own Markdown guide opens beside its boxes.
2. Select **Start interactive guide** to step through the live document, selecting boxes and opening their code. Use **Guide next**, **Guide back**, or the links in its notes.
3. Select **Run reveal_wave**. A saved Field macro creates and runs an SVG wave box in the document.
4. Edit the wave box or the opening box in Field and run it again. The audience sees the real document change.
5. Close the interactive guide and move to the closing cue when you are ready.

The controller runs a cue macro only when you press its button. Both Markdown notes and interactive guides are opened explicitly too. `interactiveGuide: true` exposes the interactive guide controls; a box in the document must build its `slideShow { ... }` first (the starter does this with autorun). The Field-document cue checks that its document exists in the running Field workspace before opening it, so a typo in a cue cannot silently create a new document. The document and its `guides/` and `macros/` folders are ordinary files in the talk repository.

## Compose cues

`src/project.ts` orders Motion Canvas scenes. A scene's `beginSlide('name')` gives a cue such as `field:field`. `presentation.json` attaches live content to a cue:

```diffstatic {data-lang=json}
{
  "title": "My talk",
  "transitionMs": 450,
  "slides": {
    "field:field": {
      "type": "field-document",
      "document": "field-demo",
      "interactiveGuide": true,
      "rect": [0.04, 0.16, 0.92, 0.80],
      "macro": {"name": "reveal_wave", "scope": "document"}
    },
    "browser:web": {"type": "webpage", "url": "https://example.org/"},
    "video:video": {"type": "youtube", "videoId": "M7lc1UVf-VE"}
  }
}
```

`rect` is the portion of the audience window used by live content, as fractions `[x, y, width, height]`; omit it to fill the window. A cue without a `slides` entry remains pure Motion Canvas. `transitionMs` sets the cross-fade time between cues. The previous audience frame stays visible while the next live page loads, then fades over the new cue.

A webpage is a top-level, isolated Electron view. That lets pages with iframe restrictions load as presentation content, while preserving their own cookies and scripts. It does not remove a site's login, network, DRM, or playback rules. YouTube uses its embed player and still needs a video that permits embedding. If a site refuses to load, the controller reports the navigation error.

The Field-document cue uses the running Field server and workspace. Open the talk as that workspace before presenting its documents. Macros run in the displayed document using Field's regular macro runner, with its normal selection and scope rules. The audience window is a real Field client, so the guide, boxes, editor, and output panels are the same ones you use outside a talk.
