# Presenting with Field

A Field presentation is a document you can teach from. Its Kotlin code combines HTML slides, real webpages, YouTube video and live Field documents. The audience gets a separate window; your Field document keeps the source and guide notes. Saved macros and interactive guides work inside the live document on the audience display.

## Start a presentation

With [Field running](installation.md) in its Electron app, open Welcome:

1. Expand **Kits** beneath Starting points and Templates, then press **Add presentation starter kit**.
2. Press **Restart Field**. Field prepares the registered kit before restarting; the same Electron window reconnects.
3. Expand **Kits → Presentations** and press **Create document** beside **Teaching with Field**.

Field copies the starting point into your workspace and records its kit revision. Its **Teaching guide**, **Live Field** and **Presentation** boxes start in order, opening the audience and presenter windows. Each new talk is an editable document; the installed starting point stays unchanged. See [Kits and Starting Points](kits.md#add-a-self-contained-kit-in-welcome) for adding a kit from another folder.

Use **Next**, **Previous** or a named step in the presenter controls. **Show audience** brings the audience window forward; **Fullscreen audience** makes that window fullscreen. In the audience, the arrow keys, Page Up/Down and Space navigate slides. F8 and F9 navigate backwards and forwards while a live webpage or Field document has focus, leaving their ordinary editing keys available.

Edit the **Presentation** box and run it again to update the talk. It keeps the current step where possible. **Refresh slide** redraws the current step; it does not rerun the box's source. Close the audience or controller to stop presenting.

There is no per-talk Node project or package installation. The presentation is an ordinary document folder with Kotlin sidecars, guides, macros and files. Keep that folder in Git if you want version history and collaboration. Put shared media in `files/` and refer to it by filename, for example `img { src = mediaName }`, rather than copying it into each slide.

For a standalone copy, the starter remains available as **presentation** under **Templates** on Welcome. That route does not register the package or create a managed package instance. In a browser, the document and its guides remain usable; the separate audience display requires the Electron app.

## Organize a long talk

The presenter has a scrollable outline, with collapsible sections and numbered slides. Wrap related steps in `section`; sections can nest. Next and Previous still follow source order across section boundaries. Flat talks need no changes.

```diffstatic {data-lang=kotlin}
presentation(title = "A lesson about motion") {
    section("Opening") {
        step("The question") { audienceMarkdown("# What makes things move?") }
    }
    section("Experiments") {
        section("Waves") {
            step("Predict") { audienceMarkdown("# What happens at twice the frequency?") }
            step("Try it") { audienceDocument("wave-study") }
        }
        step("Discuss") { audienceMarkdown("# What changed?") }
    }
}.present()
```

### Keyboard search

**Find slide** searches as you type across slide titles, authored HTML/Markdown text, reveal text,
image descriptions, section names and slide numbers. Multiple words may occur in different parts
of the slide; matching ignores case and accents. Body matches show a short excerpt.

In the presenter controls, press **/** or **⌘/Ctrl+F** to focus search. **↑/↓** chooses a match;
**Enter** visits that slide. The query stays ready for another hop. **Esc** clears the query and
returns focus to the current slide in the outline, where the usual presentation navigation keys work.
Typing and choosing a match leave the audience on the current slide until Enter.

Search includes authored reveals, and visits their containing slide at its beginning. It does not
open live webpages or documents to index their dynamic contents. The index is cached with the talk;
**Refresh slide** updates it after body edits, and rerunning **Presentation** updates a changed outline.
Jumping to a slide reveals its section. Other section disclosure choices are preserved.

![Incremental body-text search in the real presenter: propagation finds text in an authored reveal, with a keyboard-selected result and excerpt.](assets/presentation_studio/search.png)

![Presenter controls with a searchable, numbered outline grouped into Opening, Pictures and ideas, and Working material.](assets/presentation_studio/outline.png)

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
                for (word in ["Position", "Speed", "Direction"]) {
                    li { +word }
                }
            }
        }
        markdownNote("Ask for a prediction. [[back]] [[finish]]")
    }
}.present()
```

The audience has a black background, Iosevka type and a 1920 × 1080 layout scaled to fit its window. Standard HTML tags and CSS control the layout. The `columns` and `muted` classes provide a simple starting layout. Audience fragments support HTML and CSS; use a webpage step for a full page with scripts. Use `audienceMarkdown("# Heading\nSome text")` for a Markdown slide, or `audienceHtml(fragment)` for existing HTML.

![The starter audience slide beside the sine-wave illustration, with a heading and numbered Markdown list.](assets/presentation_studio/image-beside.png)

### Mix Markdown, images and HTML

Inside `audience`, `.md` turns a string into a rendered Markdown fragment. Unary `+` inserts that fragment; ordinary strings still become escaped HTML text. Indented triple-quoted Markdown is dedented, and headings, emphasis, lists, tables and code fences use Field's Markdown renderer.

```diffstatic {data-lang=kotlin}
audience {
    stylesheet("presentation.css")
    +"""
        # A question about motion
        Something _worth trying_ in the live document.

        1. Predict what changes.
        2. Run the experiment.
    """.md
}
```

`fullScreenImage` fills the audience display with a picture and its credit. It contains the entire image by default; `AudienceImageFit.Cover` crops it to fill the available space. `imageBeside` puts the picture and its credit beside HTML or Markdown content. `imageOnLeft = false` moves it to the right.

```diffstatic {data-lang=kotlin}
audience {
    stylesheet("presentation.css")
    fullScreenImage("wave.svg", alt = "A sine wave", citation = "Author · source · license")
}

// Use a separate step for the second layout.
audience {
    stylesheet("presentation.css")
    imageBeside("wave.svg", alt = "A sine wave", citation = "Author · source · license") {
        +"""
            # A small change.
            Make a prediction, then try it in Field.
        """.md
    }
}
```

Keep image files in the document's `files/` directory and reference them by filename. The starter includes an editable SVG example. Credits are separate text, so they stay readable and editable.

![The fullscreen image layout: a white sine-wave drawing on black, with its editable credit beneath it.](assets/presentation_studio/image-fullscreen.png)

### Edit the stylesheet in Field

Select the starter's **presentation.css** box to edit its CSS with Field's normal editor. It is a file box linked to `files/presentation.css`; edits write to that actual file. `stylesheet("presentation.css")` loads it into the slide. Press **Refresh slide** to fetch the saved CSS again without recompiling the talk. Re-running the **Presentation** box also reloads it.

You can drag the file from the document's `files/` directory onto the canvas and choose a document-relative link to create the same kind of editor box. A stylesheet copied into the document root is referenced as `stylesheet("../presentation.css")`, since slide media paths start in `files/`. For a few declarations on one HTML container, the usual `style = "color:white"` property remains available.

![The presentation.css file open in Field’s CSS editor alongside the presentation document.](assets/presentation_studio/stylesheet.png)

The image layouts use `field-image-full`, `field-image-beside` and `field-image-text` classes. Override those selectors in the stylesheet to adjust spacing, image size, type or credit placement.

`markdownNote`, `selectBox`, `selectBoxByName`, annotations, `onEnter`, `onExit` and link callbacks are the same guide operations used elsewhere in Field. Notes stay in the hosting document. A step without an audience surface shows its note HTML to the audience too. To present an existing guide this way, run it first, then choose **Presentation → Present Current Guide**.

Steps cross-fade by default. Set `transitionMs` on `presentation` to a duration from 0 to 2000 milliseconds. Going backwards uses the same transition. If a web surface cannot provide a snapshot, navigation proceeds with a direct switch. Navigation runs the destination step's callbacks, so make an `onEnter` action safe to revisit if it changes a document or creates output.

## Themes and simple builds

The starter includes **Black**, **Paper**, **Slate** and **Lecture** in `files/themes/`. Each is one CSS file,
with Iosevka type, solid backgrounds and matching image credits. Black is the default.
Copy the contents of `themes/paper.css` or `themes/slate.css` into the editable **presentation.css**
box, save, then **Refresh slide**. That changes every starter slide which uses that stylesheet.
To theme one HTML slide, use `stylesheet("themes/paper.css")`. For a Markdown slide file,
set its top-level `stylesheet` to `themes/paper.css`. No installation or asset build is needed.

The three colour variables are `--field-slide-bg`, `--field-slide-fg` and `--field-slide-muted`.
Image layouts and credits use them too. Images keep their original colours.

| Black | Paper | Slate |
| --- | --- | --- |
| ![Black theme in the native audience window.](assets/presentation_studio/theme-black.png) | ![Paper theme in the native audience window.](assets/presentation_studio/theme-paper.png) | ![Slate theme in the native audience window.](assets/presentation_studio/theme-slate.png) |

### Lecture: images, banners and airy lists

**Lecture** follows a quiet teaching-slide layout: an ink-blue background, warm Iosevka lettering,
a fine inset frame and small, right-aligned image credits. It is one [editable CSS file](assets/presentation_studio/lecture.css).
Use `stylesheet("themes/lecture.css")` on a slide, or copy it into **presentation.css** for a whole talk.
The standard `fullScreenImage` and `imageBeside` helpers pick up the image layouts automatically.
Use `lecture-banner` with a `lecture-tagline` for a big title and its right-aligned aside, or
`lecture-list` for a heading and generously spaced, unbulleted list.

| Large image with a credit | Image and text |
| --- | --- |
| ![A large editable line-study figure with a quiet credit in the Lecture theme.](assets/presentation_studio/lecture-image.png) | ![A square line study beside explanatory text, with a separate attribution.](assets/presentation_studio/lecture-split.png) |
| Banner and tagline | Airy list |
| ![A large italic Mapping banner with New physics aligned to its right edge.](assets/presentation_studio/lecture-banner.png) | ![A heading and seven light italic list entries in the Lecture theme.](assets/presentation_studio/lecture-list.png) |

On forward entry the theme gives the picture, words and credit a small stagger, with a four-pixel
settle on text. Pictures stay almost fully visible during the crossfade. Back and Refresh show the
finished layout immediately; retained content stays still, and reduced motion disables the animation.
No `fadeIn` wrappers or slide scripts are needed. Edit `--lecture-beat` (45 ms between parts),
`--lecture-settle` (360 ms) or `--lecture-banner-size` in the CSS to adjust it.

The starter includes this complete example as `files/examples/lecture.kt`. Paste it into a new box
and run it. The pictures are Field's editable SVG studies; replace their filenames and credits with
your own. Each image is referenced from `files/`, so it can be shared by many slides.

```diffstatic {data-lang=kotlin}
// Paste into a new Field box and run. Uses shared files beside this example's parent folder.
import afield.ui.slides.*
import kotlinx.html.*

presentation(title = "Lecture layouts", transitionMs = 450) {
    step("A large image") {
        audience {
            stylesheet("themes/lecture.css")
            fullScreenImage("examples/lecture-comparison.svg",
                alt = "Two editable line studies: a regular field and a field with interruptions",
                citation = "Field · editable SVG line studies · examples/lecture-comparison.svg")
        }
    }
    step("A figure and some text") {
        audience {
            stylesheet("themes/lecture.css")
            imageBeside("examples/lecture-lines.svg",
                alt = "A square field of short diagonal lines, with a few intentional gaps",
                citation = "Field · editable line study · examples/lecture-lines.svg") {
                h2 { +"A rule with room for interruption" }
                p { +"A small omission changes the rhythm of the whole field." }
                p(classes = "muted") { +"Make a prediction. Change the rule. Look again." }
            }
        }
    }
    step("Mapping") {
        audience {
            stylesheet("themes/lecture.css")
            div(classes = "lecture-banner") {
                h1 { +"Mapping" }
                p(classes = "lecture-tagline") { +"New physics" }
            }
        }
    }
    step("Media art and design") {
        audience {
            stylesheet("themes/lecture.css")
            div(classes = "lecture-list") {
                h1 { +"Media art and design = everything else" }
                ul {
                    for (topic in ["Net art", "Installation art", "VFX", "Experimental film",
                        "Computer games", "Odd music", "Emerging and dying technologies"]) {
                        li { +topic }
                    }
                }
            }
        }
    }
}.present()
```

### Explicit fades and layered builds

`fadeIn` fades a text block, image or group from transparent to opaque. It starts after the
whole-slide crossfade finishes. Back and Refresh show the completed build immediately;
the operating system's reduced-motion setting disables the element animation.

```diffstatic {data-lang=kotlin}
step("A figure") {
    audience {
        stylesheet("presentation.css")
        fadeIn(durationMs = 600) {
            fullScreenImage("wave.svg", alt = "A sine wave", citation = "Our study")
        }
    }
    reveal("Add an explanation", dimPrevious = 0.25) {
        fadeIn {
            h1 { +"One curve. Many questions." }
        }
    }
}
```

`reveal` adds a named cue. It retains the preceding HTML view at the chosen opacity and lays
new content on top. Set `dimPrevious = 1.0` to keep its original brightness.
Successive reveals retain the whole previous composition, so earlier layers dim again.
Retained content stays visible but cannot receive clicks or keyboard focus. Its fades are already
complete; only the new layer animates. This works with HTML, Markdown and shared images.
Use ordinary cues for native webpages, YouTube or live Field documents.

![An actual image retained at quarter opacity behind the next idea in the native audience window.](assets/presentation_studio/layered-build.png)

For the same effect across two separate slides, keep a reusable fragment:

```diffstatic {data-lang=kotlin}
val figure = audienceFragment {
    stylesheet("presentation.css")
    fullScreenImage("wave.svg", alt = "A sine wave")
}
presentation {
    step("Look") { audience(figure) }
    step("Discuss") {
        audienceOver(figure, dimPrevious = 0.2) {
            fadeIn { h1 { +"What would you change?" } }
        }
    }
}.present()
```

The image remains one shared file. `durationMs` accepts 0–10000 and the optional `delayMs`
accepts 0–60000. Layout is ordinary HTML/CSS; the fade does not move or reflow the content.
In an AI4-authored slide's JSON cue, use `retainPrevious: true`, `previousOpacity: 0.25`
and `fadeMs: 350`. Leaving these fields out keeps the original replacement behaviour.

## Let AI4 help write a slide

The starter also loads `files/talk.slides.json` with `slides("talk.slides.json")` inside its Kotlin
presentation. That file holds ordinary Markdown slides, presenter notes, section paths and reveals.
Each slide has a stable ID. Kotlin still describes the live experiments and their actions.

Open AI4 in the presentation document and try:

> Add a short slide about frequency after `ask-a-question`. Give it a prediction and a reveal.
> Keep the existing style and slides. Save the slide, but don't present it yet.

AI4 can read `guides/HANDOFF.md`, inspect the file with **PresentationReadRequest**, then use
**PresentationWriteSlideRequest** to insert or revise one slide. Writes require the revision from
the latest read; a concurrent edit refuses the write rather than overwriting it. These tools work
through the same registry for chat and automation. Reading an outline does not load every slide body;
AI4 can request one slide by ID and page a long outline with `offset` and `limit`.

**Refresh slide** reloads an existing slide's body from disk. Re-run **Presentation** after adding
slides, changing titles or sections, or changing the number/order of reveals. Explicit slide IDs keep
the current slide selected when earlier slides are inserted. Saving a slide never runs Kotlin,
executes a macro or opens the audience window.

The file can also be edited as JSON in Field. To start a content-only talk around it:

```diffstatic {data-lang=kotlin}
import afield.ui.slides.*

presentation(title = "Our lesson") {
    slides("talk.slides.json")
}.present()
```

## Reveal, experiment and reset

A slide can contain named **cues**. Next advances through its cues before moving to the next slide;
Previous walks backwards through the views. The presenter status shows the current cue. A cue can
replace the audience content, select code, and declare an `onEnter` action:

```diffstatic {data-lang=kotlin}
presentation(title = "A prediction") {
    step("What changes?", id = "prediction") {
        audienceMarkdown("# Twice the frequency?\nMake a prediction.")
        cue("Explain") {
            audienceMarkdown("# Half the wavelength.\nAssume the propagation speed stays constant.")
        }
        cue("Try it") {
            audienceDocument("wave-study", interactiveGuide = false)
        }
    }
}.present()
```

A cue action runs once on forward entry in this built talk. Back, Refresh and revisiting that cue do
not replay it. **Run cue again** explicitly repeats the current action. Rebuilding or stopping a talk
starts a new run. Ordinary step `onEnter`/`onExit` callbacks retain their existing behavior.

![The wave lesson’s explanation reveal, rendered in the real audience window.](assets/presentation_studio/wave-reveal.png)

For an experiment, capture the source you want to return to, then attach an explicit reset:

```diffstatic {data-lang=kotlin}
// experiment is a Box in this working document.
val baseline = LessonCheckpoint.capture(experiment)
presentation {
    step("Experiment", id = "experiment") {
        audienceMarkdown("# Change one thing, then observe.")
        checkpoint("Original source") {
            baseline.restore()
            // Run the experiment's setup here if it needs to recreate its outputs.
        }
    }
}.present()
```

`LessonCheckpoint` captures only the named boxes' local `code` text by default; pass
`properties = listOf("code", "fragment")` for additional local text properties. Restore stops those
boxes and restores their captured text. It does not undo arbitrary runtime objects, output, files,
other boxes or network effects. Baselines are held in memory for this built talk. Capture before
experimenting; rebuilding from edited source establishes a new baseline.

**Reset: …** runs the declared reset and returns that slide to its initial cue. The starter's
**Predict a wave** lesson restores **Wave experiment** and runs its saved `run_wave` setup macro.
Change `frequency` in that box, run it, then reset and try again. Starting from the kit creates an
editable document copy, preserving the installed teaching material.

![Presenter controls at the live wave cue, with explicit reset and replay actions above the slide outline.](assets/presentation_studio/wave-controls.png)

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

The [Presentations package](kits.md#add-a-self-contained-kit-in-welcome) is declared by `field-kit.json`. It supplies the starting point, its guide and the order of the boxes to run. Documents created through Kits belong to the **kit-presentation** Project and record their package and runtime revisions in `.field-kit-instance.json`.

To update a managed talk, close it, use **Check source for updates** on Welcome's Presentations card, then restart Field. Under **Your documents**, choose **Inspect update** before **Apply update to closed document** and **Run**. Package updates compare the original starting files, your current files and the new starting point. They preserve unrelated edits and refuse conflicts, including changes to the same talk source or `metadata.edn`. Updates are explicit; installing a new package never silently rewrites a lecture.

Inside the document, the smaller `kit.json` role kit owns the teaching-guide helper and shared stylesheet. `roles/` holds their source, and `files/kit.json` records the document's role revision. The authored **Presentation** box and **Live Field** example are outside those roles. `showKit()` inspects this inner helper/style kit; it does not report the outer package's installation or update status.

Keep the document folder, including `.field-kit-instance.json` when present, in Git. On another machine, add the package revision that instance records and restart Field before using **Run**. Keep package source versions in Git too, so that revision can be installed again. The [kit guide](kits.md#starting-point-revisions-and-instance-updates) explains package snapshots, shared Project resources and recovery.

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
