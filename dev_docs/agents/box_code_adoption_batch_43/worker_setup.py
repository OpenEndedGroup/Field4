"""Prepare an explicit ONNX selection for real analysis verification on this Mac.

The stock worker hashes the default Basic Pitch path as a TFLite file. On macOS that
path is a CoreML directory. Keep the shared service pristine; isolate the two-line
format choice in the build folder and record the exact difference. No inference or
result generation is replaced. The model ships in the locked basic-pitch 0.4.0 wheel.
"""
from pathlib import Path
import hashlib,subprocess,difflib,json
R=Path(__file__).resolve().parents[3];OUT=R/'build/box-code-revision/adoption-43';SOURCE=Path('/Users/marc/field4/services/field-audio-ai')
p=SOURCE/'worker-environments/analysis/adapter.py';before=p.read_text()
assert subprocess.check_output(['git','-C',str(SOURCE),'rev-parse','HEAD'],text=True).strip()=='8ffcb6b545fe04d3138192eec703fcaadb2d64c5'
assert not subprocess.check_output(['git','-C',str(SOURCE),'status','--porcelain'],text=True).strip()
model=OUT/'analysis-env/lib/python3.10/site-packages/basic_pitch/saved_models/icassp_2022/nmp.onnx'
digest='2c3c1d144bfa61ad236e92e169c13535c880469a12a047d4e73451f2c059a0ec'
assert hashlib.sha256(model.read_bytes()).hexdigest()==digest
old='BASIC_PITCH_MODEL_SHA256 = "3db297d54af8e01c6e5618245c956b1d71b6a2b978cb2dedb527173186552676"'
assert before.count(old)==1
selection='from basic_pitch import ICASSP_2022_MODEL_PATH';assert before.count(selection)==1
after=before.replace(old,'BASIC_PITCH_MODEL_SHA256 = "'+digest+'"').replace(selection,'from basic_pitch import build_icassp_2022_model_path, FilenameSuffix\n            ICASSP_2022_MODEL_PATH = build_icassp_2022_model_path(FilenameSuffix.onnx)')
(OUT/'analysis-worker').mkdir(exist_ok=True);(OUT/'analysis-worker/adapter.py').write_text(after)
(OUT/'worker-portability.patch').write_text(''.join(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile='pinned/adapter.py',tofile='private-onnx/adapter.py')))
(OUT/'worker-portability.json').write_text(json.dumps({'sourceUnchanged':True,'basicPitch':'0.4.0','onnxruntime':'1.23.2','modelSha256':digest,'stockIssue':'TFLite-only checksum applied to macOS CoreML directory','environmentChanges':'Install onnxruntime==1.23.2, remove auto-selected coremltools in private environment only','secondServiceIssue':'Two simultaneous equivalent requests race to insert analysis_results.request_hash; actual failed run retained. Lifecycle proof changes the window on rerun.'},indent=2)+'\n')
