"""The shortcut table on public/docs/tour_interface.md matches the shortcuts Field ships with.

Each row names a command id in backticks and its default shortcut(s). The defaults are read from
the source that registers the command, in any of its three spellings:
`id = "x"` ... `defaults = listOf("a", "b")` (KeybindingsClient.kt), `x.id = "x"` ... `x.keys = "a"`,
and `id:'x'` ... `keys:'a'` (the command tables in commands.kt). `document-search` is not a command:
documentSearch.kt matches cmd/ctrl+F itself, so that row is checked against that code.
"""
from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAGE = ROOT / "public/docs/tour_interface.md"
SOURCES = ROOT / "src/jsMain/kotlin"

ROW = re.compile(r"^\|[^|]+\|\s*`([a-z0-9-]+)`\s*\|\s*(.+?)\s*\|\s*$")
ID_DECL = re.compile(r"""(?:\bid\s*=\s*"([a-z0-9$-]+)"|\bid\s*:\s*'([a-z0-9$-]+)')""")
KEYS = re.compile(r"""(?:\bkeys\s*=\s*"([^"]*)"|\bkeys\s*:\s*'([^']*)'|\bdefaults\s*=\s*listOf\(([^)]*)\))""")


def page_rows() -> dict[str, list[str]]:
    rows = {}
    for line in PAGE.read_text().splitlines():
        m = ROW.match(line)
        if not m:
            continue
        cell = m.group(2)
        keys = [] if cell.strip() == "none" else re.findall(r"`([^`]+)`", cell)
        rows[m.group(1)] = keys
    return rows


def shipped_defaults() -> dict[str, set[str]]:
    found: dict[str, set[str]] = {}
    for path in SOURCES.rglob("*.kt"):
        lines = path.read_text().splitlines()
        for i, line in enumerate(lines):
            for m in ID_DECL.finditer(line):
                cid = m.group(1) or m.group(2)
                keys = found.setdefault(cid, set())
                # The keys belong to this declaration until the next id declaration.
                for later in lines[i:i + 14]:
                    if later is not line and ID_DECL.search(later):
                        break
                    for k in KEYS.finditer(later):
                        if k.group(3) is not None:
                            keys.update(re.findall(r'"([^"]+)"', k.group(3)))
                        elif (k.group(1) or k.group(2)):
                            keys.add(k.group(1) or k.group(2))
    return found


class TourInterfaceShortcutsTest(unittest.TestCase):
    def test_table_matches_the_shipped_defaults(self):
        rows = page_rows()
        self.assertGreater(len(rows), 10, "the shortcut table was not found on the page")
        shipped = shipped_defaults()
        for cid, keys in rows.items():
            if cid == "document-search":
                text = (SOURCES / "afield/app/client/documentSearch.kt").read_text()
                self.assertIn('key == "f" && hasModifier', text, "document search no longer opens on cmd/ctrl+F")
                self.assertEqual(keys, ["cmd+f"])
                continue
            with self.subTest(command=cid):
                self.assertIn(cid, shipped, f"{cid} is not a command Field registers")
                self.assertEqual(set(keys), shipped[cid], f"{cid}: the page says {keys}, Field ships {sorted(shipped[cid])}")


if __name__ == "__main__":
    unittest.main()
