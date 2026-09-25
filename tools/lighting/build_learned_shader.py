#!/usr/bin/env python3
"""Prepare Field's optional, pinned Slang toolchain and build both learned-function shaders.

Normally invoked as ./gradlew buildLearnedShaders. The RTXNS source is a Git submodule under
optional/lighting; compiler archives use the same clean-safe cache as Ceres/TetGen. No SDK sample,
DXC, system package or driver installation is needed. Normal Field builds never invoke this.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import stat
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from native_download_cache import native_download_cache

MANIFEST=ROOT/'optional/lighting/toolchains.json'
OUT=ROOT/'build/lighting/learned-shader'
SOURCE=ROOT/'src/jvmMain/resources/afield/four/neural/learned.slang'


def digest(path: Path) -> str:
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()


def host_key(system: str | None=None,machine: str | None=None) -> str:
    system=system or platform.system();machine=(machine or platform.machine()).lower()
    system={'Linux':'linux','Darwin':'macos','Windows':'windows'}.get(system,system.lower())
    arch={'amd64':'x86_64','arm64':'aarch64'}.get(machine,machine)
    return f'{system}-{arch}'


def ensure_sdk(config: dict,offline: bool) -> Path:
    relative=config['path'];sdk=ROOT/relative;pin=config['revision']
    entry=subprocess.check_output(['git','-C',str(ROOT),'ls-files','--stage','--',relative],text=True).split()
    if len(entry)<2 or entry[:2]!=['160000',pin]:
        raise RuntimeError(f'{relative}: Git submodule pin disagrees with toolchains.json; keep both revisions in sync')
    if not (sdk/'.git').exists():
        if offline:raise RuntimeError(f'{relative} is absent; run ./gradlew buildLearnedShaders once with network access')
        subprocess.run(['git','-C',str(ROOT),'submodule','update','--init','--',relative],check=True)
    head=subprocess.check_output(['git','-C',str(sdk),'rev-parse','HEAD'],text=True).strip()
    if head!=pin:raise RuntimeError(f'{relative} is at {head}, expected {pin}; preserve your edits and update the submodule explicitly')
    if subprocess.run(['git','-C',str(sdk),'diff','--quiet','HEAD','--'],check=False).returncode:
        raise RuntimeError(f'{relative} has local changes; the pinned build will not overwrite or silently use them')
    return sdk


def fetch(url: str,sha256: str,destination: Path,offline: bool=False) -> None:
    if destination.is_file() and digest(destination)==sha256:
        print(f'[learned shader] cached {destination.name}',flush=True);return
    if offline:raise RuntimeError(f'No valid cached {destination.name}; run ./gradlew buildLearnedShaders once without --offline')
    destination.parent.mkdir(parents=True,exist_ok=True)
    temporary=destination.with_name(f'.{destination.name}.{os.getpid()}.tmp')
    print(f'[learned shader] downloading {url}',flush=True)
    try:
        with urllib.request.urlopen(url,timeout=180) as response,temporary.open('wb') as output:
            shutil.copyfileobj(response,output)
        if digest(temporary)!=sha256:raise RuntimeError(f'Checksum mismatch downloading {url}')
        temporary.replace(destination)
    finally:temporary.unlink(missing_ok=True)


def extract(archive: Path,destination: Path) -> None:
    """Extract a verified compiler package; reject paths/links escaping our managed cache."""
    destination.mkdir(parents=True,exist_ok=True)
    links=[]
    with zipfile.ZipFile(archive) as package:
        for member in package.infolist():
            path=Path(member.filename)
            if path.is_absolute() or '..' in path.parts or '\\' in member.filename:
                raise RuntimeError(f'Unsafe compiler archive path: {member.filename}')
            mode=member.external_attr>>16
            if stat.S_ISLNK(mode):
                links.append((destination/path,package.read(member).decode('utf-8')))
                continue
            output=Path(package.extract(member,destination))
            if mode & 0o111 and not member.is_dir():output.chmod(output.stat().st_mode | 0o111)
    # Slang's Unix distributions use links such as libslang.so -> libslang.so.0.2026.10.
    # Regular files are extracted first so no archive member can write through an untrusted link.
    for link,target in links:
        if Path(target).is_absolute() or '\\' in target or not (link.parent/target).resolve().is_relative_to(destination.resolve()):
            raise RuntimeError(f'Unsafe compiler archive symlink: {link.name} -> {target}')
        link.parent.mkdir(parents=True,exist_ok=True);link.symlink_to(target)


def compiler(config: dict,offline: bool) -> tuple[Path,dict]:
    key=host_key()
    if key not in config['archives']:raise RuntimeError(f'No pinned Slang archive for {key}; normal Field rendering is unaffected')
    info=config['archives'][key];cache=native_download_cache('lighting/slang')
    archive=cache/Path(info['url']).name
    fetch(info['url'],info['sha256'],archive,offline)
    package=cache/archive.stem;marker=package/'.archive-sha256'
    executable=package/'bin'/('slangc.exe' if key.startswith('windows-') else 'slangc')
    if not executable.is_file() or not marker.is_file() or marker.read_text().strip()!=info['sha256']:
        with tempfile.TemporaryDirectory(prefix='.extract-',dir=cache) as temporary:
            prepared=Path(temporary)/'package';extract(archive,prepared)
            if not (prepared/'bin'/executable.name).is_file():raise RuntimeError('Compiler archive lacks bin/slangc')
            (prepared/'.archive-sha256').write_text(info['sha256']+'\n')
            try:prepared.rename(package)
            except OSError:
                # Another checkout may have prepared the same shared-cache entry concurrently.
                if not (executable.is_file() and marker.is_file() and marker.read_text().strip()==info['sha256']):
                    raise RuntimeError(f'Incomplete compiler cache at {package}; remove that cache entry and retry')
    version=subprocess.check_output([str(executable),'-version'],stderr=subprocess.STDOUT,text=True).strip()
    if version!=config['version']:raise RuntimeError(f'Expected Slang {config["version"]}, got {version}')
    return executable,{'host':key,'slangVersion':version,'slangArchiveUrl':info['url'],
        'slangArchiveSha256':info['sha256'],'slangSha256':digest(executable)}


def compile_shaders(slang: Path,source: Path,output: Path,report: dict) -> dict:
    # Compile both before replacing any working binary. A failed compiler leaves the old pair intact.
    output.parent.mkdir(parents=True,exist_ok=True)
    report={**report,'sourceSha256':digest(source),'binaries':{}}
    with tempfile.TemporaryDirectory(prefix='.learned-',dir=output.parent) as temporary:
        prepared=Path(temporary)
        for mode in ['scalar','coop']:
            dest=prepared/f'learned-{mode}.spv'
            command=[str(slang),str(source),'-target','spirv','-profile','sm_6_6','-entry','main','-stage','compute',
                '-DFIELD_COOPERATIVE='+str(int(mode=='coop')),'-o',str(dest)]
            if mode=='coop':command+=['-capability','spvCooperativeVectorNV']
            subprocess.run(command,check=True)
            report['binaries'][mode]=digest(dest)
        (prepared/'learned-build.json').write_text(json.dumps(report,indent=2)+'\n')
        output.mkdir(parents=True,exist_ok=True)
        for name in ['learned-scalar.spv','learned-coop.spv','learned-build.json']:
            (prepared/name).replace(output/name)
    return report


def main() -> None:
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--offline',action='store_true')
    args=parser.parse_args();pins=json.loads(MANIFEST.read_text())
    sdk=ensure_sdk(pins['rtxns'],args.offline)
    # This is the version selected by the pinned SDK, without configuring its entire sample renderer.
    if f'set(SHADERMAKE_SLANG_VERSION "{pins["slang"]["version"]}"' not in (sdk/'CMakeLists.txt').read_text():
        raise RuntimeError('RTXNS and Field Slang pins disagree')
    slang,provenance=compiler(pins['slang'],args.offline)
    report=compile_shaders(slang,SOURCE,OUT,{'rtxnsCommit':pins['rtxns']['revision'],
        'rtxnsPath':pins['rtxns']['path'],**provenance})
    # Reuse this compiler from Field's existing SlangCompiler when no PATH compiler is installed.
    # This checkout-local pointer is a generated artifact, not dependency source or provenance.
    pointer=OUT.parent/'slangc.path'
    temporary=pointer.with_name(f'.slangc.path.{os.getpid()}.tmp')
    temporary.write_text(str(slang.resolve())+'\n');temporary.replace(pointer)
    print(json.dumps(report,indent=2));print(f'[learned shader] ready: {OUT}',flush=True)


if __name__=='__main__':
    try:main()
    except (RuntimeError,OSError,subprocess.CalledProcessError,zipfile.BadZipFile) as error:
        raise SystemExit(f'[learned shader] {error}')
