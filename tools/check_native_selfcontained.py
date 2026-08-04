#!/usr/bin/env python3
"""Assert that a native library depends on nothing but the operating system.

`native/ken-ceres/build_redistributable.sh` used to do this for macOS with `otool -L`, and that
assertion is the entire point of the pinned static build: a wrapper that carries
/opt/homebrew/opt/ceres-solver in its load commands runs only on a machine with Homebrew installed
at the same prefix. The same property has a different spelling on each platform, and the same
failure mode on all three — the library loads fine on the machine that built it and fails on every
other one.

The dependency lists are read by parsing the object files directly rather than by shelling out to
otool / readelf / dumpbin. That is a little more code here and one less thing to be missing on a CI
runner: `dumpbin` in particular only exists inside a Visual Studio developer shell, and a checker
that silently skips when its tool is absent is worse than no checker at all.

    python3 tools/check_native_selfcontained.py build/ken-ceres-redistributable/libken_ceres_jvm.dylib

Exits 0 if every dependency is on the platform allowlist, 1 otherwise, naming the offenders.
"""

from __future__ import annotations

import argparse
import re
import struct
import sys
from pathlib import Path

# --------------------------------------------------------------------------- Mach-O

MH_MAGIC_64 = 0xFEEDFACF
MH_CIGAM_64 = 0xCFFAEDFE
FAT_MAGIC = 0xCAFEBABE
FAT_CIGAM = 0xBEBAFECA
LC_LOAD_DYLIB = 0x0C
LC_LOAD_WEAK_DYLIB = 0x80000018
LC_REEXPORT_DYLIB = 0x8000001F
LC_ID_DYLIB = 0x0D


def _macho_dylibs(data: bytes, offset: int = 0) -> list[str]:
    (magic,) = struct.unpack_from("<I", data, offset)
    if magic in (FAT_MAGIC, FAT_CIGAM):
        # A universal binary: check every slice, since a dependency that leaks into only the arm64
        # slice is still a dependency.
        (nfat,) = struct.unpack_from(">I", data, offset + 4)
        names: list[str] = []
        for i in range(nfat):
            arch = offset + 8 + i * 20
            (slice_offset,) = struct.unpack_from(">I", data, arch + 8)
            names.extend(_macho_dylibs(data, slice_offset))
        return names

    endian = "<" if magic == MH_MAGIC_64 else ">"
    if magic not in (MH_MAGIC_64, MH_CIGAM_64):
        raise ValueError(f"not a 64-bit Mach-O (magic {magic:#x})")

    ncmds = struct.unpack_from(endian + "I", data, offset + 16)[0]
    pos = offset + 32  # sizeof(mach_header_64)
    names = []
    for _ in range(ncmds):
        cmd, cmdsize = struct.unpack_from(endian + "II", data, pos)
        if cmd in (LC_LOAD_DYLIB, LC_LOAD_WEAK_DYLIB, LC_REEXPORT_DYLIB, LC_ID_DYLIB):
            (name_offset,) = struct.unpack_from(endian + "I", data, pos + 8)
            raw = data[pos + name_offset : pos + cmdsize]
            names.append(raw.split(b"\0", 1)[0].decode("utf-8", "replace"))
        pos += cmdsize
    return names


# --------------------------------------------------------------------------- ELF

DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
PT_DYNAMIC = 2


def _elf_needed(data: bytes) -> list[str]:
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    if data[4] != 2:
        raise ValueError("only 64-bit ELF is supported")
    endian = "<" if data[5] == 1 else ">"

    e_phoff, = struct.unpack_from(endian + "Q", data, 0x20)
    e_phentsize, e_phnum = struct.unpack_from(endian + "HH", data, 0x36)

    dynamic = None
    segments = []
    for i in range(e_phnum):
        base = e_phoff + i * e_phentsize
        p_type, = struct.unpack_from(endian + "I", data, base)
        p_offset, p_vaddr, _p_paddr, p_filesz = struct.unpack_from(endian + "QQQQ", data, base + 8)
        segments.append((p_vaddr, p_offset, p_filesz))
        if p_type == PT_DYNAMIC:
            dynamic = (p_offset, p_filesz)
    if dynamic is None:
        return []

    def vaddr_to_offset(vaddr: int) -> int | None:
        for p_vaddr, p_offset, p_filesz in segments:
            if p_vaddr <= vaddr < p_vaddr + p_filesz:
                return p_offset + (vaddr - p_vaddr)
        return None

    # Two passes: the string table's address is itself an entry in the dynamic section.
    offset, size = dynamic
    entries = []
    strtab_vaddr = None
    for pos in range(offset, offset + size, 16):
        tag, value = struct.unpack_from(endian + "qQ", data, pos)
        if tag == DT_NULL:
            break
        if tag == DT_STRTAB:
            strtab_vaddr = value
        elif tag == DT_NEEDED:
            entries.append(value)

    if strtab_vaddr is None:
        return []
    strtab = vaddr_to_offset(strtab_vaddr)
    if strtab is None:
        return []

    names = []
    for name_offset in entries:
        end = data.index(b"\0", strtab + name_offset)
        names.append(data[strtab + name_offset : end].decode("utf-8", "replace"))
    return names


# --------------------------------------------------------------------------- PE

def _pe_imports(data: bytes) -> list[str]:
    if data[:2] != b"MZ":
        raise ValueError("not a PE file")
    (e_lfanew,) = struct.unpack_from("<I", data, 0x3C)
    if data[e_lfanew : e_lfanew + 4] != b"PE\0\0":
        raise ValueError("missing PE signature")

    coff = e_lfanew + 4
    number_of_sections, = struct.unpack_from("<H", data, coff + 2)
    size_of_optional_header, = struct.unpack_from("<H", data, coff + 16)
    optional = coff + 20
    magic, = struct.unpack_from("<H", data, optional)
    if magic == 0x20B:      # PE32+
        data_directory = optional + 112
    elif magic == 0x10B:    # PE32
        data_directory = optional + 96
    else:
        raise ValueError(f"unknown optional header magic {magic:#x}")

    import_rva, import_size = struct.unpack_from("<II", data, data_directory + 8)
    if import_rva == 0 or import_size == 0:
        return []

    sections = []
    section_table = optional + size_of_optional_header
    for i in range(number_of_sections):
        base = section_table + i * 40
        virtual_size, virtual_address, size_of_raw, pointer_to_raw = struct.unpack_from(
            "<IIII", data, base + 8
        )
        sections.append((virtual_address, max(virtual_size, size_of_raw), pointer_to_raw))

    def rva_to_offset(rva: int) -> int | None:
        for virtual_address, size, pointer in sections:
            if virtual_address <= rva < virtual_address + size:
                return pointer + (rva - virtual_address)
        return None

    table = rva_to_offset(import_rva)
    if table is None:
        return []

    names = []
    while True:
        descriptor = data[table : table + 20]
        if len(descriptor) < 20 or descriptor == b"\0" * 20:
            break
        name_rva, = struct.unpack_from("<I", descriptor, 12)
        if name_rva == 0:
            break
        name_offset = rva_to_offset(name_rva)
        if name_offset is None:
            break
        end = data.index(b"\0", name_offset)
        names.append(data[name_offset:end].decode("utf-8", "replace"))
        table += 20
    return names


# --------------------------------------------------------------------------- policy

# What each platform is allowed to depend on: the C/C++ runtime the OS itself ships, and nothing
# else. Note what is deliberately absent — libstdc++.so.6 and libgcc_s.so.1 on Linux, MSVCP140.dll
# and VCRUNTIME140.dll on Windows. Those are exactly the dependencies -static-libstdc++ and the
# static MSVC runtime exist to remove, so allowing them would make the checker agree with a build
# that had quietly lost the flag.
ALLOWED = {
    "macho": [
        r"^/usr/lib/",
        r"^/System/Library/",
        r"^@rpath/libken_ceres_jvm\.dylib$",
    ],
    "elf": [
        r"^libc\.so\.6$",
        r"^libm\.so\.6$",
        r"^libdl\.so\.2$",
        r"^libpthread\.so\.0$",
        r"^librt\.so\.1$",
        r"^ld-linux-.*\.so\.\d+$",
    ],
    "pe": [
        r"(?i)^kernel32\.dll$",
        r"(?i)^advapi32\.dll$",
        r"(?i)^api-ms-win-.*\.dll$",
        r"(?i)^ntdll\.dll$",
    ],
}

FORMAT_NAMES = {"macho": "Mach-O", "elf": "ELF", "pe": "PE"}


def detect(data: bytes) -> str:
    if data[:4] in (b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf") or struct.unpack_from("<I", data, 0)[0] in (
        FAT_MAGIC,
        FAT_CIGAM,
    ):
        return "macho"
    if data[:4] == b"\x7fELF":
        return "elf"
    if data[:2] == b"MZ":
        return "pe"
    raise ValueError("unrecognised object file format")


def dependencies(path: Path) -> tuple[str, list[str]]:
    data = path.read_bytes()
    kind = detect(data)
    reader = {"macho": _macho_dylibs, "elf": _elf_needed, "pe": _pe_imports}[kind]
    return kind, reader(data)


def check(path: Path, verbose: bool = True) -> list[str]:
    """Return the list of disallowed dependencies (empty means self-contained)."""
    kind, names = dependencies(path)
    patterns = [re.compile(p) for p in ALLOWED[kind]]
    leaked = [n for n in names if not any(p.match(n) for p in patterns)]
    if verbose:
        print(f"{path.name}: {FORMAT_NAMES[kind]}, {len(names)} dependencies")
        for name in names:
            print(f"  {'LEAK' if name in leaked else 'ok  '}  {name}")
    return leaked


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("library", type=Path)
    parser.add_argument("--quiet", action="store_true", help="only print on failure")
    args = parser.parse_args()

    if not args.library.is_file():
        print(f"error: no such file: {args.library}", file=sys.stderr)
        return 2

    try:
        leaked = check(args.library, verbose=not args.quiet)
    except ValueError as exc:
        print(f"error: {args.library}: {exc}", file=sys.stderr)
        return 2

    if leaked:
        print(file=sys.stderr)
        print(f"error: {args.library.name} is not redistributable; it still links", file=sys.stderr)
        for name in leaked:
            print(f"  {name}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
