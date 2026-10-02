#!/usr/bin/env python3
"""Explicit, pinned preparation of Field's optional OpenDLSS-NR adapter."""
from pathlib import Path
import argparse, hashlib, importlib.util, json, os, shutil, subprocess, sys, tarfile, tempfile, urllib.request, zipfile
from native_download_cache import native_download_cache
ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / 'optional/opendlssnr/dependencies.json'

def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def fetch(item, cache, offline):
    target = cache / item['file']
    if target.exists():
        if digest(target) == item['sha256']:
            return target
        raise RuntimeError(f'Corrupt cached archive: {target}; remove it and run the task again')
    if offline:
        raise RuntimeError(f'Offline cache missing: {target}\nRequired URL: {item["url"]}')
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as f:
        temporary = Path(f.name)
        try:
            with urllib.request.urlopen(item['url'], timeout=120) as response:
                shutil.copyfileobj(response, f)
            f.flush()
            if digest(temporary) != item['sha256']:
                raise RuntimeError('Download SHA-256 mismatch: ' + item['url'])
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
    return target

def weights(spec, cache, offline):
    archive = fetch(spec['weights'], cache, offline)
    output = cache / 'model-310.8.Lecram'
    stage = output / 'model/stages/nr.bin'
    if stage.exists() and digest(stage) == spec['weights']['stageSha256']:
        # Check metadata as well as the payload; never trust a partial preparation.
        manifest = json.loads((output / 'manifest.json').read_text())
        if manifest['stages'][0]['sha256'] == spec['weights']['stageSha256'] and manifest['totals']['tensorCount'] == 153:
            return output
    with tempfile.TemporaryDirectory(dir=cache) as temporary:
        temp = Path(temporary)
        with zipfile.ZipFile(archive) as z:
            names = [n for n in z.namelist() if Path(n).name.lower() == 'nvngx_dlssnr.dll']
            if len(names) != 1:
                raise RuntimeError('Expected exactly one nvngx_dlssnr.dll')
            dll = temp / 'nvngx_dlssnr.dll'
            dll.write_bytes(z.read(names[0]))
        subprocess.run([sys.executable, str(ROOT/'tools/opendlssnr/extract_weights.py'), str(dll), str(temp/'model')], check=True)
        if digest(temp/'model/model/stages/nr.bin') != spec['weights']['stageSha256']:
            raise RuntimeError('Extracted stage digest differs from the pinned model')
        if output.exists():
            shutil.rmtree(output)
        (temp/'model').replace(output)
    return output

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--weights-only', action='store_true')
    args = parser.parse_args()
    if sys.platform != 'linux' or os.uname().machine != 'x86_64':
        raise SystemExit('OpenDLSS-NR native preparation currently supports Linux x86_64')
    spec = json.loads(SPEC.read_text()); cache = native_download_cache('opendlssnr')
    cache.mkdir(parents=True, exist_ok=True)
    # Cache/extraction is shared by worktrees; serialize atomic replacements.
    import fcntl
    with (cache/'.prepare.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        model = weights(spec, cache, args.offline)
        build = ROOT/'build/native/field-opendlssnr'; build.mkdir(parents=True, exist_ok=True)
        link = build/'model'
        if link.is_symlink(): link.unlink()
        if not link.exists(): link.symlink_to(model, target_is_directory=True)
        if args.weights_only:
            print('OpenDLSS-NR weights:', model); return
        for name in ('source','headers','volk','glslang'):
            archive = fetch(spec[name], cache, args.offline)
            destination = build/'upstream'/name
            stamp = destination/'.field-source-sha256'
            if stamp.exists() and stamp.read_text().strip() == (spec[name]['sha256'] + ((digest(ROOT/'optional/opendlssnr/linux.patch')+digest(ROOT/'optional/opendlssnr/interop.patch')) if name == 'source' else '')): continue
            if destination.exists(): shutil.rmtree(destination)
            destination.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.TemporaryDirectory(dir=destination.parent) as temp:
                with tarfile.open(archive) as t: t.extractall(temp, filter='data')
                roots = list(Path(temp).iterdir())
                if len(roots) != 1: raise RuntimeError('Expected one archive root')
                roots[0].replace(destination)
            if name == 'source':
                subprocess.run(['patch','--batch','-p1','-i',str(ROOT/'optional/opendlssnr/linux.patch')], cwd=destination, check=True)
                subprocess.run(['patch','--batch','-p1','-i',str(ROOT/'optional/opendlssnr/interop.patch')], cwd=destination, check=True)
            stamp.write_text(spec[name]['sha256'] + ((digest(ROOT/'optional/opendlssnr/linux.patch')+digest(ROOT/'optional/opendlssnr/interop.patch')) if name == 'source' else '')+'\n')
        compiler_build=cache/'glslang-16.3.0-build'
        compiler=compiler_build/'StandAlone/glslang'
        if not compiler.is_file():
            # A failed toolchain build may belong to a different checkout. Reconfigure cleanly.
            if compiler_build.exists(): shutil.rmtree(compiler_build)
            subprocess.run(['cmake','-S',str(build/'upstream/glslang'),'-B',str(compiler_build),'-G','Ninja',
                            '-DCMAKE_BUILD_TYPE=Release','-DENABLE_OPT=OFF','-DENABLE_HLSL=OFF',
                            '-DBUILD_TESTING=OFF','-DGLSLANG_TESTS=OFF'],check=True)
            subprocess.run(['cmake','--build',str(compiler_build),'--target','glslang-standalone','--parallel',os.environ.get('FIELD_NR_BUILD_JOBS','8')],check=True)
        subprocess.run(['cmake','-S',str(ROOT/'native/field-opendlssnr'),'-B',str(build/'cmake'),'-G','Ninja',
                        '-DCMAKE_BUILD_TYPE=Release','-DGLSLANG_VALIDATOR='+str(compiler),'-DNR_ROOT='+str(build/'upstream/source'),
                        '-DHEADERS_ROOT='+str(build/'upstream/headers'),'-DVOLK_ROOT='+str(build/'upstream/volk')],check=True)
        subprocess.run(['cmake','--build',str(build/'cmake'),'--parallel',os.environ.get('FIELD_NR_BUILD_JOBS','8')],check=True)
        temporary_library=build/'.libfield_opendlssnr.so.new'
        shutil.copy2(build/'cmake/libfield_opendlssnr.so', temporary_library)
        temporary_library.replace(build/'libfield_opendlssnr.so')
        shutil.copy2(build/'cmake/field_opendlssnr_reference', build/'field_opendlssnr_reference')
        print('OpenDLSS-NR adapter:', build/'libfield_opendlssnr.so')

if __name__ == '__main__': main()
