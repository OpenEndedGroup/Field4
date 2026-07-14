# ffnvcodec headers

Vendored from https://github.com/FFmpeg/nv-codec-headers (the same headers Arch/Debian package as
`ffnvcodec-headers`). These are NVIDIA's/FFmpeg's dlopen-based dynamic-link declarations for
`libcuda.so.1` and `libnvidia-encode.so.1` — no CUDA toolkit or Video Codec SDK install required to
build against them, only the driver's runtime libraries (already present via `nvidia-utils`).

Corresponds to Video Codec SDK version 13.1.15. Each header carries its own permissive
(MIT-style) license notice inline; unmodified except for this NOTICE file.

Not vendored via the system package manager because installing `ffnvcodec-headers` requires root
on this machine; vendoring a header-only, permissively-licensed drop-in avoids that without
changing the build in any way a package install wouldn't.
