# Field 4

[![License: AGPL v3+](https://img.shields.io/badge/license-AGPL--3.0--or--later-blue)](LICENSE)
![Kotlin](https://img.shields.io/badge/Kotlin-JVM%20%2B%20JS-7F52FF?logo=kotlin&logoColor=white)
![Vulkan](https://img.shields.io/badge/graphics-Vulkan-AC162C?logo=vulkan&logoColor=white)
![Platforms](https://img.shields.io/badge/platform-macOS%20%7C%20Linux%20%7C%20Windows-lightgrey)
[![Docs](https://img.shields.io/badge/docs-openendedgroup.com%2Ffield4-success)](https://openendedgroup.com/field4)

Field 4 is a live-coding environment for graphics, sound, exploratory data visualization, and new media art.

Project site and user documentation: https://openendedgroup.com/field4

[![A moving drawing in Field, with its editable Kotlin and inline petal count](public/docs/assets/first_steps/preview.gif)](https://openendedgroup.com/field4/first_steps/)

[Watch the short recording](https://openendedgroup.com/field4/assets/first_steps/first-look.mp4) ·
[Your first five minutes](https://openendedgroup.com/field4/first_steps/)

## Status

Field 4 is in active development but is usable for real work, teaching and inspiration.

## Requirements

Apple silicon macOS, glibc Linux (x86-64; ARM64 experimental) or Windows x64, with Git LFS
installed before you clone. Allow 10 GB of free disk and 16 GB of RAM. Java, Gradle and Node are
provisioned by the launcher.

## Quickstart

```bash
git lfs install
git clone --depth 1 https://github.com/OpenEndedGroup/Field4.git
cd Field4
./field.sh    # or .\field.bat on Windows
```

The first run compiles the whole project and downloads large native dependencies; allow 20–25
minutes. When Welcome opens, click **Start here: a moving drawing**.

The [installation guide](https://openendedgroup.com/field4/installation/) covers platform
prerequisites, workspaces, browser-only use and troubleshooting;
[AI features](https://openendedgroup.com/field4/aiFeatures/) covers API keys and local models.

## License & Privacy

**AGPL-3.0-or-later.** See [LICENSE](LICENSE). Field runs locally; optional AI uses your own
provider/account. [What stays local and what leaves](https://openendedgroup.com/field4/privacy/).

## Working on Field itself

[`AGENTS.md`](AGENTS.md) is the repository guide: layout, architecture, build and test commands.
