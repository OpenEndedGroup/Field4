#!/usr/bin/env python3
"""Launch Field on macOS with the WiVRn for macOS OpenXR runtime; leave the default runtime and driver untouched.

Field's OpenXR path needs three things on a Mac that Linux provides by itself: an OpenXR loader
(LWJGL ships none for macOS), the WiVRn runtime manifest, and the MoltenVK driver, because WiVRn's
runtime imports its swapchains with VK_EXT_metal_objects, which KosmicKrisp does not offer. All three
are set for this launch only. `--build-loader` builds the Khronos loader into `.afield-local/openxr`;
`--configure-encoder` makes WiVRn use VideoToolbox HEVC, since its automatic choice can fall back to
the x264 encoder, which crashes the WiVRn server on the first frame under MoltenVK.

`--build-kk-runtime` builds WiVRn's runtime with `optional/wivrn-macos`'s patch, which copies the eye
images into WiVRn's IOSurfaces instead of wrapping them, and `--kosmickrisp` launches Field on
KosmicKrisp with that runtime.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import hashlib
import shutil
import subprocess
import sys
import platform
import tempfile
import urllib.request

REPO = Path(__file__).resolve().parent.parent
OPENXR_SDK_URL = 'https://github.com/KhronosGroup/OpenXR-SDK.git'
OPENXR_SDK_TAG = 'release-1.1.53'
LOADER_PREFIX = REPO / '.afield-local/openxr'
DEFAULT_APP = Path('/Applications/WiVRn.app')
WIVRN_VERSION = '26.9.5'
WIVRN_URL = f'https://github.com/orchardxr/wivrn-macos/releases/download/v{WIVRN_VERSION}/WiVRn-{WIVRN_VERSION}.zip'
WIVRN_SHA256 = '779565016a7dd452e1f1d3f3dc8b5aaed4eed92f377eeafd4c5ee8af764a54be'
SETUP_HINT = 'Run ./field.sh --setup and select WiVRn headset streaming (macOS), then choose BUILD.'
VIDEOTOOLBOX = {'encoder': 'videotoolbox', 'codec': 'h265'}
KK_PIN = REPO / 'optional/wivrn-macos/pin.json'
KK_RUNTIME_DIR = REPO / '.afield-local/wivrn-kk'
KK_MANIFEST = KK_RUNTIME_DIR / 'openxr_wivrn_kk.json'


def default_app(home: Path | None = None) -> Path:
    """Prefer an installed system app; new installations need no administrator privileges."""
    user_app = (home or Path.home()) / 'Applications/WiVRn.app'
    return DEFAULT_APP if DEFAULT_APP.exists() else user_app


def verify_archive(archive: Path) -> None:
    digest = hashlib.sha256()
    with archive.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    if digest.hexdigest() != WIVRN_SHA256:
        raise ValueError('WiVRn download checksum mismatch; nothing was installed.')


def install_runtime(app: Path) -> Path:
    """Install the pinned app if absent; never replace an existing app or bypass Gatekeeper."""
    app = app.expanduser().resolve()
    if app.exists():
        return runtime_manifest(app)
    app.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.field-wivrn-', dir=app.parent) as folder:
        work = Path(folder)
        archive = work / 'WiVRn.zip'
        print(f'Downloading WiVRn {WIVRN_VERSION}…', flush=True)
        with urllib.request.urlopen(WIVRN_URL, timeout=60) as response, archive.open('wb') as output:
            shutil.copyfileobj(response, output)
        verify_archive(archive)
        unpacked = work / 'unpacked'
        # ditto preserves app bundle symlinks, executable permissions and macOS metadata.
        subprocess.run(['ditto', '-x', '-k', str(archive), str(unpacked)], check=True)
        candidates = list(unpacked.glob('WiVRn.app'))
        if len(candidates) != 1:
            raise ValueError('The WiVRn release does not contain the expected WiVRn.app bundle.')
        runtime_manifest(candidates[0])
        candidates[0].rename(app)
    return runtime_manifest(app)


def prepare(app: Path, config: Path) -> None:
    """Optional first-run preparation; does not start WiVRn or select a global runtime."""
    if sys.platform != 'darwin' or platform.machine() not in ('arm64', 'aarch64'):
        raise ValueError('WiVRn macOS preparation requires an Apple silicon Mac.')
    manifest = install_runtime(app)
    if not loader_library().is_file():
        build_loader()
    if not encoder_configured(config):
        configure_encoder(config)
    print(f'WiVRn prepared: {manifest}. Open {app}, allow macOS permissions and connect the headset. '
          'Restart WiVRn if it was already running so the encoder change applies.')


def runtime_manifest(app: Path) -> Path:
    manifest = app.expanduser() / 'Contents/Resources/openxr/1/openxr_wivrn.json'
    if not manifest.is_file():
        raise ValueError(f'No WiVRn runtime manifest at {manifest}. Install WiVRn for macOS '
                         '(https://github.com/orchardxr/wivrn-macos) or pass --wivrn PATH/WiVRn.app.')
    library = Path(json.loads(manifest.read_text()).get('runtime', {}).get('library_path', ''))
    if not library.is_absolute():
        library = manifest.parent / library
    if not library.is_file():
        raise ValueError(f'{manifest} names a runtime library that does not exist: {library}')
    return manifest.resolve()


def loader_library(prefix: Path = LOADER_PREFIX) -> Path:
    return prefix / 'lib/libopenxr_loader.dylib'


def wivrn_config_path(home: Path) -> Path:
    return home / '.config/wivrn/config.json'


def encoder_configured(config: Path) -> bool:
    try:
        return json.loads(config.read_text()).get('encoder') == VIDEOTOOLBOX
    except (OSError, ValueError, AttributeError):
        return False


def configure_encoder(config: Path) -> None:
    """Set WiVRn's encoder to VideoToolbox HEVC, keeping every other setting in the file."""
    settings = json.loads(config.read_text()) if config.is_file() else {}
    settings['encoder'] = dict(VIDEOTOOLBOX)
    config.parent.mkdir(parents=True, exist_ok=True)
    config.write_text(json.dumps(settings, indent=2) + '\n')


def build_loader(prefix: Path = LOADER_PREFIX) -> Path:
    """Build the Khronos OpenXR loader for this Mac's architecture and install it under prefix."""
    for tool in ('git', 'cmake'):
        if shutil.which(tool) is None:
            raise ValueError(f'{tool} is needed to build the OpenXR loader (brew install {tool}).')
    source = REPO / 'build/openxr-sdk' / OPENXR_SDK_TAG
    if not (source / 'CMakeLists.txt').is_file():
        source.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['git', 'clone', '--quiet', '--depth', '1', '--branch', OPENXR_SDK_TAG,
                        OPENXR_SDK_URL, str(source)], check=True)
    build = source / 'build'
    generator = ['-G', 'Ninja'] if shutil.which('ninja') else []
    # The bundled jsoncpp: Homebrew's jsoncpp CMake package names a library file that is missing.
    subprocess.run(['cmake', *generator, '-S', str(source), '-B', str(build), '-DCMAKE_BUILD_TYPE=Release',
                    '-DBUILD_TESTS=OFF', '-DDYNAMIC_LOADER=ON', '-DBUILD_WITH_SYSTEM_JSONCPP=OFF',
                    f'-DCMAKE_OSX_ARCHITECTURES={os.uname().machine}', f'-DCMAKE_INSTALL_PREFIX={prefix}'],
                   check=True)
    subprocess.run(['cmake', '--build', str(build), '--parallel'], check=True)
    subprocess.run(['cmake', '--install', str(build)], check=True)
    library = loader_library(prefix)
    if not library.is_file():
        raise ValueError(f'The OpenXR build finished but {library} is missing.')
    return library


def write_kk_manifest(runtime_dir: Path, library: Path) -> Path:
    manifest = runtime_dir / KK_MANIFEST.name
    manifest.write_text(json.dumps({'file_format_version': '1.0.0', 'runtime': {
        'name': 'WiVRn (KosmicKrisp copy path)', 'library_path': f'./{library.name}'}}, indent=2) + '\n')
    return manifest


def build_kk_runtime(work: Path = Path.home() / '.field4/wivrn-macos', runtime_dir: Path = KK_RUNTIME_DIR) -> Path:
    """Build the pinned WiVRn for macOS runtime client with optional/wivrn-macos's patch and install it."""
    pin = json.loads(KK_PIN.read_text())
    patch = KK_PIN.parent / pin['patch']
    for tool in ('git', 'cmake', 'ninja', 'xcrun'):
        if shutil.which(tool) is None:
            raise ValueError(f'{tool} is needed to build the WiVRn runtime.')
    source = work / 'src'
    if not (source / '.git').exists():
        work.mkdir(parents=True, exist_ok=True)
        subprocess.run(['git', 'clone', '--quiet', '--branch', pin['tag'], pin['source'], str(source)], check=True)
    head = subprocess.run(['git', '-C', str(source), 'rev-parse', 'HEAD'], check=True, text=True,
                          capture_output=True).stdout.strip()
    if head != pin['revision']:
        raise ValueError(f'{source} is at {head}, not the pinned {pin["revision"]} ({pin["tag"]}); '
                         'remove it to re-clone.')
    subprocess.run(['git', '-C', str(source), 'submodule', 'update', '--init', '--depth', '1',
                    'third_party/monado', 'third_party/wivrn', 'third_party/openxr-sdk'], check=True)
    monado = source / 'third_party/monado'
    # The port's script resets each submodule to its pin and stages its own patch series.
    subprocess.run([str(source / 'scripts/patches.sh'), 'apply'], cwd=source, check=True)
    subprocess.run(['git', '-C', str(monado), 'apply', '--index', str(patch)], check=True)
    # build-wivrn.sh --client-* compiles a snapshot of the staged Monado tree; take one as its
    # server build would, so the client needs no server build.
    snapshot = source / 'build/monado-snapshot'
    shutil.rmtree(snapshot, ignore_errors=True)
    snapshot.mkdir(parents=True)
    subprocess.run(['git', '-C', str(monado), 'checkout-index', '-a', f'--prefix={snapshot}/'], check=True)
    for name, args in (('.snapshot-base', ['rev-parse', 'HEAD']), ('.snapshot-tree', ['write-tree'])):
        (snapshot / name).write_text(subprocess.run(['git', '-C', str(monado), *args], check=True, text=True,
                                                    capture_output=True).stdout)
    (snapshot / '.snapshot-patches').write_text(
        '\n'.join(sorted(p.name for p in (source / 'patches/monado').glob('*.patch'))) + '\n')
    env = dict(os.environ)
    env['SDKROOT'] = subprocess.run(['xcrun', '--sdk', 'macosx', '--show-sdk-path'], check=True, text=True,
                                    capture_output=True).stdout.strip()
    sdks = sorted((Path.home() / 'VulkanSDK').glob('[0-9]*/macOS'))
    if 'VULKAN_SDK' not in env and sdks:
        env['VULKAN_SDK'] = str(sdks[-1])
    shutil.rmtree(source / 'build/wivrn-client-arm64', ignore_errors=True)
    subprocess.run([str(source / 'scripts/build-wivrn.sh'), '--client-arm64'], cwd=source, env=env, check=True)
    built = source / 'build/wivrn-client-arm64/client/libopenxr_wivrn.dylib'
    if not built.is_file():
        raise ValueError(f'The WiVRn build finished but {built} is missing.')
    runtime_dir.mkdir(parents=True, exist_ok=True)
    library = runtime_dir / built.name
    shutil.copy2(built, library)
    (runtime_dir / 'build-info.json').write_text(json.dumps({
        **pin, 'patchSha256': hashlib.sha256(patch.read_bytes()).hexdigest(),
        'vulkanSdk': env.get('VULKAN_SDK')}, indent=2) + '\n')
    return write_kk_manifest(runtime_dir, library)


def launch_environment(manifest: Path, loader: Path, environ: dict[str, str],
                       driver: str = 'moltenvk') -> dict[str, str]:
    """The variables this launch overrides. JAVA_TOOL_OPTIONS keeps whatever the caller already had."""
    tool_options = ' '.join(filter(None, [environ.get('JAVA_TOOL_OPTIONS', '').strip(),
                                          f'-Dorg.lwjgl.openxr.libname={loader}']))
    return {'XR_RUNTIME_JSON': str(manifest), 'AFIELD_VULKAN_DRIVER': driver,
            'JAVA_TOOL_OPTIONS': tool_options, 'AFIELD_FIELD_SH_NO_TERMINAL': '1'}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--wivrn', type=Path, default=default_app(), help='WiVRn.app (auto-detect /Applications or ~/Applications)' )
    parser.add_argument('--prepare', action='store_true', help='Install missing WiVRn/loader and configure VideoToolbox; used by ./field.sh --setup')
    parser.add_argument('--loader', type=Path, help='libopenxr_loader.dylib to use instead of the one in .afield-local')
    parser.add_argument('--build-loader', action='store_true', help='Build the OpenXR loader into .afield-local/openxr, then exit')
    parser.add_argument('--configure-encoder', action='store_true',
                        help="Set WiVRn's encoder to VideoToolbox HEVC in ~/.config/wivrn/config.json, then exit")
    parser.add_argument('--build-kk-runtime', action='store_true',
                        help="Build WiVRn's runtime with optional/wivrn-macos's KosmicKrisp patch into "
                             '.afield-local/wivrn-kk, then exit')
    parser.add_argument('--kosmickrisp', action='store_true',
                        help='Run Field on KosmicKrisp with the runtime --build-kk-runtime built')
    parser.add_argument('--check', action='store_true', help='Print the launch plan without starting anything')
    parser.add_argument('field_args', nargs=argparse.REMAINDER, help='Arguments after -- go to field.sh')
    args = parser.parse_args()
    config = wivrn_config_path(Path.home())
    try:
        if args.prepare:
            prepare(args.wivrn, config)
            return 0
        if args.build_loader:
            print(build_loader())
            return 0
        if args.build_kk_runtime:
            print(build_kk_runtime())
            return 0
        if args.configure_encoder:
            configure_encoder(config)
            print(f'{config}: encoder set to VideoToolbox HEVC. Restart WiVRn for it to apply.')
            return 0
        # The installed app is needed either way: it runs the WiVRn server.
        manifest = runtime_manifest(args.wivrn)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.error(f'{error} {SETUP_HINT}')
    loader = (args.loader or loader_library()).expanduser().resolve()
    problems = []
    driver = 'moltenvk'
    if args.kosmickrisp:
        driver, manifest = 'kosmickrisp', KK_MANIFEST
        if not KK_MANIFEST.is_file():
            problems.append(f'No KosmicKrisp runtime at {KK_MANIFEST}; run this tool with --build-kk-runtime first.')
    if not loader.is_file():
        problems.append(f'No OpenXR loader at {loader}; run this tool with --build-loader first.')
    if not encoder_configured(config):
        problems.append(f'{config} does not select VideoToolbox; run this tool with --configure-encoder, '
                        'then restart WiVRn.')
    launcher = REPO / 'field.sh'
    field_args = args.field_args[1:] if args.field_args[:1] == ['--'] else args.field_args
    overrides = launch_environment(manifest, loader, os.environ, driver)
    if args.check:
        print(json.dumps({'environment': overrides, 'command': [str(launcher), *field_args],
                          'problems': problems, 'status': 'Launch plan only; nothing was started.'}, indent=2))
        return 0
    if problems:
        parser.error(' '.join(problems) + ' ' + SETUP_HINT)
    print(f'Field ({driver}) → WiVRn for macOS. Open WiVRn and connect the headset first; set useHeadset = true in the '
          'VR starting point.', file=sys.stderr, flush=True)
    os.execve(launcher, [str(launcher), *field_args], {**os.environ, **overrides})
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
