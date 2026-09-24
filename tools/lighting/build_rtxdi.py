#!/usr/bin/env python3
"""Optional pinned RTXDI shader build. No effect on ordinary Field/Gradle startup.
Use --prepare to download the SDK and DXC into ignored build storage.
"""
import argparse, hashlib, json, os, pathlib, subprocess, tarfile, urllib.request
ROOT = pathlib.Path(__file__).resolve().parents[2]
BASE = ROOT / 'build/lighting-experiment'
SDK_COMMIT = 'f12037fa8e97ebc08e9e3edfd2de528ed1772a4b'
ROOT_COMMIT = 'a6efab966b7c3b272da0461578eb56ac61c7cbff'
DXC_URL = 'https://github.com/microsoft/DirectXShaderCompiler/releases/download/v1.8.2505.1/linux_dxc_2025_07_14.x86_64.tar.gz'

DXC_FILES = {
    'bin/dxc': '25927f0c2eb2eb22ebdf72ecf4798345a53b997ef1b05fea0280410d220427bb',
    'lib/libdxcompiler.so': '72832c39c5dfdf537586ca6500de94ef28cbf69d148ab3abe573da6ea209bad0',
    'lib/libdxil.so': '51f6a6eb5c77391a86c03b82a79affdb7fa5ea9a3a67be37f5f573ca112ad931',
}

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def run(*args): return subprocess.check_output(args, text=True).strip()
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--sdk', type=pathlib.Path, default=BASE/'upstream/RTXDI/Libraries/Rtxdi')
    parser.add_argument('--dxc', type=pathlib.Path, default=BASE/'upstream/RTXDI/External/dxc/bin/dxc')
    parser.add_argument('--source', type=pathlib.Path, default=ROOT/'src/jvmMain/resources/afield/four/lighting/rtxdi/lighting.hlsl')
    args = parser.parse_args()
    if args.prepare and not args.sdk.exists():
        parent = BASE/'upstream/RTXDI'
        if not parent.exists():
            subprocess.run(['git','clone','https://github.com/NVIDIA-RTX/RTXDI',str(parent)],check=True)
        subprocess.run(['git','-C',str(parent),'checkout',ROOT_COMMIT],check=True)
        subprocess.run(['git','-C',str(parent),'submodule','update','--init','Libraries/Rtxdi'],check=True)
    if args.prepare and not args.dxc.exists():
        archive=BASE/'linux_dxc_2025_07_14.x86_64.tar.gz'
        archive.parent.mkdir(parents=True,exist_ok=True)
        urllib.request.urlretrieve(DXC_URL,archive)
        target=args.dxc.parent.parent; target.mkdir(parents=True,exist_ok=True)
        with tarfile.open(archive) as tar: tar.extractall(target,filter='data')
        print('DXC archive sha256:',sha(archive))
    if not args.sdk.exists() or not args.dxc.exists():
        parser.error('Missing optional SDK/DXC. Run with --prepare on Linux, or supply --sdk and --dxc. Read the SDK LICENSE.txt before use.')
    if run('git','-C',str(args.sdk),'rev-parse','HEAD') != SDK_COMMIT:
        parser.error('RTXDI revision differs from the bridge pin')
    if run('git','-C',str(args.sdk),'status','--porcelain','--untracked-files=no'):
        parser.error('RTXDI SDK has local modifications; use the pinned clean revision')
    for relative, expected in DXC_FILES.items():
        path=args.dxc.parent.parent/relative
        if not path.is_file() or sha(path)!=expected:
            parser.error(f'DXC toolchain checksum mismatch: {path}; use the pinned Linux x86_64 release')
    source=args.source.read_bytes()
    version=run(str(args.dxc),'--version')
    compiler_hash=sha(args.dxc)
    digest=hashlib.sha256(source+SDK_COMMIT.encode()+compiler_hash.encode()).hexdigest()
    directory=BASE/'rtxdi-shaders'/digest; directory.mkdir(parents=True,exist_ok=True)
    output=directory/'lighting.spv'
    temporary=directory/'lighting.pending.spv'
    command=[str(args.dxc),'-spirv','-T','cs_6_6','-E','main','-fspv-target-env=vulkan1.3','-fvk-use-scalar-layout',
             '-I',str(args.sdk/'Include'),'-Fo',str(temporary),str(args.source)]
    subprocess.run(command,check=True)
    os.replace(temporary,output)
    manifest={'schema':1,'sdkCommit':SDK_COMMIT,'dxcUrl':DXC_URL,'dxcVersion':version,'dxcSha256':compiler_hash,'dxcFiles':DXC_FILES,
              'sourceSha256':hashlib.sha256(source).hexdigest(),'spirvSha256':sha(output),'spirv':str(output.relative_to(ROOT)),
              'command':command,'license':str(args.sdk/'LICENSE.txt')}
    manifest_path=BASE/'rtxdi-shaders/current.json'
    pending=manifest_path.with_suffix('.pending.json');pending.write_text(json.dumps(manifest,indent=2)+'\n')
    os.replace(pending,manifest_path)
    print(manifest_path)
if __name__=='__main__': main()
