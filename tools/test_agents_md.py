"""Keep AGENTS.md from regrowing the two things the 2026-09-28 rewrite removed.

1. **Line-number citations.** The old guide's probe example named `Server.kt:1758`, a line that
   had moved by the time anyone read it. A `SomeFile.kt:<digits>` reference in the file every agent
   reads first sends the next one to the wrong place, silently. Name the file and the symbol.
2. **Dead links.** Relative Markdown links (and their `#anchor`, for Markdown targets) must
   resolve, because the rewrite moved detail out to `dev_docs/agents/` and a link is now the only
   route to it.

Plan item 4 of section 4 in `dev_docs/ARCHITECTURE_REVIEW_2026-09-28.md`.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
AGENTS = REPO / "AGENTS.md"

LINE_REFERENCE = re.compile(r"\b[\w./-]+\.(?:kt|kts|java|py|js|mjs|ts|gradle)[:#]L?\d+\b")
LINK = re.compile(r"\]\(([^)\s]+)\)")


def line_references(text: str) -> list[str]:
    return [match.group(0) for match in LINE_REFERENCE.finditer(text)]


def github_anchor(heading: str) -> str:
    slug = heading.strip().lower()
    slug = re.sub(r"[^\w\- ]", "", slug)
    return slug.replace(" ", "-")


def anchors_of(markdown: Path) -> set[str]:
    return {
        github_anchor(line.lstrip("#"))
        for line in markdown.read_text(encoding="utf-8").splitlines()
        if line.startswith("#")
    }


class NoLineNumberCitations(unittest.TestCase):
    def test_agents_md_cites_no_source_line_numbers(self) -> None:
        found = line_references(AGENTS.read_text(encoding="utf-8"))
        self.assertEqual(
            found,
            [],
            "AGENTS.md cites source by line number; name the file and the symbol instead "
            "(lines move, and a stale one misdirects every agent that reads it)",
        )

    def test_the_pattern_catches_the_shapes_it_exists_for(self) -> None:
        """A negative control: the old guide's own example, and its neighbours, must be caught."""
        for sample in (
            '{"file":"Server.kt","line":1758}',  # not a line ref by this rule: quoted JSON
            "see Server.kt:1758 for the loop",
            "agent_backdoor.kt:924",
            "tools/field_backdoor.py:396",
            "build.gradle.kts:4",
        ):
            with self.subTest(sample=sample):
                hits = line_references(sample)
                if sample.startswith("{"):
                    self.assertEqual(hits, [])
                else:
                    self.assertTrue(hits, sample)
        self.assertEqual(line_references("Server.kt, and the RPC in rpc.kt"), [])


class LinksResolve(unittest.TestCase):
    def test_relative_links_and_anchors_resolve(self) -> None:
        problems: list[str] = []
        for target in LINK.findall(AGENTS.read_text(encoding="utf-8")):
            if re.match(r"^[a-z]+://", target) or target.startswith("#"):
                continue
            path_part, _, anchor = target.partition("#")
            path = (REPO / path_part).resolve()
            if not path.exists():
                problems.append(f"{target}: {path_part} does not exist")
                continue
            if anchor and path.suffix == ".md" and anchor not in anchors_of(path):
                problems.append(f"{target}: no heading with anchor #{anchor} in {path_part}")
        self.assertEqual(problems, [])

    def test_the_guide_still_has_its_nine_sections(self) -> None:
        headings = [
            line for line in AGENTS.read_text(encoding="utf-8").splitlines() if line.startswith("## ")
        ]
        self.assertEqual(len(headings), 9, headings)
        self.assertTrue(all(re.match(r"## \d\. ", h) for h in headings), headings)


if __name__ == "__main__":
    unittest.main()
