"""Keep git commands pointed at the repository they name, whatever the environment says.

Git exports `GIT_DIR` and its relatives to what a hook or a merge runs, and those variables win over
`git -C <path>` and over a `git init <path>`'s own target. On 2026-10-21, with `GIT_DIR` naming a linked
worktree's git dir (what git sets for a hook in one), a test's scratch `git init <tmp>/repo` wrote
`core.bare = true` into the shared `.git/config`; every worktree and the lander stopped working until it
was put back. Anything that builds a scratch repository, or that addresses a repository by path, uses
this module so the inherited variables cannot redirect it.

The variable list is git's own: `git rev-parse --local-env-vars`, the set it clears for a submodule.
"""

from __future__ import annotations

import functools
import os
import subprocess
from typing import MutableMapping

_FALLBACK = (
    "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_CONFIG", "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_COUNT",
    "GIT_OBJECT_DIRECTORY", "GIT_DIR", "GIT_WORK_TREE", "GIT_IMPLICIT_WORK_TREE", "GIT_GRAFT_FILE",
    "GIT_INDEX_FILE", "GIT_NO_REPLACE_OBJECTS", "GIT_REPLACE_REF_BASE", "GIT_PREFIX", "GIT_SHALLOW_FILE",
    "GIT_COMMON_DIR",
)


@functools.lru_cache(maxsize=1)
def repository_variables() -> tuple[str, ...]:
    """The variables that tell git which repository it is in."""
    listed = subprocess.run(["git", "rev-parse", "--local-env-vars"], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, text=True, check=False, env=_without(os.environ, _FALLBACK)
                            ).stdout.split()
    return tuple(listed) or _FALLBACK


def _without(environ, names) -> dict[str, str]:
    return {k: v for k, v in environ.items() if k not in names}


def scrubbed_env(environ: MutableMapping[str, str] | None = None) -> dict[str, str]:
    """A copy of *environ* (default: this process's) without the repository variables."""
    return _without(os.environ if environ is None else environ, repository_variables())


def scrub_process_env(environ: MutableMapping[str, str] = os.environ) -> list[str]:
    """Remove the repository variables from *environ* in place; returns the names removed.

    For a test module that builds scratch repositories: call it at import, so every `git` it starts,
    however it starts them, sees no inherited repository.
    """
    return [name for name in repository_variables() if environ.pop(name, None) is not None]
