"""`line-heat=true` on a `capture="editor"` fence turns on the editor's live line heat for the capture.

Line heat counts a box's lines only from a run that starts after the editor watches the box, so
the switch has to land between `editor-open` and the run; the capture then waits for painted heat
(a figure without it would show a plain gutter), and line heat is switched off again afterwards so
the shared capture browser does not carry it into the next fence. A fence without the attribute
gets none of these commands and hashes exactly as before.
"""

import shlex
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "public"))

import docs_hooks  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
PAGE = REPO_ROOT / "public" / "docs" / "visualizers.md"


def _options(attrs: str) -> "docs_hooks.RunnerOptions":
    return docs_hooks.parse_runner_options(
        runner_name="diffstatic_runner",
        open_line="```diffstatic_runner {data-lang=kotlin " + attrs + "}",
        page_path=PAGE,
        line_number=1,
        block_index=0,
    )


def _commands(options) -> list[list[str]]:
    invocation = docs_hooks.build_backdoor_command(
        repo_root=REPO_ROOT, page_path=PAGE, block_index=0,
        screenshot_path=PAGE.parent / "assets" / "x.png", options=options, code="loop { frame() }",
    )
    return [shlex.split(line) for line in invocation.backdoor_commands]


def _index(commands, predicate) -> int:
    for i, parts in enumerate(commands):
        if predicate(parts):
            return i
    raise AssertionError("no such command in " + repr(commands))


def _page_eval_with(commands, text) -> int:
    return _index(commands, lambda p: p[0] == "page-eval" and any(text in part for part in p))


class LineHeatCaptureTests(unittest.TestCase):

    def test_line_heat_goes_on_after_the_editor_opens_and_before_the_run(self):
        commands = _commands(_options('capture="editor" execution="launch" line-heat=true screenshot="assets/x.png"'))
        editor_open = _index(commands, lambda p: p[0] == "editor-open")
        on = _page_eval_with(commands, "'live', true")
        launch = _index(commands, lambda p: p[0] == "launch")
        painted = _page_eval_with(commands, "nothing was painted")
        shot = _index(commands, lambda p: p[0] == "screenshot-editor")
        off = _page_eval_with(commands, "'live', false")
        stop = _index(commands, lambda p: p[0] == "stop")
        self.assertLess(editor_open, on)
        self.assertLess(on, launch)
        self.assertLess(launch, painted)
        self.assertLess(painted, shot)
        self.assertLess(shot, off)
        self.assertLess(off, stop)
        self.assertIn(docs_hooks.LINE_HEAT_STORAGE_KEY, " ".join(commands[on]))

    def test_it_enters_the_digest_only_when_set(self):
        self.assertIs(docs_hooks.options_digest_payload(
            _options('capture="editor" line-heat=true screenshot="assets/x.png"'))["lineHeat"], True)
        plain = _options('capture="editor" screenshot="assets/x.png"')
        self.assertNotIn("lineHeat", docs_hooks.options_digest_payload(plain))
        self.assertFalse(any(p[0] == "page-eval" and "LineHeatmap" in " ".join(p) for p in _commands(plain)))

    def test_it_needs_an_editor_capture(self):
        with self.assertRaises(RuntimeError):
            _options('capture="box" line-heat=true screenshot="assets/x.png"')


if __name__ == "__main__":
    unittest.main()
