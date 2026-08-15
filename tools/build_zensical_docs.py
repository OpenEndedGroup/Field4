#!/usr/bin/env python3
from __future__ import annotations

import argparse
import fnmatch
import logging
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

import yaml

from verification_runtime import verification_lock


def ceres_library_path(repo_root: Path) -> Path:
    """Where `afield.stdlib.ceres.CeresNativeLibrary.defaultPath` will look, from Python.

    Kept deliberately in step with that object: the override properties, the three
    platform file names, and the `build/ken-ceres-redistributable` default are the
    same three rules in the same order. `-Dafield.ceres.library` is a JVM system
    property this process cannot see, so only the environment override is honoured
    here; a run that sets the property and not the variable gets a false alarm it
    can silence by exporting `AFIELD_CERES_LIBRARY` too.
    """
    override = os.environ.get("AFIELD_CERES_LIBRARY", "").strip()
    if override:
        return Path(override)
    system = sys.platform
    if system.startswith("win"):
        name = "ken_ceres_jvm.dll"
    elif system == "darwin":
        name = "libken_ceres_jvm.dylib"
    else:
        name = "libken_ceres_jvm.so"
    return repo_root / "build" / "ken-ceres-redistributable" / name


def manifold_library_path(repo_root: Path) -> Path:
    """Where `afield.manifold.ManifoldLibrary` will look, from Python.

    Deliberately shaped differently from [ceres_library_path], because the two
    optional native dependencies are: the Ceres wrapper is built into `build/` by a
    task in this repo, so there is exactly one path to check. Manifold is installed
    by a package manager, so this walks the same candidate list `ManifoldLibrary`
    does and returns the first hit — falling back to the first candidate so the
    "missing" message names a plausible place rather than nothing. `repo_root` is
    unused for the same reason, and is kept so every entry in
    NATIVE_PREREQUISITES has one signature.
    """
    override = os.environ.get("AFIELD_MANIFOLD_LIBRARY", "").strip()
    if override:
        return Path(override)
    if sys.platform.startswith("win"):
        name, roots = "manifoldc.dll", ["C:\\Program Files\\manifold\\bin"]
    elif sys.platform == "darwin":
        name, roots = "libmanifoldc.dylib", ["/opt/homebrew/lib", "/usr/local/lib"]
    else:
        name, roots = "libmanifoldc.so", [
            "/usr/local/lib",
            "/usr/lib",
            "/usr/lib/x86_64-linux-gnu",
            "/usr/lib/aarch64-linux-gnu",
        ]
    candidates = [Path(root, name) for root in roots]
    return next((c for c in candidates if c.exists()), candidates[0])


# A capture that cannot run without a native artifact declares it with `requires="<key>"`
# on its fence. Each key maps to where the artifact lives and the one command that builds
# it. Nothing else reads the attribute -- docs_hooks ignores unknown fence attributes, so
# adding one does not change a fence's capture digest.
NATIVE_PREREQUISITES: dict[str, tuple[Any, str]] = {
    "ceres": (ceres_library_path, "./gradlew buildCeresRedistributable"),
    "manifold": (manifold_library_path, "brew install manifold"),
}

REQUIRES_ATTR_RE = re.compile(r'(?m)^```\w*_?runner\b[^\n]*?\brequires="([^"]*)"')


def check_native_prerequisites(
    *,
    public_dir: Path,
    repo_root: Path,
    page_filters: tuple[str, ...],
    runner_mode: str,
) -> None:
    """Fail in a second on a missing native artifact, instead of 38 minutes in.

    `clean` deletes `build/`, and the Ceres wrapper that lives there is an optional
    extra that no task in the docs path declares a dependency on -- so a clean tree
    (including every fresh clone) used to run 311 captures and then die on the 312th
    with the FFM linker's "Cannot open library". The artifact was never going to
    appear in between, so there was nothing to learn from those 38 minutes.

    Only a full recapture is gated. Under `incremental` the committed asset is
    normally reused and Ceres is genuinely not needed, so a missing artifact is a
    warning there: blocking it would stop a fresh clone from building the docs at all,
    which is the same class of mistake in the other direction.
    """
    if runner_mode == "never":
        return
    source_docs = public_dir / "docs"
    needed: dict[str, list[str]] = {}
    for path in sorted(source_docs.rglob("*.md")):
        relative = path.relative_to(source_docs)
        if page_filters and not matches_page_filter(relative, page_filters):
            continue
        for match in REQUIRES_ATTR_RE.finditer(path.read_text(encoding="utf-8")):
            for raw_key in match.group(1).split(","):
                key = raw_key.strip().lower()
                if not key:
                    continue
                if key not in NATIVE_PREREQUISITES:
                    raise RuntimeError(
                        f"{relative} declares requires=\"{key}\", which is not a known native "
                        f"prerequisite. Known: {', '.join(sorted(NATIVE_PREREQUISITES))}."
                    )
                needed.setdefault(key, [])
                if relative.as_posix() not in needed[key]:
                    needed[key].append(relative.as_posix())

    missing: list[str] = []
    for key, pages in sorted(needed.items()):
        resolve, how_to_build = NATIVE_PREREQUISITES[key]
        artifact = resolve(repo_root)
        if artifact.exists():
            continue
        missing.append(
            f"  {key}: no artifact at {artifact}\n"
            f"    needed by: {', '.join(pages)}\n"
            f"    build it with: {how_to_build}"
        )
    if not missing:
        return
    detail = "\n".join(missing)
    if runner_mode != "always":
        print(
            "Warning: a native prerequisite is missing. Captures that need it will fail if the "
            f"cache does not cover them:\n{detail}",
            file=sys.stderr,
        )
        return
    raise RuntimeError(
        "This is a full recapture (FIELD_DOCS_RUNNER_MODE=always) and a native prerequisite is "
        f"missing, so a capture will certainly fail:\n{detail}"
    )


def _configure_logging() -> None:
    """Surface the docs runner's per-capture progress.

    The runner logs rich progress through `logging.getLogger("zensical.field_runner")`
    at INFO, but running the preprocessor standalone leaves the root logger
    unconfigured, so only WARNINGs reach the terminal and a build looks
    silent/hung between the sparse warnings. Configure a concise INFO handler once
    (respect FIELD_DOCS_LOG_LEVEL for override) so `capturing ... / completed ...`
    lines are visible.
    """
    if logging.getLogger().handlers:
        return
    level = os.environ.get("FIELD_DOCS_LOG_LEVEL", "INFO").upper()
    logging.basicConfig(
        level=getattr(logging, level, logging.INFO),
        format="%(asctime)s %(message)s",
        datefmt="%H:%M:%S",
    )


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


def matches_page_filter(relative: Path, filters: tuple[str, ...]) -> bool:
    rel = relative.as_posix()
    stem = relative.stem
    name = relative.name
    for raw_filter in filters:
        page_filter = raw_filter.strip().replace("\\", "/")
        if not page_filter:
            continue
        if page_filter in {rel, name, stem}:
            return True
        if "/" not in page_filter and "." not in page_filter and page_filter == stem:
            return True
        if fnmatch.fnmatch(rel, page_filter) or fnmatch.fnmatch(name, page_filter):
            return True
    return False


def build_generated_config(*, public_dir: Path, generated_root: Path) -> Path:
    source_config = public_dir / "zensical.yml"
    with source_config.open("r", encoding="utf-8") as handle:
        config = yaml.load(handle, Loader=yaml.Loader)  # noqa: S506 - existing config uses Python tags.

    config["docs_dir"] = os.path.relpath(generated_root / "docs", public_dir)
    config["site_dir"] = "field4"

    theme = config.get("theme")
    if isinstance(theme, dict) and "custom_dir" in theme:
        theme["custom_dir"] = str(theme["custom_dir"])

    target_config = public_dir / "zensical.generated.yml"
    with target_config.open("w", encoding="utf-8") as handle:
        yaml.dump(config, handle, sort_keys=False, allow_unicode=True)
    return target_config


def preprocess_docs(*, public_dir: Path, generated_docs: Path, page_filters: tuple[str, ...] = ()) -> None:
    _configure_logging()
    sys.path.insert(0, str(public_dir))
    import docs_hooks  # type: ignore[import-not-found]

    source_docs = public_dir / "docs"
    config = DocsConfig(
        config_file_path=public_dir / "zensical.yml",
        docs_dir=source_docs,
        site_dir=public_dir / "field4",
    )
    files = GeneratedFiles()
    state = docs_hooks.ensure_state(config)
    runner_mode = state.runner_mode
    matched_pages = 0
    total_pages = 0

    try:
        for path in sorted(source_docs.rglob("*.md")):
            relative = path.relative_to(source_docs)
            destination = generated_docs / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            markdown = path.read_text(encoding="utf-8")
            total_pages += 1
            page_matches = not page_filters or matches_page_filter(relative, page_filters)
            if page_matches:
                matched_pages += 1

            previous_mode = state.runner_mode
            if page_filters and not page_matches:
                state.runner_mode = "never"
            try:
                processed = docs_hooks.process_markdown(
                    markdown=markdown,
                    page_path=path.resolve(),
                    state=state,
                    files=files,
                )
            finally:
                state.runner_mode = previous_mode
            destination.write_text(processed, encoding="utf-8")
    finally:
        state.runner_mode = runner_mode
        docs_hooks.on_post_build(config)

    if page_filters and matched_pages == 0:
        requested = ", ".join(page_filters)
        raise RuntimeError(f"No docs pages matched --page filter(s): {requested}")
    if page_filters:
        print(f"Processed {matched_pages}/{total_pages} docs page(s) with runner mode {runner_mode}; non-matching pages used runner mode never.")

    copy_non_markdown_files(source_docs, generated_docs)


def emit_offline_viewer(*, site_dir: Path) -> None:
    """Copy the offline document viewer into the built site.

    The docs embed a read-only Field viewer via `<iframe src="/offline.html?...">`.
    `offline.html` and its bundle (Monaco, fonts, the viewer JS) are otherwise only
    served by the running Field app, so a plain static serving of the built site
    would 404 the iframe. Emitting the bundle into the site dir makes the viewer
    self-contained: `/offline.html` and its relative assets resolve against the
    site root, and the viewer resolves `?file=` paths relative to that same root.

    Reuses `tools/sync_offline_assets.py` so the canonical source files stay the
    single source of truth. Metadata examples and the distribution README/manifest
    are skipped -- the docs carry their own `assets/*/metadata.edn` files.
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import sync_offline_assets  # type: ignore[import-not-found]

    sync_offline_assets.sync_target(site_dir, metadata=False, docs=False)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Preprocess Field docs runner fences, then build with Zensical.",
    )
    parser.add_argument("--public-dir", default="public", help="Path to the public docs project.")
    parser.add_argument("--clean", action="store_true", help="Pass --clean to zensical build.")
    parser.add_argument("--strict", action="store_true", help="Pass --strict to zensical build.")
    parser.add_argument("--skip-build", action="store_true", help="Only generate preprocessed sources and config.")
    parser.add_argument(
        "--page",
        action="append",
        default=[],
        metavar="PATTERN",
        help=(
            "Only run capture-enabled docs hooks for matching markdown pages. "
            "Non-matching pages are still generated, but with runner mode never. "
            "Matches relative paths, filenames, stems, or shell globs; may be repeated."
        ),
    )
    return parser.parse_args()


def run(args: argparse.Namespace) -> int:

    repo_root = Path(__file__).resolve().parent.parent
    public_dir = (repo_root / args.public_dir).resolve()
    generated_root = public_dir / ".zensical-generated"
    generated_docs = generated_root / "docs"

    if generated_docs.exists():
        # macOS (Finder/Spotlight) can re-create a .DS_Store inside a directory between
        # rmtree clearing its contents and the final rmdir, so rmdir fails with
        # "Directory not empty". Retry a few times; each pass clears whatever reappeared.
        for attempt in range(5):
            try:
                shutil.rmtree(generated_docs)
                break
            except OSError:
                if not generated_docs.exists():
                    break
                if attempt == 4:
                    raise
                time.sleep(0.1)
    generated_root.mkdir(parents=True, exist_ok=True)
    generated_docs.mkdir(parents=True, exist_ok=True)

    sys.path.insert(0, str(public_dir))
    import docs_hooks  # type: ignore[import-not-found]

    check_native_prerequisites(
        public_dir=public_dir,
        repo_root=repo_root,
        page_filters=tuple(args.page),
        runner_mode=docs_hooks.resolve_runner_mode(os.environ.get(docs_hooks.RUNNER_MODE_ENV)),
    )

    preprocess_docs(public_dir=public_dir, generated_docs=generated_docs, page_filters=tuple(args.page))
    generated_config = build_generated_config(public_dir=public_dir, generated_root=generated_root)

    # A referenced-but-untracked asset renders fine in this warm tree and 404s on a clean
    # checkout. Warn on every build; under --strict (CI / pre-merge) it gates.
    asset_check = subprocess.run(
        [sys.executable, str(Path(__file__).resolve().parent / "check_doc_assets.py")],
        cwd=repo_root,
        check=False,
    ).returncode
    if asset_check != 0 and args.strict:
        print("Doc asset check failed (see above). Failing because --strict.", file=sys.stderr)
        return asset_check

    if args.skip_build:
        print(f"Generated {generated_config}")
        return 0

    if args.clean:
        site_dir = public_dir / "field4"
        if site_dir.exists():
            shutil.rmtree(site_dir)

    command = [sys.executable, "-m", "zensical", "build", "-f", str(generated_config)]
    if args.strict:
        command.append("--strict")
    returncode = subprocess.run(command, cwd=public_dir, check=False).returncode
    if returncode == 0:
        emit_offline_viewer(site_dir=public_dir / "field4")
    return returncode


def main() -> int:
    args = parse_args()
    repo_root = Path(__file__).resolve().parent.parent
    with verification_lock(repo_root, label="Zensical docs build", progress=lambda message: print(message, flush=True)):
        return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
