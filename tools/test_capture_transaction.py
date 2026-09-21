import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "public"))
from capture_transaction import CaptureTransaction
import docs_hooks
from verification_environment import VerificationEnvironmentError


class CaptureTransactionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.destination = self.root / "good.png"
        self.destination.write_bytes(b"good")
        self.original_stat = self.destination.stat()
        self.transaction = CaptureTransaction()
        self.addCleanup(self.transaction.close)

    def test_capture_does_not_touch_published_asset_until_promoted(self):
        staged = self.transaction.stage(self.destination)
        staged.write_bytes(b"new")
        self.assertEqual(self.destination.read_bytes(), b"good")
        self.assertEqual(self.destination.stat().st_mtime_ns, self.original_stat.st_mtime_ns)
        self.transaction.publish()
        self.assertEqual(self.destination.read_bytes(), b"new")

    def test_missing_second_output_preserves_both_destinations(self):
        self.transaction.stage(self.destination).write_bytes(b"bad")
        second = self.root / "video.mp4"
        self.transaction.stage(second)
        with self.assertRaisesRegex(RuntimeError, "nonempty"):
            self.transaction.publish()
        self.assertEqual(self.destination.read_bytes(), b"good")
        self.assertFalse(second.exists())

    def test_empty_output_cannot_replace_a_good_asset(self):
        self.transaction.stage(self.destination).touch()
        with self.assertRaises(RuntimeError):
            self.transaction.publish()
        self.assertEqual(self.destination.read_bytes(), b"good")

    def test_failed_second_promotion_restores_bytes_and_mtime(self):
        self.transaction.stage(self.destination).write_bytes(b"new")
        second = self.root / "video.mp4"
        self.transaction.stage(second).write_bytes(b"video")
        replace = os.replace
        def fail_second(source, destination):
            if destination == second:
                raise OSError("disk failure")
            return replace(source, destination)
        with patch("capture_transaction.os.replace", side_effect=fail_second):
            with self.assertRaisesRegex(OSError, "disk failure"):
                self.transaction.publish()
        self.assertEqual(self.destination.read_bytes(), b"good")
        self.assertEqual(self.destination.stat().st_mtime_ns, self.original_stat.st_mtime_ns)
        self.assertFalse(second.exists())

    def test_symlink_is_not_followed_or_replaced(self):
        link = self.root / "link.png"
        link.symlink_to(self.destination)
        with self.assertRaisesRegex(RuntimeError, "symlink"):
            self.transaction.stage(link)
        self.assertEqual(self.destination.read_bytes(), b"good")

    def test_rollback_io_failure_keeps_recovery_files(self):
        self.transaction.stage(self.destination).write_bytes(b"new")
        second = self.root / "video.mp4"
        self.transaction.stage(second).write_bytes(b"video")
        replace = os.replace
        def fail_after_first(source, destination):
            if destination == second or source.parent.name == "backup":
                raise OSError("disk failure")
            return replace(source, destination)
        with patch("capture_transaction.os.replace", side_effect=fail_after_first):
            with self.assertRaisesRegex(RuntimeError, "Recovery files retained"):
                self.transaction.publish()
        self.transaction.close()
        self.assertEqual((self.transaction.directories[0] / "backup" / self.destination.name).read_bytes(), b"good")

    def state(self):
        state = docs_hooks.RunnerState.__new__(docs_hooks.RunnerState)
        state.cache = {str(self.destination): "old"}
        state.cache_metadata = {}
        state.dirty = False
        state.screenshots_run = state.videos_run = state.audio_run = state.ui_drives_run = 0
        state.save = Mock()
        return state

    def test_rejected_capture_restores_cache_and_accounting(self):
        state = self.state()
        with self.assertRaisesRegex(RuntimeError, "pixels"):
            with state._capture_transaction():
                state._stage_capture_output(self.destination).write_bytes(b"bad")
                state.cache[str(self.destination)] = "new"
                state.screenshots_run += 1
                state.dirty = True
                raise RuntimeError("invalid pixels")
        self.assertEqual(self.destination.read_bytes(), b"good")
        self.assertEqual(state.cache[str(self.destination)], "old")
        self.assertEqual(state.screenshots_run, 0)
        self.assertFalse(state.dirty)
        state.save.assert_not_called()
        self.assertEqual(list(self.root.glob(".field-capture-*")), [])

    def test_cache_hit_needs_no_staging_directory(self):
        state = self.state()
        with state._capture_transaction():
            pass
        self.assertEqual(list(self.root.glob(".field-capture-*")), [])
        self.assertEqual(self.destination.stat().st_mtime_ns, self.original_stat.st_mtime_ns)

    def test_cache_save_is_deferred_until_after_publication(self):
        state = self.state()
        state.cache_path = self.root / "cache.json"
        state.runner_mode = "always"
        state.save = lambda: docs_hooks.RunnerState.save(state)
        with state._capture_transaction():
            state._stage_capture_output(self.destination).write_bytes(b"new")
            state.cache[str(self.destination)] = "new"
            state.dirty = True
            state.save()
            self.assertFalse(state.cache_path.exists())
        self.assertEqual(self.destination.read_bytes(), b"new")
        self.assertIn('"new"', state.cache_path.read_text())


class RunnerCaptureTransactionTests(unittest.TestCase):
    """Run the real ensure_* methods, faking only their external Field child."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.page = self.root / "page.md"
        self.state = docs_hooks.RunnerState.__new__(docs_hooks.RunnerState)
        state = self.state
        state.repo_root = state.docs_dir = self.root
        state.cache_path = self.root / "cache.json"
        state.shared_log_path = self.root / "field.log"
        state.runner_mode, state.runner_source_digest = "always", "fixture"
        state.cache, state.cache_metadata, state.dirty = {}, {}, False
        state.source_refs_digest = Mock(return_value="fixture")
        state.screenshots_run = state.videos_run = state.audio_run = state.ui_drives_run = 0
        state.cache_hits = state.assets_reused = 0
        state.captures_started = 0
        state.session_enabled = False
        state._captures_since_server_restart = 0
        state.environment_outcomes = []
        state._stop_session = state._stop_shared_field_server = Mock()
        state._ensure_runner_owned_field_server = Mock()

    def options(self, kind):
        attrs = {
            "screenshot": 'screenshot="still.png" capture="box" trim="false"',
            "video": 'video="clip.mp4" capture="box"',
            "audio": 'audio="sound.wav" audio-spec="scene"',
            "ui_drive": 'video="clip.mp4" screenshot="still.png" capture="ui"',
        }[kind]
        return docs_hooks.parse_runner_options(runner_name="diffstatic_runner",
            open_line=f"```diffstatic_runner {{{attrs}}}", page_path=self.page, line_number=1, block_index=0)

    def prepare(self, kind, *, omit_output=False):
        options = self.options(kind)
        destinations = [self.root / value for value in (options.video, options.audio, options.screenshot) if value]
        for destination in destinations:
            destination.write_bytes(b"previous good capture")
        def capture(command, **kwargs):
            from PIL import Image
            import wave
            self.assertTrue(self.state._active_capture_transaction.outputs)
            invocation = " ".join(command) + kwargs.get("input_text", "")
            for destination, staged in self.state._active_capture_transaction.outputs.items():
                self.assertIn(str(staged), invocation, "the real capture command must target staging")
                self.assertEqual(destination.read_bytes(), b"previous good capture")
                if omit_output:
                    continue
                if staged.suffix == ".wav":
                    with wave.open(str(staged), "wb") as wav:
                        wav.setnchannels(1)
                        wav.setsampwidth(2)
                        wav.setframerate(44100)
                        wav.writeframes(b"\x01\x02" * 100)
                elif staged.suffix == ".png":
                    image = Image.new("RGB", (100, 100), "white")
                    image.paste("black", (0, 0, 50, 100))
                    image.save(staged)
                else:
                    staged.write_bytes(b"boundary-test video bytes")
            return subprocess.CompletedProcess(command, 0, "[]", "")
        self.state._run_capture_child = Mock(side_effect=capture)
        kwargs = dict(options=options, page_path=self.page, block_index=0)
        if kind != "audio":
            kwargs["code"] = "val x = 1"
        return destinations, getattr(self.state, f"ensure_{kind}"), kwargs

    def test_each_real_capture_path_stages_publishes_and_counts_only_its_kind(self):
        for index, kind in enumerate(("screenshot", "video", "audio", "ui_drive")):
            with self.subTest(kind=kind):
                destinations, capture, kwargs = self.prepare(kind)
                capture(**kwargs)
                for destination in destinations:
                    self.assertNotEqual(destination.read_bytes(), b"previous good capture")
                counts = [self.state.screenshots_run, self.state.videos_run,
                          self.state.audio_run, self.state.ui_drives_run]
                self.assertEqual(counts, [int(i <= index) for i in range(4)])
                # A strict cache hit still uses authored paths, not the random staging directory.
                self.state.runner_mode = "strict"
                # Rebuild digest under strict once, then repeat unchanged.
                capture(**kwargs)
                before = self.state._run_capture_child.call_count
                capture(**kwargs)
                self.assertEqual(self.state._run_capture_child.call_count, before)
                self.state.runner_mode = "always"
                # The strict refresh is a separate successful capture.
                setattr(self.state, {"screenshot": "screenshots_run", "video": "videos_run",
                        "audio": "audio_run", "ui_drive": "ui_drives_run"}[kind], 1)
                self.assertEqual(list(self.root.glob(".field-capture-*")), [])

    def test_successful_child_without_output_is_failure_for_every_capture_kind(self):
        for kind in ("screenshot", "video", "audio", "ui_drive"):
            with self.subTest(kind=kind):
                destinations, capture, kwargs = self.prepare(kind, omit_output=True)
                with self.assertRaises(RuntimeError):
                    capture(**kwargs)
                for destination in destinations:
                    self.assertEqual(destination.read_bytes(), b"previous good capture")
                self.assertEqual(self.state.cache, {})
                self.assertEqual([self.state.screenshots_run, self.state.videos_run,
                                  self.state.audio_run, self.state.ui_drives_run], [0, 0, 0, 0])

    def test_environment_loss_after_capture_prevents_publication(self):
        destinations, capture, kwargs = self.prepare("screenshot")
        self.state.environment = Mock()
        self.state.environment.check.side_effect = VerificationEnvironmentError("display removed")
        with self.assertRaisesRegex(VerificationEnvironmentError, "display removed"):
            self.state.capture_with_environment(capture, **kwargs)
        self.state._run_capture_child.assert_called_once()
        self.assertEqual(destinations[0].read_bytes(), b"previous good capture")
        self.assertEqual(self.state.screenshots_run, 0)
        self.assertEqual(self.state.cache, {})


if __name__ == "__main__":
    unittest.main()
