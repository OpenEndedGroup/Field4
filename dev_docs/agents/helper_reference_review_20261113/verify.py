"""Verify the selected helper links and preserve every published fence byte for byte.

Run from the repository root with ``uv run python <this file>``. This checks source
links; ``tools/check_doc_site_links.py`` checks their rendered HTML destinations.
"""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent


def fenced_blocks(text: str) -> list[str]:
    blocks = []
    current = []
    marker = None
    for line in text.splitlines(keepends=True):
        match = re.match(r"^\s*(`{3,}|~{3,})", line)
        if marker is None:
            if match:
                marker = match[1]
                current = [line]
        else:
            current.append(line)
            if re.match(r"^\s*" + re.escape(marker) + r"[ \t]*$", line.rstrip("\r\n")):
                blocks.append("".join(current))
                current = []
                marker = None
    if current:
        blocks.append("".join(current))
    return blocks


def section(text: str, anchor: str) -> str:
    if not anchor:
        return text
    start = re.search(r"^###\s+`?" + re.escape(anchor) + r"`?[ \t]*$", text, re.M)
    assert start, anchor
    tail = text[start.end():]
    end = re.search(r"^###?\s", tail, re.M)
    return tail[:end.start()] if end else tail


def verify() -> dict:
    scope = json.loads((HERE / "scope.json").read_text())
    selection = json.loads((HERE / "selection.json").read_text())
    pages = {page.partition("#")[0] for row in selection for page in row["pages"]}
    preserved = []
    for name in sorted(pages):
        old = subprocess.check_output(["git", "show", scope["head"] + ":" + name], cwd=ROOT, text=True)
        current = (ROOT / name).read_text()
        before, after = fenced_blocks(old), fenced_blocks(current)
        assert before == after, f"fenced content or capture declaration changed: {name}"
        digest = hashlib.sha256("".join(after).encode()).hexdigest()
        preserved.append(dict(path=name, fences=len(after), sha256=digest))
    checked = set()
    for row in selection:
        for page in row["pages"]:
            name, _, anchor = page.partition("#")
            prose = section((ROOT / name).read_text(), anchor)
            for block in fenced_blocks(prose):
                prose = prose.replace(block, "")
            for field in ["classTarget", "methodTarget"]:
                target = row[field]
                assert target in prose, f"missing {target} in {page}"
                file, fragment = target.split("#")
                reference = (ROOT / "public/docs" / file).read_text()
                assert f'id="{fragment}"' in reference or re.search(
                    r"\{\s*#" + re.escape(fragment) + r"\s*\}", reference
                ), target
                checked.add((page, target))
    return dict(
        baseline=scope["head"], helpers=len({row["type"] for row in selection}),
        selectedMethods=len(selection), pages=len(pages), checkedDestinations=len(checked),
        fences=sum(row["fences"] for row in preserved), preserved=preserved,
    )


if __name__ == "__main__":
    result = verify()
    (HERE / "verification.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({key: value for key, value in result.items() if key != "preserved"}))
