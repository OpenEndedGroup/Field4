# Welcome to _Field·4_

 _Field·4_ is an experimental, live-coding environment for new media art, VR, music and data-visualization. Although _Field_ began as a research project at MIT's Media Lab in 2001, this new version is currently in "early preview"—your help in developing it is deeply appreciated. It's all new code, incubated during UChicago's winter 2026 _VR Production class_  after a successful 2025 trial in the _Augmented Reality in the Garden_ and _Remaking Movies_ classes.

_Field·4_ works on MacOS, Windows and Linux, supports a high performance [Vulkan](https://www.vulkan.org/) graphics system for desktop graphics, and has bindings for local (that is Desktop) [OpenXR](https://www.khronos.org/openxr/) on supported platforms as we as remote (that is JavaScript / WebXR) on devices that support WebXR. There is also a small native front end for the [Apple Vision Pro](vision.md).

The primary programming language for this version of Field is [Kotlin](https://kotlinlang.org/) for desktop, which we've gone to some effort to bend into a language and runtime that is both a joy to experiment in and fast enough to make real things with. Kotlin, together with [Vulkan GLSL](https://www.khronos.org/opengl/wiki/OpenGL_Shading_Language), is the primary combination of languages used daily in Field. In addition, there are bindings for other languages, include [Clojure](https://clojure.org/) (as a JVM language), [Python](python.md) (via the [JEP project](https://github.com/ninia/jep)), [Lua](lua.md) (via [LuaJIT](https://luajit.org)), [JavaScript](remotejs.md) that executes remotely in-browser or on mobile for [Three.js](https://threejs.org/)-based [WebXR](https://immersive-web.github.io/webxr/) and a great many other languages. These extensions usually have autocomplete, AI assistance, live preview and some kind of interopation with the rest of Field's supported languages.

_Field·4_ has and will continue to have enthusiastic support for AI-forward coding practices, offering documentation-, source-code-, current-document-, code- and line-based chat across languages, as well as a comprehensive, reflexive and self-improving set of tools for agents to use to interact with Field in all ways. If that isn't your cup of tea, then this project might not be the coziest spot for you to curl up in. I wish you well, and we'll see each other on the other side. I don't know the location of the other side, and I'll note that the very first version of Field (then called *Fluid* in 2001) was built to help build, interact, train and play with with software agents.

Field currently supports "bring your own key" OpenAI / Anthropic integration. The [agent transcript gallery](agent_transcripts.md) shows what that tooling makes: documents that agents have built with limited supervision from a blank canvas, each one openable in Field. [Long way round](chains.md) walks through agent-generated projects that connect several of Field's tools, from geometry and simulation to rendered images.

Finally, this documentation site itself is largely a _living document_ — both in the sense that it is an ongoing collaboration with agents who have written much of the text inside a custom harness that's part of Field — and in the sense that it's part of Field's *build infrastructure*. Almost all screenshots and code-snippets on this site go together and are built nightly. While Field has an additional and extensive set of tests, to a large part you are reading the nightly test suite right now.

## Starting places

In a hurry, see [the first drawing](first_steps.md), then open **Start here: a moving drawing** on Welcome.
Change a number, launch, inspect, stop and reopen your saved copy.

Field's code is AGPL-3.0-or-later; [your code, AI and local history](privacy.md) explains the
local services and optional external providers.

If you aren't reading this documentation inside *Field·4* itself, then you'll want to read the [Installation instructions](installation.md) or, if you are window-shopping, perhaps [take a tour](funFeatures.md).

Alternatively, the [Survival Guide](survivalGuide.md) is a good cheat-sheet.

If you do have Field open on your machine while reading these docs, many of the read-only examples will open directly in Field when you push the button in the bottom right.
