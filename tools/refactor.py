#!/usr/bin/env python3
"""Plan and apply offline Field import migrations. Run --help for the review workflow."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO))

from tools.refactoring.core import (KotlinEngine, RefactorError, apply_plan, load_recipe,
                                    make_plan, undo, verify)

ENGINE = REPO / 'tools/refactor-engine/build/install/field-refactor-engine/bin/field-refactor-engine'


def parser():
    cli = argparse.ArgumentParser(description=__doc__)
    commands = cli.add_subparsers(dest='command', required=True)
    commands.add_parser('build-engine', help='build/test the standalone Kotlin parser distribution')
    for name in ('inventory', 'plan'):
        cmd = commands.add_parser(name)
        if name == 'plan':
            cmd.add_argument('recipe', type=Path, help='versioned JSON or YAML recipe')
        cmd.add_argument('--repo', action='append', type=Path, default=[], help='tracked repository inputs (repeatable)')
        cmd.add_argument('--workspace', action='append', type=Path, default=[], help='external workspace (repeatable)')
        cmd.add_argument('--out', required=True, type=Path, help='new private report/plan directory, outside inputs')
        cmd.add_argument('--engine', type=Path, default=ENGINE, help='prebuilt standalone parser executable')
    cmd = commands.add_parser('declarations', help='index top-level Kotlin declarations in tracked source sets')
    cmd.add_argument('--repo', type=Path, required=True)
    cmd.add_argument('--out', type=Path, required=True)
    cmd.add_argument('--engine', type=Path, default=ENGINE)
    cmd = commands.add_parser('symbol-map', help='expand reviewed file/symbol assignments into a migration recipe')
    cmd.add_argument('--repo', type=Path, required=True)
    cmd.add_argument('--index', type=Path, required=True)
    cmd.add_argument('--assignments', type=Path, required=True)
    cmd.add_argument('--recipe', type=Path, required=True)
    cmd.add_argument('--out', type=Path, required=True)
    cmd = commands.add_parser('apply')
    cmd.add_argument('plan', type=Path)
    cmd.add_argument('--root-id', default='0', help='root number in the plan report')
    target = cmd.add_mutually_exclusive_group(required=True)
    target.add_argument('--copy-to', type=Path, help='publish a migrated workspace copy at a new path')
    target.add_argument('--offline', action='store_true', help='edit in place; asserts this root has no live writers')
    cmd.add_argument('--allow-partial', action='store_true', help='apply ready groups while leaving held groups unchanged')
    for name in ('verify', 'undo'):
        cmd = commands.add_parser(name)
        cmd.add_argument('plan', type=Path)
        cmd.add_argument('--root-id', default='0')
    return cli


def main(argv=None):
    cli = parser()
    args = cli.parse_args(argv)
    try:
        if args.command == 'build-engine':
            return subprocess.run([str(REPO / 'gradlew'), '-p', str(REPO / 'tools/refactor-engine'),
                                   'test', 'installDist', '--console=plain'], cwd=REPO).returncode
        if args.command == 'declarations':
            from tools.refactoring.symbols import inventory
            with KotlinEngine(args.engine.resolve()) as engine:
                result = inventory(args.repo, engine, args.out.resolve())
            print(json.dumps({'declarations': len(result['declarations']), 'errors': len(result['errors']), 'out': str(args.out)}))
            return 0
        if args.command == 'symbol-map':
            from tools.refactoring.symbols import generate
            print(json.dumps(generate(json.loads(args.index.read_text()), json.loads(args.assignments.read_text()),
                                      args.recipe, args.repo.resolve(), args.out.resolve())))
            return 0
        if args.command in ('plan', 'inventory'):
            roots = [(p, True) for p in args.repo] + [(p, False) for p in args.workspace]
            if not roots:
                cli.error('select at least one --repo or --workspace')
            recipe = load_recipe(args.recipe) if args.command == 'plan' else {
                'schema': 1, 'id': 'inventory', 'packages': {}, 'symbols': {}, 'splitPackages': [], 'generated': []}
            with KotlinEngine(args.engine.resolve()) as engine:
                result = make_plan(roots, recipe, args.out.absolute(), engine,
                                   progress=lambda s: print(s, file=sys.stderr, flush=True))
            print(json.dumps({'plan': str(args.out.resolve()), 'roots': [
                {'id': r['id'], 'files': r['files'], 'units': r['units'],
                 'readyFiles': sum(not c['held'] for c in r['changes']),
                 'heldFiles': sum(c['held'] for c in r['changes']),
                 'reviewFindings': sum(not f.get('retained') for f in r['findings'])} for r in result['roots']]}, indent=2))
            return 0
        if args.command == 'apply':
            result = apply_plan(args.plan, args.root_id, copy_to=args.copy_to,
                                offline=args.offline, allow_partial=args.allow_partial)
            result = {k: v for k, v in result.items() if k not in ('changes', 'completed')}
        elif args.command == 'verify':
            result = verify(args.plan.resolve(), args.root_id)
        else:
            result = undo(args.plan.resolve(), args.root_id)
        print(json.dumps(result, indent=2))
        return 0
    except (RefactorError, OSError, ValueError, subprocess.SubprocessError) as error:
        print(f'refactor: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
