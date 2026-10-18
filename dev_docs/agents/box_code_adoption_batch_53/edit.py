"""Own callback registrations and release a held Csound note during cancellation."""
from pathlib import Path
import json,re,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
def record(path,fn):
 p=R/path;a=p.read_text();b=fn(a);assert a!=b,path;p.write_text(b);rows.append({'path':path,'before':a,'after':b})
def transcript(s):
 edits=[]
 def visit(n):
  if isinstance(n,yaml.MappingNode):
   d={k.value:v for k,v in n.value}
   if d.get('box') and d['box'].value in ['cs_talk','cs_keys'] and 'properties' in d:
    k,v=next((k,v) for k,v in d['properties'].value if k.value=='code');c=v.value
    if d['box'].value=='cs_talk':
     c='import afield.runtime.lifecycle.RunScope\n\n'+c;c=c.replace('// outvalue calls back on Field\'s main thread.','// Stop or rerun removes these listeners from the independently playing orchestra.\nval run = requireNotNull(RunScope.current())')
     c=c.replace('pluck.onOutvalue("said") { println("Csound said: $it") }','run.own(pluck.onOutvalue("said") { println("Csound said: $it") })')
     old=next(l for l in c.splitlines() if l.startswith('pluck.onOutvalue("level")'));c=c.replace(old,'run.own('+old+')')
    else:
     c=c.replace('    delay(220)\n    pluck.noteOff(0, note)','    try {\n        delay(220)\n    } finally {\n        // Stop can cancel the delay; release this run\'s note before leaving.\n        pluck.noteOff(0, note)\n    }')
    ind=k.start_mark.column+2;value='|\n'+''.join(' '*ind+l+'\n' if l else '\n' for l in c.rstrip().splitlines());edits.append((v.start_mark.index,v.end_mark.index,value))
   else:
    for k,v in n.value:visit(v)
  elif isinstance(n,yaml.SequenceNode):
   for v in n.value:visit(v)
 visit(yaml.compose(s));assert len(edits)==2
 for a,b,c in sorted(edits,reverse=True):s=s[:a]+c+s[b:]
 return s
record('dev_docs/agents/transcripts/csound_demo.yaml',transcript)
def csound(s):
 old='pluck.onOutvalue("level") { v -> println("Reverb rms $v") } // outvalue "level", krms';assert old in s
 return s.replace(old,'// The orchestra can keep playing after this controlling box stops.\nval run = requireNotNull(afield.runtime.lifecycle.RunScope.current())\nrun.own(pluck.onOutvalue("level") { v -> println("Reverb rms $v") }) // outvalue "level", krms')
record('public/docs/csound.md',csound)
def browser(s):
 old='browserControl.addEventListener { event ->\n    if (event.name == "console" || event.name == "javascript-exception")\n        println("${event.name}: ${event.payload}")\n}';assert old in s
 new='''val run = requireNotNull(afield.runtime.lifecycle.RunScope.current())
val removeListener = browserControl.addEventListener { event ->
    if (event.name == "console" || event.name == "javascript-exception")
        println("${event.name}: ${event.payload}")
}
// Remove this registration on Stop/rerun while the shared browser stays open.
run.own(release = removeListener)'''
 return s.replace(old,new)
record('public/docs/puppet_browser.md',browser)
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
