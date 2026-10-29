#!/usr/bin/env python3
from __future__ import annotations

import argparse
import fnmatch
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

try:
    from .field_python_env import ensure_repo_python
except ImportError:  # Executed as tools/build_zensical_docs.py rather than imported as a package.
    from field_python_env import ensure_repo_python

if __name__ == "__main__":
    # `zensical` runs as `sys.executable -m zensical`, and `public/docs_hooks.py` runs the backdoor
    # with this same interpreter, so all three must be importable here.
    ensure_repo_python(("yaml", "zensical", "websockets"), group="docs")

import yaml  # noqa: E402

from verification_runtime import verification_lock  # noqa: E402

# Where `generate_source_pages` tells `public/docs_hooks.py` to find the basename -> page/anchor map
# that turns a citation into a link. Named here and read there; an absent variable is the signal that
# no source pages exist this run, which is what makes the citation chips degrade rather than break.
SOURCE_PAGE_ANCHORS_ENV = "FIELD_DOCS_SOURCE_PAGE_ANCHORS"

# The served site, `public/field4/`, is only ever replaced whole. Zensical renders into a staging dir
# under `public/.zensical-generated/`, and `install_site` renames it into place once the build is
# known to be complete, so a failed or interrupted build leaves yesterday's site, not half of today's.
SITE_DIR_NAME = "field4"
STAGING_SITE_DIR_NAME = "site"
PREVIOUS_SITE_DIR_NAME = "previous-site"
BUILD_CONFIG_NAME = "zensical.generated.build.yml"
LOCAL_SITE_CAPTURE = re.compile(r"<!--\s*docs-capture-needs-local-site:\s*([a-zA-Z0-9_/-]+)\s*-->")

# Seconds `run_zensical` lets zensical go with no output and under STALL_CPU_SECONDS of CPU before it
# calls the build wedged. A healthy build burns about one CPU-second per second throughout.
ZENSICAL_STALL_ENV = "FIELD_DOCS_ZENSICAL_STALL_SEC"
ZENSICAL_STALL_SECONDS = 120.0
STALL_CPU_SECONDS = 1.0
# A wedge with no file changing under zensical is the machine, not the tree: on 2026-10-24 three lander
# builds in a row sat at "Build started" at 0% CPU in the eleven minutes after the Mac woke from
# hibernate on a flat battery, with nothing written under the watched dirs, and the same build passed
# by hand minutes later and in the lander from then on. Such a wedge is tried again this many times,
# after ZENSICAL_STALL_RETRY_PAUSE seconds. A wedge with files changing is a writer and fails at once.
ZENSICAL_STALL_RETRIES = 1
ZENSICAL_STALL_RETRY_PAUSE = 30.0

# A site missing more than this share of its pages is refused rather than installed.
MAX_MISSING_PAGE_FRACTION = 0.01

# Every failure this script reports on its own starts with this, so `tools/land.py` can quote the
# cause instead of the first line in a docs log that happens to contain "error".
FAILURE_PREFIX = "docs build failed: "


def ceres_library_path(repo_root: Path) -> Path:
    """Where `afield.math.optimization.ceres.CeresNativeLibrary.defaultPath` will look, from Python.

    Kept deliberately in step with that object: the override properties, the three
    platform file names, and the `build/ken-ceres-redistributable` default are the
    same three rules in the same order. `-Dafield.ceres.library` is a JVM system
    property this process cannot see, so only the environment override is honoured
    here; a run that sets the property and not the variable gets a false alarm it
    can silence by exporting `AFIELD_CERES_LIBRARY` too.
    """
    override = os.environ.get("AFIELD_CERES_LIBRARY", "").strip()
    if override:
        return Path(override)
    system = sys.platform
    if system.startswith("win"):
        name = "ken_ceres_jvm.dll"
    elif system == "darwin":
        name = "libken_ceres_jvm.dylib"
    else:
        name = "libken_ceres_jvm.so"
    return repo_root / "build" / "ken-ceres-redistributable" / name


def manifold_library_path(repo_root: Path) -> Path:
    """Where `afield.geometry.solid.manifold.ManifoldLibrary` will look, from Python.

    Deliberately shaped differently from [ceres_library_path], because the two
    optional native dependencies are: the Ceres wrapper is built into `build/` by a
    task in this repo, so there is exactly one path to check. Manifold is installed
    by a package manager, so this walks the same candidate list `ManifoldLibrary`
    does and returns the first hit — falling back to the first candidate so the
    "missing" message names a plausible place rather than nothing. `repo_root` is
    unused for the same reason, and is kept so every entry in
    NATIVE_PREREQUISITES has one signature.
    """
    override = os.environ.get("AFIELD_MANIFOLD_LIBRARY", "").strip()
    if override:
        return Path(override)
    if sys.platform.startswith("win"):
        name, roots = "manifoldc.dll", ["C:\\Program Files\\manifold\\bin"]
    elif sys.platform == "darwin":
        name, roots = "libmanifoldc.dylib", ["/opt/homebrew/lib", "/usr/local/lib"]
    else:
        name, roots = "libmanifoldc.so", [
            "/usr/local/lib",
            "/usr/lib",
            "/usr/lib/x86_64-linux-gnu",
            "/usr/lib/aarch64-linux-gnu",
        ]
    candidates = [Path(root, name) for root in roots]
    return next((c for c in candidates if c.exists()), candidates[0])


def solidcraft_library_path(repo_root: Path) -> Path:
    """The optional Rust bridge built by ``buildSolidCraftNative``."""
    override = os.environ.get("AFIELD_SOLIDCRAFT_LIBRARY", "").strip()
    if override:
        return Path(override).expanduser()
    name = ("field_solidcraft_jvm.dll" if sys.platform.startswith("win") else
            "libfield_solidcraft_jvm.dylib" if sys.platform == "darwin" else
            "libfield_solidcraft_jvm.so")
    return repo_root / "build" / "native" / "solidcraft" / name


def native_geometry_library_path(module: str, target: str, env: str):
    """A path function for one of the optional native geometry libraries
    (`tools/build_native_geometry.py <module>`), shaped like [tetgen_library_path]: the
    environment override, then `build/field-<module>/build[/Release]/`."""

    def resolve(repo_root: Path) -> Path:
        override = os.environ.get(env, "").strip()
        if override:
            return Path(override)
        name = (f"{target}.dll" if sys.platform.startswith("win") else
                f"lib{target}.dylib" if sys.platform == "darwin" else f"lib{target}.so")
        base = repo_root / "build" / f"field-{module}" / "build"
        choices = [base / name, base / "Release" / name]
        return next((c for c in choices if c.exists()), choices[0])

    return resolve


def tetgen_library_path(repo_root: Path) -> Path:
    """Where `afield.geometry.mesh.tetgen.TetgenLibrary.resolvedPath` will look, from Python.

    Shaped like [ceres_library_path] rather than [manifold_library_path], because TetGen
    is the same kind of dependency as Ceres: nothing packages it, so `TetgenLibrary`
    looks only inside this repo's `build/`. It checks two directories, the second being
    the multi-config layout CMake uses on Windows, so this checks both and returns the
    first that exists — falling back to the first candidate so a miss names a plausible
    place. `-Dafield.tetgen.library` is a JVM property this process cannot see, so only
    the environment override is honoured; a run that sets the property and not the
    variable can silence the false alarm by exporting `AFIELD_TETGEN_LIBRARY` too.
    """
    override = os.environ.get("AFIELD_TETGEN_LIBRARY", "").strip()
    if override:
        return Path(override)
    if sys.platform.startswith("win"):
        name = "field_tetgen_jvm.dll"
    elif sys.platform == "darwin":
        name = "libfield_tetgen_jvm.dylib"
    else:
        name = "libfield_tetgen_jvm.so"
    candidates = [
        repo_root / "build" / "field-tetgen" / "build" / name,
        repo_root / "build" / "field-tetgen" / "build" / "Release" / name,
    ]
    return next((c for c in candidates if c.exists()), candidates[0])


def paint_library_path(repo_root: Path) -> Path:
    """Where `afield.graphics.paint.PaintLibrary.resolvedPath` will look, from Python.

    One candidate, where `./gradlew buildPaintNative` writes (cargo's `--target-dir`
    `build/field-paint`, release profile). As with TetGen only the environment override
    is honoured; the JVM property is invisible from here.
    """
    override = os.environ.get("AFIELD_PAINT_LIBRARY", "").strip()
    if override:
        return Path(override)
    if sys.platform.startswith("win"):
        name = "field_paint_jvm.dll"
    elif sys.platform == "darwin":
        name = "libfield_paint_jvm.dylib"
    else:
        name = "libfield_paint_jvm.so"
    return repo_root / "build" / "field-paint" / "release" / name


def libpd_library_path(repo_root: Path) -> Path:
    """Where `afield.audio.pd.PdLibrary.resolvedPath` will look, from Python.

    TetGen's shape again: `tools/build_libpd_native.py` builds the multi-instance libpd into
    this repo's `build/`, with a `Release/` variant for CMake's multi-config layout on
    Windows. Only the environment override is honoured; `-Dafield.pd.library` is a JVM
    property this process cannot see.
    """
    override = os.environ.get("AFIELD_PD_LIBRARY", "").strip()
    if override:
        return Path(override)
    if sys.platform.startswith("win"):
        name = "libpd-multi.dll"
    elif sys.platform == "darwin":
        name = "libpd-multi.dylib"
    else:
        name = "libpd-multi.so"
    candidates = [
        repo_root / "build" / "field-libpd" / "build" / "libs" / name,
        repo_root / "build" / "field-libpd" / "build" / "libs" / "Release" / name,
    ]
    return next((c for c in candidates if c.exists()), candidates[0])


def csound_library_path(repo_root: Path) -> Path:
    """Where `afield.audio.csound.CsoundLibrary.resolvedPath` will look, from Python.

    libpd's shape: `tools/build_csound_native.py` builds libcsound into this repo's `build/`
    (a framework on macOS), and an installed Csound is the fallback. Only the environment override
    is honoured; `-Dafield.csound.library` is a JVM property this process cannot see.
    """
    override = os.environ.get("AFIELD_CSOUND_LIBRARY", "").strip()
    if override:
        return Path(override)
    build = repo_root / "build" / "field-csound" / "build"
    if sys.platform == "darwin":
        candidates = [build / "CsoundLib64.framework" / "CsoundLib64",
                      Path("/Library/Frameworks/CsoundLib64.framework/CsoundLib64")]
    elif sys.platform.startswith("win"):
        candidates = [Path("C:/Program Files/Csound6_x64/bin/csound64.dll")]
    else:
        candidates = [build / "libcsound64.so"] + [
            Path(d) / "libcsound64.so.6.0"
            for d in ("/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/usr/lib64", "/usr/lib", "/usr/local/lib")
        ]
    return next((c for c in candidates if c.exists()), candidates[0])


def luajit_library_path(repo_root: Path) -> Path:
    """Where `afield.lang.lua.LuaLibrary.resolvedPath` will look, from Python.

    Manifold's shape: LuaJIT comes from a package manager (`brew install luajit`), so this walks
    the same directories and file names `LuaLibrary` does and returns the first hit, falling back
    to the first candidate so the "missing" message names a plausible place. Only the environment
    override is honoured; `-Dafield.lua.library` is a JVM property this process cannot see.
    """
    override = os.environ.get("AFIELD_LUA_LIBRARY", "").strip()
    if override:
        return Path(override)
    if sys.platform.startswith("win"):
        return Path("lua51.dll")
    if sys.platform == "darwin":
        names = ["libluajit-5.1.dylib", "libluajit-5.1.2.dylib"]
        roots = ["/opt/homebrew/lib", "/usr/local/lib", "/opt/local/lib"]
    else:
        names = ["libluajit-5.1.so.2", "libluajit-5.1.so"]
        roots = ["/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/usr/lib64", "/usr/lib", "/usr/local/lib"]
    candidates = [Path(root) / name for root in roots for name in names]
    return next((c for c in candidates if c.exists()), candidates[0])


def browser_bridge_library_path(repo_root: Path) -> Path:
    """Where `BrowserHandleTransport.forThisPlatform` will look, from Python.

    Mirrors `MacMachSurfaceTransport`/`LinuxDmaBufTransport`, which build the path as
    `File(projectRoot, "build/field-browser-bridge/<platform>")` and nothing else — there is
    deliberately no environment override here, because the Kotlin side honours none either and
    inventing one would let this checker pass while a capture still fails.

    Windows has no producer bridge at all (`forThisPlatform` errors there), so no path can
    satisfy this on Windows; the returned path is then a diagnostic rather than a target.
    """
    if sys.platform.startswith("win"):
        return repo_root / "build" / "field-browser-bridge" / "unsupported-on-windows"
    platform_dir, name = (
        ("linux", "libfield_browser_bridge.so") if sys.platform.startswith("linux")
        else ("macos", "libfield_browser_bridge.dylib")
    )
    return repo_root / "build" / "field-browser-bridge" / platform_dir / name


def electron_runtime_path(repo_root: Path) -> Path:
    """Where `fieldElectronBinary` will look, from Python.

    The second half of the browser-texture prerequisite and a genuinely separate artifact: the
    bridge is a compiled dylib under `build/`, this is the Electron runtime under
    `electron/node_modules/`, which `clean` does *not* remove and a fresh worktree does *not*
    carry. Two artifacts, two remedies, so two entries.

    Mirrors `fieldElectronBinary`'s candidate list, including that `electronInstall` may rename
    the app to `Field4`, and falls back to the last candidate so a miss names a plausible place.
    """
    dist = repo_root / "electron" / "node_modules" / "electron" / "dist"
    if sys.platform != "darwin":
        return dist / "electron"
    candidates = [
        dist / "Field4.app" / "Contents" / "MacOS" / "Field4",
        dist / "Electron.app" / "Contents" / "MacOS" / "Field4",
        dist / "Electron.app" / "Contents" / "MacOS" / "Electron",
    ]
    return next((c for c in candidates if c.is_file()), candidates[-1])


# A capture that cannot run without a native artifact declares it with `requires="<key>"`
# on its fence. Each key maps to where the artifact lives and the one command that builds
# it. Nothing else reads the attribute -- docs_hooks ignores unknown fence attributes, so
# adding one does not change a fence's capture digest.
def jep_python_candidates() -> Iterator[Path]:
    """Environment/settings candidates in PythonInterface.pythonConfigChoices order.

    Docs launches Field itself; JVM-only system-property overrides are not part of
    this shell preflight. Use FIELD_PYTHON_VENV/EXECUTABLE for docs builds.
    """
    def venv_python(raw):
        root = Path(raw).expanduser() / ("Scripts" if os.name == "nt" else "bin")
        names = ("python.exe", "python3.exe") if os.name == "nt" else ("python3", "python")
        return next((root / name for name in names if (root / name).is_file()
                     and os.access(root / name, os.X_OK)), root / names[0])

    if raw := os.environ.get("FIELD_PYTHON_VENV", "").strip():
        yield venv_python(raw)
    if raw := os.environ.get("FIELD_PYTHON_EXECUTABLE", "").strip():
        yield Path(shutil.which(raw) or raw).expanduser()
    settings = Path.home() / ".afield" / "python.properties"
    if settings.exists():
        values = dict(line.split("=", 1) for line in settings.read_text(encoding="utf-8").splitlines()
                      if "=" in line and not line.startswith("#"))
        if raw := values.get("path", "").strip():
            yield venv_python(raw) if values.get("kind", "venv").strip() == "venv" else Path(raw).expanduser()
    for var in ("VIRTUAL_ENV", "CONDA_PREFIX"):
        if raw := os.environ.get(var, "").strip():
            yield venv_python(raw)
    for name in ("python3", "python"):
        if found := shutil.which(name):
            yield Path(found)


def resolve_jep_python() -> tuple[Path, Path] | None:
    """Find JEP without importing it (standalone CPython cannot import JEP).

    Inspect the same site directories and library globs as PythonInterface;
    return the executable too so module checks cannot silently use another venv.
    """
    script = """
import glob, json, os, site, sys
paths = list(site.getsitepackages()) + [site.getusersitepackages()]
paths += [p for p in sys.path if p and ('site-packages' in p or 'dist-packages' in p)]
libs = [p for sp in paths for pattern in ('libjep*', 'jep*.dll')
        for p in glob.glob(os.path.join(sp, 'jep', pattern)) if os.path.isfile(p)]
print(json.dumps([sys.executable, libs[0] if libs else None]))
"""
    for candidate in dict.fromkeys(jep_python_candidates()):
        try:
            result = subprocess.run([str(candidate), "-c", script], capture_output=True,
                                    text=True, timeout=6, check=False)
            if result.returncode == 0:
                executable, library = json.loads(result.stdout)
                if library:
                    return Path(executable), Path(library)
        except (OSError, subprocess.SubprocessError, ValueError, TypeError):
            continue
    return None


def jep_library_path(repo_root: Path) -> Path:
    """Where `PythonInterface` will find JEP's native library, or a plausible miss.

    Walks the candidates in `resolve_jep_python`: a candidate counts only
    if a `libjep` actually sits in its site-packages. Testing importability instead would be
    wrong — a correctly installed JEP raises `ImportError: Jep is not supported in standalone
    Python`, because it is only ever loaded from inside a JVM.

    `repo_root` supplies the diagnostic path when no candidate has JEP installed.
    """
    if resolved := resolve_jep_python():
        return resolved[1]
    return repo_root / ".venv" / "missing-jep-native-library"


def default_python_venv_to_this_tree(repo_root: Path) -> None:
    """Make Python fences use the venv of the tree being built, not a machine-global one.

    Field resolves its Python/JEP venv from ``~/.afield/python.properties``, which holds an
    absolute path — on this machine, the primary checkout's ``.venv``. A docs build in any other
    worktree therefore runs its Python fences against a *different* tree's interpreter, so what
    the fences import is decided by a file outside the repo and a capture cannot be reproduced
    from a clean checkout. Declaring a dependency in ``pyproject.toml``'s ``docs`` group does not
    help, because the declaration lands in this tree's ``.venv`` and JEP never looks there.

    So prefer this tree's own ``.venv`` when it exists and carries JEP. For the primary checkout
    the two paths coincide and this changes nothing; for a worktree it makes the build
    self-consistent. An explicit ``FIELD_PYTHON_*`` in the environment still wins.

    Deliberately *not* guarded on ``VIRTUAL_ENV``/``CONDA_PREFIX``. In
    ``PythonInterface.pythonConfigChoices`` those rank **below** the persisted welcome setting,
    so an activated venv already loses to ``python.properties`` — and ``uv run`` always exports
    ``VIRTUAL_ENV``, so treating it as intent would make this a no-op on every invocation.
    ``FIELD_PYTHON_VENV`` is used because it is the one env var that ranks above the settings
    file.
    """
    for var in ("FIELD_PYTHON_EXECUTABLE", "FIELD_PYTHON_VENV"):
        if os.environ.get(var, "").strip():
            return

    venv = repo_root / ".venv"
    interpreter = venv / ("Scripts" if os.name == "nt" else "bin") / "python3"
    if not interpreter.exists():
        return
    if not list(venv.glob("lib/python*/site-packages/jep")):
        return

    os.environ["FIELD_PYTHON_VENV"] = str(venv)
    os.environ["FIELD_PYTHON_EXECUTABLE"] = str(interpreter)


def steam_audio_library_path(repo_root: Path) -> Path:
    """Where `./gradlew fetchSteamAudioNatives` unpacks this host's ``libphonon``.

    The layout of ``steamAudioNativeTree`` in ``build.gradle.kts``, which puts that directory on the
    runtime classpath for ``SteamAudioNatives`` to find. ``-PsteamaudioLibrary`` is a Gradle
    property this process cannot see, so a library supplied that way is not detected here.
    """
    if sys.platform.startswith("win"):
        platform, name = "windows-x64", "phonon.dll"
    elif sys.platform == "darwin":
        platform, name = "macos", "libphonon.dylib"
    else:
        platform, name = "linux-x64", "libphonon.so"
    return repo_root / "build" / "steamaudio" / platform / name


def dirt_samples_root_path(repo_root: Path) -> Path:
    """The pinned Dirt-Samples subset, when it is complete and every file has its pinned digest.

    Not a native library, but listed here for the same reason the libraries are: the docs' sample
    clips (``sound_paths.md``, ``boolean_network_music_examples.md``, ``sound.md``'s ``beat-dirt``)
    are rendered from it, nothing vendors it, and a render without it fails rather than falling
    back. ``tools/fetch_dirt_samples.py`` owns the manifest and the cache layout;
    ``DirtRender.pinnedSampleRoot()`` reads the same manifest on the JVM side.
    """
    tools_dir = str(repo_root / "tools")
    if tools_dir not in sys.path:
        sys.path.insert(0, tools_dir)
    from fetch_dirt_samples import load_manifest, pinned_root, problems_in

    manifest = load_manifest()
    root = pinned_root(manifest)
    return root if root.is_dir() and not problems_in(root, manifest) else root / "incomplete"


def loris_executable_path(repo_root: Path) -> Path:
    override = os.environ.get("AFIELD_LORIS_EXECUTABLE", "").strip()
    if override:
        return Path(override).expanduser()
    name = "field_loris.exe" if sys.platform == "win32" else "field_loris"
    base = repo_root / "build/field-loris/build"
    choices = [base / name, base / "Release" / name]
    return next((p for p in choices if p.is_file()), choices[0])


NATIVE_PREREQUISITES: dict[str, tuple[Any, str]] = {
    "loris": (loris_executable_path, "./gradlew buildLorisNative"),
    "ceres": (ceres_library_path, "./gradlew buildCeresRedistributable"),
    "manifold": (manifold_library_path, "brew install manifold"),
    "solidcraft": (solidcraft_library_path, "./gradlew buildSolidCraftNative"),
    "tetgen": (tetgen_library_path, "./gradlew buildTetgenNative"),
    "morse-smale": (
        native_geometry_library_path("morse-smale", "field_morse_smale", "AFIELD_MORSE_SMALE_LIBRARY"),
        "./gradlew buildMorseSmaleNative",
    ),
    "principal-curves": (
        native_geometry_library_path("principal-curves", "field_principal_curves", "AFIELD_PRINCIPAL_CURVES_LIBRARY"),
        "./gradlew buildPrincipalCurvesNative",
    ),
    "polyvector": (
        native_geometry_library_path("polyvector", "field_polyvector", "AFIELD_POLYVECTOR_LIBRARY"),
        "brew install opencv boost eigen (apt install libopencv-dev libboost-dev libeigen3-dev), then ./gradlew buildPolyvectorNative",
    ),
    "paint": (paint_library_path, "./gradlew buildPaintNative"),
    "libpd": (libpd_library_path, "./gradlew buildLibpdNative"),
    "csound": (csound_library_path, "./gradlew buildCsoundNative"),
    "luajit": (luajit_library_path, "brew install luajit (or apt install libluajit-5.1-2)"),
    "browser-bridge": (browser_bridge_library_path, "./gradlew buildFieldBrowserBridge"),
    "electron-runtime": (electron_runtime_path, "./gradlew electronInstall"),
    "dirt-samples": (dirt_samples_root_path, "./gradlew fetchDocsDirtSamples"),
    "steam-audio": (steam_audio_library_path, "./gradlew fetchSteamAudioNatives"),
    "jep": (
        jep_library_path,
        "pip install 'jep==4.3.1' jedi into the interpreter Field resolves, then point "
        "FIELD_PYTHON_EXECUTABLE at it (on macOS the build needs "
        "CFLAGS=\"-isysroot $(xcrun --show-sdk-path)\")",
    ),
}

REQUIRES_ATTR_RE = re.compile(r'(?m)^```\w*_?runner\b[^\n]*?\brequires="([^"]*)"')


# The task a remedy ends in: `./gradlew buildPaintNative`, or `brew install ..., then ./gradlew buildPolyvectorNative`.
GRADLE_PREREQUISITE_RE = re.compile(r"(?:^|, then )\./gradlew (\w+)$")


def refresh_native_prerequisites(repo_root: Path, needed: dict[str, list[str]]) -> None:
    """Run the Gradle task behind each needed native artifact, so a stale one is rebuilt.

    The existence check below cannot see staleness: a `libfield_paint_jvm.dylib` built before
    `field_paint_stroke_noise` existed passes it, and the capture that first calls the new entry
    point fails with an UnsatisfiedLinkError an hour into the build. The tasks declare their
    sources as inputs, so a current artifact costs Gradle's up-to-date check and nothing more.
    A failure here (no cargo, no network) is left to the existence check, which already decides
    whether a missing artifact is a warning or an error in this mode.
    `FIELD_DOCS_REFRESH_NATIVES=0` skips it.
    """
    if os.environ.get("FIELD_DOCS_REFRESH_NATIVES", "1").strip() == "0":
        return
    gradlew = repo_root / "gradlew"
    if not gradlew.is_file():
        return
    tasks = sorted({
        match.group(1)
        for key in needed
        if key in NATIVE_PREREQUISITES
        and (match := GRADLE_PREREQUISITE_RE.search(NATIVE_PREREQUISITES[key][1]))
    })
    if not tasks:
        return
    started = time.monotonic()
    from verification_runtime import pinned_graalvm_args  # noqa: PLC0415
    result = subprocess.run(
        [str(gradlew), *pinned_graalvm_args(repo_root), "--no-daemon", "-q", "--continue", *tasks],
        cwd=repo_root,
        check=False,
    )
    if result.returncode != 0:
        print(
            f"Refreshing native prerequisites ({', '.join(tasks)}) failed; checking what exists.",
            file=sys.stderr,
        )
        return
    print(f"Native prerequisites current ({', '.join(tasks)}) in {time.monotonic() - started:.1f}s")


def check_native_prerequisites(
    *,
    public_dir: Path,
    repo_root: Path,
    page_filters: tuple[str, ...],
    runner_mode: str,
) -> None:
    """Fail in a second on a missing native artifact, instead of 38 minutes in.

    `clean` deletes `build/`, and the Ceres wrapper that lives there is an optional
    extra that no task in the docs path declares a dependency on -- so a clean tree
    (including every fresh clone) used to run 311 captures and then die on the 312th
    with the FFM linker's "Cannot open library". The artifact was never going to
    appear in between, so there was nothing to learn from those 38 minutes.

    Only a full recapture is gated. Under `incremental` the committed asset is
    normally reused and Ceres is genuinely not needed, so a missing artifact is a
    warning there: blocking it would stop a fresh clone from building the docs at all,
    which is the same class of mistake in the other direction.
    """
    if runner_mode == "never":
        return
    source_docs = public_dir / "docs"
    needed: dict[str, list[str]] = {}
    for path in sorted(source_docs.rglob("*.md")):
        relative = path.relative_to(source_docs)
        if page_filters and not matches_page_filter(relative, page_filters):
            continue
        for match in REQUIRES_ATTR_RE.finditer(path.read_text(encoding="utf-8")):
            for raw_key in match.group(1).split(","):
                key = raw_key.strip()
                key = "python:" + key.split(":", 1)[1] if key.lower().startswith("python:") else key.lower()
                if not key:
                    continue
                if key not in NATIVE_PREREQUISITES and not re.fullmatch(r"python:[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*", key):
                    raise RuntimeError(
                        f"{relative} declares requires=\"{key}\", which is not a known native "
                        f"prerequisite. Known: {', '.join(sorted(NATIVE_PREREQUISITES))}, python:<module>."
                    )
                needed.setdefault(key, [])
                if relative.as_posix() not in needed[key]:
                    needed[key].append(relative.as_posix())

    refresh_native_prerequisites(repo_root, needed)

    missing: list[str] = []
    python = resolve_jep_python() if any(key.startswith("python:") for key in needed) else None
    for key, pages in sorted(needed.items()):
        if key.startswith("python:"):
            module = key.removeprefix("python:")
            detail = "No JEP-capable interpreter resolved"
            if python:
                try:
                    result = subprocess.run([str(python[0]), "-c",
                        "import importlib, sys; importlib.import_module(sys.argv[1])", module],
                        capture_output=True, text=True, timeout=30, check=False)
                    if result.returncode == 0:
                        continue
                    detail = (result.stderr or result.stdout).strip()[-2000:]
                except (OSError, subprocess.SubprocessError) as exc:
                    detail = str(exc)
            missing.append(f"  {key}: import failed in {python[0] if python else 'unresolved Python'}: {detail}\n"
                           f"    needed by: {', '.join(pages)}\n"
                           "    install the docs dependencies into that interpreter; for the repo venv: "
                           "uv sync --group docs --group backdoor")
            continue
        resolve, how_to_build = NATIVE_PREREQUISITES[key]
        artifact = resolve(repo_root)
        if artifact.exists():
            continue
        missing.append(
            f"  {key}: no artifact at {artifact}\n"
            f"    needed by: {', '.join(pages)}\n"
            f"    build it with: {how_to_build}"
        )
    if not missing:
        return
    detail = "\n".join(missing)
    if runner_mode != "always":
        print(
            "Warning: a native prerequisite is missing. Captures that need it will fail if the "
            f"cache does not cover them:\n{detail}",
            file=sys.stderr,
        )
        return
    raise RuntimeError(
        "This is a full recapture (FIELD_DOCS_RUNNER_MODE=always) and a native prerequisite is "
        f"missing, so a capture will certainly fail:\n{detail}"
    )


def _configure_logging() -> None:
    """Surface the docs runner's per-capture progress.

    The runner logs rich progress through `logging.getLogger("zensical.field_runner")`
    at INFO, but running the preprocessor standalone leaves the root logger
    unconfigured, so only WARNINGs reach the terminal and a build looks
    silent/hung between the sparse warnings. Configure a concise INFO handler once
    (respect FIELD_DOCS_LOG_LEVEL for override) so `capturing ... / completed ...`
    lines are visible.
    """
    if logging.getLogger().handlers:
        return
    level = os.environ.get("FIELD_DOCS_LOG_LEVEL", "INFO").upper()
    logging.basicConfig(
        level=getattr(logging, level, logging.INFO),
        format="%(asctime)s %(message)s",
        datefmt="%H:%M:%S",
    )


class DocsConfig(dict):
    def __init__(self, *, config_file_path: Path, docs_dir: Path, site_dir: Path) -> None:
        super().__init__(
            docs_dir=str(docs_dir),
            site_dir=str(site_dir),
            use_directory_urls=True,
        )
        self.config_file_path = str(config_file_path)


class GeneratedFiles:
    def get_file_from_path(self, relative: str) -> None:
        del relative
        return None

    def append(self, file: Any) -> None:
        del file


# The three markdown files Field itself writes into a workspace directory: the onboarding pair
# `ensureAgentGuides` copies verbatim into every document folder, and the narrative mirror of
# `metadata.edn` that `saving.kt` keeps beside it for code and AI tools. Matched by name rather
# than by "any markdown under assets/", because a few asset folders also hold hand-written prose
# that *is* a page and *is* linked from one -- `canonical_graphics/LESSONS.md` from
# `afield_four_graphics.md`, `fabricate_demo/ATTRIBUTION.md` from `path_tracing.md`. Excluding
# those broke both links and cost the build two `page does not exist` warnings, which is what a
# `--strict` CI build fails on.
WORKSPACE_MARKDOWN_FILENAMES = frozenset({"README.md", "AGENTS.md", "document.md"})


def is_workspace_markdown(relative: Path) -> bool:
    """True for a markdown file that belongs to a downloadable workspace, not to the site.

    Every `assets/<name>/` folder under the docs root is a *Field workspace* -- a document the
    gallery offers as a starting point -- and none of the three files Field writes into one is a
    page: 96 transcripts means 96 byte-identical copies of the same two guides, and a rendered
    `document.md` is a wall of EDN. Built as pages they were 312 orphans that nothing on the site
    linked to, reachable only by turning up in search -- 1265 of 6673 index entries, 2.3 MB of a
    10.2 MB `search.json`, mostly 96-fold duplicates crowding out real hits.

    So they are copied verbatim into the built site by `copy_workspace_markdown` instead of being
    rendered, which keeps the raw file at its own URL (`assets/<name>/document.md`) for whoever
    wants to read the workspace on disk, and keeps the thing the gallery actually links --
    `metadata.edn`, opened in the offline viewer -- untouched.
    """
    return relative.name in WORKSPACE_MARKDOWN_FILENAMES and relative.parts[:1] == ("assets",)


# The two ways a page names a local file: an HTML attribute, and a markdown link/image target.
# Only a ref that already starts with `../` can over-climb.
#
# `src` and `href` ONLY, and `poster` deliberately excluded: zensical's rebasing is
# attribute-selective, which nothing in the tree said out loud until this pass got it wrong and
# `tools/check_doc_site_links.py` caught it. Measured on `rectsl_examples.md`, from one `<video>`
# tag rewritten to be file-relative throughout: `src="assets/rectsl/x.mp4"` came out
# `../assets/rectsl/x.mp4` (rebased) while `poster="assets/rectsl/x.png"` came out unchanged, so
# it resolved inside the page's own URL directory and 404'd. A `poster` must therefore be written
# in the *published URL's* frame -- the opposite convention to everything beside it.
#
# `public/docs/vision_geometry.md:340` is the witness that someone already found this empirically:
# one tag carrying `src="assets/..."` and `poster="../assets/..."` together, each correct. All 39
# `poster=` attributes in the tree (on 2 of 500 pages) are `../`-framed, and all 39 resolve.
ESCAPING_ATTR_REF_RE = re.compile(r'\b(?:src|href)\s*=\s*"(?P<ref>\.\./[^"\s]*)"')
ESCAPING_MARKDOWN_REF_RE = re.compile(r'\]\(\s*(?P<ref>\.\./[^)\s]*)')
OVER_CLIMB = "../"


def normalize_over_climbing_refs(*, markdown: str, page_path: Path, docs_dir: Path, docs_hooks: Any) -> tuple[str, list[tuple[str, str]]]:
    """Re-seat local refs written from the published URL's frame instead of the file's.

    `public/docs_hooks.py:page_directory_depth` records the contract this enforces: zensical rebases
    a relative `href`/`src`/markdown target for the page's *URL* depth, so the source must name a
    file relative to the markdown file's own directory and let the build add the `../`. A page that
    helpfully writes the `../` itself gets a second one, and its refs leave the site. That is
    invisible while the site is served at `/`, because `..` clamps at the server root -- and it is a
    404 the moment the site is served under a path prefix, which is exactly how this one is
    published (`site_url: http://openendedgroup.com/Field4/`). Measured before this existed:
    `rectsl_examples.md`'s 12 `<img src="../assets/rectsl/...">` rendered as `../../assets/...` and
    404'd under a simulated `/Field4/` prefix, while `rectsl_cheatsheet.md`'s three `<img
    src="assets/rectsl/...">` -- the same construct, written file-relative -- served 200.

    Two conditions must both hold before anything is touched, which is what keeps a correct ref
    safe: in the source frame the ref must land outside the docs root, so it cannot be what the
    author meant; and one level shallower it must land on a file that really exists inside the docs
    tree, so the repair is confirmed rather than inferred. Dropping exactly one `../` is the whole
    repair, because directory URLs put a page exactly one directory deeper than its markdown sat.
    A ref failing either test is left as written, for `tools/check_doc_site_links.py` to report --
    a rewrite that cannot name the file it repaired is a rewrite that should not happen.

    Lives here rather than in `public/docs_hooks.py` deliberately. That module is in
    `RUNNER_DIGEST_SOURCE_FILES`, so any edit to it changes every capture's digest and a warm tree's
    next incremental build would re-run all 633 captures to produce identical pictures. This file is
    not in that list, so the same transform here costs nothing.

    Returns the rewritten markdown and the `(before, after)` pairs, so the caller can report them.
    """
    docs_root = docs_dir.resolve()
    page_dir = page_path.resolve().parent
    repairs: list[tuple[str, str]] = []

    def repair(match: re.Match[str]) -> str:
        ref = match.group("ref")
        target = ref.split("#", 1)[0].split("?", 1)[0]
        if docs_hooks.path_is_relative_to(page_dir / target, docs_root):
            return match.group(0)
        candidate = page_dir / target[len(OVER_CLIMB):]
        if not candidate.is_file() or not docs_hooks.path_is_relative_to(candidate, docs_root):
            return match.group(0)
        reseated = ref[len(OVER_CLIMB):]
        repairs.append((ref, reseated))
        return match.group(0).replace(ref, reseated, 1)

    # Fenced blocks are skipped on the same grounds `docs_hooks.rewrite_source_citations` skips them:
    # a fence showing what an `<img>` looks like must keep showing it.
    out: list[str] = []
    closing: re.Pattern[str] | None = None
    for line in markdown.splitlines(keepends=True):
        if closing is not None:
            out.append(line)
            if closing.match(line):
                closing = None
            continue
        generic = docs_hooks.GENERIC_FENCE_OPEN_RE.match(line)
        if generic is not None:
            out.append(line)
            closing = re.compile(rf"^[ ]{{0,3}}{re.escape(generic.group('fence'))}\s*(?:\r?\n)?$")
            continue
        out.append(ESCAPING_MARKDOWN_REF_RE.sub(repair, ESCAPING_ATTR_REF_RE.sub(repair, line)))
    return "".join(out), repairs


def copy_non_markdown_files(source: Path, target: Path) -> None:
    for path in source.rglob("*"):
        if not path.is_file() or path.suffix == ".md":
            continue
        relative = path.relative_to(source)
        destination = target / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, destination)


def matches_page_filter(relative: Path, filters: tuple[str, ...]) -> bool:
    rel = relative.as_posix()
    stem = relative.stem
    name = relative.name
    for raw_filter in filters:
        page_filter = raw_filter.strip().replace("\\", "/")
        if not page_filter:
            continue
        if page_filter in {rel, name, stem}:
            return True
        if "/" not in page_filter and "." not in page_filter and page_filter == stem:
            return True
        if fnmatch.fnmatch(rel, page_filter) or fnmatch.fnmatch(name, page_filter):
            return True
    return False


def generate_source_pages(*, repo_root: Path, generated_docs: Path, enabled: bool) -> Path | None:
    """Render the source pages into the docs tree, and return the anchor index if it worked.

    These are the only generated docs pages that are *not* committed, because a source mirror moves
    whenever any line of its source moves; see `afield.tools.docs.SourcePageGenerator` for the argument.
    That makes them a build step, and a build step that needs a compiled Kotlin tree — which a
    docs-only contributor may not have. So a failure here is a warning rather than an error: the
    citation linker in `docs_hooks` links a citation only when its page was generated, so the site
    degrades to the unlinked chips it had before rather than to a page of dead links. Under `--strict`
    the caller turns the warning into a failure, the same trade `check_doc_assets.py` makes above.
    """
    if not enabled:
        return None
    gradlew = repo_root / "gradlew"
    if not gradlew.is_file():
        print(f"No {gradlew}; skipping source pages.", file=sys.stderr)
        return None
    started = time.monotonic()
    # The task writes build/source-pages with declared inputs, so it is skipped when nothing it reads
    # changed and restored from the build cache in a fresh worktree; the copy below is what goes into
    # the docs tree this script wipes on every build.
    from verification_runtime import pinned_graalvm_args  # noqa: PLC0415
    result = subprocess.run(
        [str(gradlew), *pinned_graalvm_args(repo_root), "--no-daemon", "-q", "generateSourcePages"],
        cwd=repo_root,
        check=False,
    )
    if result.returncode != 0:
        print(
            "Source page generation failed; the site will build without them and citations will "
            "stay unlinked. Run ./gradlew generateSourcePages to see why.",
            file=sys.stderr,
        )
        return None
    shutil.copytree(repo_root / "build" / "source-pages", generated_docs, dirs_exist_ok=True)
    anchors = generated_docs / "reference" / "source" / "anchors.json"
    pages = len(list((generated_docs / "reference" / "source").rglob("*.md")))
    print(f"Generated {pages} source page(s) in {time.monotonic() - started:.1f}s")
    return anchors if anchors.is_file() else None


def source_page_nav(generated_docs: Path) -> list[Any]:
    """One nav entry for the source tree: its overview page, and nothing else.

    The obvious thing is a nav subtree with all 240 files in it, grouped by package. Measured, that is
    a bad trade: material renders the whole nav into *every* page, so listing them took each of the
    site's ~350 pages from 97 KB to 198 KB, the site from 396 MB to 528 MB, and the docs build from
    21s to 63s. None of that buys much — the overview table lists the same files with their line and
    declaration counts, and the ways anyone actually arrives at one of these pages are a citation in
    the prose, a row in the reference, or that table.

    Built here rather than in `public/zensical.yml` because the pages are a build artifact: an entry in
    the tracked config would describe a file a fresh checkout does not have.
    """
    if not (generated_docs / "reference" / "source" / "index.md").is_file():
        return []
    return [{"Source": "reference/source/index.md"}]


def build_generated_config(
    *,
    public_dir: Path,
    generated_root: Path,
    source_nav: list[Any] | None = None,
) -> Path:
    source_config = public_dir / "zensical.yml"
    with source_config.open("r", encoding="utf-8") as handle:
        config = yaml.load(handle, Loader=yaml.Loader)  # noqa: S506 - existing config uses Python tags.

    config["docs_dir"] = os.path.relpath(generated_root / "docs", public_dir)
    config["site_dir"] = SITE_DIR_NAME

    if source_nav:
        # Hang the generated tree off the existing "Reference" section rather than making a new
        # top-level one: the reference index is the page that already answers "what is in here?", and
        # the source pages are the next click from it.
        for entry in config.get("nav", []):
            if isinstance(entry, dict) and "Reference" in entry:
                entry["Reference"].extend(source_nav)
                break
        else:
            config.setdefault("nav", []).extend(source_nav)

    theme = config.get("theme")
    if isinstance(theme, dict) and "custom_dir" in theme:
        theme["custom_dir"] = str(theme["custom_dir"])

    # Two configs that differ only in `site_dir`: `zensical.generated.yml` writes `field4/` and is the
    # one to `zensical serve`; the build config writes the staging dir that `install_site` swaps in.
    # Both sit in `public/` because zensical keys its cache (`.cache/`) and every relative path on the
    # config file's own directory.
    target_config = public_dir / "zensical.generated.yml"
    with target_config.open("w", encoding="utf-8") as handle:
        yaml.dump(config, handle, sort_keys=False, allow_unicode=True)
    config["site_dir"] = os.path.relpath(generated_root / STAGING_SITE_DIR_NAME, public_dir)
    build_config = public_dir / BUILD_CONFIG_NAME
    with build_config.open("w", encoding="utf-8") as handle:
        yaml.dump(config, handle, sort_keys=False, allow_unicode=True)
    return build_config


def preprocess_docs(*, public_dir: Path, generated_docs: Path, page_filters: tuple[str, ...] = ()) -> None:
    _configure_logging()
    sys.path.insert(0, str(public_dir))
    import docs_hooks  # type: ignore[import-not-found]

    source_docs = public_dir / "docs"
    config = DocsConfig(
        config_file_path=public_dir / "zensical.yml",
        docs_dir=source_docs,
        site_dir=public_dir / "field4",
    )
    files = GeneratedFiles()
    state = docs_hooks.ensure_state(config)
    runner_mode = state.runner_mode
    matched_pages = 0
    total_pages = 0
    reseated_refs: list[tuple[str, str, str]] = []

    try:
        for path in sorted(source_docs.rglob("*.md")):
            relative = path.relative_to(source_docs)
            if is_workspace_markdown(relative):
                # Copied verbatim below rather than rendered; see is_workspace_markdown.
                continue
            destination = generated_docs / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            markdown = path.read_text(encoding="utf-8")
            total_pages += 1
            page_matches = not page_filters or matches_page_filter(relative, page_filters)
            if page_matches:
                matched_pages += 1

            previous_mode = state.runner_mode
            if page_filters and not page_matches:
                state.runner_mode = "never"
            try:
                processed = docs_hooks.process_markdown(
                    markdown=markdown,
                    page_path=path.resolve(),
                    state=state,
                    files=files,
                )
            finally:
                state.runner_mode = previous_mode
            # After process_markdown, not before, so that fence-emitted refs are covered by the
            # same rule as author-written ones rather than trusted to a second convention.
            processed, repairs = normalize_over_climbing_refs(
                markdown=processed,
                page_path=path.resolve(),
                docs_dir=source_docs,
                docs_hooks=docs_hooks,
            )
            for before, after in repairs:
                reseated_refs.append((relative.as_posix(), before, after))
            destination.write_text(processed, encoding="utf-8")
    finally:
        state.runner_mode = runner_mode
        docs_hooks.on_post_build(config)

    if reseated_refs:
        by_page: dict[str, int] = {}
        for page_rel, _before, _after in reseated_refs:
            by_page[page_rel] = by_page.get(page_rel, 0) + 1
        print(
            f"Re-seated {len(reseated_refs)} over-climbing local ref(s) on {len(by_page)} page(s) "
            f"so they stay inside the published path prefix:"
        )
        for page_rel in sorted(by_page):
            print(f"  {page_rel}: {by_page[page_rel]}")
        print(
            "  These were written from the published URL's point of view; zensical adds the `../` "
            "itself.\n  Fixing them in the page source is better than relying on this pass -- see "
            "normalize_over_climbing_refs."
        )

    if page_filters and matched_pages == 0:
        requested = ", ".join(page_filters)
        raise RuntimeError(f"No docs pages matched --page filter(s): {requested}")
    if page_filters:
        print(f"Processed {matched_pages}/{total_pages} docs page(s) with runner mode {runner_mode}; non-matching pages used runner mode never.")

    copy_non_markdown_files(source_docs, generated_docs)


def copy_workspace_markdown(*, source_docs: Path, site_dir: Path) -> int:
    """Emit the workspace markdown the page pipeline skipped, as raw files in the built site.

    Runs after the Zensical build rather than into the generated docs tree, because Zensical
    renders every markdown file it finds under `docs_dir` -- a copy there would be the page again.
    Landing them in the site dir instead means `assets/<name>/document.md` serves its own text and
    nothing indexes a 96-fold duplicate of a workspace guide. See `is_workspace_markdown`.
    """
    copied = 0
    for path in sorted(source_docs.rglob("*.md")):
        relative = path.relative_to(source_docs)
        if not is_workspace_markdown(relative):
            continue
        destination = site_dir / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, destination)
        copied += 1
        prune_stale_page(source_docs=source_docs, site_dir=site_dir, relative=relative)
    return copied


def prune_stale_page(*, source_docs: Path, site_dir: Path, relative: Path) -> None:
    """Delete the page an earlier build rendered from this file, so an incremental build drops it.

    `zensical build` without `--clean` leaves whatever it wrote last time in place, and the site dir
    is git-ignored, so a tree that built these as pages keeps serving them (and keeps them in the
    stale `search.json`) until someone cleans. Two output shapes to undo, from `use_directory_urls`:
    `document.md` became `document/index.html`, and `README.md` became the folder's own
    `index.html` -- which is only removed when the source folder has no static `index.html` of its
    own to be confused with (`assets/mode/**` has several).
    """
    if relative.name == "README.md":
        if (source_docs / relative.parent / "index.html").exists():
            return
        rendered = site_dir / relative.parent / "index.html"
    else:
        rendered = site_dir / relative.parent / relative.stem / "index.html"
    if not rendered.is_file():
        return
    rendered.unlink()
    parent = rendered.parent
    if parent != site_dir / relative.parent and not any(parent.iterdir()):
        parent.rmdir()


def emit_build_info(*, site_dir: Path, repo_root: Path) -> None:
    """Write `field-docs-build.json`: which commit this site was built from.

    The annotation panel (`docsAnnotations.js`) copies `commit` into every note it saves, and
    `tools/docs_notes.py sync` matches the note's quote against the page's Markdown *at that
    commit* before tracking it forward with `git blame`. Without it a note written on Monday and
    synced on Friday is matched against Friday's text. `docsDirty` records that the Markdown had
    uncommitted edits, in which case the working tree, not the commit, is what was rendered.
    """
    def git(*args: str) -> str:
        result = subprocess.run(["git", *args], cwd=repo_root, capture_output=True, text=True, check=False)
        return result.stdout.strip() if result.returncode == 0 else ""

    info = {
        "commit": git("rev-parse", "HEAD"),
        "docsDirty": bool(git("status", "--porcelain", "--", "public/docs")),
        "builtAtEpochMs": int(time.time() * 1000),
    }
    (site_dir / "field-docs-build.json").write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")


def emit_offline_viewer(*, site_dir: Path) -> None:
    """Copy the offline document viewer into the built site.

    The docs embed a read-only Field viewer via `<iframe src="/offline.html?...">`.
    `offline.html` and its bundle (Monaco, fonts, the viewer JS) are otherwise only
    served by the running Field app, so a plain static serving of the built site
    would 404 the iframe. Emitting the bundle into the site dir makes the viewer
    self-contained: `/offline.html` and its relative assets resolve against the
    site root, and the viewer resolves `?file=` paths relative to that same root.

    Reuses `tools/sync_offline_assets.py` so the canonical source files stay the
    single source of truth. Metadata examples and the distribution README/manifest
    are skipped -- the docs carry their own `assets/*/metadata.edn` files.
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import sync_offline_assets  # type: ignore[import-not-found]

    sync_offline_assets.sync_target(site_dir, metadata=False, docs=False)
    import docs_publications

    (site_dir / "offline-publications.json").write_text(
        json.dumps(docs_publications.registry(), indent=2) + "\n", encoding="utf-8"
    )


def remove_tree(path: Path, *, attempts: int = 5) -> bool:
    """`shutil.rmtree` that outlasts Finder. Finder (and Spotlight) can write a `.DS_Store` into a
    directory between rmtree emptying it and the final rmdir, which fails "Directory not empty";
    each retry clears whatever reappeared. True once [path] is gone."""
    for attempt in range(attempts):
        try:
            shutil.rmtree(path)
            return True
        except FileNotFoundError:
            return True
        except OSError:
            if not path.exists():
                return True
            if attempt == attempts - 1:
                return False
            time.sleep(0.1 * (attempt + 1))
    return not path.exists()


# --- Running zensical ----------------------------------------------------------------------------
#
# `zensical build` (0.0.47) watches its inputs with FSEvents even when it only builds. Its file
# monitor (`zensical-watch`, `agent/handler.rs`) drains the files it has found only after 20 ms with
# no new file event, and lets the build finish only after 100 ms with none at all. A process that
# keeps writing under a watched directory -- the docs dir, the theme dirs, or the site dir -- keeps
# zensical waiting at "Build started" with idle executors and no error. On 2026-10-09 Finder, which
# had `public/field4/` open, kept writing `.DS_Store` files into it, and four lander docs builds sat
# at 0% CPU until someone interrupted them. Rendering into a staging dir nobody has open removes that
# writer; `run_zensical` stops a build that wedges anyway, instead of leaving it to the lander's
# 30-minute watchdog. And zensical exits 0 after "Received interrupt, exiting" (`lib.rs`), so its exit
# code alone cannot tell a finished build from an interrupted one.

ZENSICAL_FINISHED = re.compile(r"^Build finished in ")
ZENSICAL_INTERRUPTED = "Received interrupt, exiting"
# Printed once the pages have rendered (`python/issues.rs`). "Build finished" follows at once.
ZENSICAL_REPORTED = re.compile(r"^(No issues found|\d+ issues? found)$")


@dataclass
class ZensicalRun:
    returncode: int | None  # None when run_zensical stopped it.
    finished: bool = False  # Printed "Build finished in ...".
    interrupted: bool = False  # Printed "Received interrupt, exiting".
    stalled: str | None = None  # Why run_zensical stopped it.
    changed: int = 0  # Files changed under the watched dirs while it ran, counted when it stalled.

    def failure(self) -> str | None:
        """Why this run is not a complete build, or None if it is."""
        if self.stalled:
            return f"zensical stalled ({self.stalled}) and was stopped"
        if self.interrupted:
            return f"zensical was interrupted ({ZENSICAL_INTERRUPTED!r}), which it reports as exit code 0"
        if self.returncode != 0:
            return f"zensical exited with code {self.returncode}"
        if not self.finished:
            return "zensical exited 0 without printing 'Build finished'"
        return None


def process_cpu_seconds(pid: int) -> float | None:
    """User + system CPU time of a running process, or None if it cannot be read."""
    stat = Path(f"/proc/{pid}/stat")
    if stat.exists():
        try:
            fields = stat.read_text().rsplit(")", 1)[1].split()
            return (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK")
        except (OSError, ValueError, IndexError):
            return None
    try:
        out = subprocess.run(["ps", "-o", "time=", "-p", str(pid)], capture_output=True, text=True,
                             check=False, timeout=10).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None
    if not out:
        return None
    # macOS prints `M:SS.ss` (or `H:MM:SS.ss`), Linux `[DD-]HH:MM:SS`.
    days, _, clock = out.rpartition("-")
    try:
        seconds = 0.0
        for part in clock.split(":"):
            seconds = seconds * 60 + float(part)
        return seconds + (int(days) * 86400 if days else 0)
    except ValueError:
        return None


def files_changed_since(roots: list[Path], since: float, *, limit: int = 8) -> tuple[int, list[Path]]:
    """Files under [roots] modified after the wall-clock time [since]: how many, and the first few."""
    found: list[Path] = []
    count = 0
    for root in roots:
        for dirpath, _, filenames in os.walk(root):
            for name in filenames:
                path = Path(dirpath) / name
                try:
                    if path.stat().st_mtime > since:
                        count += 1
                        if len(found) < limit:
                            found.append(path)
                except OSError:
                    continue
    return count, found


def seconds_since_wake() -> float | None:
    """Seconds since macOS last woke from sleep (`kern.waketime`), or None elsewhere or if unknown."""
    if sys.platform != "darwin":
        return None
    try:
        out = subprocess.run(["sysctl", "-n", "kern.waketime"], capture_output=True, text=True, timeout=5).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    match = re.search(r"sec = (\d+)", out)
    if not match or int(match.group(1)) == 0:
        return None
    return max(0.0, time.time() - int(match.group(1)))


def run_zensical(command: list[str], *, cwd: Path, watched: list[Path], stall_seconds: float) -> ZensicalRun:
    """Run zensical, echoing its output, and stop it if it wedges.

    Wedged is either of the two ways the file monitor holds a build open (see above): nothing printed
    and under STALL_CPU_SECONDS of CPU for [stall_seconds], or the issue report printed and no
    "Build finished" [stall_seconds] later, however busy the monitor is.
    """
    started_wall = time.time()
    proc = subprocess.Popen(command, cwd=cwd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, errors="replace", bufsize=1)
    run = ZensicalRun(returncode=None)
    last_line = [time.monotonic()]
    reported_at: list[float | None] = [None]

    def pump() -> None:
        assert proc.stdout is not None
        for line in proc.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            last_line[0] = time.monotonic()
            text = line.strip()
            if ZENSICAL_FINISHED.match(text):
                run.finished = True
            elif text == ZENSICAL_INTERRUPTED:
                run.interrupted = True
            elif ZENSICAL_REPORTED.match(text):
                reported_at[0] = last_line[0]

    reader = threading.Thread(target=pump, name="zensical-output", daemon=True)
    reader.start()
    progress = time.monotonic()
    cpu_mark = process_cpu_seconds(proc.pid) or 0.0
    while True:
        try:
            run.returncode = proc.wait(timeout=min(5.0, max(stall_seconds / 4, 0.1)))
            break
        except subprocess.TimeoutExpired:
            pass
        now = time.monotonic()
        cpu = process_cpu_seconds(proc.pid)
        # CPU that cannot be read counts as progress: better the lander's watchdog than a false stall.
        if cpu is None or cpu - cpu_mark >= STALL_CPU_SECONDS:
            cpu_mark = cpu if cpu is not None else cpu_mark
            progress = now
        progress = max(progress, last_line[0])
        if reported_at[0] is not None and not run.finished and now - reported_at[0] > stall_seconds:
            run.stalled = f"pages rendered {now - reported_at[0]:.0f} s ago, but it never finished"
        elif now - progress > stall_seconds:
            run.stalled = f"no output and under {STALL_CPU_SECONDS:g} CPU-second(s) for {now - progress:.0f} s"
        if run.stalled:
            break
    if run.stalled:
        count, changed = files_changed_since(watched, started_wall)
        run.changed = count
        print(f"\nzensical has stalled: {run.stalled}.")
        if (woke := seconds_since_wake()) is not None:
            print(f"The machine last woke from sleep {woke / 60:.0f} min ago.")
        if count:
            print(f"{count} file(s) changed under directories zensical watches since it started. A process "
                  "that keeps writing there holds zensical's build open; the first of them:")
            for path in changed:
                print(f"  {path}")
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
    reader.join(timeout=10)
    if proc.stdout is not None and not reader.is_alive():
        proc.stdout.close()
    return run


def run_zensical_retrying_idle_wedges(
    run: Callable[[], ZensicalRun],
    *,
    before_retry: Callable[[], bool],
    retries: int = ZENSICAL_STALL_RETRIES,
    pause: float = ZENSICAL_STALL_RETRY_PAUSE,
) -> ZensicalRun | None:
    """[run], and again up to [retries] times while it wedges with nothing changing under it (see
    ZENSICAL_STALL_RETRIES). None if [before_retry] (clearing the staging dir) fails."""
    result = run()
    for attempt in range(retries):
        if not (result.stalled and result.changed == 0):
            break
        print(f"Nothing changed under the watched dirs, so this wedge is the machine, not the tree; "
              f"trying again in {pause:.0f} s (retry {attempt + 1} of {retries}).", flush=True)
        time.sleep(pause)
        if not before_retry():
            return None
        result = run()
    return result


def page_output(relative: Path) -> Path:
    """Where zensical (with `use_directory_urls`) writes the page built from [relative]."""
    if relative.stem in ("index", "README"):
        return relative.parent / "index.html"
    return relative.parent / relative.stem / "index.html"


def missing_pages(*, generated_docs: Path, site_dir: Path) -> tuple[int, list[Path]]:
    """How many pages the generated docs hold, and those of them with no built page in [site_dir]."""
    pages = [path.relative_to(generated_docs) for path in sorted(generated_docs.rglob("*.md"))]
    pages = [page for page in pages if not is_workspace_markdown(page)]
    return len(pages), [page for page in pages if not (site_dir / page_output(page)).is_file()]


def install_site(*, staging: Path, site_dir: Path, previous: Path) -> None:
    """Replace [site_dir] with [staging] by two renames, so the site is never half built."""
    if previous.exists() and not remove_tree(previous):
        raise OSError(f"could not remove {previous}, left by an earlier build")
    if site_dir.exists():
        site_dir.rename(previous)
    try:
        staging.rename(site_dir)
    except OSError:
        if previous.exists() and not site_dir.exists():
            previous.rename(site_dir)
        raise
    # A Finder window that had the old site open follows it here and may keep it busy; leaving it
    # costs one hidden directory, which the next build removes.
    if previous.exists() and not remove_tree(previous):
        print(f"warning: could not remove the previous site at {previous}; the next build will retry.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Preprocess Field docs runner fences, then build with Zensical.",
    )
    parser.add_argument("--public-dir", default="public", help="Path to the public docs project.")
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Accepted for old command lines. Every build renders into a fresh staging dir and replaces "
             "the whole site, so no page from an earlier build survives either way.",
    )
    parser.add_argument("--strict", action="store_true", help="Pass --strict to zensical build.")
    parser.add_argument(
        "--gate-site-links",
        action="store_true",
        help="Fail when check_doc_site_links.py fails, without the rest of --strict. The lander's "
             "docs job passes it.",
    )
    parser.add_argument("--skip-build", action="store_true", help="Only generate preprocessed sources and config.")
    parser.add_argument(
        "--page",
        action="append",
        default=[],
        metavar="PATTERN",
        help=(
            "Only run capture-enabled docs hooks for matching markdown pages. "
            "Non-matching pages are still generated, but with runner mode never. "
            "Matches relative paths, filenames, stems, or shell globs; may be repeated."
        ),
    )
    parser.add_argument(
        "--no-source-pages",
        action="store_true",
        help="Skip rendering src/ as docs pages. Citations then stay unlinked rather than dead.",
    )
    return parser.parse_args()


def prepare_local_site_for_captures(*, public_dir: Path, repo_root: Path,
                                    page_filters: tuple[str, ...], no_source_pages: bool,
                                    skip_build: bool) -> int:
    """Build a local site before UI drives that open one of its pages, if that page is absent."""
    if skip_build or os.environ.get("FIELD_DOCS_RUNNER_MODE", "incremental") == "never":
        return 0
    needed = set()
    for source in (public_dir / "docs").rglob("*.md"):
        relative = source.relative_to(public_dir / "docs")
        if page_filters and not matches_page_filter(relative, page_filters):
            continue
        needed.update(LOCAL_SITE_CAPTURE.findall(source.read_text(encoding="utf-8")))
    missing = sorted(page for page in needed if not (public_dir / SITE_DIR_NAME / page / "index.html").is_file())
    if not missing:
        return 0
    print(f"Preparing local docs pages for UI captures: {', '.join(missing)}", flush=True)
    env = os.environ.copy()
    env["FIELD_DOCS_RUNNER_MODE"] = "never"
    command = [sys.executable, str(Path(__file__).resolve()), "--public-dir", str(public_dir), "--clean"]
    if no_source_pages:
        command.append("--no-source-pages")
    result = subprocess.run(command, cwd=repo_root, env=env, check=False)
    if result.returncode:
        print(f"{FAILURE_PREFIX}could not prepare the local docs site for UI captures.", file=sys.stderr)
        return result.returncode
    still_missing = [page for page in missing if not (public_dir / SITE_DIR_NAME / page / "index.html").is_file()]
    if still_missing:
        print(f"{FAILURE_PREFIX}local docs build omitted {', '.join(still_missing)}.", file=sys.stderr)
        return 1
    return 0


def run(args: argparse.Namespace) -> int:

    repo_root = Path(__file__).resolve().parent.parent
    public_dir = (repo_root / args.public_dir).resolve()
    generated_root = public_dir / ".zensical-generated"
    generated_docs = generated_root / "docs"

    # A UI drive can need a page from this checkout's /field-docs/ mirror before captures finish.
    # The capture-free child build inherits this process's verification lock owner.
    bootstrap = prepare_local_site_for_captures(
        public_dir=public_dir, repo_root=repo_root, page_filters=tuple(args.page),
        no_source_pages=args.no_source_pages, skip_build=args.skip_build,
    )
    if bootstrap:
        return bootstrap

    if generated_docs.exists() and not remove_tree(generated_docs):
        print(f"{FAILURE_PREFIX}could not clear {generated_docs}", file=sys.stderr)
        return 1
    generated_root.mkdir(parents=True, exist_ok=True)
    generated_docs.mkdir(parents=True, exist_ok=True)

    sys.path.insert(0, str(public_dir))
    import docs_hooks  # type: ignore[import-not-found]

    default_python_venv_to_this_tree(repo_root)

    check_native_prerequisites(
        public_dir=public_dir,
        repo_root=repo_root,
        page_filters=tuple(args.page),
        runner_mode=docs_hooks.resolve_runner_mode(os.environ.get(docs_hooks.RUNNER_MODE_ENV)),
    )

    anchors = generate_source_pages(
        repo_root=repo_root,
        generated_docs=generated_docs,
        enabled=not args.no_source_pages,
    )
    if anchors is not None:
        os.environ[SOURCE_PAGE_ANCHORS_ENV] = str(anchors)
    else:
        os.environ.pop(SOURCE_PAGE_ANCHORS_ENV, None)
        if args.strict and not args.no_source_pages:
            print("Source pages are missing. Failing because --strict.", file=sys.stderr)
            return 1

    # Refresh before copying docs assets; unchanged publications need neither Field nor a browser.
    import docs_publications
    docs_publications.sync_publications(
        mode=docs_hooks.resolve_runner_mode(os.environ.get(docs_hooks.RUNNER_MODE_ENV)))
    preprocess_docs(public_dir=public_dir, generated_docs=generated_docs, page_filters=tuple(args.page))
    generated_config = build_generated_config(
        public_dir=public_dir,
        generated_root=generated_root,
        source_nav=source_page_nav(generated_docs),
    )

    # A referenced-but-untracked asset renders fine in this warm tree and 404s on a clean
    # checkout. Warn on every build; under --strict (CI / pre-merge) it gates.
    asset_check = subprocess.run(
        [sys.executable, str(Path(__file__).resolve().parent / "check_doc_assets.py")],
        cwd=repo_root,
        check=False,
    ).returncode
    if asset_check != 0 and args.strict:
        print("Doc asset check failed (see above). Failing because --strict.", file=sys.stderr)
        return asset_check

    if args.skip_build:
        print(f"Generated {generated_config}")
        return 0

    site_dir = public_dir / SITE_DIR_NAME
    staging = generated_root / STAGING_SITE_DIR_NAME
    if staging.exists() and not remove_tree(staging):
        print(f"{FAILURE_PREFIX}could not clear the staging site dir {staging}", file=sys.stderr)
        return 1

    command = [sys.executable, "-m", "zensical", "build", "-f", str(generated_config)]
    if args.strict:
        command.append("--strict")
    stall_seconds = float(os.environ.get(ZENSICAL_STALL_ENV) or ZENSICAL_STALL_SECONDS)
    watched = [generated_docs, public_dir / "overrides"]

    def clear_staging() -> bool:
        return not staging.exists() or remove_tree(staging)

    zensical = run_zensical_retrying_idle_wedges(
        lambda: run_zensical(command, cwd=public_dir, watched=watched, stall_seconds=stall_seconds),
        before_retry=clear_staging,
    )
    if zensical is None:
        print(f"{FAILURE_PREFIX}could not clear the staging site dir {staging}", file=sys.stderr)
        return 1
    sys.stdout.flush()
    failure = zensical.failure()
    if failure:
        print(f"{FAILURE_PREFIX}{failure}; {site_dir} was left as it was.", file=sys.stderr)
        return zensical.returncode or 1
    expected, missing = missing_pages(generated_docs=generated_docs, site_dir=staging)
    if missing:
        print(f"{len(missing)} of {expected} page(s) were not built, among them:")
        for page in missing[:10]:
            print(f"  {page}")
    if len(missing) > expected * MAX_MISSING_PAGE_FRACTION:
        print(f"{FAILURE_PREFIX}zensical built {expected - len(missing)} of {expected} page(s); "
              f"{site_dir} was left as it was.", file=sys.stderr)
        return 1
    # From here on the site is finished in the staging dir; install_site swaps it in whole.
    # Repo source paths become GitHub links, and prose inline code is marked for Kotlin
    # highlighting (docs_hooks.postprocess_built_site). Both run on the rendered HTML because
    # only there is a span's context (a `pre`, a link, a reference table) known. `docs_hooks`
    # is importable: preprocess_docs put `public/` on sys.path.
    import docs_hooks  # type: ignore[import-not-found]

    report = docs_hooks.postprocess_built_site(staging, repo_root)
    print(
        f"{report.source_links} repo source path(s) linked to GitHub; {report.spans_marked} prose "
        f"inline code span(s) marked for Kotlin highlighting ({report.pages_rewritten} page(s) rewritten)."
    )
    if report.stale_paths:
        count = sum(len(paths) for paths in report.stale_paths.values())
        print(
            f"WARNING: {count} repo path(s) in backticks name no tracked file, so they stay unlinked. "
            "Fix the path, or drop the backticks if it is not meant to be one:"
        )
        for page_rel, paths in report.stale_paths.items():
            for stale_path in paths:
                print(f"  {page_rel}: {stale_path}")
    emit_offline_viewer(site_dir=staging)
    emit_build_info(site_dir=staging, repo_root=repo_root)
    copied = copy_workspace_markdown(source_docs=public_dir / "docs", site_dir=staging)
    print(f"Copied {copied} workspace markdown file(s) verbatim (not built as pages).")
    install_site(staging=staging, site_dir=site_dir, previous=generated_root / PREVIOUS_SITE_DIR_NAME)
    print(f"Installed the built site at {site_dir} ({expected - len(missing)} page(s)).")

    # Only now, after the offline viewer and the verbatim copies are in place and the site is
    # installed, is the site dir what a browser will actually see -- so this has to run last
    # (and on `field4/` itself, the site its ledger describes) rather than beside
    # check_doc_assets.py above. That one asks whether an asset is committed and reads the
    # markdown; this one asks whether every reference on every built page resolves under
    # `site_url`'s path prefix, and reads the rendered HTML. Warn on every build; gate under
    # --strict, the same trade check_doc_assets.py makes, or under --gate-site-links alone.
    # Our own stdout is block-buffered when the build log is redirected, while the child writes
    # straight through; without this the guard's report lands *above* the lines that caused it.
    sys.stdout.flush()
    link_check = subprocess.run(
        [sys.executable, str(Path(__file__).resolve().parent / "check_doc_site_links.py")],
        cwd=repo_root,
        check=False,
    ).returncode
    if link_check != 0 and (args.strict or args.gate_site_links):
        flag = "--strict" if args.strict else "--gate-site-links"
        print(f"{FAILURE_PREFIX}the site link check failed (see above); failing because {flag}. "
              "The site itself was installed.", file=sys.stderr)
        return link_check
    return 0


def main() -> int:
    args = parse_args()
    repo_root = Path(__file__).resolve().parent.parent
    with verification_lock(repo_root, label="Zensical docs build", progress=lambda message: print(message, flush=True)):
        return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
