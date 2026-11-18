#!/usr/bin/env python3
"""Regenerate the dependency-free Xcode project using stable object identifiers."""
import hashlib
from pathlib import Path
root = Path(__file__).resolve().parents[1]
def uid(s): return hashlib.sha1(s.encode()).hexdigest()[:24].upper()
objects = []
def add(name, body):
    objects.append(f'{uid(name)} = {{ {body} }};')
    return uid(name)
sources = sorted((root / 'DrawingOn').glob('*.swift'))
resources = sorted((root / 'DrawingOn/Resources').glob('*.ttf'))
source_refs, source_builds, resource_refs, resource_builds = [], [], [], []
for path in sources + resources:
    rel = path.relative_to(root).as_posix()
    ref = add(rel, f'isa = PBXFileReference; lastKnownFileType = {"sourcecode.swift" if path.suffix == ".swift" else "file"}; path = "{rel}"; sourceTree = SOURCE_ROOT;')
    build = add('build-' + rel, f'isa = PBXBuildFile; fileRef = {ref};')
    (source_refs if path in sources else resource_refs).append(ref)
    (source_builds if path in sources else resource_builds).append(build)
def arr(items): return '(' + ','.join(items) + (',' if items else '') + ')'
product = add('product', 'isa = PBXFileReference; explicitFileType = wrapper.application; path = DrawingOn.app; sourceTree = BUILT_PRODUCTS_DIR;')
products = add('products', f'isa = PBXGroup; children = ({product}); name = Products; sourceTree = "<group>";')
main = add('main', f'isa = PBXGroup; children = {arr(source_refs + resource_refs + [products])}; sourceTree = "<group>";')
src_phase = add('sources', f'isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = {arr(source_builds)}; runOnlyForDeploymentPostprocessing = 0;')
res_phase = add('resources', f'isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = {arr(resource_builds)}; runOnlyForDeploymentPostprocessing = 0;')
frameworks = add('frameworks', 'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;')
base = '''SDKROOT = iphoneos; IPHONEOS_DEPLOYMENT_TARGET = 18.0; SWIFT_VERSION = 5.0; CLANG_ENABLE_MODULES = YES; CLANG_ENABLE_OBJC_ARC = YES; ENABLE_USER_SCRIPT_SANDBOXING = YES;'''
app = '''PRODUCT_BUNDLE_IDENTIFIER = org.openendedgroup.DrawingOn; PRODUCT_NAME = DrawingOn; TARGETED_DEVICE_FAMILY = 2; INFOPLIST_FILE = DrawingOn/Info.plist; GENERATE_INFOPLIST_FILE = NO; CODE_SIGN_STYLE = Automatic; CURRENT_PROJECT_VERSION = 1; MARKETING_VERSION = 0.1; SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"; SUPPORTS_MACCATALYST = NO; SWIFT_EMIT_LOC_STRINGS = YES;'''
for scope in ['project', 'app']:
    configs = []
    for mode in ['Debug', 'Release']:
        flags = 'SWIFT_OPTIMIZATION_LEVEL = "-Onone"; DEBUG_INFORMATION_FORMAT = dwarf; SWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG;' if mode == 'Debug' else 'SWIFT_OPTIMIZATION_LEVEL = "-O"; DEBUG_INFORMATION_FORMAT = "dwarf-with-dsym";'
        configs.append(add(scope+mode, f'isa = XCBuildConfiguration; buildSettings = {{ {base if scope == "project" else app} {flags} }}; name = {mode};'))
    add(scope+'config', f'isa = XCConfigurationList; buildConfigurations = {arr(configs)}; defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
target = add('target', f'isa = PBXNativeTarget; buildConfigurationList = {uid("appconfig")}; buildPhases = {arr([src_phase, frameworks, res_phase])}; buildRules = (); dependencies = (); name = DrawingOn; productName = DrawingOn; productReference = {product}; productType = "com.apple.product-type.application";')
project = add('project', f'isa = PBXProject; attributes = {{ LastUpgradeCheck = 2700; }}; buildConfigurationList = {uid("projectconfig")}; compatibilityVersion = "Xcode 14.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en,Base); mainGroup = {main}; productRefGroup = {products}; projectDirPath = ""; projectRoot = ""; targets = ({target});')
(root/'DrawingOn.xcodeproj/project.pbxproj').write_text('// !$*UTF8*$!\n{ archiveVersion = 1; classes = {}; objectVersion = 56; objects = {\n' + '\n'.join(objects) + f'\n}}; rootObject = {project}; }}\n')
(root/'DrawingOn.xcodeproj/xcshareddata/xcschemes/DrawingOn.xcscheme').write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2700" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="DrawingOn.app" BlueprintName="DrawingOn" ReferencedContainer="container:DrawingOn.xcodeproj"/></BuildActionEntry></BuildActionEntries></BuildAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="DrawingOn.app" BlueprintName="DrawingOn" ReferencedContainer="container:DrawingOn.xcodeproj"/></BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"/>
<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>''')
