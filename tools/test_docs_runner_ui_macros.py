"""The docs runner's capture="ui" plumbing never shows up as a user's macro.

A capture="ui" fence runs its setup transcript and its drive as macros in the captured document.
The setup is deleted as soon as it has run (before the drive, so a capture of the Macro Recorder
or the palette cannot list it), the drive is written with `internal: true` (unlisted by
OperationTranscript.isInternalMacro while it runs) and deleted afterwards. A fence with no setup
gets no delete for one: the positive control that the deletes follow the macros actually written.
"""
from __future__ import annotations

import json
import shlex
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "public"))
import docs_hooks  # noqa: E402

PAGE = Path("public/docs/tour_interface.md")


def commands(open_line: str) -> list[list[str]]:
    options = docs_hooks.parse_runner_options(
        runner_name="diffstatic_runner", open_line=open_line, page_path=PAGE, line_number=1, block_index=0,
    )
    lines = docs_hooks.build_backdoor_command(
        repo_root=ROOT, page_path=PAGE, block_index=0, screenshot_path=Path("/tmp/ui.png"),
        options=options, code='ui.pause(1)',
    ).backdoor_commands
    return [shlex.split(line) for line in lines]


def macro_ops(cmds: list[list[str]]) -> list[tuple[str, str]]:
    return [(c[1], c[2]) for c in cmds if c and c[0] == "macro"]


class DocsRunnerUiMacrosTest(unittest.TestCase):
    FENCE = ('```diffstatic_runner {data-lang=kotlin capture="ui" region="window" screenshot="assets/x.png" '
             'transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface.yaml" name="ui-macros"}')

    def test_setup_is_deleted_before_the_drive_and_the_drive_is_internal_and_deleted(self):
        cmds = commands(self.FENCE)
        ops = macro_ops(cmds)
        setup, drive = "ui-macros_ui_setup", "ui-macros_ui_drive"
        setup = next(name for op, name in ops if name.endswith("_ui_setup"))
        drive = next(name for op, name in ops if name.endswith("_ui_drive"))
        self.assertEqual(ops, [("write", setup), ("run", setup), ("delete", setup),
                               ("write", drive), ("run", drive), ("delete", drive)])
        written = next(c for c in cmds if c[:3] == ["macro", "write", drive])
        content = json.loads(written[written.index("--content") + 1])
        self.assertIs(content.get("internal"), True)

    def test_a_fence_without_setup_deletes_only_its_drive(self):
        ops = macro_ops(commands(self.FENCE.replace(
            ' transcript="../../dev_docs/agents/transcripts/docs_capture/tour_interface.yaml"', '')))
        self.assertEqual([op for op, _ in ops], ["write", "run", "delete"])
        self.assertTrue(all(name.endswith("_ui_drive") for _, name in ops))


if __name__ == "__main__":
    unittest.main()
