#!/usr/bin/env python3
"""Install Field's tracked hooks without changing core.hooksPath or discarding other hooks.

Field claims two hook points and neither is exclusively its own:

* ``pre-commit`` guards the generated artifacts a commit touches.
* ``post-merge`` guards the ones a *merge* brought in, which the pre-commit path structurally
  cannot see — ``.git/hooks`` is per-clone and Git never transports it, so work authored in
  another clone and pulled in here has passed no hook at all.

Git LFS already owns ``post-merge`` in this repository. Replacing it would silently break LFS, and
refusing to install would leave the gap open, so a foreign hook is *preserved* alongside as
``<name>.local`` and Field's hook invokes it first. That makes installation idempotent and
reversible: move ``<name>.local`` back to ``<name>`` to undo.
"""

from __future__ import annotations

import filecmp
import os
import shutil
import subprocess
import sys
from pathlib import Path


HOOKS = ("pre-commit", "post-merge")

# Both tracked hooks invoke this script, so its presence identifies a hook as Field's and makes
# reinstalling over an older copy an update rather than a conflict.
FIELD_MARKER = "tools/check_staged_generated_artifacts.py"


def _is_field_hook(path: Path) -> bool:
    return FIELD_MARKER in path.read_text(errors="replace")


def _preserve_foreign_hook(target: Path) -> bool:
    """Move a non-Field hook aside so Field's can chain to it. False if that is not safe."""
    preserved = target.with_name(f"{target.name}.local")
    if preserved.exists():
        if filecmp.cmp(target, preserved, shallow=False):
            return True
        print(
            f"refusing to overwrite {target}: it is not Field's hook, and {preserved} already "
            "exists with different contents. Resolve by hand.",
            file=sys.stderr,
        )
        return False
    shutil.move(str(target), str(preserved))
    os.chmod(preserved, 0o755)
    print(f"preserved existing hook as {preserved} (Field's hook will run it first)")
    return True


def _install(source: Path, target: Path) -> bool:
    if target.exists() and not _is_field_hook(target):
        if not _preserve_foreign_hook(target):
            return False
    shutil.copyfile(source, target)
    os.chmod(target, 0o755)
    print(f"installed {target}")
    return True


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
    hook_dir.mkdir(parents=True, exist_ok=True)

    failed = False
    for name in HOOKS:
        source = repo / "tools/hooks" / name
        if not source.exists():
            print(f"missing tracked hook: {source}", file=sys.stderr)
            failed = True
            continue
        if not _install(source, hook_dir / name):
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
