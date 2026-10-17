"""Apply the final reviewed drafts only to their exact original sources."""
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
rows=json.loads((HERE/'changes.json').read_text())
for row in rows:
 assert (ROOT/row['path']).read_text()==row['before'],row['path']
for row in rows:(ROOT/row['path']).write_text(row['after'])
print(len(rows),'reviewed drafts applied')
