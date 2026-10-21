"""Read every Kotlin occurrence, including generated/inline copies, for nonadjacent resets.

Candidates are review prompts: later assignments can be intentional state changes. The
adjacent-pair cleanup deliberately does not remove them without individual review.
"""
from collections import Counter
from pathlib import Path
import json
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from tools.inventory_box_code import Inventory

candidates = []
comments = []
coverage = Counter()

class Audit(Inventory):
    def add(self, path, locator, code, *args, **kwargs):
        count = len(self.rows)
        super().add(path, locator, code, *args, **kwargs)
        if len(self.rows) == count:
            return
        row = self.rows[-1]
        if row['language'] != 'kotlin':
            return
        coverage[row['role']] += 1
        declarations = {}
        for index, line in enumerate(code.splitlines()):
            declaration = re.match(r'^(\s*)var\s+(\w+)(?:\s*:\s*[^=]+)?\s*=\s*(.*)', line)
            if declaration:
                declarations[(declaration[1], declaration[2])] = (index, declaration[3])
            assignment = re.match(r'^(\s*)(\w+)\s*=\s*(?!=)(.+)', line)
            if assignment and (assignment[1], assignment[2]) in declarations:
                earlier, initializer = declarations[(assignment[1], assignment[2])]
                candidates.append(dict(path=path, locator=locator, role=row['role'], name=assignment[2],
                    declarationLine=earlier + 1, assignmentLine=index + 1,
                    initializer=initializer, assignment=assignment[3]))
            if re.search(r'(?:reset|assignment|declaration)', line, re.I) and re.search(r'(?:Alt.?Enter|Alt\+Enter|rerun|re-run|live)', line, re.I):
                comments.append(dict(path=path, locator=locator, role=row['role'], line=index+1, text=line.strip()))

tracked = subprocess.check_output(['git','ls-files','-z'], cwd=ROOT).decode().split('\0')
audit = Audit(ROOT, filter(None, tracked))
audit.run()
result = dict(coverage=dict(coverage), candidates=candidates, comments=comments)
out = ROOT/'build/repl-variable-rebinding/broad-reset-audit.json'
out.write_text(json.dumps(result, indent=2)+'\n')
print('Kotlin bodies by role:', dict(coverage))
print('Assignment candidates by role:', dict(Counter(x['role'] for x in candidates)))
for row in candidates:
    if row['role'] in {'source','docs-fence'}:
        print(json.dumps(row))
print('Live-edit comments:',len(comments))
