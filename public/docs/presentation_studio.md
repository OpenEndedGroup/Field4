# Presenting with Field

A Field presentation is a document you can teach from. Its Kotlin code combines HTML slides, real webpages, YouTube video and live Field documents. The audience gets a separate window; your Field document keeps the source and guide notes. Saved macros and interactive guides work inside the live document on the audience display.

## Start a presentation

With [Field running](installation.md) in its Electron app, choose **Presentation → New Presentation…**. Give the new document a name inside your current workspace. Field copies the starter and opens it. The starter runs its **Presentation** box to open the audience and presenter windows.

Use **Next**, **Previous** or a named step in the presenter controls. **Show audience** brings the audience window forward; **Fullscreen audience** makes that window fullscreen. In the audience, the arrow keys, Page Up/Down and Space navigate slides. F8 and F9 navigate backwards and forwards while a live webpage or Field document has focus, leaving their ordinary editing keys available.

Edit the **Presentation** box and run it again to update the talk. It keeps the current step where possible. **Refresh slide** redraws the current step; it does not rerun the box's source. Close the audience or controller to stop presenting.

There is no per-talk Node project or package installation. The presentation is an ordinary document folder with Kotlin sidecars, guides, macros and files. Keep that folder in Git if you want version history and collaboration. Put shared media in `files/` and refer to it by filename, for example `img { src = mediaName }`, rather than copying it into each slide.

The starter is also available as **presentation** under **Templates** on the Welcome screen. In a browser, the document and its guides remain usable; the separate audience display requires the Electron app.

## Write slides with Kotlin and HTML

Presentation steps use Field's existing guide system. A step's `audience` block uses standard [kotlinx.html](https://github.com/Kotlin/kotlinx.html) builders, so Kotlin functions, loops and completion work inside it. Text added with `+` is escaped by the library.

Run this in a box to open a two-step talk:

```diffstatic {data-lang=kotlin}
import afield.ui.slides.*
import kotlinx.html.*

presentation(title = "A lesson about motion") {
    step("Welcome") {
        audience {
            h1 { +"Working with motion" }
            p { +"Change the code while the audience watches." }
        }
        markdownNote("Begin with the question. [[next]]")
    }
    step("Try it") {
        audience {
            h1 { +"What changes?" }
            ul {
                for (word in listOf("Position", "Speed", "Direction")) {
                    li { +word }
                }
            }
        }
        markdownNote("Ask for a prediction. [[back]] [[finish]]")
    }
}.present()
```

The audience has a black background, Iosevka type and a 1920 × 1080 layout scaled to fit its window. Standard HTML tags and CSS control the layout. The starter's `files/presentation.css` supplies shared inline styles; its talk reads that file and applies it to the slide container. The `columns` and `muted` classes provide a simple starting layout. Audience fragments support HTML and CSS; use a webpage step for a full page with scripts. Use `audienceMarkdown("# Heading\nSome text")` for a Markdown slide, or `audienceHtml(fragment)` for existing HTML.

`markdownNote`, `selectBox`, `selectBoxByName`, annotations, `onEnter`, `onExit` and link callbacks are the same guide operations used elsewhere in Field. Notes stay in the hosting document. A step without an audience surface shows its note HTML to the audience too. To present an existing guide this way, run it first, then choose **Presentation → Present Current Guide**.

Steps cross-fade by default. Set `transitionMs` on `presentation` to a duration from 0 to 2000 milliseconds. Going backwards uses the same transition. If a web surface cannot provide a snapshot, navigation proceeds with a direct switch. Navigation runs the destination step's callbacks, so make an `onEnter` action safe to revisit if it changes a document or creates output.

## Bring the working material on screen

A step can show a webpage, YouTube player or existing Field document instead of an HTML slide:

```diffstatic {data-lang=kotlin}
presentation(title = "Working examples", transitionMs = 450) {
    step("Website") {
        audienceWebpage("https://example.org/")
        markdownNote("Interact with the page directly. [[next]]")
    }
    step("Video") {
        audienceYouTube("M7lc1UVf-VE")
        markdownNote("Replace this with a video for the lesson. [[back]] [[next]]")
    }
    step("Live document") {
        audienceDocument("my-example", macro = "reset-example")
        markdownNote("Run the saved macro when ready. [[back]] [[finish]]")
    }
}.present()
```

Replace `my-example` with a document that exists in the running workspace, and `reset-example` with a saved macro in that document. Field checks that the document exists before opening it. A typo cannot silently create a new document.

At a Field-document step, **Document notes** opens its Markdown guide. **Start document guide** starts its interactive guide, with separate **Guide next** and **Guide back** controls. A box in that document must have built a `slideShow { ... }` first; the starter's **Teaching guide** box does this. The outer presentation and inner document guide have separate navigation. Pass `interactiveGuide = false` to `audienceDocument` if it has no interactive guide.

**Run reset-example** runs the named saved macro only when pressed. It uses Field's normal macro runner in the displayed document. The audience can edit and run its real boxes, use the editor and interact with output panels. The starter's **Run reveal_wave** demonstrates a macro that creates and runs a wave box.

Each live surface fills the audience window by default. Pass `rect = listOf(0.04, 0.16, 0.92, 0.80)` to place it within a fraction of the window: `[x, y, width, height]`.

Webpages use top-level, isolated Electron views, allowing sites with iframe restrictions to load normally. A site's login, network, DRM and playback rules still apply. YouTube uses its embed player and needs a video that permits embedding. Navigation failures appear in the presenter controls.

## Reuse the starter as a kit

The starter includes a small [Field kit](kits.md): the teaching-guide helper and the shared presentation style. Its `kit.json`, `roles/` and `files/kit.json` record the shared parts and their revision. The authored **Presentation** box and **Live Field** example are ordinary document content outside those roles.

That separation lets a kit update improve a teaching helper without replacing the words of a lecture. Run `showKit()` in a box to inspect the shared parts. Kit migrations are explicit and refuse to overwrite locally edited shared parts. The kit travels inside the document, so moving the talk to another machine does not depend on a path in the original author's home directory.

## Optional Motion Canvas scenes

[Motion Canvas](https://motioncanvas.io/) remains available for talks that need its animation system. Its TypeScript/TSX project is an optional scene source. It is not required by the Kotlin starter.

For a Motion Canvas talk, install Node.js 22 or newer with npm, then create and install the separate example from your Field checkout:

```diffstatic {data-lang=bash}
node tools/presentation/new-talk.mjs ~/animated-talk
cd ~/animated-talk
npm ci
```

Open Field with that folder as its workspace, then choose **Presentation → Open Motion Canvas Project…** and select it. **Attach source files to Field** adds editable links to its scenes and `presentation.json`. Linked TSX uses the project's TypeScript configuration and installed libraries for completion and diagnostics. Running `npm run check` inside the talk checks the scenes.

`src/project.ts` orders Motion Canvas scenes, and `beginSlide('name')` supplies cue names. `presentation.json` attaches live content to those cues. See the [Motion Canvas starter](https://github.com/OpenEndedGroup/Field4/tree/main/tools/presentation/example) for that project format. Its webpages, YouTube and live Field documents use the same Electron host as Kotlin presentations.
