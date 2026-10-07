# Source installation release audit

Audit date: 2026-10-04. Topic branch: `launch/first-run-hardening-20261004`.

The Linux source launch works on the audited host, including the editor and native graphics.
This is not a claim that a clean Windows or macOS machine has passed. The installation guide
now states prerequisites, unsupported architectures and optional-feature boundaries explicitly.

## Changes made

| First-use problem | Result |
| --- | --- |
| LFS pointers became a compiler ZIP error after a lengthy build | Launchers stop early with the affected files and recovery commands. |
| Main build and semantic sidecar could bootstrap the same JDK concurrently | Bootstrap/build precedes sidecar startup; sidecar failures remain visible in its log. |
| Finder launch bypassed the source launcher's checks | `field.command` delegates to `field.sh`. |
| Whitespace in Java classpaths and terminal arguments broke launch | Java argument files quote tokens, terminal re-execution preserves arguments, and Windows process arguments are quoted. |
| Unsupported GraalVM architectures led to nonexistent downloads | Intel macOS and native Windows ARM64 fail with an explicit support message. |
| A missing compiler/display or Electron shared library failed late | Prerequisite checks and actionable diagnostics precede normal startup. |
| Electron forced ANGLE/Vulkan and bypassed GPU crash/blocklist protections | The editor uses Chromium's normal backend choice; Vulkan is an explicit option. Field's native renderer is separate. |
| The server bound every interface and accepted cross-site browser commands by default | HTTPS defaults to loopback and the existing browser-origin guard defaults on. Remote clients opt into network binding. |
| Enabling that guard blocked Monaco's opaque-origin `data:` worker | Monaco's bootstrap is served from Field's own origin; its real JavaScript diagnostics worker was exercised. |
| Empty first-use caches/documents produced error-looking output | Missing persisted cache and missing initial-frame preview are normal first-use cases. |
| Optional setup was scattered across prerequisites and runtime panels | A separate Kotlin/JVM 17 terminal welcome app offers desktop/browser mode and optional Vulkan/Python prerequisites. |

The welcome app builds independently of Field's compiler fork and native code. It shows package
manager argument vectors and requires `INSTALL` before execution. LFS recovery requires `PULL`.
No shell evaluation, API-key collection or downloaded installation scripts are involved. Python
prerequisites still need JEP configuration; Vulkan diagnostics still need the appropriate GPU driver.
`--setup` reopens the tool; `--check-install` checks without building; `--server-only` omits Electron
and desktop bridge compilation. The main source build still downloads substantial dependencies.

## Evidence obtained here

Host: CachyOS Linux x86-64, KDE desktop, NVIDIA RTX 4090 plus integrated AMD graphics, 64 GB RAM.
The isolated checkout was moved to `/tmp/Field first run 20261004` to exercise paths with spaces.
Build directories started empty; dependency and installed JDK caches were reused. This was not a
cold download test or a low-memory qualification. Package-manager plans were reviewed and deferred;
no host packages were installed by the welcome tool.

- Independent welcome app: four tests pass; `prepareFirstRun` builds and produces a runnable
  Java argument file. An interactive session showed choices and saved a temporary configuration.
- Launcher/dependency Python tests: 41 pass, exercising sh, bash, zsh, dash and ksh.
- Electron GPU policy: one Node test passes. Windows launcher parses in PowerShell 7.6.6;
  this is syntax evidence, not Windows execution evidence.
- JVM guards: 32 targeted tests pass, zero skipped/failing; the test tree-write guard passes.
- Real `field.sh --server-only`: health, checkout/workspace identity, welcome page, editor HTML,
  built browser module and refusal of a foreign-origin API read pass.
- Real desktop `field.sh`: Electron opens, live `println("hello, Field")` succeeds, and a screenshot
  was inspected. The same-origin Monaco JavaScript worker returns syntax diagnostics.
- Actual listening socket is `127.0.0.1`, not a LAN wildcard.
- Native graphics: the canonical animated-triangle lesson ran through a real renderer; the
  captured image contains the rendered triangles and was inspected. This is one graphics smoke,
  not certification of every Vulkan feature.
- Docs HTML build: successful, no issues found, existing captures retained, zero capture failures.
  No screenshots were fabricated or added to the docs pipeline.

Some system-library and automation teardown warnings remain in diagnostic logs. The desktop test
does not establish a warning-free launch on every driver. A failed initial graphics automation
command was corrected to use the operation transcript; its later successful real capture is the
graphics evidence. Full repository certification was not run as part of this scoped audit.

## Before announcing public availability

1. Run the prepared manual `.github/workflows/first-run.yml` on Linux, Windows x64 and Apple-silicon
   macOS. It runs the actual source launcher and uploads evidence. It has not run remotely yet.
   Its server-only smoke does not qualify desktop windows or native GPU features: those need a
   manual desktop launch on Windows/macOS too. Linux ARM64 remains experimental until exercised.
2. Check the public repository with an anonymous shallow clone after changing visibility. Anonymous
   access currently returns 404 while the repository is private; it cannot be qualified in advance.
3. Check GitHub LFS bandwidth/budget settings before inviting a large audience. The compiler LFS
   object is about 190 MB per clone; 1,000 downloads are roughly 190 GB before CI traffic.
   [GitHub bills LFS downloads to the repository owner](https://docs.github.com/en/billing/concepts/product-billing/git-lfs).
4. Retain the honest source-beta allowances: at least 10 GB free as planning guidance and preferably
   16 GB RAM. An 8 GB machine has not been qualified, and the Kotlin compiler can use a 10 GB heap.
5. Keep the platform table explicit. The pinned
   [GraalVM Community 25.0.2 release](https://github.com/graalvm/graalvm-ce-builds/releases/tag/jdk-25.0.2)
   has no macOS x64 or Windows ARM64 artifact. Neither should be advertised as supported.

Normal use needs no manually installed Java/Node, API key, Python or optional submodules. Advanced
features can add requirements later. LAN access remains an unauthenticated code-execution surface
when explicitly enabled; browser-origin checks are not authentication.
