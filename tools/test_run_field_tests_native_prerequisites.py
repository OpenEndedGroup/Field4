"""Native smoke tests must be runnable from a fresh checkout."""

import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_field_tests as smoke  # noqa: E402


class NativePrerequisiteTests(unittest.TestCase):
    def test_selected_tags_choose_only_the_needed_builds(self):
        slug = {"id": "four.slug.readback", "tags": ["smoke", "slug"]}
        steam = {"id": "synth.pat-spatial.budget", "tags": ["steamaudio"]}
        self.assertEqual(smoke.smoke_native_tasks([slug]), ["buildSlugNative"])
        self.assertEqual(smoke.smoke_native_tasks([steam]), ["fetchSteamAudioNatives"])
        self.assertEqual(smoke.smoke_native_tasks([slug, steam]), ["buildSmokeNativePrerequisites"])
        self.assertEqual(smoke.smoke_native_tasks([{"id": "unrelated"}]), [])

    def test_preflight_runs_gradle_and_rejects_a_failed_build(self):
        slug = {"id": "four.slug.readback", "tags": ["slug"]}
        failed = subprocess.CompletedProcess(["./gradlew"], 1, "", "native build failed")
        with patch.object(smoke, "run_with_watchdog", return_value=(failed, 1, False)) as run:
            with self.assertRaisesRegex(smoke.RunnerError, "native build failed"):
                smoke.prepare_smoke_natives(
                    [slug], environment=None, heartbeat_interval=15, progress=lambda _: None,
                )
        self.assertEqual(run.call_args.args[0][-1], "buildSlugNative")


if __name__ == "__main__":
    unittest.main()
