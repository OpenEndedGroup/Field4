#!/usr/bin/env python3
"""Regenerate the default-shortcut table in `public/docs/keybindings.md` from a running Field.

The table has to come from Field's own command inventory rather than from a hand-kept list: the
inventory is what generates `~/.afield/keybindings.yaml` in the first place, so anything typed by
hand here could disagree with the file a reader is actually looking at.

How it gets it: attach a browser to Field (that is what reports the inventory), then ask the server
to write a *fresh* `keybindings.yaml` at a scratch path — `UserKeybindingsManager.watchFile` on a
file that does not exist regenerates it exactly as it would a missing one. Nothing touches
`~/.afield`. The scratch file is then read back and turned into the table.

    python3 tools/keybindings_defaults_table.py --port 9633

Writes between the BEGIN/END markers in the page and leaves everything else alone.
"""

from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
BACKDOOR = REPO_ROOT / "tools" / "field_backdoor.py"
DEFAULT_PAGE = REPO_ROOT / "public" / "docs" / "keybindings.md"
BEGIN = "<!-- BEGIN generated: default bindings -->"
END = "<!-- END generated: default bindings -->"

# `#scope canvas` / `#scope editor` trail the label comment on a generated line.
ENTRY = re.compile(r"^(?P<indent>\s*)(?P<key>\"[^\"]+\"|[^\s:#][^:]*):\s*(?P<value>[^#]*?)\s*(?:#\s*(?P<comment>.*))?$")


def drive_script(scratch: Path) -> str:
    """Kotlin run inside Field: wait for the inventory, regenerate, hand the watcher back."""
    path = str(scratch).replace("\\", "\\\\").replace('"', '\\"')
    return "\n".join(
        [
            # A good half of the inventory only exists while boxes are selected — the box actions the
            # server declares, `-copy`, `connect`/`disconnect`, the Set Main Language family. Select
            # the two boxes the transcript just made, so the reported list is the complete one.
            'ui.eval("var a=(window.lastActions||[]).find(x=>x.id===\'select-all-boxes\');'
            'if(a)a.run();return (window.selectedList||[]).length")',
            "ui.pause(1000)",
            'println("keybindings-table: selected " + ui.eval("return (window.selectedList||[]).length"))',
            # The client reports its inventory 1.2s after it registers its actions; the palette and
            # the editor both have to have registered before the list is complete.
            "ui.pause(4000)",
            f'val scratch = java.io.File("{path}")',
            "scratch.delete()",
            "afield.UserKeybindingsManager.watchFile(scratch)",
            "ui.pause(500)",
            "afield.UserKeybindingsManager.watchFile(null)",
            'println("keybindings-table: wrote " + scratch.length() + " bytes")',
        ]
    )


def setup_operations() -> list[dict[str, object]]:
    """Two connected boxes, so the selection-only half of the command inventory registers."""
    return [
        {
            "create": {
                "box": "keybindings_table_a",
                "name": "Keybindings inventory A",
                "frame": {"x": 40.0, "y": 40.0, "w": 220.0, "h": 100.0},
                "properties": {"code": "// present so that selection-scoped commands register\n"},
            }
        },
        {
            "create": {
                "box": "keybindings_table_b",
                "name": "Keybindings inventory B",
                "frame": {"x": 320.0, "y": 40.0, "w": 220.0, "h": 100.0},
                "properties": {"code": "// a second box, so `connect` and `disconnect` register too\n"},
            }
        },
    ]


def generate_yaml(*, port: int, timeout: float) -> str:
    scratch = Path(tempfile.mkdtemp(prefix="field-keybindings-table-")) / "keybindings.yaml"
    transcript = {
        "name": "keybindings_defaults_table",
        "description": "Regenerate a pristine keybindings.yaml from the live command inventory.",
        "operations": setup_operations() + [{"drive": {"script": drive_script(scratch)}}],
    }
    command = [
        sys.executable,
        str(BACKDOOR),
        "--json",
        "--with-playwright",
        "--scratch",
        "--port",
        str(port),
        "--timeout",
        str(timeout),
        "--command",
        shlex.join(["macro", "write", "keybindings_defaults_table", "--content", json.dumps(transcript, separators=(",", ":")), "--timeout", str(timeout)]),
        "--command",
        shlex.join(["macro", "run", "keybindings_defaults_table", "--timeout", str(timeout)]),
    ]
    print("$ " + shlex.join(command), file=sys.stderr)
    completed = subprocess.run(command, cwd=REPO_ROOT, capture_output=True, text=True, timeout=timeout + 300)
    if completed.returncode != 0:
        sys.stderr.write(completed.stdout)
        sys.stderr.write(completed.stderr)
        raise SystemExit(f"backdoor failed with exit {completed.returncode}")
    if not scratch.exists():
        sys.stderr.write(completed.stdout[-4000:])
        raise SystemExit(
            f"Field did not write {scratch}. The usual cause is that no browser client reported a "
            "command inventory — check that --with-playwright attached."
        )
    return scratch.read_text(encoding="utf-8")


def parse_entries(text: str) -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    group = ""
    for raw in text.splitlines():
        line = raw.rstrip()
        if not line.strip():
            continue
        stripped = line.strip()
        if stripped.startswith("#"):
            heading = re.match(r"#\s*──\s*(?P<name>[A-Za-z ]+?)\s*─+$", stripped)
            if heading:
                group = heading.group("name").strip()
            continue
        match = ENTRY.match(line)
        if not match or not match.group("indent"):
            continue
        key = match.group("key").strip().strip('"')
        if key in {"bindings", ""}:
            continue
        value = (match.group("value") or "").strip()
        comment = (match.group("comment") or "").strip()
        scope = ""
        scope_match = re.search(r"#scope\s+(\w+)", comment)
        if scope_match:
            scope = scope_match.group(1)
            comment = comment[: scope_match.start()].strip()
        rows.append({"id": key, "value": value, "label": comment, "scope": scope, "group": group or "Other"})
    return rows


def unquote(value: str) -> str:
    """Strip *matching* YAML quotes, and only those.

    `strip("\"'")` is wrong here: it strips those characters from both ends regardless of pairing, so
    a spec whose key *is* a quote character loses it — `"cmd+'"` came out as `cmd+` and the published
    table advertised a shortcut that does not exist.
    """
    value = value.strip()
    for quote in ('"', "'"):
        if len(value) >= 2 and value[0] == quote and value[-1] == quote:
            return value[1:-1]
    return value


def format_specs(value: str) -> str:
    value = value.strip()
    if value in {'""', "''", ""}:
        return "*(none)*"
    if value.startswith("["):
        parts = [unquote(p) for p in value.strip("[]").split(",")]
    else:
        parts = [unquote(value)]
    return " or ".join(f"`{p}`" for p in parts if p)


def render_table(rows: list[dict[str, str]]) -> str:
    order = ["Global", "Canvas", "Editor", "Bookmarks", "Other"]
    by_group: dict[str, list[dict[str, str]]] = {}
    for row in rows:
        by_group.setdefault(row["group"], []).append(row)
    out: list[str] = []
    bound = sum(1 for r in rows if format_specs(r["value"]) != "*(none)*")
    out.append(
        f"**{len(rows)} rebindable commands**, {bound} of them with a shortcut out of the box. "
        f"Regenerate this table against a running Field with `tools/keybindings_defaults_table.py`."
    )
    out.append("")
    for group in order + [g for g in sorted(by_group) if g not in order]:
        group_rows = by_group.get(group)
        if not group_rows:
            continue
        out.append(f"### {group}")
        out.append("")
        out.append("| Command id | Default | Where | What it does |")
        out.append("| --- | --- | --- | --- |")
        for row in sorted(group_rows, key=lambda r: r["id"]):
            where = {"canvas": "canvas", "editor": "editor"}.get(row["scope"], "both")
            label = row["label"].replace("|", "\\|") or "—"
            out.append(f"| `{row['id']}` | {format_specs(row['value'])} | {where} | {label} |")
        out.append("")
    return "\n".join(out).rstrip() + "\n"


def splice(page: Path, table: str) -> bool:
    text = page.read_text(encoding="utf-8")
    if BEGIN not in text or END not in text:
        raise SystemExit(f"{page} has no {BEGIN} / {END} markers")
    head, rest = text.split(BEGIN, 1)
    _, tail = rest.split(END, 1)
    updated = f"{head}{BEGIN}\n\n{table}\n{END}{tail}"
    if updated == text:
        return False
    page.write_text(updated, encoding="utf-8")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=9633)
    parser.add_argument("--timeout", type=float, default=240.0)
    parser.add_argument("--page", type=Path, default=DEFAULT_PAGE)
    parser.add_argument("--yaml", type=Path, help="also keep a copy of the generated keybindings.yaml here")
    parser.add_argument("--from-yaml", type=Path, help="format an already-generated file instead of running Field")
    args = parser.parse_args()

    text = args.from_yaml.read_text(encoding="utf-8") if args.from_yaml else generate_yaml(port=args.port, timeout=args.timeout)
    if args.yaml:
        args.yaml.write_text(text, encoding="utf-8")
    rows = parse_entries(text)
    if len(rows) < 20:
        raise SystemExit(f"only {len(rows)} commands parsed out of the generated file — that cannot be right")
    changed = splice(args.page, render_table(rows))
    print(f"{len(rows)} commands -> {args.page} ({'updated' if changed else 'unchanged'})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
