"""Run only the affected published fences through the normal transactional docs capture methods."""
import json,logging,os,re,sys
from pathlib import Path
from run import S
sys.path.insert(0,str(S.ROOT/'public'))
import docs_hooks as H
logging.basicConfig(level=logging.INFO,format='%(message)s')
class Config(dict):
    config_file_path=str(S.ROOT/'public/zensical.yml')
def main():
    os.environ['FIELD_DOCS_RUNNER_MODE']='always'
    os.environ['FIELD_DOCS_RUNNER_PORT']='9782'
    os.environ.setdefault('AFIELD_PAINT_LIBRARY','/Users/marc/field4/build/field-paint/release/libfield_paint_jvm.dylib')
    state=H.RunnerState(Config(docs_dir=str(S.ROOT/'public/docs'),site_dir=str(S.ROOT/'build/docs-catchup-preview'),use_directory_urls=True))
    selected={'fline_examples.md':{'fline-swept-cubic','fline-swept-degenerate'}}
    receipt=S.HERE/'capture-verification.json';records=json.loads(receipt.read_text())['captures'] if receipt.exists() else []
    done={(r['page'],r['name']):r.get('sourceFingerprint') for r in records if r['status']=='passed'}
    try:
        for page,names in selected.items():
            path=S.ROOT/'public/docs'/page;text=path.read_text();block=0
            for m in re.finditer(r'^(`{3,})(diffstatic_runner|diff_runner|diffstatic|diff)(?![\w-])([^\n]*)\n(.*?)^\1[ \t]*$',text,re.M|re.S):
                block+=1
                if m[2] not in {'diffstatic_runner','diff_runner'}:continue
                attrs=H.parse_attrs(m[3]);name=attrs.get('name')
                if name not in names:continue
                digest=__import__('hashlib').sha256(m[0].encode())
                for key in ('transcript','macro-file'):
                    if attrs.get(key):digest.update((path.parent/attrs[key]).read_bytes())
                fingerprint=digest.hexdigest()
                if done.get((page,name))==fingerprint:continue
                opening=m[1]+m[2]+m[3];line=text[:m.start()].count('\n')+1
                options=H.parse_runner_options(runner_name=m[2],open_line=opening,page_path=path,line_number=line,block_index=block)
                code=H.extract_runnable_code(lines=m[4].splitlines(keepends=True),fence_name=m[2],page_path=path,line_number=line,options=options)
                options=H.maybe_enable_output_wait(options=options,code=code)
                code=H.code_with_macro_digest(code=code,options=options,repo_root=S.ROOT,page_path=path)
                method=state.ensure_ui_drive if options.capture=='ui' else state.ensure_video if options.video else state.ensure_screenshot
                print('CAPTURE',page,name,flush=True)
                state.capture_with_environment(method,code=code,options=options,page_path=path,block_index=block)
                records=[r for r in records if (r['page'],r['name'])!=(page,name)]
                records.append(dict(page=page,name=name,status='passed',screenshot=options.screenshot,video=options.video,sourceFingerprint=fingerprint))
                receipt.write_text(json.dumps(dict(captures=records,method='Normal RunnerState capture_with_environment and transactional publication; actual authored fence and transcript.'),indent=2)+'\n')
    finally:state.close()
if __name__=='__main__':
    sys.path.insert(0, str(S.ROOT/'tools'))
    from verification_runtime import verification_lock
    with verification_lock(S.ROOT, label='Catch-up docs capture'):
        main()
