"""`window-outputs="true"` keeps a `capture="window"` fence a window capture with outputs open.

Window + open output panels used to collapse to a box capture, so a figure could not frame what a
box drew on the canvas (its notation strokes) beside the box's own output panels: the crop showed
fragments of the strokes cut off across the panel. With the attribute the capture stays a window
screenshot and `clip=` frames both.
"""

import shlex
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "public"))

import docs_hooks  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
PAGE = REPO_ROOT / "public" / "docs" / "chain_drawn_paint.md"


def _options(attrs: str) -> "docs_hooks.RunnerOptions":
    return docs_hooks.parse_runner_options(
        runner_name="diffstatic_runner",
        open_line="```diffstatic_runner {data-lang=kotlin " + attrs + "}",
        page_path=PAGE,
        line_number=1,
        block_index=0,
    )


def _screenshot_command(options) -> list[str]:
    invocation = docs_hooks.build_backdoor_command(
        repo_root=REPO_ROOT, page_path=PAGE, block_index=0,
        screenshot_path=PAGE.parent / "assets" / "x.png", options=options, code="println(1)",
    )
    for line in invocation.backdoor_commands:
        parts = shlex.split(line)
        if parts and parts[0].startswith("screenshot-"):
            return parts
    raise AssertionError("no screenshot command in " + repr(invocation.backdoor_commands))


class WindowOutputsTests(unittest.TestCase):

    def test_window_with_outputs_still_collapses_to_a_box_capture_by_default(self):
        options = _options('capture="window" open-outputs="true" screenshot="assets/x.png"')
        self.assertEqual(docs_hooks.effective_capture_mode(options), "box")
        self.assertEqual(_screenshot_command(options)[0], "screenshot-box")

    def test_window_outputs_keeps_a_window_screenshot_with_outputs_open(self):
        options = _options('capture="window" open-outputs="true" window-outputs="true" clip="0,0,1850,780" screenshot="assets/x.png"')
        self.assertTrue(options.window_outputs)
        self.assertEqual(docs_hooks.effective_capture_mode(options), "window")
        parts = _screenshot_command(options)
        self.assertEqual(parts[0], "screenshot-window")
        self.assertIn("--open-outputs", parts)


class NativeWindowVideoTests(unittest.TestCase):
    """A native-window video films the renderer's own window with `record-native`, never a browser recorder."""

    def test_native_window_with_video_records_the_native_window(self):
        options = _options('capture="native-window" graphics-context=true video="assets/x.mp4" duration="8"')
        invocation = docs_hooks.build_backdoor_command(
            repo_root=REPO_ROOT, page_path=PAGE, block_index=0,
            screenshot_path=PAGE.parent / "assets" / "x.mp4", options=options, code="println(1)", is_video=True,
        )
        commands = [shlex.split(line) for line in invocation.backdoor_commands]
        recorders = [c for c in commands if c and c[0].startswith("record-")]
        self.assertEqual([c[0] for c in recorders], ["record-native"])
        self.assertEqual(recorders[0][recorders[0].index("--duration") + 1], "8.0")

    def test_native_window_screenshot_still_parses(self):
        self.assertEqual(_options('capture="native-window" screenshot="assets/x.png"').capture, "native-window")


if __name__ == "__main__":
    unittest.main()
