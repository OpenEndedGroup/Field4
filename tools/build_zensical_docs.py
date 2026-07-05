#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any

import yaml


class DocsConfig(dict):
    def __init__(self, *, config_file_path: Path, docs_dir: Path, site_dir: Path) -> None:
        super().__init__(
            docs_dir=str(docs_dir),
            site_dir=str(site_dir),
            use_directory_urls=True,
        )
        self.config_file_path = str(config_file_path)


class GeneratedFiles:
    def get_file_from_path(self, relative: str) -> None:
        del relative
        return None

    def append(self, file: Any) -> None:
        del file


def copy_non_markdown_files(source: Path, target: Path) -> None:
    for path in source.rglob("*"):
        if not path.is_file() or path.suffix == ".md":
            continue
        relative = path.relative_to(source)
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, destination)


def build_generated_config(*, public_dir: Path, generated_root: Path) -> Path:
    source_config = public_dir / "mkdocs.yml"
    with source_config.open("r", encoding="utf-8") as handle:
        config = yaml.load(handle, Loader=yaml.Loader)  # noqa: S506 - existing config uses Python tags.

    config.pop("hooks", None)
    config["docs_dir"] = os.path.relpath(generated_root / "docs", public_dir)
    config["site_dir"] = "field4"

    theme = config.get("theme")
    if isinstance(theme, dict) and "custom_dir" in theme:
        theme["custom_dir"] = str(theme["custom_dir"])

    target_config = public_dir / "zensical.generated.yml"
    with target_config.open("w", encoding="utf-8") as handle:
        yaml.dump(config, handle, sort_keys=False, allow_unicode=True)
    return target_config


def preprocess_docs(*, public_dir: Path, generated_docs: Path) -> None:
    sys.path.insert(0, str(public_dir))
    import docs_hooks  # type: ignore[import-not-found]

    source_docs = public_dir / "docs"
    config = DocsConfig(
        config_file_path=public_dir / "mkdocs.yml",
        docs_dir=source_docs,
        site_dir=public_dir / "field4",
    )
    files = GeneratedFiles()
    state = docs_hooks.ensure_state(config)

    try:
        for path in sorted(source_docs.rglob("*.md")):
            relative = path.relative_to(source_docs)
            destination = generated_docs / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            markdown = path.read_text(encoding="utf-8")
            processed = docs_hooks.process_markdown(
                markdown=markdown,
                page_path=path.resolve(),
                state=state,
                files=files,
            )
            destination.write_text(processed, encoding="utf-8")
    finally:
        docs_hooks.on_post_build(config)

    copy_non_markdown_files(source_docs, generated_docs)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Preprocess Field docs runner fences, then build with Zensical.",
    )
    parser.add_argument("--public-dir", default="public", help="Path to the public docs project.")
    parser.add_argument("--clean", action="store_true", help="Pass --clean to zensical build.")
    parser.add_argument("--strict", action="store_true", help="Pass --strict to zensical build.")
    parser.add_argument("--skip-build", action="store_true", help="Only generate preprocessed sources and config.")
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parent.parent
    public_dir = (repo_root / args.public_dir).resolve()
    generated_root = public_dir / ".zensical-generated"
    generated_docs = generated_root / "docs"

    if generated_docs.exists():
        shutil.rmtree(generated_docs)
    generated_root.mkdir(parents=True, exist_ok=True)
    generated_docs.mkdir(parents=True, exist_ok=True)

    preprocess_docs(public_dir=public_dir, generated_docs=generated_docs)
    generated_config = build_generated_config(public_dir=public_dir, generated_root=generated_root)

    if args.skip_build:
        print(f"Generated {generated_config}")
        return 0

    command = [sys.executable, "-m", "zensical", "build", "-f", str(generated_config)]
    if args.clean:
        command.append("--clean")
    if args.strict:
        command.append("--strict")
    return subprocess.run(command, cwd=public_dir, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
