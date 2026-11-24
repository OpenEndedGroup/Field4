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


class _Registry(unittest.TestCase):
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


class PreviewServerTests(_Registry):

    def test_start_serves_the_directory_and_logs_outside_it(self) -> None:
        server = self.start(self.tree / "public" / "field4")
        with urllib.request.urlopen(server["url"] + "index.html", timeout=5) as response:
            self.assertEqual(b"hello preview\n", response.read())
        self.assertEqual(["index.html"], sorted(p.name for p in (self.tree / "public" / "field4").iterdir()))
        self.assertTrue((self.registry.parent / "preview-logs" / f"{server['port']}.log").exists())

    def test_a_burst_of_page_requests_is_all_served(self) -> None:
        # A docs page opens 50-70 connections at once; `python -m http.server` queues 5, and macOS
        # resets the rest, so pages lost their fence scripts and figures (2026-11-16).
        import socket
        from concurrent.futures import ThreadPoolExecutor

        server = self.start(self.tree / "public" / "field4")
        held = [socket.create_connection(("127.0.0.1", server["port"]), timeout=5) for _ in range(40)]
        try:
            def fetch(_: int) -> bytes:
                with urllib.request.urlopen(server["url"] + "index.html", timeout=10) as response:
                    return response.read()
            with ThreadPoolExecutor(max_workers=60) as pool:
                bodies = list(pool.map(fetch, range(120)))
        finally:
            for sock in held:
                sock.close()
        self.assertEqual([b"hello preview\n"] * 120, bodies)

    def test_a_rebuilt_site_renamed_into_place_is_served(self) -> None:
        # The docs build renders into a staging directory and renames it over public/field4.
        site = self.tree / "public" / "field4"
        server = self.start(site)
        staging = self.tree / "public" / "staging"
        staging.mkdir()
        (staging / "index.html").write_text("rebuilt\n")
        site.rename(self.tree / "public" / "old")
        staging.rename(site)
        with urllib.request.urlopen(server["url"] + "index.html", timeout=5) as response:
            self.assertEqual(b"rebuilt\n", response.read())

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


# Stands in for a worktree's ./field.sh: records what it was given, then runs a child that listens
# on $PORT with its cwd in the worktree, as the Field JVM does under field.sh -> gradlew -> runJvm.
FAKE_FIELD_SH = """#!/bin/sh
printf '%s\\n' "$@" > .field-args
echo "$PORT $AFIELD_FIELD_SH_NO_TERMINAL" > .field-env
"{python}" -c 'import socket, sys, time
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", int(sys.argv[1]))); s.listen(); time.sleep(600)' "$PORT"
"""


class DemoFieldTests(_Registry):
    """`field` registers a worktree's Field so the 16-hour `stop` reaches it like a preview server."""

    def make_worktree(self, name: str = "wt") -> Path:
        worktree = self.scratch / name
        worktree.mkdir()
        script = worktree / "field.sh"
        script.write_text(FAKE_FIELD_SH.format(python=sys.executable))
        script.chmod(0o755)
        return worktree

    def start_field(self, worktree: Path) -> dict:
        result = self.tool("field", str(worktree), "--owner", "nightly/x", "--wait-seconds", "20",
                           "--", "build/morning-ws", "--read-only")
        self.assertEqual(0, result.returncode, result.stderr)
        return json.loads(result.stdout)

    def listener(self, port: int) -> int:
        out = subprocess.run(["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-t"], capture_output=True, text=True)
        return int(out.stdout.split()[0])

    def test_field_gets_only_its_own_arguments_and_a_port_with_a_valid_mirror(self) -> None:
        worktree = self.make_worktree()
        started = self.start_field(worktree)
        self.assertEqual(["build/morning-ws", "--read-only"], (worktree / ".field-args").read_text().split())
        self.assertEqual(f"{started['port']} 1", (worktree / ".field-env").read_text().strip())
        self.assertLessEqual(started["port"] + 10000, 65535)
        self.assertEqual([("field", "nightly/x")], [(e["kind"], e["owner"]) for e in self.entries()])

    def test_stop_ends_the_listening_field_and_its_launcher(self) -> None:
        worktree = self.make_worktree()
        started = self.start_field(worktree)
        server = self.listener(started["port"])
        result = self.tool("stop", "--dir", str(worktree))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        time.sleep(0.3)
        self.assertFalse(self.alive(server))
        self.assertFalse(self.alive(started["pid"]))
        self.assertEqual([], self.entries())

    def test_a_young_field_survives_the_age_stop(self) -> None:
        started = self.start_field(self.make_worktree())
        self.tool("stop", "--older-than-hours", "16")
        self.assertTrue(self.alive(started["pid"]))

    def test_a_listener_outside_the_worktree_on_a_registered_port_is_never_signalled(self) -> None:
        # The Field is gone and its port now belongs to a stranger whose cwd is elsewhere.
        stranger = subprocess.Popen([sys.executable, "-m", "http.server", "0", "--bind", "127.0.0.1"],
                                    cwd=self.scratch, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.addCleanup(stranger.kill)
        port = None
        for _ in range(50):
            out = subprocess.run(["lsof", "-nP", "-a", "-p", str(stranger.pid), "-iTCP", "-sTCP:LISTEN", "-Fn"],
                                 capture_output=True, text=True).stdout
            names = [line for line in out.splitlines() if line.startswith("n")]
            if names:
                port = int(names[0].rsplit(":", 1)[1])
                break
            time.sleep(0.1)
        self.assertIsNotNone(port)
        worktree = self.make_worktree()
        self.registry.parent.mkdir(parents=True, exist_ok=True)
        self.registry.write_text(json.dumps([
            {"kind": "field", "pid": 1, "port": port, "dir": str(worktree), "owner": "", "started": 0},
        ]))
        result = self.tool("stop", "--all")
        self.assertIn("forgot", result.stdout)
        self.assertIsNone(stranger.poll())


if __name__ == "__main__":
    unittest.main()
