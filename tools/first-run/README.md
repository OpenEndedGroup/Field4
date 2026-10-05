# First-run welcome tool

A standalone Kotlin/JVM 17 application, built independently of Field with its wrapper JVM.
It uses the standard Kotlin plugin and JDK classes: no compiler fork, LFS JAR, native bridge,
GPU, Python, JSON library, or GUI toolkit is required to build it.

`./field.sh --setup` / `field.bat --setup` prepares and launches it in the user's terminal.
The launchers also show it on the first interactive launch. The tool never installs packages
without the exact `INSTALL` response, uses argument vectors rather than a shell, and never
requests credentials or AI keys. A package manager may request its normal administrator
credentials. Git LFS recovery has its own explicit `PULL` response.

It stores choices, not a claim that optional features are configured. Python prerequisites
still require selecting an interpreter and installing JEP in Field's welcome page; Vulkan
diagnostic tools do not install a GPU driver. Without a terminal, it reports prerequisites
and changes neither packages nor preferences.

Run its focused tests with `./gradlew -p tools/first-run test`.
