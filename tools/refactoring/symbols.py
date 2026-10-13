"""PSI declaration inventory and reviewed file-to-package expansion; no binding claims."""
from collections import defaultdict
from pathlib import Path
import json
import re
import subprocess
from .core import RefactorError, confined, digest, load_recipe, save_json

NAME = re.compile(r'[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*\Z')


def inventory(repo, engine, out):
    repo = repo.resolve()
    if out.exists():
        raise RefactorError('Declaration inventory output must be new')
    rows, files, packages, errors = [], {}, set(), []
    paths = subprocess.check_output(['git', '-C', str(repo), 'ls-files', '-z'], text=True).split('\0')
    for path in sorted(paths):
        # Compiled source sets, including standalone tools; never saved .kt box sidecars.
        if not path.endswith('.kt') or 'kotlin' not in Path(path).parts:
            continue
        source = confined(repo, path).read_bytes()
        result = engine.declarations(source.decode('utf-8'))
        package = result['package']
        packages.add(package)
        files[path] = {'hash': digest(source), 'package': package}
        errors.extend({'path': path, 'detail': message} for message in result['errors'])
        for d in result['declarations']:
            rows.append(dict(d, path=path, package=package, sourceSet=path.split('/kotlin/')[0],
                             id=digest(json.dumps([path, d], sort_keys=True).encode())))
    result = {'schema': 1, 'base': subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip(),
              'evidence': 'Kotlin PSI top-level declaration syntax, not resolved bindings or JVM ABI',
              'packages': sorted(packages - {''}), 'files': files, 'declarations': rows, 'errors': errors}
    out.mkdir(parents=True)
    save_json(out / 'declarations.json', result)
    return result


def generate(index, assignments, recipe_path, repo, out):
    """Expand exact source-file assignments; a symbol assignment covers every overload/platform."""
    if out.exists():
        raise RefactorError('Symbol map output must be new')
    recipe = load_recipe(recipe_path)
    if assignments.get('schema') != 1 or set(assignments) - {'schema', 'files', 'symbols', 'keep'}:
        raise RefactorError('Assignments need schema: 1 and files/symbols/keep only')
    for field in ('files', 'symbols'):
        if not isinstance(assignments.get(field, {}), dict):
            raise RefactorError(f'{field} assignments must be an object')
    keep = assignments.get('keep', [])
    if not isinstance(keep, list) or any(not isinstance(s, str) for s in keep):
        raise RefactorError('keep must list exact symbol families')
    for path, record in index['files'].items():
        if digest(confined(repo, path).read_bytes()) != record['hash']:
            raise RefactorError(f'Declaration inventory is stale: {path}')
    current = set(subprocess.check_output(['git', '-C', str(repo), 'ls-files', '-z'], text=True).split('\0'))
    current = {p for p in current if p.endswith('.kt') and 'kotlin' in Path(p).parts}
    if current != set(index['files']):
        raise RefactorError('Declaration inventory is stale: source files added or removed')
    families = defaultdict(list)
    for d in index['declarations']:
        if d['visibility'] != 'private':
            families[d['fqn']].append(d)
    unknown = set(assignments.get('symbols', {})) | set(keep)
    if unknown - set(families):
        raise RefactorError(f'Unknown symbol families: {sorted(unknown - set(families))}')
    for path, target in assignments.get('files', {}).items():
        if path not in index['files'] or (target is not None and (not isinstance(target, str) or not NAME.fullmatch(target))):
            raise RefactorError(f'Invalid file assignment: {path}')
        if index['files'][path]['package'] not in recipe['splitPackages']:
            raise RefactorError(f'File is not in an exact split package: {path}')
    if any(assignments.get('files', {}).get(e['path']) for e in index['errors']):
        raise RefactorError('An assigned file has parser errors; cannot expand incomplete declarations')
    generated, pending, expanded = {}, [], []
    for fqn, declarations in sorted(families.items()):
        if declarations[0]['package'] not in recipe['splitPackages']:
            continue
        targets = set()
        for d in declarations:
            package = assignments.get('files', {}).get(d['path'])
            if package:
                targets.add(package + '.' + d['name'])
        explicit = assignments.get('symbols', {}).get(fqn, recipe['symbols'].get(fqn))
        if explicit:
            if not isinstance(explicit, str) or not NAME.fullmatch(explicit):
                raise RefactorError(f'Invalid symbol destination: {fqn}')
            targets.add(explicit)
        retained = fqn in keep or fqn in recipe.get('retainedSymbols', [])
        if len(targets) > 1 or (retained and targets):
            raise RefactorError(f'Conflicting destinations across overloads/platforms: {fqn}: {sorted(targets)}')
        if targets:
            target = next(iter(targets))
            if target == fqn:
                raise RefactorError(f'Use keep for unchanged symbol: {fqn}')
            generated[fqn] = target
        elif retained:
            target = fqn
        else:
            pending.append({'fqn': fqn, 'kind': declarations[0]['kind'], 'sources': sorted({d['path'] for d in declarations})})
            continue
        expanded.extend(dict(d, destination=target, retained=retained) for d in declarations)
    unused = set(assignments.get('symbols', {})) | set(keep)
    unused -= {d['fqn'] for d in expanded}
    if unused:
        raise RefactorError(f'Assignments outside selected split packages: {sorted(unused)}')
    for old, target in generated.items():
        if target in families and target not in generated and target not in recipe['symbols']:
            raise RefactorError(f'Destination already declared: {old} -> {target}')
    recipe['symbols'].update(generated)
    recipe['retainedSymbols'] = sorted(set(recipe.get('retainedSymbols', [])) | set(keep))
    destinations = {v.rsplit('.', 1)[0] for v in recipe['symbols'].values()}
    for package in index['packages']:
        matches = [old for old in recipe['packages'] if package == old or package.startswith(old + '.')]
        if matches:
            old = max(matches, key=len)
            destinations.add(recipe['packages'][old] + package[len(old):])
    recipe['knownPackages'] = sorted(set(index['packages']) | destinations)
    # Validate the expanded recipe before publishing any output.
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        check = Path(tmp) / 'recipe.json'; save_json(check, recipe); load_recipe(check)
    out.mkdir(parents=True)
    save_json(out / 'recipe.json', recipe)
    save_json(out / 'symbol-map.json', {'schema': 1, 'base': index['base'], 'evidence': index['evidence'],
                                      'declarations': expanded, 'unassigned': pending, 'inventoryErrors': index['errors']})
    files = defaultdict(list)
    for item in pending:
        for path in item['sources']:
            files[path].append(item['fqn'])
    template = dict(assignments, files=dict(assignments.get('files', {})))
    for path in sorted(files):
        template['files'].setdefault(path, None)
    save_json(out / 'assignments.template.json', template)
    report = ['# Split package map', '', f'{len(generated)} moved symbol families; {len(keep)} explicitly kept; {len(pending)} unassigned.', '',
              'These are reference mappings. Declaration relocation, same-package imports and star-import bindings still require verification.', '',
              '## Unassigned files', '']
    for path, names in sorted(files.items()):
        report.extend([f'### {path}', '', ', '.join(f'`{n}`' for n in names), ''])
    (out / 'report.md').write_text('\n'.join(report) + '\n')
    return {'mappedFamilies': len(generated), 'retainedFamilies': len(keep), 'unassignedFamilies': len(pending),
            'inventoryErrors': len(index['errors']), 'out': str(out)}
