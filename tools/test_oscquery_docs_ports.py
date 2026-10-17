"""The OSCQuery docs captures must not bind a fixed port.

Two docs runners (two Field JVMs, e.g. sibling worktrees) capturing public/docs/oscquery.md at once
both bound the default 9020 and one failed. The runner fences ask for ``httpPort = 0`` and read the
bound port back from ``q.httpPort``; this pins that.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path

PAGE = Path(__file__).resolve().parent.parent / "public" / "docs" / "oscquery.md"
RUNNER_FENCE = re.compile(r"^```diffstatic_runner[^\n]*\n(.*?)^```", re.S | re.M)


def runner_fences(text: str) -> list[str]:
    return RUNNER_FENCE.findall(text)


def fixed_port_problems(fence: str) -> list[str]:
    problems = []
    for call in re.findall(r"oscQuery\(([^)]*)\)", fence):
        if not re.search(r"httpPort\s*=\s*0\b", call):
            problems.append(f"oscQuery({call}) binds a fixed port; pass httpPort = 0")
    if re.search(r"\b9\d{3}\b", fence):
        problems.append("a literal port number; read q.httpPort instead")
    return problems


class OscQueryDocsPortsTest(unittest.TestCase):
    def test_page_has_runner_fences(self) -> None:
        self.assertGreaterEqual(len(runner_fences(PAGE.read_text(encoding="utf-8"))), 2)

    def test_runner_fences_pick_a_free_port(self) -> None:
        for fence in runner_fences(PAGE.read_text(encoding="utf-8")):
            self.assertEqual(fixed_port_problems(fence), [], fence)

    def test_checker_flags_the_old_spelling(self) -> None:
        old = 'oscQuery("My piece").float("/x", 0.0..1.0, 0.5) { }\nURI("http://127.0.0.1:9020/x")\n'
        self.assertEqual(len(fixed_port_problems(old)), 2)


if __name__ == "__main__":
    unittest.main()
