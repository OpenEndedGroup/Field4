# Welcome to _Field·4_

 _Field·4_ is an experimental, live-coding environment for new media art, VR, and music. Although _Field_ began as a research project at MIT's Media Lab in 2001, this new version is currently in "early-ish beta"—your help in developing it is deeply appreciated. It's all new code, incubated during UChicago's winter 2026 _VR Production class_  after a successful 2025 trial in the _Augmented Reality in the Garden_ and _Remaking Movies_ classes.

_Field·4_ works on MacOS, Windows and Linux, supports a high performance [Vulkan](https://www.vulkan.org/) graphics system for desktop graphics, and has bindings for [OpenXR](https://www.khronos.org/openxr/) on supported platforms. There is also a native front end for the [Apple Vision Pro](vision.md) — head-tracked, stereo, and explicit about which parts of it have been run on a device.

The primary programming language for this version of Field is [Kotlin](https://kotlinlang.org/) for desktop, which we've gone to some effort to bend into a language that is both a joy to experiment in and fast enough to make real things with. Kotlin, together with Vulkan [GLSL](https://www.khronos.org/opengl/wiki/OpenGL_Shading_Language), is the primary combination of languages used day to day in Field. In addition, there are early-stage bindings for [Clojure](https://clojure.org/) (as a JVM language), [Python](https://www.python.org/) (via the [JEP project](https://github.com/ninia/jep)), and JavaScript that executes remotely in-browser or on mobile for [Three.js](https://threejs.org/)-based [WebXR](https://immersive-web.github.io/webxr/).

_Field·4_ has and will continue to have enthusiastic support for AI-forward coding practices, offering documentation-, source-code-, current-document-, code- and line-based chat, as well as an increasingly comprehensive set of tools for agents to use to interact with Field. It currently supports "bring your own key" OpenAI integration.

## Starting places

If you aren't reading this documentation inside *Field·4* itself, then you'll want to read the [Installation instructions](installation.md) or perhaps [take a tour](funFeatures.md).

Alternatively, the [Survival Guide](survivalGuide.md) is a good cheat-sheet.

If you have Field open on your machine while reading these docs, many of the read-only examples will open directly in Field when you push the button in the bottom right.
