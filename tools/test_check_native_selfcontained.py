#!/usr/bin/env python3
"""Hold `check_native_selfcontained.py`'s three object-file parsers and its allowlist.

`tools/check_native_selfcontained.py` was, on 2026-09-22, one of exactly two `tools/check_*.py`
that no gate reached by any route — no `build.gradle.kts` occurrence and no suite beside it. See
`tools/test_check_offline_assets.py`'s docstring for why the other unwired-looking checkers are in
fact gated: `run_python_tests.py` discovers by glob, so a suite is wired in by existing.

**This checker's risk is concentrated somewhere unusual.** It deliberately parses Mach-O, ELF and
PE by hand instead of shelling out to `otool`/`readelf`/`dumpbin`, and its docstring gives the
reason: *"a checker that silently skips when its tool is absent is worse than no checker at all."*
That is the right call and it moves the whole risk into three hand-written binary parsers, two of
which cannot run on the machine anyone develops on. The ELF reader has no committed ELF to read
and the PE reader has exactly one committed `.dll`; on macOS, CI or not, `_elf_needed` would
silently never execute. So the ELF case is driven by a **synthesised** ELF64 built here, which is
the only way to exercise that parser at all on this tree.

The two committed binaries under `libs/` are used as real fixtures, with their dependency lists
pinned exactly as measured 2026-09-22. They are characterization tests: the point is not that
these numbers are *good*, it is that a parser regression changes them.

**Two findings this file records rather than fixes**, both about the policy rather than the
parsers, and both left as pinned behaviour because changing a native build script's gate is not
this file's business:

1. `LC_ID_DYLIB` is read as though it were a dependency, so a library's own install name is
   reported as a leak. `ALLOWED["macho"]` papers over it with a literal
   `^@rpath/libken_ceres_jvm\\.dylib$` — the one library the checker was written for — which means
   the checker cannot be pointed at any *other* dylib without a false positive on its own ID.
   `libs/libabl_link_jvm.dylib` demonstrates it: 3 "dependencies", one of which is itself.
2. Neither committed library passes. That is not a bug in them — `abl_link` genuinely links
   `MSVCP140.dll` and the allowlist deliberately excludes the MSVC runtime — but it means the
   repository contains no example of the property this checker asserts, so there is nothing here
   to assert a passing case against except a synthesised one.

Run with:  uv run --project . --group backdoor python -m unittest tools.test_check_native_selfcontained
"""

from __future__ import annotations

import re
import struct
import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from tools.check_native_selfcontained import (
    ALLOWED,
    check,
    dependencies,
    detect,
)

REPO_ROOT = Path(__file__).resolve().parent.parent
CHECKER = REPO_ROOT / "tools" / "check_native_selfcontained.py"

DYLIB = REPO_ROOT / "libs" / "libabl_link_jvm.dylib"
DLL = REPO_ROOT / "libs" / "abl_link_jvm.dll"

# Measured 2026-09-22 with `dependencies()`. Exact lists, in order: a parser that starts reading
# one command short, or mis-walks the import descriptors, changes these.
DYLIB_DEPENDENCIES = [
    "@rpath/libabl_link_jvm.dylib",
    "/usr/lib/libc++.1.dylib",
    "/usr/lib/libSystem.B.dylib",
]
DLL_DEPENDENCIES = [
    "IPHLPAPI.DLL",
    "WS2_32.dll",
    "KERNEL32.dll",
    "MSVCP140.dll",
    "VCRUNTIME140.dll",
    "VCRUNTIME140_1.dll",
    "api-ms-win-crt-runtime-l1-1-0.dll",
    "api-ms-win-crt-math-l1-1-0.dll",
    "api-ms-win-crt-heap-l1-1-0.dll",
    "api-ms-win-crt-convert-l1-1-0.dll",
    "api-ms-win-crt-stdio-l1-1-0.dll",
    "api-ms-win-crt-string-l1-1-0.dll",
]


def synth_elf(needed: list[str], *, little_endian: bool = True) -> bytes:
    """A minimal ELF64 whose `DT_NEEDED` entries are exactly `needed`.

    Enough of the format for `_elf_needed` and no more: an ELF header, a `PT_LOAD` covering the
    whole file so virtual addresses map to file offsets one-to-one, a `PT_DYNAMIC` segment, and a
    string table the dynamic section points at by virtual address. Written little- or big-endian so
    the reader's `e_ident[EI_DATA]` branch is exercised in both directions.
    """
    endian = "<" if little_endian else ">"
    ehsize, phentsize, phnum = 64, 56, 2
    phoff = ehsize
    dyn_off = phoff + phentsize * phnum

    strtab = b"\0" + b"".join(name.encode() + b"\0" for name in needed)
    name_offsets, cursor = [], 1
    for name in needed:
        name_offsets.append(cursor)
        cursor += len(name) + 1

    entries = [(1, off) for off in name_offsets]          # DT_NEEDED
    dyn_size = (len(entries) + 2) * 16                     # + DT_STRTAB + DT_NULL
    strtab_off = dyn_off + dyn_size
    entries.append((5, strtab_off))                        # DT_STRTAB, vaddr == offset
    entries.append((0, 0))                                 # DT_NULL

    dynamic = b"".join(struct.pack(endian + "qQ", tag, value) for tag, value in entries)
    total = strtab_off + len(strtab)

    header = bytearray(ehsize)
    header[0:4] = b"\x7fELF"
    header[4] = 2                                          # ELFCLASS64
    header[5] = 1 if little_endian else 2
    header[6] = 1
    struct.pack_into(endian + "HHI", header, 0x10, 3, 0x3E, 1)
    struct.pack_into(endian + "Q", header, 0x20, phoff)
    struct.pack_into(endian + "HHH", header, 0x36, phentsize, phnum, 0)

    def program_header(p_type: int, offset: int, vaddr: int, filesz: int) -> bytes:
        return struct.pack(
            endian + "IIQQQQQQ", p_type, 0, offset, vaddr, vaddr, filesz, filesz, 1
        )

    body = bytearray(total)
    body[0:ehsize] = header
    body[phoff:phoff + phentsize] = program_header(1, 0, 0, total)                 # PT_LOAD
    body[phoff + phentsize:phoff + 2 * phentsize] = program_header(
        2, dyn_off, dyn_off, dyn_size
    )                                                                              # PT_DYNAMIC
    body[dyn_off:dyn_off + dyn_size] = dynamic
    body[strtab_off:strtab_off + len(strtab)] = strtab
    return bytes(body)


class Detect(unittest.TestCase):
    def test_each_real_format_is_recognised(self) -> None:
        self.assertEqual("macho", detect(DYLIB.read_bytes()))
        self.assertEqual("pe", detect(DLL.read_bytes()))
        self.assertEqual("elf", detect(synth_elf(["libc.so.6"])))

    def test_an_unrecognised_file_raises_rather_than_passing(self) -> None:
        """A checker that returns "no dependencies" for a text file would pass everything."""
        with self.assertRaises(ValueError):
            detect(b"#!/bin/sh\necho hello\n")


class MachO(unittest.TestCase):
    def test_the_committed_dylib_parses_to_its_measured_dependencies(self) -> None:
        kind, names = dependencies(DYLIB)
        self.assertEqual("macho", kind)
        self.assertEqual(DYLIB_DEPENDENCIES, names)

    def test_a_library_s_own_install_name_is_reported_as_a_leak(self) -> None:
        """Pinned defect, not endorsed: `LC_ID_DYLIB` is read as a dependency. See the docstring.

        `ALLOWED["macho"]` allows `@rpath/libken_ceres_jvm.dylib` by name, so the one library this
        checker was written for is unaffected and every other one is a false positive.
        """
        leaked = check(DYLIB, verbose=False)
        self.assertEqual(["@rpath/libabl_link_jvm.dylib"], leaked)

    def test_the_system_libraries_are_allowed(self) -> None:
        patterns = [re.compile(p) for p in ALLOWED["macho"]]
        for name in ("/usr/lib/libc++.1.dylib", "/usr/lib/libSystem.B.dylib"):
            self.assertTrue(any(p.match(name) for p in patterns), name)

    def test_a_homebrew_prefix_is_not_allowed(self) -> None:
        """The original failure this checker exists for: a load command naming a Homebrew prefix."""
        patterns = [re.compile(p) for p in ALLOWED["macho"]]
        name = "/opt/homebrew/opt/ceres-solver/lib/libceres.4.dylib"
        self.assertFalse(any(p.match(name) for p in patterns))


class Elf(unittest.TestCase):
    """The parser with no committed fixture, and therefore the one that most needs a synthetic."""

    def test_needed_entries_are_read_in_order(self) -> None:
        names = ["libc.so.6", "libm.so.6", "libstdc++.so.6"]
        self.assertEqual(names, dependencies_from_bytes(synth_elf(names)))

    def test_a_big_endian_elf_is_read_too(self) -> None:
        names = ["libc.so.6", "librt.so.1"]
        self.assertEqual(names, dependencies_from_bytes(synth_elf(names, little_endian=False)))

    def test_an_elf_with_no_dynamic_segment_has_no_dependencies(self) -> None:
        self.assertEqual([], dependencies_from_bytes(synth_elf([])))

    def test_the_cpp_runtime_is_not_allowed(self) -> None:
        """`-static-libstdc++` exists to remove these two, so allowing them would agree with a
        build that had quietly lost the flag — which the module's own comment says in words."""
        patterns = [re.compile(p) for p in ALLOWED["elf"]]
        for name in ("libstdc++.so.6", "libgcc_s.so.1"):
            self.assertFalse(any(p.match(name) for p in patterns), name)

    def test_the_os_c_runtime_is_allowed(self) -> None:
        patterns = [re.compile(p) for p in ALLOWED["elf"]]
        for name in ("libc.so.6", "libm.so.6", "libpthread.so.0", "ld-linux-x86-64.so.2"):
            self.assertTrue(any(p.match(name) for p in patterns), name)


class Pe(unittest.TestCase):
    def test_the_committed_dll_parses_to_its_measured_imports(self) -> None:
        kind, names = dependencies(DLL)
        self.assertEqual("pe", kind)
        self.assertEqual(DLL_DEPENDENCIES, names)

    def test_the_msvc_runtime_is_not_allowed(self) -> None:
        patterns = [re.compile(p) for p in ALLOWED["pe"]]
        for name in ("MSVCP140.dll", "VCRUNTIME140.dll", "VCRUNTIME140_1.dll"):
            self.assertFalse(any(p.match(name) for p in patterns), name)

    def test_the_api_set_and_kernel_are_allowed_case_insensitively(self) -> None:
        patterns = [re.compile(p) for p in ALLOWED["pe"]]
        for name in ("KERNEL32.dll", "kernel32.DLL", "api-ms-win-crt-stdio-l1-1-0.dll"):
            self.assertTrue(any(p.match(name) for p in patterns), name)

    def test_the_committed_dll_leaks_exactly_what_was_measured(self) -> None:
        self.assertEqual(
            ["IPHLPAPI.DLL", "WS2_32.dll", "MSVCP140.dll", "VCRUNTIME140.dll", "VCRUNTIME140_1.dll"],
            check(DLL, verbose=False),
        )


class TheCommandLine(unittest.TestCase):
    """Exit codes, which are the only thing a build script reads."""

    def run_checker(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(CHECKER), *args],
            capture_output=True, text=True, cwd=str(REPO_ROOT),
        )

    def test_a_self_contained_library_exits_zero(self) -> None:
        """Synthesised, because no committed binary has the property. Both sides need a case."""
        with TemporaryDirectory() as raw:
            path = Path(raw) / "clean.so"
            path.write_bytes(synth_elf(["libc.so.6", "libm.so.6"]))
            result = self.run_checker(str(path))
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_a_leaking_library_exits_one_and_names_the_offender(self) -> None:
        with TemporaryDirectory() as raw:
            path = Path(raw) / "leaky.so"
            path.write_bytes(synth_elf(["libc.so.6", "libstdc++.so.6"]))
            result = self.run_checker(str(path))
            self.assertEqual(1, result.returncode)
            self.assertIn("libstdc++.so.6", result.stderr)

    def test_a_missing_file_exits_two_not_one(self) -> None:
        """2 is "could not check", 1 is "checked and it leaks". A build script must tell them apart."""
        result = self.run_checker("no/such/library.dylib")
        self.assertEqual(2, result.returncode)

    def test_an_unparseable_file_exits_two(self) -> None:
        with TemporaryDirectory() as raw:
            path = Path(raw) / "notes.txt"
            path.write_text("not an object file", encoding="utf-8")
            self.assertEqual(2, self.run_checker(str(path)).returncode)

    def test_quiet_prints_nothing_on_success(self) -> None:
        with TemporaryDirectory() as raw:
            path = Path(raw) / "clean.so"
            path.write_bytes(synth_elf(["libc.so.6"]))
            result = self.run_checker(str(path), "--quiet")
            self.assertEqual(0, result.returncode)
            self.assertEqual("", result.stdout)


def dependencies_from_bytes(data: bytes) -> list[str]:
    """`dependencies()` takes a path, so round-trip through a temp file to keep one code path."""
    with TemporaryDirectory() as raw:
        path = Path(raw) / "fixture.so"
        path.write_bytes(data)
        return dependencies(path)[1]


if __name__ == "__main__":
    unittest.main()
