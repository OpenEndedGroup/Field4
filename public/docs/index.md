# Welcome to *Field·4*

*Field·4* is an experimental, live-coding environment for new media art, VR, music and data visualization.

If you want to get your hands on it, [install Field](installation.md) and make a [first moving drawing](first_steps.md). If you'd rather look around first, take the [Tour](funFeatures.md), or follow an idea from one medium into another in the [Long Way Round](chains.md). Looking for a particular thing? Start with [what's already in the box](reference/index.md).

*Field·4* runs on macOS, Windows and Linux. It draws desktop graphics with [Vulkan](https://www.vulkan.org/), so you can write shaders and render meshes and particles from a box. For VR, you can drive a headset attached to your computer through [OpenXR](https://www.khronos.org/openxr/), where the platform supports it, or send a scene to a headset or phone browser through [WebXR](https://immersive-web.github.io/webxr/). There is also a small native front end for the [Apple Vision Pro](vision.md).

The primary programming language for this version of Field is [Kotlin](https://kotlinlang.org/) on the desktop, which we've gone to some effort to bend into a language and runtime that is both a joy to experiment in and fast enough to make real things with. Kotlin, together with [Vulkan GLSL](https://www.khronos.org/opengl/wiki/OpenGL_Shading_Language), is the primary combination of languages used daily in Field. In addition, there are bindings for other languages, including [Clojure](https://clojure.org/) (as a JVM language), [Python](python.md) (via the [JEP project](https://github.com/ninia/jep)), [Lua](lua.md) (via [LuaJIT](https://luajit.org)), [JavaScript](remotejs.md) that executes remotely in the browser or on mobile devices for [Three.js](https://threejs.org/)-based [WebXR](https://immersive-web.github.io/webxr/), and a great many other languages. These extensions usually have autocomplete, AI assistance, live preview and some kind of interoperability with the rest of Field's supported languages.

*Field·4* has, and will continue to have, enthusiastic support for AI-forward coding practices, offering chat grounded in documentation, source code, the current document, and individual code selections, as well as tools that let an agent drive Field the way you do: create and connect boxes, run their code, read the errors and output, and take screenshots of the result. If that isn't your cup of tea, then this project might not be the coziest spot for you to curl up in. The very first version of Field (then called *Fluid*, in 2001) was itself built to build, interact with, train and play with software agents.

Field supports "bring your own key" OpenAI / Anthropic integration, as well as local models. The [agent transcript gallery](agent_transcripts.md) shows what that tooling can make: documents that agents have built with limited supervision from a blank canvas, each one openable in Field. [Long Way Round](chains.md) walks through agent-generated projects that connect several of Field's tools, from geometry and simulation to rendered images.

Although *Field* began as a research project at MIT's Media Lab in 2001, this new version is currently in "early preview"—your help in developing it is deeply appreciated. It's all new code, incubated during UChicago's winter 2026 *VR Production* class after a successful 2025 trial in the *Augmented Reality in the Garden* and *Remaking Movies* classes.

## Starting places

In a hurry? Field opens on a Welcome page; [the first drawing](first_steps.md) walks you from its **Start here: a moving drawing** button to a drawing you change, run, stop and reopen.

If you aren't reading this documentation inside *Field·4* itself, then you'll want to read the [Installation instructions](installation.md) or, if you are window-shopping, perhaps [take a tour](funFeatures.md).

Alternatively, the [Survival Guide](survivalGuide.md) is a good cheat sheet.

If you do have Field open on your machine while reading these docs, many of the read-only examples will open directly in Field when you click the "Open in Field" button.

Field's code is AGPL-3.0-or-later; [your code, AI and local history](privacy.md) explains the local services and optional external providers.

??? aside "How this site is made"
    Agents wrote much of this text, using a harness that is part of Field, and the site is part of Field's build: almost every one of the 2000 screenshots here is captured by running the code beside it. Even the video behind this page is automatically generated and regenerated too. Field has lots of other tests and build infrastructure, but these excersize in agent-driven literate programming is a large part of its test suite.
