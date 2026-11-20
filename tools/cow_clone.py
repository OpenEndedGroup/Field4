"""Copy-on-write file clones, and APFS's count of the bytes a file does not share.

A clone (APFS `clonefile`, the `FICLONE` ioctl on btrfs/XFS) shares its source's blocks until one of
them is written, so it costs metadata, not bytes, and writing either file never changes the other.
`tools/build_zensical_docs.py` clones the 4.8 GiB of `public/docs/` assets instead of copying them,
and `tools/worktree_seed.py` makes a new worktree's tracked files clones of the primary checkout's.

`du` counts a clone as a full copy. The bytes that deleting a file would free are
`ATTR_CMNEXT_PRIVATESIZE` (`private_bytes`), which is how both of those are measured.

Stdlib only, and Python 3.9 compatible: the routines' shell scripts run it with whatever `python3`
launchd's PATH finds, which can be the system 3.9.
"""

from __future__ import annotations

import errno
import os
import shutil
import struct
import sys
from pathlib import Path
from typing import Any, Iterable, Tuple

_FICLONE = 0x40049409  # linux/fs.h
_clonefile_fn: Any = None


def _darwin_clonefile() -> Any:
    global _clonefile_fn
    if _clonefile_fn is None:
        import ctypes  # noqa: PLC0415

        function = ctypes.CDLL(None, use_errno=True).clonefile
        function.argtypes = (ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint32)
        function.restype = ctypes.c_int
        _clonefile_fn = (function, ctypes.get_errno)
    return _clonefile_fn


def try_clone(source: str | os.PathLike[str], destination: str | os.PathLike[str]) -> bool:
    """Make [destination] a clone of [source], or do nothing and return False.

    Never falls back to a copy. Replaces an existing destination file; the clone carries the
    source's mode, times and extended attributes, as `clonefile` does.
    """
    if sys.platform == "darwin":
        clonefile, get_errno = _darwin_clonefile()
        source_bytes, destination_bytes = os.fsencode(source), os.fsencode(destination)
        if clonefile(source_bytes, destination_bytes, 0) == 0:
            return True
        if get_errno() == errno.EEXIST and os.path.isfile(destination):
            os.unlink(destination)  # clonefile will not replace a file; copy2 does
            return clonefile(source_bytes, destination_bytes, 0) == 0
        return False
    if sys.platform.startswith("linux"):
        import fcntl  # noqa: PLC0415

        try:
            with open(source, "rb") as reader, open(destination, "wb") as writer:
                fcntl.ioctl(writer.fileno(), _FICLONE, reader.fileno())
        except OSError:
            try:
                os.unlink(destination)
            except OSError:
                pass
            return False
        shutil.copystat(source, destination)
        return True
    return False


def clone_file(source: str | os.PathLike[str], destination: str | os.PathLike[str]) -> bool:
    """Copy [source] to [destination] as `shutil.copy2` does, as a clone where possible.

    True when the copy is a clone. Like `copy2` it follows a symlinked source, replaces an existing
    destination, and keeps the source's mode and times. Fits `shutil.copytree(copy_function=...)`.
    """
    if try_clone(source, destination):
        return True
    shutil.copy2(source, destination)
    return False


def private_bytes(path: str | os.PathLike[str]) -> int | None:
    """APFS's count of [path]'s bytes that no clone shares (sys/attr.h, macOS 10.15+).

    None where the filesystem does not report it (not macOS, not APFS).
    """
    if sys.platform != "darwin":
        return None
    import ctypes  # noqa: PLC0415

    class _AttrList(ctypes.Structure):
        _fields_ = [("bitmapcount", ctypes.c_ushort), ("reserved", ctypes.c_uint16),
                    ("commonattr", ctypes.c_uint32), ("volattr", ctypes.c_uint32),
                    ("dirattr", ctypes.c_uint32), ("fileattr", ctypes.c_uint32),
                    ("forkattr", ctypes.c_uint32)]

    libc = ctypes.CDLL(None, use_errno=True)
    attrs = _AttrList(5, 0, 0, 0, 0, 0, 0x00000008)  # ATTR_CMNEXT_PRIVATESIZE
    buffer = ctypes.create_string_buffer(32)
    # FSOPT_ATTR_CMN_EXTENDED | FSOPT_NOFOLLOW
    if libc.getattrlist(os.fsencode(path), ctypes.byref(attrs), buffer, len(buffer), 0x20 | 0x1) != 0:
        return None
    length, private = struct.unpack_from("<Iq", buffer.raw)
    return private if length >= 12 else None


def tree_bytes(paths: Iterable[str | os.PathLike[str]]) -> Tuple[int, int, int]:
    """(files, logical bytes, private bytes) of every regular file under [paths], symlinks not
    followed. A file whose private size cannot be read counts as wholly private."""
    files = logical = private = 0
    for root in paths:
        root = Path(root)
        walk = [root] if root.is_file() else (p for p in root.rglob("*"))
        for path in walk:
            if path.is_symlink() or not path.is_file():
                continue
            size = path.stat().st_size
            unshared = private_bytes(path)
            files += 1
            logical += size
            private += size if unshared is None else unshared
    return files, logical, private
