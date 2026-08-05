# Installation

Field is distributed as source, and the way you get it is the way you keep it up to date:

```diffstatic {data-lang=bash}
git clone https://github.com/OpenEndedGroup/Field4.git
```

There are no binaries and no installer. A checkout is the installation.

### Quick Start (Double-Click)

You can now launch Field·4 by simply double-clicking the appropriate file in the repository:

- **Mac**: double-click `field.command` in Finder. It runs the Gradle `runDesktop` task.
- **Windows**: double-click `field.bat` in File Explorer. It delegates to `field.ps1` when present,
  otherwise it runs `gradlew runDesktop`.
- **Linux**: double-click `field.sh` in a file manager. The script tries several common terminal
  emulators and falls back to running in its current process.

The first Gradle build downloads dependencies and compiles the JVM and browser code, so it can take
substantially longer than later starts.

Eventually you'll be greeted with the boot-screen:

![working](assets/screenshot_22.png)

The desktop launcher opens Field's Electron shell. You can also open
[`https://localhost:9092/index.html?file=firstDocument`](https://localhost:9092/index.html?file=firstDocument)
in a browser. The development certificate is self-signed, so the browser may require an explicit
local-certificate exception. Press `?` for help, or consult the [survival guide](survivalGuide.md).

### Troubleshooting

_Field·4_ is still beta software. Report reproducible problems through the public repository's
[GitHub issue tracker](https://github.com/OpenEndedGroup/Field4/issues).

On Mac you might find that Apple cannot abide the idea of you downloading some code from the internet, building it and running it — all outside their ecosystem. Most likely, this complaint is framed in terms of security and privacy. It's possible that you'll have to give `Terminal` permission to read files from your home directory. For reporting bugs with our installation process, beyond that I'll be very interested in the contents of your Security and Privacy settings page:

![](assets/screenshot_4.png)

### Updating

You update Field the way you update any checkout:

```diffstatic {data-lang=bash}
git pull
```

and then start it again, which rebuilds whatever changed.

The `F1` command palette includes **Check for updates**, which tells you whether that is worth
doing. It asks `origin` what it has on your branch — one `git ls-remote`, and nothing at all is
fetched if you are already level — and reports how many commits you are behind, along with any
local commits of your own that `origin` does not have.

It stops there. It will not pull, merge, rebase or write anything into your working tree, because
only you know whether that checkout has work in it. If the answer is "7 new commits on
origin/main", the `git pull` is yours to run when you are ready.

![](assets/screenshot_17.png)

The command is honest about not being able to answer. If you unpacked Field from an archive instead
of cloning it, if `HEAD` is detached, if there is no `origin`, or if the network is down, it says
which of those happened rather than reporting that you are up to date.

!!! note "For maintainers: making an update announce itself"

    Most commits should pass without comment — the count is enough. When a commit needs everyone
    who pulls it to *do* something, give it a `Field-Notice` trailer:

    ```diffstatic {data-lang=text}
    feat(repl): move to the 2.5.255 Kotlin fork

    ...body...

    Field-Notice: the Kotlin fork moved — run ./gradlew clean before your next build
    ```

    Anyone whose update check spans that commit sees the trailer's text verbatim, instead of the
    subject of the newest commit. Several notices in one range are all shown, deduplicated.
