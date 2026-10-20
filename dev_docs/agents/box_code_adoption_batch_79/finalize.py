"""Record the phase's final mechanical coverage and queue explicit owned paths."""
from pathlib import Path
import json,hashlib,subprocess,tarfile,shutil
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;O=R/'build/box-code-revision/adoption-79';S=H.parent/'box_code_second_pass'
rows=json.loads((H/'changes.json').read_text())
for x in rows:assert (R/x['path']).read_text()==x['after'],x['path']
assert json.loads((H/'typed-level-behavior.json').read_text())['existingSourceSurvivedRepeatedInitializer']
audit=json.loads((R/'build/box-code-revision/final-audit.json').read_text());retained=[]
for x in audit['residual']:
 if not x['literalCandidate']:continue
 reason='compiler preamble or its saved mirror' if 'preamble' in x['locator'] or x['path']=='dev_docs/agents/demos/skinned_characters/document.md' else 'recorded conversation provenance'
 assert reason!='recorded conversation provenance' or 'function_conversation' in x['path'] or '/stackauto_conversation/' in x['path'],x
 retained.append({**x,'reason':reason})
(H/'residual-literals.json').write_text(json.dumps({'remainingUntypedCalls':retained,'typedEmptyOrSpreadCallsRetained':True},indent=2)+'\n')
images=['public/docs/assets/interactive_guides/navigation.png','public/docs/assets/interactive_guides/annotations.png']
(H/'capture-verification.json').write_text(json.dumps({'changedImages':[{'path':p,'sha256':hashlib.sha256((R/p).read_bytes()).hexdigest()} for p in images],'actualDocsRunner':True,'visuallyInspected':True,'navigationHighlightsActualLine':11,'annotationsTargetActualLine':17,'videoAttempt':'invalidated by unobserved clock interval; existing committed video unchanged; no full docs build claimed'},indent=2)+'\n')
(H/'README.md').write_text('''# Complete residual collection style and guide screenshot reconciliation

All 405 frozen findings are addressed. This final audit uses parsed containers and the frozen scope.

239 distinct nonempty untyped listOf calls in 129 Kotlin bodies became collection literals, covering 175 authored occurrences. The real import/ktfmt formatter ran on every candidate; candidate executable tokens, raw strings and fragment boundaries were checked before hash-fenced application. The real Field codec updated 25 saved documents, proving graph IDs and unselected properties unchanged. Nine matching mirrors were refreshed; the pre-existing lambda-oracle mirror drift remains explicit. The final source/metadata/mirror receipt covers 143 files, including the typed level change below. Empty, typed and spread calls, compiler preambles and recorded conversation provenance remain intentional; residual-literals.json classifies every remaining untyped candidate.

The staged level initializer now declares prop<String?>(level), initializes only when missing, and reads level.orEmpty(). Its complete authored box ran; editing the real property and re-executing the initializer preserved the existing source. Tutorial writes through setPropertyRaw now remain only in the deliberate invalid-shader failure smoke. Dynamic raw writes inside packaged FunctionStudyTools implement its generic property adapter and are retained with the recorded conversation.

The normal docs runner recaptured the guide screenshots. The changed navigation and annotation images were inspected: highlights and arrows target actual lines 11 and 17. An unrelated video recapture attempt was invalidated by an unobserved machine-clock interval; its committed video remains unchanged. No complete documentation-build success is claimed for that attempt.

Source review and mechanical coverage are complete. Final full guards and landing outcomes belong to the lander; queued is not landed. Platform/optional-worker limitations in earlier batch receipts remain in force.
''')
for name in ['guide-recapture.log','disk-cleanup.json']:
 p=R/'build/box-code-revision'/name
 if p.is_file():shutil.copy2(p,O/name)
with tarfile.open(H/'evidence.tar.gz','w:gz') as archive:
 for p in sorted(O.rglob('*')):
  if p.is_file() and p.suffix in {'.log','.json'} and p.stat().st_size<2_000_000:archive.add(p,arcname=str(p.relative_to(O)))
p=S/'checkpoint.json';d=json.loads(p.read_text());e='../box_code_adoption_batch_79/README.md';assert e not in d['batches'];d['batches'].append(e);d['next']='Source review and mechanical coverage complete. Await final lander outcomes and repair any concrete bounces; retain documented optional-backend limitations.';d['inProgress']={'batch':79,'branch':'codex/box-code-readability-79','baseline':'46818a5a9','state':'405/405 addressed; final collection and guide screenshot reconciliation complete; awaiting lander','evidence':e};d['mechanical']['finalCollectionAudit']={'evidence':e,'distinctConversions':239,'distinctBodies':129,'authoredOccurrences':175,'codecDocuments':25,'matchingMirrors':9};d['closure']={'sourceReview':'complete','mechanicalCoverage':'complete_with_explicit_retentions','guideScreenshotReconciliation':'complete','landing':'pending'};p.write_text(json.dumps(d,indent=2)+'\n')
p=S/'PRIORITIES.md';p.write_text(p.read_text().replace('Residual collection-literal coverage, guide capture reconciliation and final landing outcomes remain.','Collection-literal coverage and guide screenshot reconciliation are complete. Final landing outcomes remain.'))
p=S/'README.md';p.write_text(p.read_text()+f'\nFinal mechanical closure: [collection literals and guide screenshots]({e}). All 405 frozen findings are addressed; final landing outcomes are tracked separately.\n')
p=H.parent/'box_code_adoption_batch_71/README.md';p.write_text(p.read_text()+'\n## Capture reconciliation\n\nBatch 79 recaptured and inspected the changed navigation/annotation screenshots through the normal docs runner. They now show actual lines 11 and 17. The separate video attempt was invalidated by a machine-clock interval; its committed video is unchanged.\n')
paths=list(dict.fromkeys([x['path'] for x in rows]+images+[str(p.relative_to(R)) for p in H.iterdir() if p.is_file()]+[str((S/n).relative_to(R)) for n in ['checkpoint.json','README.md','PRIORITIES.md','final_audit.py']]+['dev_docs/agents/box_code_adoption_batch_71/README.md']))
subprocess.run(['git','diff','--check'],cwd=R,check=True)
subprocess.run(['git','add','--',*[p for p in paths if not p.endswith('.tar.gz')]],cwd=R,check=True);subprocess.run(['git','add','-f','--',*[p for p in paths if p.endswith('.tar.gz')]],cwd=R,check=True)
subprocess.run(['git','commit','--only','-m','Finish collection literal coverage and reconcile guide screenshots','--',*paths],cwd=R,check=True)
subprocess.run(['uv','run','python','tools/land.py','enqueue','codex/box-code-readability-79'],cwd=R,check=True)
