#!/usr/bin/env python3
"""Install Field's tracked hooks without changing core.hooksPath or other hooks."""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path


def main() -> int:
    repo = Path(
        subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            check=True,
            stdout=subprocess.PIPE,
            text=True,
        ).stdout.strip()
    ).resolve()
    hook_dir_text = subprocess.run(
        ["git", "rev-parse", "--git-path", "hooks"],
        cwd=repo,
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    ).stdout.strip()
    hook_dir = Path(hook_dir_text)
    if not hook_dir.is_absolute():
        hook_dir = repo / hook_dir

    source = repo / "tools/hooks/pre-commit"
    target = hook_dir / "pre-commit"
    expected_marker = "tools/check_staged_generated_artifacts.py"
    if target.exists() and expected_marker not in target.read_text(errors="replace"):
        print(f"refusing to overwrite unrelated hook: {target}", file=sys.stderr)
        return 1

    hook_dir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    os.chmod(target, 0o755)
    print(f"installed {target}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
