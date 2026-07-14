# field-fdpass

A tiny JNI shim (one function) so the JVM can hand a raw file descriptor to another process via
SCM_RIGHTS over a Unix domain socket. Used by `afield.four.texture.UnixFdPass` to get a
Vulkan-exported opaque fd into the `field-encode-linux` helper process — see
`../field-encode/WIRE_FORMAT.md`'s "Linux sender addendum" for the full protocol context.

## Why this exists

Java has no standard-library support for `sendmsg()`/ancillary data, so passing a real fd to
another process requires either JNI or a subprocess trick. A subprocess can't help here: a
`ProcessBuilder`-launched child only inherits fds 0/1/2 (verified empirically — nothing else
survives `posix_spawn`/`fork+exec` on this JVM), so there's no way to hand it an arbitrary fd
without something that can call `sendmsg`. Hence this shim.

An earlier design avoided JNI entirely by having the helper duplicate the fd out of the producer's
`/proc/<pid>/fd/<fd>` — cheap, no native glue, and it works for anonymous-inode files (pipes,
eventfds, real dma-bufs). It does **not** work for NVIDIA's
`VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT` export: that fd turned out to be backed by
`/dev/nvidiactl` (a real device-special file), and reopening it via the procfs magic symlink
performs a *fresh* `open()` against the driver — numerically valid, semantically unrelated to the
original memory object, and `cuImportExternalMemory` fails with `CUDA_ERROR_UNKNOWN` (999).
SCM_RIGHTS duplicates the actual kernel `struct file*` regardless of what's behind it, which is why
it's the mechanism actually used here.

## Isolation from Field4's main classpath — load-bearing, not style

`java/fieldfdpass/UnixFdPassNative.java` is **plain Java**, compiled into its own standalone jar
(`fieldfdpass-native.jar`), and is never referenced from Field4's `jvmMain` Kotlin module. This
matters: a class declaring a JNI `external fun`/`native` method, if it's ever loaded on Field4's
main classpath (even just referenced, never called), breaks the live box/REPL execution host with
a `NoClassDefFoundError: ReplState` — a real bug, confirmed by isolating it down to
`SomeClass.toString()` alone triggering it, unrelated to the native call itself. `UnixFdPass.kt`
(the safe wrapper Field4 code actually calls) loads `UnixFdPassNative` at runtime via a fresh
`URLClassLoader`, so Field's classpath scanning never sees it.

A second, separate gotcha the class comment documents: `System.load()` must be called *from a
static method on the class declaring the native method* (`UnixFdPassNative.loadLibrary(path)`),
not from the Kotlin wrapper — HotSpot scopes a loaded library's native-method bindings to the
classloader that was active at `System.load()` time, and resolves a `native` method only against
libraries registered under *its own declaring class's* classloader. Loading from the wrong
classloader compiles and loads fine but every call throws `UnsatisfiedLinkError` despite the
symbol being present in the process.

## Build

```sh
cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build -j$(nproc)   # -> build/libfieldfdpass.so, build/fieldfdpass-native.jar
```

Needs a JDK with JNI headers (any JDK's `include/jni.h` works) and `javac`/`jar` — CMake's
`find_package(Java REQUIRED COMPONENTS Development)` locates them.

## Status

Verified working end-to-end as part of the Linux/NVENC RTT streaming path: real Vulkan-exported
fds handed to `field-encode-linux` this way, imported into CUDA, encoded, and streamed to a real
visionOS receiver successfully (see `../field-encode-linux/README.md`).
