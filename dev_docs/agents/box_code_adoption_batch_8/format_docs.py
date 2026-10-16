"""Reuse the complete state/fold-aware documentation formatter on this batch's paths."""
from pathlib import Path
import importlib.util,json,sys
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[2]
spec=importlib.util.spec_from_file_location('corpus_format',HERE.parent/'box_code_adoption_batch_7/format_docs.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
m.OUT=ROOT/'build/box-code-revision/adoption-8/format-docs';m.HERE=m.OUT/'input';m.HERE.mkdir(parents=True,exist_ok=True)
paths=json.loads((ROOT/'build/box-code-revision/adoption-8/edited-paths.json').read_text())
(m.HERE/'comment_changes.json').write_text(json.dumps([{'path':p} for p in paths if p.endswith('.md')]))
if __name__=='__main__':m.main()
