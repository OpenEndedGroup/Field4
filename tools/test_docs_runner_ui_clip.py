"""`clip=` and `viewport=` on a `capture="ui"` fence reach the drive op.

Until 2026-10-01 a ui capture had no way to frame a rectangle no single element owns (a box and
its editor, a drawing under the Excalidraw toolbar): `clip=` was applied only on the screenshot
path, after the fact, and never reached the `drive` op. Now `clip` travels as the op's
`clipRect` (used for the recording too) and `viewport` lays the page out at a chosen size.
Neither enters the digest of a fence that does not set it, so existing captures keep theirs.
"""

import json
import shlex
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "public"))

import docs_hooks  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
PAGE = REPO_ROOT / "public" / "docs" / "survivalGuide.md"


def _options(attrs: str) -> "docs_hooks.RunnerOptions":
    return docs_hooks.parse_runner_options(
        runner_name="diffstatic_runner",
        open_line="```diffstatic_runner {data-lang=kotlin " + attrs + "}",
        page_path=PAGE,
        line_number=1,
        block_index=0,
    )


def _drive_op(options) -> dict:
    invocation = docs_hooks.build_backdoor_command(
        repo_root=REPO_ROOT, page_path=PAGE, block_index=0,
        screenshot_path=PAGE.parent / "assets" / "x.png", options=options, code="ui.pause(1)",
    )
    for line in invocation.backdoor_commands:
        parts = shlex.split(line)
        if parts[:2] == ["macro", "write"] and "--content" in parts:
            transcript = json.loads(parts[parts.index("--content") + 1])
            return transcript["operations"][0]["drive"]
    raise AssertionError("no drive op in " + repr(invocation.backdoor_commands))


class UiClipAndViewportTests(unittest.TestCase):

    def test_clip_and_viewport_reach_the_drive_op(self):
        op = _drive_op(_options('capture="ui" region="window" viewport="760x560" clip="0,0,760,380" video="assets/x.mp4"'))
        self.assertEqual(op["clipRect"], [0.0, 0.0, 760.0, 380.0])
        self.assertEqual(op["viewport"], [760, 560])

    def test_a_fence_without_them_sends_neither_and_hashes_as_before(self):
        options = _options('capture="ui" region="window" screenshot="assets/x.png"')
        op = _drive_op(options)
        self.assertNotIn("clipRect", op)
        self.assertNotIn("viewport", op)
        payload = docs_hooks.options_digest_payload(options)
        self.assertNotIn("clip", payload)
        self.assertNotIn("viewport", payload)

    def test_they_enter_the_digest_when_set(self):
        payload = docs_hooks.options_digest_payload(_options('capture="ui" viewport="760x560" clip="0,0,10,10" screenshot="assets/x.png"'))
        self.assertEqual(payload["viewport"], [760, 560])
        self.assertEqual(payload["clip"], [0.0, 0.0, 10.0, 10.0])

    def test_a_malformed_viewport_is_refused(self):
        with self.assertRaises(RuntimeError):
            docs_hooks.parse_viewport("760 by 560")
        with self.assertRaises(RuntimeError):
            docs_hooks.parse_viewport("100x100")


if __name__ == "__main__":
    unittest.main()
