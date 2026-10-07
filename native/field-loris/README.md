# Optional Loris worker

`./gradlew buildLorisNative` builds Loris plus Field's offline binary adapter. Nothing in the
default Field build or launch depends on it. Requires CMake 3.24+, a C++17 toolchain, and network
on the first build. FFTW, Python bindings, libsndfile, GPUs and model downloads are not needed.

Upstream: https://github.com/kellyfitz/loris, revision
`eccd42ac847b08769643b0d0c36442ef0fd27f38`; archive SHA-256
`7942ae03e087338104959176d5bcf6483e62c35f3e68dca3d537c1c99df82a47`.
Loris is copyright Kelly Fitz and Lippold Haken and contributors, GPL-2.0-or-later (see the
pinned source's COPYING and file notices). Field's wrapper is under Field's AGPL-3.0-or-later.
No upstream source or compiled binaries are vendored. A distributor of the built worker must
also provide the corresponding source and notices. The build leaves that source in
`build/field-loris/source/` and modifies only two Unix-specific CMake conditions for Windows.

`AFIELD_LORIS_EXECUTABLE` or `-Dafield.loris.executable=...` overrides discovery. Keep the Loris
shared library beside the executable on Windows, or in its original build-tree location on
Linux/macOS. Linux is live-tested; macOS/Windows use portable CMake but still need live validation.

The worker reads/writes big-endian binary files (FLORIS01 / FLORIS02). An analysis request
contains the exact mono PCM samples, sample rate, and six independent analyzer controls. A
resynthesis request contains explicit frame extent, partial IDs/labels and irregular native
breakpoints (time, Hz, amplitude, radians phase, bandwidth). No full-array JSON. The Kotlin
client hashes the request, executable and local Loris shared libraries for durable caching;
validates results before publishing; and runs a bounded subprocess off the Field UI thread.

Run `./gradlew jvmTest --tests 'afield.stdlib.loris.LorisTest'` after the opt-in build for the
actual tracking/reconstruction/cache/resynthesis smoke. Without the native build the same test
suite checks data validation, transforms and the viewer without fetching anything.
