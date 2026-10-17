# Typed polytope push binding and truthful KotlinJS names

Baseline `ce168ea49`. Three frozen findings addressed:

- `38f740826e40-01`: m.params uses the shader-generated typed delegate instead of a string-keyed Structure bind. The actual bound time advances before and after a hot edit under the same live renderer, using both authored shaders and unchanged StackAuto programs.
- `38f740826e40-02`: position/normal/index arrays and the complete face-centre accumulation are readable logical statements. The FloatArray comment explains x/y/z sums and triangle-corner count. The final before/after native captures are nonempty and their 1440x1440 RGB pixels are identical (292,393 visible pixels each) under the original static slot programs.
- `33496126aa4f-01`: the KotlinJS accumulator is named sum. Actual Field KotlinToJsCompiler output executes in the bundled Node runtime; the vector-sum output is identical before/after. The test also exposed an open-chain perimeter: the last-to-first edge is now included, yielding 62.730969810918786, exactly matching the regular 32-gon reference at radius 10. This extra correction adds no frozen finding count.

Actual import/ktfmt formatting covers one Kotlin sidecar and both KotlinJS math/vector bodies; neither KotlinJS body has imports to remove. Actual formatting covers both GLSL properties. Reviewed tokens, containers, comments, fragment markers and raw StackAuto program strings are preserved. The normal checker covers the sidecar; its check_block engine separately audits the two KotlinJS bodies because the CLI excludes dev_docs/kotlinjs_live_test.yaml. All are clean. SavingContext refreshes the saved polytope fallback strings while graph identity and non-source properties stay unchanged. There is no document.md mirror in this document.

The initial native rehearsal destroyed/recreated a StackAuto renderer and the private JVM aborted with an invalid Vulkan device handle. A fresh private host and one live renderer avoid that teardown/recreation path. An initial cold capture was empty; final capture readiness requires actual visible pixels, bounded to six real native captures. Both final images are inspected. No placeholder image is accepted. The private module is released before its renderer and the renderer is stopped. Crash/cold-capture evidence remains in the archive.

The machine's Homebrew Node cannot load libsimdjson.33.dylib. The app-bundled Node runs the emitted JavaScript successfully; no machine-wide dependency is changed. This is actual KotlinJS math execution in Node, not a claim that the connected browser head was exercised.

changes.json / verification.json retain the reviewed draft and preservation checks. Reusable formatting, native/JS rehearsal and codec refresh harnesses are included. evidence.tar.gz contains the actual captures, time checks, compiler results, numerical output, native crash and Node loader diagnostics. The full corpus sweep remains incomplete.
