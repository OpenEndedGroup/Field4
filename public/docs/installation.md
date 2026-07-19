# Installation

Field is distributed via source — you can download it from [our repository](https://github.com/OpenEndedGroup/Field4). 

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

### Autoupdate

The `F1` command palette includes **Check for updates & download**. It compares
`localVersion.json` with Field's update manifest, downloads a changed archive into `staging/`,
promotes its `field4/` contents into the checkout while moving colliding existing files aside, and
then updates `localVersion.json`. Restart Field after a successful update. If the local version file
is absent or invalid, the command reports the checkout as versionless instead of applying anything.

![](assets/screenshot_17.png)
