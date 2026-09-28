"""The verification lock reaches raw Gradle builds (settings.gradle.kts), and its owner's own do not wait.

The Gradle half was verified live when it landed (2026-09-28): with a Python process holding the
lock, `AFIELD_VERIFICATION_LOCK=fail ./gradlew help` failed in 1s naming the owner; the default
mode printed `WAIT gradle: ...` every 15s and continued when the lock was released; a build
carrying `AFIELD_VERIFICATION_LOCK_OWNER=<owner pid>` went straight through; and a lock file naming
a dead pid, or a live pid that started after the lock was written, was ignored as stale. What
this suite pins is the contract between the two files, which is where they could drift apart.
"""

from __future__ import annotations

import json
import os
import re
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))

import verification_runtime  # noqa: E402

SETTINGS = TOOLS.parent / "settings.gradle.kts"


class OwnerMarker(unittest.TestCase):
    def test_the_marker_names_the_holder_only_while_the_lock_is_held(self) -> None:
        name = verification_runtime.VERIFICATION_LOCK_OWNER_ENV
        os.environ.pop(name, None)
        with tempfile.TemporaryDirectory() as root:
            with verification_runtime.verification_lock(Path(root), label="t", progress=lambda _m: None):
                self.assertEqual(os.environ.get(name), str(os.getpid()))
                record = json.loads((Path(root) / ".field-backdoor" / "verification.lock").read_text())
            self.assertNotIn(name, os.environ)
            self.assertEqual((Path(root) / ".field-backdoor" / "verification.lock").read_text(), "")
        # The two fields the Gradle side parses, in the shape it parses them.
        self.assertEqual(record["pid"], os.getpid())
        self.assertRegex(record["started"], r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d[+-]\d{4}$")

    def test_a_previous_marker_is_restored(self) -> None:
        name = verification_runtime.VERIFICATION_LOCK_OWNER_ENV
        os.environ[name] = "12345"
        try:
            with tempfile.TemporaryDirectory() as root:
                with verification_runtime.verification_lock(Path(root), label="t", progress=lambda _m: None):
                    self.assertEqual(os.environ[name], str(os.getpid()))
            self.assertEqual(os.environ[name], "12345")
        finally:
            os.environ.pop(name, None)

    def test_children_inherit_the_marker_through_the_server_environment(self) -> None:
        name = verification_runtime.VERIFICATION_LOCK_OWNER_ENV
        with tempfile.TemporaryDirectory() as root:
            with verification_runtime.verification_lock(Path(root), label="t", progress=lambda _m: None):
                env = verification_runtime.automation_server_environment(9999)
        self.assertEqual(env.get(name), str(os.getpid()))


class SettingsContract(unittest.TestCase):
    def setUp(self) -> None:
        self.text = SETTINGS.read_text(encoding="utf-8")

    def test_settings_reads_the_same_file_and_marker(self) -> None:
        self.assertIn('".field-backdoor/verification.lock"', self.text)
        self.assertIn(f'"{verification_runtime.VERIFICATION_LOCK_OWNER_ENV}"', self.text)

    def test_settings_parses_the_fields_the_python_side_writes(self) -> None:
        self.assertIn('\\"pid\\"', self.text)
        self.assertIn('\\"started\\"', self.text)
        self.assertIn("yyyy-MM-dd'T'HH:mm:ssxx", self.text)

    def test_the_opt_out_and_fail_fast_modes_exist(self) -> None:
        self.assertIn('"AFIELD_VERIFICATION_LOCK"', self.text)
        self.assertRegex(self.text, r'mode != "off"')
        self.assertRegex(self.text, r'mode == "fail"')
        self.assertIn('"AFIELD_VERIFICATION_LOCK_WAIT_SEC"', self.text)
        self.assertIn("idea.sync.active", self.text)

    def test_the_python_started_format_parses_with_the_kotlin_pattern(self) -> None:
        """`time.strftime('%z')` gives +0200; Java's `xx` pattern is exactly that shape."""
        import time

        stamp = time.strftime("%Y-%m-%dT%H:%M:%S%z")
        self.assertIsNotNone(re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d[+-]\d{4}", stamp))


if __name__ == "__main__":
    unittest.main()
