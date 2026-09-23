#!/usr/bin/env python3
"""Package the blank starter and recorded example for the public walkthrough.

Run with --check to verify the committed downloads without rewriting them.
Only document inputs and recorded evidence are included, never live backups or caches.
"""
from __future__ import annotations

import argparse
import io
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEMOS = ROOT / "dev_docs/agents/demos"
OUTPUT = ROOT / "public/docs/assets/function_conversation"
STARTER_FILES = (
    "README.md", "AGENTS.md", "metadata.edn", "function_source_code_.kt",
    "function_tools_code_.kt", "guides/index.md", "guides/working.md",
)


def archive(name: str) -> bytes:
    folder = DEMOS / name
    if name == "function_conversation":
        files = [folder / p for p in STARTER_FILES]
    else:
        files = [folder / p for p in STARTER_FILES]
        for directory in ("revisions", "conversations", "prompts", "checks"):
            files.extend(p for p in (folder / directory).rglob("*") if p.is_file())
        files.extend((folder / "guides/conversations").glob("*.md"))
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p in sorted(set(files)):
            entry = zipfile.ZipInfo(f"{name}/{p.relative_to(folder).as_posix()}",
                                    date_time=(2026, 9, 22, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            z.writestr(entry, p.read_bytes())
    return buffer.getvalue()


def assets() -> dict[str, bytes]:
    example = DEMOS / "function_conversation_example"
    result = {name + ".zip": archive(name) for name in
              ("function_conversation", "function_conversation_example")}
    for name, source in {
        "created.png": "revisions/r3-66fd67f6fe7c-0.0-3600.0.png",
        "lag.png": "revisions/r6-deb1b1738cb0-1505.0-1530.0-vs-r5-2aa6023ddfca.png",
        "final.png": "revisions/r9-82456e1e48ac-0.0-3600.0.png",
        "final.kt": "function_source_code_.kt",
        "function_tools.kt": "function_tools_code_.kt",
        "independent-verification.json": "conversations/independent-verification.json",
    }.items():
        result[name] = (example / source).read_bytes()
    for name in ("01-create", "02-move", "03-stretch", "04-lag", "05-delete",
                 "06-warp", "07-restore-background", "08-loop-improvements"):
        result[name + ".txt"] = (example / "conversations" / (name + ".md")).read_bytes()
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    expected = assets()
    if args.check:
        stale = [name for name, data in expected.items()
                 if not (OUTPUT / name).is_file() or (OUTPUT / name).read_bytes() != data]
        if stale:
            raise SystemExit("Stale function conversation assets: " + ", ".join(stale))
        print(f"Function conversation downloads: {len(expected)} assets match their sources.")
    else:
        OUTPUT.mkdir(parents=True, exist_ok=True)
        for name, data in expected.items():
            (OUTPUT / name).write_bytes(data)
        print(f"Packaged {len(expected)} function conversation assets.")


if __name__ == "__main__":
    main()
