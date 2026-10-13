#!/usr/bin/env python3
"""Build Field's optional Patchy WebAssembly site outside the Field checkout."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys


FIELD_ROOT = Path(__file__).resolve().parents[1]
RECIPE = FIELD_ROOT / "optional" / "patchy-web"
PIN = json.loads((RECIPE / "pin.json").read_text())
PATCH = RECIPE / "field-bridge.patch"
UI_FONT = RECIPE / "iosevka-field-ui.ttf"
ASSETS = ("patchy.html", "patchy.js", "patchy.wasm", "patchy.data", "qtloader.js", "qtlogo.svg")


def run(*command, cwd=None, env=None):
    print("+", " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), cwd=cwd, env=env, check=True)


def output(*command, cwd=None):
    return subprocess.check_output(list(map(str, command)), cwd=cwd, text=True).strip()


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def pinned_checkout(path, url, revision):
    if not (path / ".git").is_dir():
        path.mkdir(parents=True, exist_ok=True)
        run("git", "init", "-q", path)
    has_head = subprocess.run(["git", "-C", str(path), "rev-parse", "--verify", "HEAD"],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    if has_head and output("git", "rev-parse", "HEAD", cwd=path) == revision:
        return
    run("git", "fetch", "--depth", "1", url, revision, cwd=path)
    run("git", "-c", "core.autocrlf=false", "checkout", "--force", "--detach", "FETCH_HEAD", cwd=path)
    actual = output("git", "rev-parse", "HEAD", cwd=path)
    if actual != revision:
        raise RuntimeError(f"Expected {revision}, got {actual} from {url}")


def prepare_source(cache, source_url):
    source = cache / "source"
    patch_hash = sha256(PATCH)
    source_hash = hashlib.sha256((patch_hash + sha256(UI_FONT)).encode()).hexdigest()
    marker = source / ".field-patch-sha256"
    if (marker.is_file() and marker.read_text().strip() == source_hash and
            output("git", "rev-parse", "HEAD", cwd=source) == PIN["revision"]):
        return source, patch_hash
    if source.exists():
        shutil.rmtree(source)
    pinned_checkout(source, source_url, PIN["revision"])
    run("git", "apply", "--check", PATCH, cwd=source)
    run("git", "apply", PATCH, cwd=source)
    font_destination = source / "third_party/fonts-web/field/iosevka-field-ui.ttf"
    font_destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(UI_FONT, font_destination)
    marker.write_text(source_hash + "\n")
    return source, patch_hash


def executables(venv):
    directory = venv / ("Scripts" if os.name == "nt" else "bin")
    suffix = ".exe" if os.name == "nt" else ""
    return directory / ("python" + suffix), directory / ("aqt" + suffix), directory / ("ninja" + suffix)


def prepare_python(cache):
    venv = cache / "tools"
    python, aqt, ninja = executables(venv)
    if not python.is_file():
        run(sys.executable, "-m", "venv", venv)
    if not aqt.is_file() or not ninja.is_file():
        run(python, "-m", "pip", "install", "--disable-pip-version-check",
            f"aqtinstall=={PIN['aqtinstallVersion']}", f"ninja=={PIN['ninjaVersion']}")
    return aqt, ninja


def emsdk_command(emsdk, *args):
    entry = emsdk / ("emsdk.bat" if os.name == "nt" else "emsdk")
    if os.name == "nt":
        run("cmd.exe", "/d", "/c", f'call "{entry}" ' + " ".join(args))
    else:
        run(entry, *args)


def prepare_emsdk(cache):
    emsdk = cache / "emsdk"
    pinned_checkout(emsdk, PIN["emsdk"], PIN["emsdkRevision"])
    emsdk_command(emsdk, "install", PIN["emscriptenVersion"])
    emsdk_command(emsdk, "activate", PIN["emscriptenVersion"])
    return emsdk


def emsdk_environment(emsdk):
    if os.name == "nt":
        script = emsdk / "emsdk_env.bat"
        raw = subprocess.check_output(["cmd.exe", "/d", "/c", f'call "{script}" >nul && set'])
        lines = raw.decode(errors="replace").splitlines()
        values = dict(line.split("=", 1) for line in lines if "=" in line and not line.startswith("="))
        return {**os.environ, **values}
    script = emsdk / "emsdk_env.sh"
    raw = subprocess.check_output(["bash", "-c", 'source "$1" >/dev/null && env -0', "bash", str(script)])
    values = dict(part.decode(errors="replace").split("=", 1) for part in raw.split(b"\0")
                  if b"=" in part)
    return {**os.environ, **values}


def prepare_qt(cache, aqt):
    host = {"Darwin": ("mac", "clang_64", "macos"),
            "Linux": ("linux", "gcc_64", "gcc_64"),
            "Windows": ("windows", "win64_msvc2022_64", "msvc2022_64")}.get(platform.system())
    if host is None:
        raise RuntimeError("Patchy Web builds support macOS, Linux and Windows")
    qt_root = cache / "Qt"
    host_kit = qt_root / PIN["qtVersion"] / host[2]
    wasm_kit = qt_root / PIN["qtVersion"] / "wasm_multithread"
    suffix = ".exe" if os.name == "nt" else ""
    if not (host_kit / "bin" / ("lrelease" + suffix)).is_file():
        run(aqt, "install-qt", host[0], "desktop", PIN["qtVersion"], host[1], "-O", qt_root)
    if not (wasm_kit / "lib" / "cmake" / "Qt6" / "qt.toolchain.cmake").is_file():
        run(aqt, "install-qt", "all_os", "wasm", PIN["qtVersion"], "wasm_multithread",
            "-m", "qtimageformats", "-O", qt_root)
    for probe in ("qmake", "lrelease", "lupdate"):
        if not (host_kit / "bin" / (probe + suffix)).is_file():
            raise RuntimeError(f"Qt host kit is missing {probe}: {host_kit}")
    return host_kit, wasm_kit


def stage_site(cache, build, patch_hash):
    next_site = cache / "site-next"
    if next_site.exists():
        shutil.rmtree(next_site)
    next_site.mkdir(parents=True)
    for name in ASSETS:
        source = build / name
        if not source.is_file() or source.stat().st_size == 0:
            raise RuntimeError(f"Patchy build did not produce {source}")
        shutil.copy2(source, next_site / name)
    source = cache / "source"
    shutil.copy2(source / "LICENSE", next_site / "Patchy-LICENSE.txt")
    shutil.copy2(source / "NOTICE-THIRD-PARTY.md", next_site / "NOTICE-THIRD-PARTY.md")
    shutil.copy2(source / "src" / "formats" / "libheif" / "COPYING", next_site / "libheif-COPYING.txt")
    shutil.copy2(FIELD_ROOT / "public/docs/assets/fonts/iosevka/LICENSE.txt", next_site / "Iosevka-LICENSE.txt")
    manifest = {"source": PIN["source"], "revision": PIN["revision"], "patchSha256": patch_hash,
                "iosevkaSha256": sha256(UI_FONT),
                "emscriptenVersion": PIN["emscriptenVersion"], "qtVersion": PIN["qtVersion"],
                "assets": {name: sha256(next_site / name) for name in ASSETS}}
    (next_site / "field-patchy.json").write_text(json.dumps(manifest, indent=2) + "\n")
    site, previous = cache / "site", cache / "site-previous"
    if previous.exists():
        shutil.rmtree(previous)
    if site.exists():
        site.rename(previous)
    try:
        next_site.rename(site)
    except Exception:
        if previous.exists():
            previous.rename(site)
        raise
    if previous.exists():
        shutil.rmtree(previous)
    print(f"Patchy Web is ready at {site}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=Path.home() / ".field4" / "patchy-web")
    parser.add_argument("--source-repository", default=PIN["source"], help="Pinned Git source; local repo accepted for verification")
    parser.add_argument("--check-patch", action="store_true", help="Only fetch the pinned source and check the Field patch")
    args = parser.parse_args()
    cache = args.cache.expanduser().resolve()
    cache.mkdir(parents=True, exist_ok=True)
    source, patch_hash = prepare_source(cache, args.source_repository)
    if args.check_patch:
        print(f"Patch applies to {PIN['revision']} ({patch_hash})")
        return
    aqt, ninja = prepare_python(cache)
    emsdk = prepare_emsdk(cache)
    host, wasm = prepare_qt(cache, aqt)
    env = emsdk_environment(emsdk)
    build = cache / "build"
    toolchain = wasm / "lib" / "cmake" / "Qt6" / "qt.toolchain.cmake"
    chainload = emsdk / "upstream" / "emscripten" / "cmake" / "Modules" / "Platform" / "Emscripten.cmake"
    run("cmake", "-S", source, "-B", build, "-G", "Ninja",
        f"-DCMAKE_MAKE_PROGRAM={ninja}", f"-DCMAKE_TOOLCHAIN_FILE={toolchain}",
        f"-DQT_CHAINLOAD_TOOLCHAIN_FILE={chainload}", f"-DQT_HOST_PATH={host}",
        "-DCMAKE_BUILD_TYPE=Release", "-DPATCHY_BUILD_APP=ON", "-DPATCHY_BUILD_TESTS=OFF",
        "-DPATCHY_REQUIRE_AI_SELECTION=OFF", "-DCMAKE_C_FLAGS=-fexceptions -pthread -msimd128",
        "-DCMAKE_CXX_FLAGS=-fexceptions -pthread -msimd128", env=env)
    run("cmake", "--build", build, "--target", "patchy", "-j", str(min(os.cpu_count() or 2, 4)), env=env)
    stage_site(cache, build, patch_hash)


if __name__ == "__main__":
    main()
