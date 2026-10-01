"""The backdoor's agent-task check parser accepts exactly the kinds the server's AgentCheck.parse does.

`parse_agent_check` in tools/field_backdoor.py is a hand-kept mirror of `AgentCheck.parse` in
src/jvmMain/kotlin/afield/ai/agent/AgentTask.kt. A kind added on one side only is a check that either
cannot be typed on the command line or is sent and then rejected by the server; this reads both sources,
so it needs no JVM.
"""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))  # field_backdoor imports its siblings by bare name

import field_backdoor  # noqa: E402

REPO = TOOLS.parent
KOTLIN = REPO / "src/jvmMain/kotlin/afield/ai/agent/AgentTask.kt"


def kotlin_kinds() -> set[str]:
    source = KOTLIN.read_text()
    start = source.index("fun parse(spec: String): AgentCheck")
    body = source[start:source.index('else -> throw IllegalArgumentException("Unknown check kind', start)]
    kinds: set[str] = set()
    for line in body.splitlines():
        if "->" in line:
            kinds.update(re.findall(r'"([a-z-]+)"', line.split("->")[0]))
    return kinds


def python_kinds() -> set[str]:
    source = Path(field_backdoor.__file__).read_text()
    start = source.index("def parse_agent_check(")
    body = source[start:source.index("raise BackdoorError(f\"unknown agent-task check kind", start)]
    kinds: set[str] = set()
    for line in body.splitlines():
        if line.strip().startswith("if kind"):
            kinds.update(re.findall(r'"([a-z-]+)"', line))
    return kinds


class AgentCheckParseParityTest(unittest.TestCase):
    def test_both_parsers_accept_the_same_kinds(self) -> None:
        self.assertEqual(kotlin_kinds(), python_kinds())

    def test_the_effect_and_tool_checks_parse(self) -> None:
        self.assertEqual(field_backdoor.parse_agent_check("effects-absent:EXECUTE_CODE"),
                         {"kind": "effects-absent", "text": "EXECUTE_CODE"})
        self.assertEqual(field_backdoor.parse_agent_check("tool-used:RunToolProgram"),
                         {"kind": "tool-used", "text": "RunToolProgram"})


if __name__ == "__main__":
    unittest.main()
