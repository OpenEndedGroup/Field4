import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
from pathlib import Path

TOOL = Path(__file__).resolve().parent / "preview_server.py"


class PreviewServerTests(unittest.TestCase):
    """Real servers on real ports: what matters is which processes `stop` signals."""

    def setUp(self) -> None:
        self.scratch = Path(tempfile.mkdtemp(prefix="preview-server-")).resolve()
        self.addCleanup(shutil.rmtree, self.scratch, True)
        self.registry = self.scratch / "state" / "preview-servers.json"
        self.env = {**os.environ, "FIELD4_PREVIEW_REGISTRY": str(self.registry)}
        self.addCleanup(self.tool, "stop", "--all")
        self.tree = self.scratch / "tree"
        (self.tree / "public" / "field4").mkdir(parents=True)
        (self.tree / "public" / "field4" / "index.html").write_text("hello preview\n")

    def tool(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(TOOL), *args], capture_output=True, text=True,
                              env=self.env, timeout=60)

    def start(self, directory: Path) -> dict:
        result = self.tool("start", str(directory), "--owner", "test")
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)

    def alive(self, pid: int) -> bool:
        return subprocess.run(["ps", "-o", "pid=", "-p", str(pid)], capture_output=True).returncode == 0

    def entries(self) -> list[dict]:
        return json.loads(self.registry.read_text())

    def test_start_serves_the_directory_and_logs_outside_it(self) -> None:
        server = self.start(self.tree / "public" / "field4")
        with urllib.request.urlopen(server["url"] + "index.html", timeout=5) as response:
            self.assertEqual(b"hello preview\n", response.read())
        self.assertEqual(["index.html"], sorted(p.name for p in (self.tree / "public" / "field4").iterdir()))
        self.assertTrue((self.registry.parent / "preview-logs" / f"{server['port']}.log").exists())

    def test_stop_by_age_leaves_a_young_server_running(self) -> None:
        server = self.start(self.tree)
        result = self.tool("stop", "--older-than-hours", "1")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(self.alive(server["pid"]))
        self.assertEqual(1, len(self.entries()))

    def test_stop_by_age_stops_an_old_server(self) -> None:
        server = self.start(self.tree)
        entries = self.entries()
        entries[0]["started"] -= 20 * 3600
        self.registry.write_text(json.dumps(entries))
        result = self.tool("stop", "--older-than-hours", "16")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("stopped", result.stdout)
        time.sleep(0.3)
        self.assertFalse(self.alive(server["pid"]))
        self.assertEqual([], self.entries())

    def test_stop_by_dir_reaches_only_servers_inside_that_tree(self) -> None:
        other = self.scratch / "other"
        other.mkdir()
        inside = self.start(self.tree / "public" / "field4")
        outside = self.start(other)
        result = self.tool("stop", "--dir", str(self.tree))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        time.sleep(0.3)
        self.assertFalse(self.alive(inside["pid"]))
        self.assertTrue(self.alive(outside["pid"]))

    def test_a_reused_pid_is_forgotten_and_never_signalled(self) -> None:
        # A registered PID that now belongs to some other process, e.g. after a reboot.
        bystander = subprocess.Popen(["sleep", "30"])
        self.addCleanup(bystander.kill)
        self.registry.parent.mkdir(parents=True, exist_ok=True)
        self.registry.write_text(json.dumps([
            {"pid": bystander.pid, "port": 1, "dir": str(self.tree), "owner": "", "started": 0},
        ]))
        result = self.tool("stop", "--all")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("forgot", result.stdout)
        self.assertIsNone(bystander.poll())
        self.assertEqual([], self.entries())

    def test_an_unregistered_server_is_not_touched(self) -> None:
        stranger = subprocess.Popen([sys.executable, "-m", "http.server", "0", "--bind", "127.0.0.1"],
                                    cwd=self.tree, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(stranger.kill)
        self.start(self.tree)
        self.tool("stop", "--all")
        self.assertIsNone(stranger.poll())

    def test_stop_with_no_selector_is_refused(self) -> None:
        result = self.tool("stop")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("--older-than-hours, --dir or --all", result.stderr)


if __name__ == "__main__":
    unittest.main()
