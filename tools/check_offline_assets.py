#!/usr/bin/env python3
"""Verify Field's offline asset ownership after vendoring cleanup."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
JS_RESOURCES = REPO_ROOT / "src" / "jsMain" / "resources"

CANONICAL_FILES = (
    "src/commonMain/resources/offline.html",
    "src/commonMain/resources/edn-parser.js",
    "src/commonMain/resources/offline-viewer.js",
    "src/commonMain/resources/examples-gallery.html",
    "src/jsMain/resources/iosevka.css",
    "src/jsMain/resources/outputareas.css",
    "src/jsMain/resources/woff2/iosevka-regular.woff2",
    "src/jsMain/resources/woff2/iosevka-light.woff2",
    "src/jsMain/resources/woff2/iosevka-lightitalic.woff2",
    "src/jsMain/resources/woff2/iosevka-condensedlight.woff2",
    "src/jsMain/resources/woff2/iosevka-condensedlightitalic.woff2",
    "src/jsMain/resources/woff2/iosevka-bold.woff2",
    "src/jsMain/resources/woff2/iosevka-italic.woff2",
    "src/jsMain/resources/monaco/min/vs/loader.js",
    "src/jsMain/resources/monaco/min/vs/editor/editor.main.js",
    "src/jsMain/resources/monaco/min/vs/editor/editor.main.css",
    "src/jsMain/resources/monaco/min/vs/language/typescript/tsWorker.js",
)

GENERATED_TRACKED_PATHS = (
    "public/min",
    "public/woff2",
    "public/offline.html",
    "public/edn-parser.js",
    "public/offline-viewer.js",
    "public/examples-gallery.html",
    "public/iosevka-*.woff2",
    "dev_docs/min",
    "dev_docs/woff2",
    "dev_docs/offline.html",
    "dev_docs/edn-parser.js",
    "dev_docs/offline-viewer.js",
    "dev_docs/examples-gallery.html",
    "dev_docs/iosevka-*.woff2",
)

TARGET_FILES = (
    "offline.html",
    "edn-parser.js",
    "offline-viewer.js",
    "examples-gallery.html",
    "iosevka.css",
    "outputareas.css",
    "woff2/iosevka-regular.woff2",
    "min/vs/loader.js",
    "min/vs/editor/editor.main.js",
)


def repo_path(path: str) -> Path:
    return REPO_ROOT / path


def git_ls_files(patterns: tuple[str, ...]) -> list[str]:
    command = ["git", "ls-files", "--", *patterns]
    completed = subprocess.run(
        command,
        cwd=REPO_ROOT,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"git ls-files failed: {completed.stderr.strip()}")
    return [line for line in completed.stdout.splitlines() if line.strip()]


def check_target(target: Path, errors: list[str]) -> None:
    for relative in TARGET_FILES:
        path = target / relative
        if not path.exists():
            errors.append(f"generated target is missing {path}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Check that offline viewer assets have canonical sources and that "
            "generated public/dev_docs distributions have not become tracked again."
        )
    )
    parser.add_argument(
        "--target",
        action="append",
        type=Path,
        default=[],
        help="Optional generated offline viewer directory to verify, e.g. dist/offline-viewer.",
    )
    args = parser.parse_args(argv)

    errors: list[str] = []
    for relative in CANONICAL_FILES:
        path = repo_path(relative)
        if not path.exists():
            errors.append(f"canonical asset is missing: {relative}")

    extended_fonts = sorted((JS_RESOURCES / "woff2").glob("*extended*.woff2"))
    for path in extended_fonts:
        errors.append(f"extended Iosevka face should not be shipped: {repo_relative(path)}")

    iosevka_css = JS_RESOURCES / "iosevka.css"
    if iosevka_css.exists() and "extended" in iosevka_css.read_text(encoding="utf-8").lower():
        errors.append("src/jsMain/resources/iosevka.css should not register Extended faces")

    tracked_generated = git_ls_files(GENERATED_TRACKED_PATHS)
    for path in tracked_generated:
        errors.append(f"generated duplicate is tracked and should be produced by sync_offline_assets.py: {path}")

    for target in args.target:
        check_target(target if target.is_absolute() else REPO_ROOT / target, errors)

    if errors:
        print("Offline asset check failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print(
        "Offline asset check passed: canonical normal/condensed Monaco/fonts/viewer assets are present; "
        "generated public/dev_docs duplicates are untracked."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
