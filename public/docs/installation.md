# Installation

Field is distributed as source. You install it by cloning it:

```diffstatic {data-lang=bash}
git clone https://github.com/OpenEndedGroup/Field4.git
```

There are no binaries and no installer — the checkout is the installation.

## Starting Field

Launch Field·4 from the repository:

- **Mac**: run `./field.sh` in Terminal, or double-click `field.command` in Finder.
- **Windows**: double-click `field.bat` in File Explorer, or run `.\field.ps1` in PowerShell.
- **Linux**: run `./field.sh` in a terminal.

The first Gradle build downloads dependencies and compiles the JVM and browser code, so it can take
substantially longer than later starts.

Eventually you'll be greeted with the boot-screen:

![A dark terminal filling the window with the word FIELD drawn very large in ASCII art, each letter built from slashes and dots. Beneath it three lines each tagged with a dim "welcome" marker read "welcome to Field, ß-023" and "http://localhost:9092/index.html?file=helloVideo". Below those, the server's own log: Ktor reporting "Application started in 0.206 seconds" and "Responding at http://0.0.0.0:9092", the filestore persisting its cache, "indexed 243 files, 3617 definitions", "found 54429 classes on classpath in 1.851s", and at the bottom a Gradle progress line reading "96% EXECUTING" against the still-running `:runJvm` task](assets/screenshot_22.png)

The launcher then opens Field's desktop app. Press `?` for help, or consult the
[survival guide](survivalGuide.md).

!!! note "Field in a browser"
    You can also open
    [`https://localhost:9092/index.html?file=firstDocument`](https://localhost:9092/index.html?file=firstDocument)
    in a browser. The development certificate is self-signed, so the browser may require an
    explicit local-certificate exception.

    That URL is a real web server, running inside Field and listening on your machine. If you share
    a network with anyone else, read [What Field Exposes, and What Stops It](server_surface.md) for
    what it exposes and what does, and does not, protect it.

## The welcome page

The first tab in the desktop app is the welcome page, also at `https://localhost:9092/welcome` in
a browser:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-welcome-frame" screenshot="assets/welcome/welcome-page.png" padding="0" wait="0" timeout="120" embed=true hidden=true name="welcome-page" alt="The welcome page in a fresh workspace. Across the top, Field·4 and buttons to open the welcome document, open the docs with notes, improve annotated docs, and shut Field down, then a filename box with Create. On the left, Running now, a search box, No documents found in this workspace yet, and the collapsed Starting points and Templates lists with their counts. On the right, the Changelog open on recent commits, and below it the collapsed Projects, Maven, Workspace, Runtime, Vulkan, Python, AI, Preferences and OSC panels with one-line summaries."}
// /welcome is its own page, so it is loaded into a frame on the capture page at a desktop
// window's size, against the runner's fresh checkout-local workspace: what a new user sees.
ui.eval("""
    document.getElementById("docs-welcome-frame")?.remove()
    const f = document.createElement("iframe")
    f.id = "docs-welcome-frame"
    f.src = "/welcome"
    f.setAttribute("style", "position:fixed;left:0;top:0;width:1100px;height:1000px;border:0;background:#111;z-index:2147483000")
    document.body.appendChild(f)
    return true
""".trimIndent())
// Wait for the parts that load after the page: the starting-points dock and every panel summary.
var state = ""
for (i in 0 until 60) {
    ui.pause(250)
    state = ui.eval("""
        const d = document.getElementById("docs-welcome-frame")?.contentDocument
        if (!d || !d.getElementById("starting-points-dock")) return "loading"
        const pending = [...d.querySelectorAll("details > summary")].filter(s => /loading|checking/i.test(s.textContent)).length
        return pending === 0 ? "ready" : "pending:" + pending
    """.trimIndent()).toString()
    if (state.contains("ready")) break
}
check(state.contains("ready")) { "the welcome page did not finish loading: " + state }
ui.pause(1200)
```

The left side is your documents. Type a name into the box at the top and press **Create** to make
a new one; the search box above the list searches the text of every document in the workspace as
well as their names. Below the list, **Starting points** and **Templates** are curated scenes and
scaffolds; opening one gives you an editable copy in your workspace, which is the quickest way to
see what Field does.

The right side is a stack of collapsible panels, one per part of Field:

- **Changelog**: the most recent commits in your checkout.
- **Projects**, **Maven**, **Workspace**: what your documents can see, from other projects to
  extra Java libraries.
- **Runtime** and **Vulkan**: the JVM Field is running on, and whether graphics windows will open
  on this machine. If Vulkan is missing, the panel gives install steps for your OS; the graphics
  system itself is described in [afield.four Graphics](afield_four_graphics.md).
- **Python**: which interpreter [Python boxes](python.md) will use.
- **AI**: model settings and the background tasks that improve Field's docs and tools, described
  in [Your History with Field](history_overview.md#the-welcome-dashboard); what the models do
  inside a document is in [AI features](aiFeatures.md).
- **Preferences** and the **OSC editor**.

## Troubleshooting

_Field·4_ is still beta software. Report reproducible problems through the public repository's
[GitHub issue tracker](https://github.com/OpenEndedGroup/Field4/issues).

On Mac you might find that Apple cannot abide the idea of you downloading some code from the internet, building it and running it — all outside their ecosystem. Most likely, this complaint is framed in terms of security and privacy. It's possible that you'll have to give `Terminal` permission to read files from your home directory. For reporting bugs with our installation process, beyond that I'll be very interested in the contents of your Security and Privacy settings page:

![macOS System Settings open on the Privacy & Security pane, that row highlighted in the sidebar among Desktop & Dock, Displays, Notifications, Sound, Lock Screen and the rest. The pane is scrolled to its lower half: Screen & System Audio Recording with 3 apps, Speech Recognition with 0, Sensitive Content Warning off, Analytics & Improvements, Apple Advertising, Apple Intelligence Report on. Then a Security section with "Allow applications from — App Store & Known Developers", "Allow accessories to connect — Ask for New Accessories", FileVault off and Lockdown Mode off, with an Advanced button bottom right](assets/screenshot_4.png)

## Updating

Update it the same way:

```diffstatic {data-lang=bash}
git pull
```

and then start it again, which rebuilds whatever changed.

The command palette (`Cmd-Shift-P`, or `Ctrl-Shift-P` on Windows and Linux; `F1` also works) includes **Check for updates**, which tells you whether that is worth
doing. It asks `origin` what it has on your branch with one `git ls-remote`, fetching nothing at
all if you are already level, and reports how many commits you are behind, along with any local
commits of your own that `origin` does not have.

It will not pull, merge, rebase or write anything into your working tree; only you know whether
that checkout has work in it. If the answer is "7 new commits on origin/main", the `git pull` is
yours to run when you are ready.

![Field's command palette open over the canvas: an empty search box above four commands — New Box, Delete Box, Insert document from workspace, and Check for updates, that last one highlighted in blue as the selected row](assets/screenshot_17.png)

The command says when it cannot answer. If you unpacked Field from an archive instead
of cloning it, if `HEAD` is detached, if there is no `origin`, or if the network is down, it says
which of those happened rather than reporting that you are up to date.

??? aside "For maintainers: making an update announce itself"
    Most commits should pass without comment — the count is enough. When a commit needs everyone
    who pulls it to *do* something, give it a `Field-Notice` trailer:

    ```diffstatic {data-lang=text}
    feat(repl): move to the 2.5.255 Kotlin fork

    ...body...

    Field-Notice: the Kotlin fork moved — run ./gradlew clean before your next build
    ```

    Anyone whose update check spans that commit sees the trailer's text verbatim, instead of the
    subject of the newest commit. Several notices in one range are all shown, deduplicated.
