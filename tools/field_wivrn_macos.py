#!/usr/bin/env python3
"""Launch Field on macOS with the WiVRn for macOS OpenXR runtime; leave the default runtime and driver untouched.

Field's OpenXR path needs three things on a Mac that Linux provides by itself: an OpenXR loader
(LWJGL ships none for macOS), the WiVRn runtime manifest, and the MoltenVK driver, because WiVRn's
runtime imports its swapchains with VK_EXT_metal_objects, which KosmicKrisp does not offer. All three
are set for this launch only. `--build-loader` builds the Khronos loader into `.afield-local/openxr`;
`--configure-encoder` makes WiVRn use VideoToolbox HEVC, since its automatic choice can fall back to
the x264 encoder, which crashes the WiVRn server on the first frame under MoltenVK.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

REPO = Path(__file__).resolve().parent.parent
OPENXR_SDK_URL = 'https://github.com/KhronosGroup/OpenXR-SDK.git'
OPENXR_SDK_TAG = 'release-1.1.53'
LOADER_PREFIX = REPO / '.afield-local/openxr'
DEFAULT_APP = Path('/Applications/WiVRn.app')
VIDEOTOOLBOX = {'encoder': 'videotoolbox', 'codec': 'h265'}


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


def launch_environment(manifest: Path, loader: Path, environ: dict[str, str]) -> dict[str, str]:
    """The variables this launch overrides. JAVA_TOOL_OPTIONS keeps whatever the caller already had."""
    tool_options = ' '.join(filter(None, [environ.get('JAVA_TOOL_OPTIONS', '').strip(),
                                          f'-Dorg.lwjgl.openxr.libname={loader}']))
    return {'XR_RUNTIME_JSON': str(manifest), 'AFIELD_VULKAN_DRIVER': 'moltenvk',
            'JAVA_TOOL_OPTIONS': tool_options, 'AFIELD_FIELD_SH_NO_TERMINAL': '1'}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--wivrn', type=Path, default=DEFAULT_APP, help='WiVRn.app (default /Applications/WiVRn.app)')
    parser.add_argument('--loader', type=Path, help='libopenxr_loader.dylib to use instead of the one in .afield-local')
    parser.add_argument('--build-loader', action='store_true', help='Build the OpenXR loader into .afield-local/openxr, then exit')
    parser.add_argument('--configure-encoder', action='store_true',
                        help="Set WiVRn's encoder to VideoToolbox HEVC in ~/.config/wivrn/config.json, then exit")
    parser.add_argument('--check', action='store_true', help='Print the launch plan without starting anything')
    parser.add_argument('field_args', nargs=argparse.REMAINDER, help='Arguments after -- go to field.sh')
    args = parser.parse_args()
    config = wivrn_config_path(Path.home())
    try:
        if args.build_loader:
            print(build_loader())
            return 0
        if args.configure_encoder:
            configure_encoder(config)
            print(f'{config}: encoder set to VideoToolbox HEVC. Restart WiVRn for it to apply.')
            return 0
        manifest = runtime_manifest(args.wivrn)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.error(str(error))
    loader = (args.loader or loader_library()).expanduser().resolve()
    problems = []
    if not loader.is_file():
        problems.append(f'No OpenXR loader at {loader}; run this tool with --build-loader first.')
    if not encoder_configured(config):
        problems.append(f'{config} does not select VideoToolbox; run this tool with --configure-encoder, '
                        'then restart WiVRn.')
    launcher = REPO / 'field.sh'
    field_args = args.field_args[1:] if args.field_args[:1] == ['--'] else args.field_args
    overrides = launch_environment(manifest, loader, os.environ)
    if args.check:
        print(json.dumps({'environment': overrides, 'command': [str(launcher), *field_args],
                          'problems': problems, 'status': 'Launch plan only; nothing was started.'}, indent=2))
        return 0
    if problems:
        parser.error(' '.join(problems))
    print('Field → WiVRn for macOS. Open WiVRn and connect the headset first; set useHeadset = true in the '
          'VR starting point.', file=sys.stderr, flush=True)
    os.execve(launcher, [str(launcher), *field_args], {**os.environ, **overrides})
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
