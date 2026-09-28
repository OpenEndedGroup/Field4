"""Output assertions must see what Field emitted, never the source it echoed."""

import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from tools.run_field_tests import evaluate_expectations, render_event_text


class OutputExpectationTests(unittest.TestCase):
    def evaluate(self, events, **expect):
        return evaluate_expectations(
            {"expect": expect},
            [{"ok": True, "events": events, "data": {}}],
            process=subprocess.CompletedProcess([], 0),
            artifacts={},
        )

    def test_echoed_source_cannot_satisfy_output_assertions(self):
        source = {"name": "set-string", "payload": {
            "property": "code", "value": 'println("PASS exact muting")'}}
        batch = {"name": "message-batch", "payload": {"messages": [source]}}
        for events in ([source], [batch], [batch, source]):
            with self.subTest(events=events):
                self.assertEqual(
                    self.evaluate(events, outputContains=["PASS exact muting"]),
                    ["output missing substring: PASS exact muting"],
                )

    def test_only_user_visible_fields_count(self):
        for name, field in (("output", "html"), ("peer-output", "html"),
                            ("set-output", "value"), ("notify", "text")):
            event = {"name": name, "payload": {field: "actual result", "uid": "metadata-only"}}
            with self.subTest(name=name):
                self.assertEqual(render_event_text(event), "actual result")
                self.assertEqual(self.evaluate([event], outputContains=["actual result"],
                                               outputNotContains=["metadata-only"]), [])

    def test_bookkeeping_and_malformed_payloads_have_no_output_text(self):
        for event in (
            {"name": "is-running", "payload": {"running": True}},
            {"name": "output-control", "payload": {"action": "open", "channel": "main"}},
            {"name": "future-message", "payload": {"value": "not output"}},
            {"name": "output", "payload": "malformed payload"},
        ):
            with self.subTest(event=event):
                self.assertEqual(render_event_text(event), "")

    def test_negative_assertions_ignore_source_but_reject_real_output(self):
        source = {"name": "set-string", "payload": {"value": 'check(!text.contains("BAD"))'}}
        self.assertEqual(self.evaluate([source], outputNotContains=["BAD"]), [])
        self.assertEqual(self.evaluate([source, {"name": "output", "payload": {"html": "BAD"}}],
                                       outputNotContains=["BAD"]),
                         ["output unexpectedly contained: BAD"])

    def test_flattened_batch_output_counts_without_counting_its_source(self):
        source = {"name": "set-string", "payload": {"value": "source-only"}}
        output = {"name": "set-output", "payload": {"value": "<td>true</td>"}}
        # FieldBackdoor retains the envelope and adds each decoded child as its own event.
        batch = {"name": "message-batch", "payload": {"messages": [source, output]}}
        self.assertEqual(self.evaluate([batch, source, output], outputContains=[">true</td>"],
                                       outputNotContains=["source-only"]), [])

    def test_event_kind_assertions_still_see_bookkeeping(self):
        self.assertEqual(self.evaluate([{"name": "output-control", "payload": {}}],
                                       eventKinds=["output-control"]), [])




class TimeoutFlagTests(unittest.TestCase):
    """`--timeout` gets a pointer to `timeoutSec`, not argparse's bare "unrecognized arguments"."""

    def test_timeout_is_refused_with_the_real_knob_named(self) -> None:
        import contextlib
        import io

        from tools.run_field_tests import parse_args

        for argv in (["--timeout", "45"], ["--timeout=45"], ["--timeout"]):
            with self.subTest(argv=argv):
                err = io.StringIO()
                with contextlib.redirect_stderr(err), self.assertRaises(SystemExit) as raised:
                    parse_args(argv)
                self.assertEqual(raised.exception.code, 2)
                self.assertIn("timeoutSec", err.getvalue())

    def test_ordinary_arguments_still_parse(self) -> None:
        from tools.run_field_tests import parse_args

        args = parse_args(["--id", "a.b", "--port", "9690"])
        self.assertEqual(args.ids, ["a.b"])
        self.assertEqual(args.port, 9690)


if __name__ == "__main__":
    unittest.main()
