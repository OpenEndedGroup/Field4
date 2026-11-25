#!/usr/bin/env python3
"""Fail when an optional native library is missing from the welcome page's registry.

Every optional native library Field loads has a locator object that reads an override such as
`System.getProperty("afield.csound.library")` or `System.getenv("AFIELD_LORIS_EXECUTABLE")`. The
welcome page's Optional native libraries panel lists what `afield.runtime.NativeLibraries`
registers, so a locator that is not registered there never shows up on the page, and its users
never learn it exists or how to build it.

For each `.kt` file under `src/jvmMain/kotlin` that reads such an override, this requires the file
to declare an object implementing `NativeCapability` (or `NativeGeometryCapability`) and that
object to be registered in `NativeLibraries.kt`. A library that is not optional, or that ships
with Field and only takes an override, goes in EXEMPT with the reason.

    uv run python tools/check_native_capability_registry.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = Path("src/jvmMain/kotlin")
REGISTRY = KOTLIN / "afield/runtime/NativeLibraries.kt"

# Library token (lowercased, separators dropped) -> why it is not on the welcome page.
EXEMPT = {
    "joltjni": "ships inside the jolt-jni Maven jar; the property only overrides the bundled copy",
    "vulkan": "not optional; the welcome page's Vulkan panel reports it",
    "python": "an interpreter, not a library; the welcome page's Python panel chooses it",
}

OVERRIDE = re.compile(
    r'System\.(?:getProperty|getenv)\(\s*"(?:afield\.(?P<prop>[A-Za-z0-9]+)\.(?:library|executable)'
    r'|AFIELD_(?P<env>[A-Z0-9_]+?)_(?:LIBRARY|EXECUTABLE))"'
)
CAPABILITY_OBJECT = re.compile(r"\bobject\s+(\w+)\s*:\s*[^{]*\bNative(?:Geometry)?Capability\b")
REGISTERED = re.compile(r'register\(\s*"\w+"\s*\)\s*\{\s*([\w.]+)\s*\}')


def token(match: re.Match[str]) -> str:
    raw = match.group("prop") or match.group("env") or ""
    return raw.replace("_", "").lower()


def registered_objects(repo: Path) -> set[str]:
    text = (repo / REGISTRY).read_text(encoding="utf-8")
    return {name.rsplit(".", 1)[-1] for name in REGISTERED.findall(text)}


def violations(repo: Path) -> list[str]:
    registered = registered_objects(repo)
    problems: list[str] = []
    for path in sorted((repo / KOTLIN).rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        reads = [m for m in OVERRIDE.finditer(text) if token(m) not in EXEMPT]
        if not reads:
            continue
        rel = path.relative_to(repo)
        line = text.count("\n", 0, reads[0].start()) + 1
        objects = CAPABILITY_OBJECT.findall(text)
        if not objects:
            problems.append(
                f"{rel}:{line}: reads {reads[0].group(0).split('(', 1)[1].strip()} but declares no object implementing "
                "NativeCapability; make its locator one and register it in NativeLibraries, or add it to EXEMPT "
                "in tools/check_native_capability_registry.py with the reason"
            )
            continue
        missing = [o for o in objects if o not in registered]
        if missing:
            problems.append(
                f"{rel}:{line}: {', '.join(missing)} implements NativeCapability but is not registered in "
                f"{REGISTRY}, so the welcome page never lists it"
            )
    return problems


def main() -> int:
    problems = violations(ROOT)
    for problem in problems:
        print(problem, file=sys.stderr)
    if problems:
        print(f"{len(problems)} optional native librar{'y is' if len(problems) == 1 else 'ies are'} "
              "missing from the welcome page's registry.", file=sys.stderr)
        return 1
    print(f"Every optional native library locator is registered ({len(registered_objects(ROOT))} in NativeLibraries).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
