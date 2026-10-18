"""Keep long loops responsive to frame scheduling and cooperative cancellation."""
from pathlib import Path
import json
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;rows=[]
def record(path,fn):
 p=R/path;a=p.read_text();b=fn(a);assert a!=b;rows.append({'path':path,'before':a,'after':b});p.write_text(b)
def cancel(s):
 old='            repeat(perBatch) {\n                val x = random.nextDouble()';assert old in s
 return s.replace(old,'            repeat(perBatch) { sample ->\n                // Check within the batch so Stop need not wait for a billion samples.\n                if (sample % 65_536 == 0) context.checkCancelled()\n                val x = random.nextDouble()')
record('public/docs/ai4_long_builds.md',cancel)
def frames(s):
 a='    f.lineTo(at + vec(0, 0, 0.1))\n    lines["ourLine"] = f\n';assert a in s;s=s.replace(a,a+'    frame() // Yield while held, so input updates and Stop can be processed.\n')
 a='    lines["ourLine"] = f // put this line onto the screen under the name "ourLine"\n  }';assert s.count(a)==1;s=s.replace(a,'    lines["ourLine"] = f // put this line onto the screen under the name "ourLine"\n    frame() // WAIT while held so controller updates and Stop can be processed\n  }');return s
record('public/docs/diff-player.md',frames)
(H/'changes.json').write_text(json.dumps(rows,indent=2)+'\n')
