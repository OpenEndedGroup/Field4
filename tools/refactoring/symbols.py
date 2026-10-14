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
                             id=digest(json.dumps([path, {k: v for k, v in d.items() if k != 'line'}], sort_keys=True).encode())))
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
    if assignments.get('schema') != 1 or set(assignments) - {'schema', 'files', 'symbols', 'keep', 'declarations'}:
        raise RefactorError('Assignments need schema: 1 and files/symbols/keep only')
    for field in ('files', 'symbols', 'declarations'):
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
    declared = assignments.get('declarations', {})
    public_ids = {d['id']: d for ds in families.values() for d in ds if d['package'] in recipe['splitPackages']}
    if set(declared) - set(public_ids):
        raise RefactorError('Unknown declaration assignments; refresh the inventory')
    if any(not isinstance(v, str) or not NAME.fullmatch(v) for v in declared.values()):
        raise RefactorError('Declaration assignments must name qualified destinations')
    split_symbols = {}
    generated, pending, expanded = {}, [], []
    for fqn, declarations in sorted(families.items()):
        if declarations[0]['package'] not in recipe['splitPackages']:
            continue
        specific = [declared.get(d['id']) for d in declarations]
        if any(specific):
            if not all(specific):
                raise RefactorError(f'Assign every declaration in split family: {fqn}')
            explicit = assignments.get('symbols', {}).get(fqn, recipe['symbols'].get(fqn))
            if fqn in keep or fqn in recipe.get('retainedSymbols', []) or (explicit and set(specific) != {explicit}):
                raise RefactorError(f'Conflicting declaration/family assignment: {fqn}')
            if any(target.rsplit('.', 1)[-1] != d['name'] for d, target in zip(declarations, specific)):
                raise RefactorError('Split declaration assignments must preserve short names')
            if len(set(specific)) == 1:
                generated[fqn] = specific[0]
            else:
                split_symbols[fqn] = sorted(set(specific))
            expanded.extend(dict(d, destination=t, retained=False) for d, t in zip(declarations, specific))
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
    # Per-declaration evidence must be supplied again; do not inherit stale split completeness.
    recipe['splitSymbols'] = split_symbols
    recipe['retainedSymbols'] = sorted(set(recipe.get('retainedSymbols', [])) | set(keep))
    destinations = {v.rsplit('.', 1)[0] for v in recipe['symbols'].values()}
    destinations.update(v.rsplit('.', 1)[0] for ts in split_symbols.values() for v in ts)
    for package in index['packages']:
        matches = [old for old in recipe['packages'] if package == old or package.startswith(old + '.')]
        if matches:
            old = max(matches, key=len)
            destinations.add(recipe['packages'][old] + package[len(old):])
    recipe['knownPackages'] = sorted(set(index['packages']) | destinations)
    # An absent assignment is unresolved, never implicit permission to retain it.
    # Recompute rather than carrying forward a completeness claim from an older inventory.
    blocked = {families[item['fqn']][0]['package'] for item in pending}
    blocked.update(index['files'][e['path']]['package'] for e in index['errors'])
    stars = {}
    for package in recipe['splitPackages']:
        declarations = [d for d in expanded if d['package'] == package]
        if package in blocked or not declarations:
            continue
        if any(d['name'] != d['destination'].rsplit('.', 1)[-1] for d in declarations):
            continue  # A star cannot express a renamed symbol's old spelling.
        targets = sorted({d['destination'].rsplit('.', 1)[0] for d in declarations})
        if any(t != package and t in recipe['splitPackages'] for t in targets):
            continue
        stars[package] = targets
    recipe['splitStarImports'] = stars
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
    report = ['# Split package map', '', f'{len(generated)} moved symbol families; {len(split_symbols)} explicitly split overload families; {len(keep)} explicitly kept; {len(pending)} unassigned.', '',
              'These are reference mappings. Declaration relocation, same-package imports and star-import bindings still require verification.', '',
              '## Unassigned files', '']
    for path, names in sorted(files.items()):
        report.extend([f'### {path}', '', ', '.join(f'`{n}`' for n in names), ''])
    (out / 'report.md').write_text('\n'.join(report) + '\n')
    return {'mappedFamilies': len(generated), 'splitFamilies': len(split_symbols), 'retainedFamilies': len(keep), 'unassignedFamilies': len(pending),
            'inventoryErrors': len(index['errors']), 'expandedStarPackages': len(stars), 'out': str(out)}
