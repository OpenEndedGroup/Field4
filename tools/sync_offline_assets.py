#!/usr/bin/env python3
"""Synchronize Field offline viewer distributions from canonical local sources."""

from __future__ import annotations

import argparse
import json
import re
import shutil
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
COMMON_RESOURCES = REPO_ROOT / "src" / "commonMain" / "resources"
JS_RESOURCES = REPO_ROOT / "src" / "jsMain" / "resources"
EXAMPLE_DOCUMENTS = REPO_ROOT / "public" / "docs" / "assets" / "agent_transcripts"
BUNDLED_EXAMPLES = ("hello_display", "shared_types_demo")

OFFLINE_FILES = (
    "offline.html",
    "edn-parser.js",
    "offline-viewer.js",
    "offline-outputs.js",
    "offline-publications.json",
    "examples-gallery.html",
)

RESOURCE_FILES = (
    "iosevka.css",
    "outputareas.css",
)

GENERATED_DIRS = (
    "min",
    "woff2",
    "katex",
    "assets/agent_transcripts",
)


def repo_relative(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def require_path(path: Path) -> None:
    if not path.exists():
        raise FileNotFoundError(repo_relative(path))


def copy_file(source: Path, target: Path) -> None:
    require_path(source)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def replace_dir(source: Path, target: Path) -> None:
    require_path(source)
    if target.exists() or target.is_symlink():
        if target.is_dir() and not target.is_symlink():
            shutil.rmtree(target)
        else:
            target.unlink()
    shutil.copytree(source, target, symlinks=False)


def remove_stale_root_fonts(target: Path) -> None:
    for path in target.glob("iosevka-*.woff2"):
        path.unlink()


def write_readme(target: Path) -> None:
    (target / "README.md").write_text(
        """# Field4 Offline Viewer

This directory contains a static Field4 offline viewer generated from the local
repository resources. It does not need internet access.

## Quick Start

```bash
python3 -m http.server 8000
```

Then open:

```text
http://localhost:8000/offline.html?file=assets/agent_transcripts/hello_display/metadata.edn
```

## Contents

- `offline.html` - read-only Field document viewer
- `edn-parser.js` - EDN subset parser used by the viewer
- `offline-viewer.js` - viewer logic
- `examples-gallery.html` - sample document gallery
- `min/vs/` - vendored Monaco editor
- `woff2/` and `iosevka.css` - vendored Iosevka web fonts
- `assets/agent_transcripts/` - bundled documents generated from CI-executed transcripts
""",
        encoding="utf-8",
    )


def write_htaccess(target: Path) -> None:
    (target / ".htaccess").write_text(
        """# Field4 Offline Viewer Apache Configuration

<IfModule mod_deflate.c>
    AddOutputFilterByType DEFLATE text/html text/plain text/xml text/css text/javascript application/javascript application/json
</IfModule>

<IfModule mod_expires.c>
    ExpiresActive On
    ExpiresByType text/html "access plus 1 hour"
    ExpiresByType text/css "access plus 1 week"
    ExpiresByType text/javascript "access plus 1 week"
    ExpiresByType application/javascript "access plus 1 week"
    ExpiresByType font/woff2 "access plus 1 month"
</IfModule>

<IfModule mod_headers.c>
    Header set Access-Control-Allow-Origin "*"
</IfModule>
""",
        encoding="utf-8",
    )


def write_manifest(target: Path) -> None:
    payload = {
        "name": "Field4 Offline Viewer",
        "version": "1.0.0",
        "description": "Static offline documentation viewer for Field4 projects",
        "files": list(OFFLINE_FILES),
        "dependencies": {
            "monaco-editor": "bundled in min/vs/",
            "iosevka-font": "bundled in woff2/",
        },
        "requirements": {
            "browser": "Modern browser with JavaScript enabled",
            "server": "Any static HTTP server",
            "network": "No internet access required",
        },
    }
    (target / "manifest.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


GALLERY_INDEX = "offline-gallery.json"
_EDN_DRAWING = re.compile(r'"drawing" "((?:[^"\\]|\\.)*)"')


def drawing_elements(text: str) -> int:
    """Live elements in the root `drawing` (an Excalidraw scene stored as an EDN string)."""
    for match in _EDN_DRAWING.finditer(text):
        try:
            scene = json.loads(json.loads('"' + match.group(1) + '"'))
        except (ValueError, TypeError):
            continue
        if isinstance(scene, dict) and isinstance(scene.get("elements"), list):
            return sum(1 for e in scene["elements"] if isinstance(e, dict) and not e.get("isDeleted"))
    return 0


def document_summary(metadata: Path, target: Path) -> dict | None:
    """What `examples-gallery.html` lists for one document: counts read from its metadata.edn text."""
    try:
        text = metadata.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return None
    if '"boxes"' not in text and ":boxes" not in text:
        return None
    boxes = max(0, len(re.findall(r'\{(?:"id"|:id) "', text)) - (1 if '{"id" "root"' in text else 0))
    tombstones = text.count('"_tombstone" true')
    folder = metadata.parent
    name = folder.name
    shots = sorted(
        p for p in (target / "assets" / "agent_transcripts_gallery").glob(f"{name}*.png")
        if p.stem == name or p.stem.startswith(name + "_")
    ) if (target / "assets" / "agent_transcripts_gallery").is_dir() else []
    return {
        "name": name,
        "file": metadata.relative_to(target).as_posix(),
        "boxes": max(0, boxes - tombstones),
        "notes": len(re.findall(r'"notes" #afield/file', text)),
        "drawing": drawing_elements(text),
        "image": shots[0].relative_to(target).as_posix() if shots else None,
    }


def write_gallery_index(target: Path) -> int:
    """Write `offline-gallery.json`: every document under `target/assets`, for the gallery page."""
    assets = target / "assets"
    docs = []
    if assets.is_dir():
        for metadata in sorted(assets.glob("**/metadata.edn")):
            if "backups" in metadata.parts:
                continue
            summary = document_summary(metadata, target)
            if summary:
                docs.append(summary)
    (target / GALLERY_INDEX).write_text(json.dumps({"version": 1, "documents": docs}, indent=1) + "\n", encoding="utf-8")
    return len(docs)


def sync_target(target: Path, *, metadata: bool, docs: bool) -> None:
    target = target if target.is_absolute() else REPO_ROOT / target
    target.mkdir(parents=True, exist_ok=True)

    for name in OFFLINE_FILES:
        copy_file(COMMON_RESOURCES / name, target / name)

    for name in RESOURCE_FILES:
        copy_file(JS_RESOURCES / name, target / name)

    replace_dir(JS_RESOURCES / "monaco" / "min", target / "min")
    replace_dir(JS_RESOURCES / "woff2", target / "woff2")
    replace_dir(JS_RESOURCES / "katex", target / "katex")

    if metadata:
        for name in BUNDLED_EXAMPLES:
            replace_dir(EXAMPLE_DOCUMENTS / name, target / "assets" / "agent_transcripts" / name)
        # Retire the old generated sample bundle when refreshing an existing distribution.
        legacy = target / "metadata_store"
        if legacy.is_dir():
            shutil.rmtree(legacy)

    remove_stale_root_fonts(target)
    write_gallery_index(target)

    if docs:
        write_readme(target)
        write_htaccess(target)
        write_manifest(target)

    print(f"synced {repo_relative(target)}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--target",
        action="append",
        default=[],
        help="Directory to synchronize. May be repeated. Defaults to dist/offline-viewer.",
    )
    parser.add_argument(
        "--no-metadata",
        action="store_true",
        help="Do not copy the bundled example documents into the target.",
    )
    parser.add_argument(
        "--no-docs",
        action="store_true",
        help="Do not write README.md, .htaccess, and manifest.json into the target.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    targets = args.target or ["dist/offline-viewer"]
    for target in targets:
        sync_target(Path(target), metadata=not args.no_metadata, docs=not args.no_docs)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
