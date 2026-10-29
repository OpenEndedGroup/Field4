"""Which coding agent Field's tools hand work to: `claude` (Claude Code), `codex` (Codex CLI), or `field`
(Field's own AI4, through `tools/field_repo_agent.py`: repo tools confined to the working directory, no shell).

The landing queue's fixer and its `huh?`/`bouncy?`, the docs-notes revision pass, and the
docs-pass page's terminals all ask here. The choice is, first match:

1. `FIELD_AGENT=claude|codex|field` in the environment;
2. the first word of `~/.afield/agent` (one machine-wide file, so a launchd lander that never sees
   your shell's environment agrees with it);
3. `claude`.

The Field server reads the same two places (`fieldAgent()` in `afield/server/terminal.kt`).
Stdlib only, so any tool can import it.
"""

from __future__ import annotations

import os
import shutil
from pathlib import Path

AGENTS = ("claude", "codex", "field")
REPO_AGENT = Path(__file__).resolve().parent / "field_repo_agent.py"
DEFAULT = "claude"
AGENT_FILE = Path.home() / ".afield" / "agent"
INSTALL_DIRS = (Path.home() / ".local" / "bin", Path.home() / ".claude" / "local", Path("/opt/homebrew/bin"), Path("/usr/local/bin"))


def configured_agent() -> str:
    for value in (os.environ.get("FIELD_AGENT", ""), _file_choice()):
        value = value.strip().lower()
        if value in AGENTS:
            return value
    return DEFAULT


def _file_choice() -> str:
    try:
        words = AGENT_FILE.read_text().split()
    except OSError:
        return ""
    return words[0] if words else ""


def find_executable(name: str) -> str | None:
    """*name* on PATH, or in the usual install places: a process started by launchd or the Dock has a
    PATH without ~/.local/bin (claude) or /opt/homebrew/bin (codex)."""
    for candidate in (shutil.which(name), *(str(d / name) for d in INSTALL_DIRS)):
        if candidate and Path(candidate).is_file() and os.access(candidate, os.X_OK):
            return candidate
    return None


def headless_argv(agent: str | None = None, *, edits: bool = False) -> list[str] | None:
    """A one-shot, non-interactive run that reads its prompt on stdin and prints its answer, or None if
    the agent is not installed. With [edits] it may edit files and run commands in its working
    directory without asking: there is nobody to ask."""
    agent = agent or configured_agent()
    if agent == "field":
        # AI4 in a running (or backdoor-started) Field; it reads the prompt on stdin like the others.
        # Confined to the cwd: it reads everything there and, with [edits], writes everything but .git.
        # A caller that knows which files a job owns passes narrower globs to field_repo_agent itself.
        import sys
        return [sys.executable, str(REPO_AGENT), "--root", "."] + (["--write", "**"] if edits else [])
    exe = find_executable(agent)
    if exe is None:
        return None
    if agent == "codex":
        # `-` reads the prompt from stdin. The fixer runs Gradle, which writes outside the worktree
        # (its user home, the Kotlin daemon), so it gets what Claude's unrestricted Bash gets.
        sandbox = "danger-full-access" if edits else "read-only"
        return [exe, "exec", "--sandbox", sandbox, "--skip-git-repo-check", "-"]
    argv = [exe, "-p", "--output-format", "text"]
    if edits:
        argv += ["--permission-mode", "acceptEdits", "--allowedTools", "Bash Read Edit Write Grep Glob"]
    return argv


def describe(agent: str | None = None) -> str:
    """`claude -p` or `codex exec`: how the tools say who they asked."""
    agent = agent or configured_agent()
    return {"codex": "codex exec", "field": "Field AI4"}.get(agent, "claude -p")
