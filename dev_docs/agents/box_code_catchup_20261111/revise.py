"""Reviewed idioms and small drawing/output edits, restricted to the frozen source scope."""
import json,re
from pathlib import Path
import session as S
R=S.load('previous_literals',S.HERE.parent/'box_code_catchup_20261027/revise.py')

def literals(code):
    """Keep factories where collection-literal inference needs an expected type."""
    edits=[];i=0
    while i<len(code):
        j=R.skip(code,i)
        if j!=i:i=j;continue
        m=re.match(r'listOf\(',code[i:]) if i==0 or not re.match(r'[\w.]',code[i-1]) else None
        if not m:i+=1;continue
        a=i+len('listOf');b=R.close(code,a);inside=code[a+1:b]
        prefix=code[max(0,i-200):i];suffix=code[b+1:];line=prefix.rsplit('\n',1)[-1]
        operand=re.search(r'(?:[-+*/%]=?|\bto)\s*$',prefix) or re.match(r'\s*[-+*/%]',suffix)
        receiver=re.match(r'\s*(?:\.|\?\.|!!)',suffix)
        result=re.match(r'\s*}',suffix) and (not line.strip() or re.search(r'(?:->|\{)\s*$',line))
        if inside.strip() and not operand and not receiver and not result and not inside.lstrip().startswith('*') and not re.search(r',\s*\*',inside):
            edits.extend([(i,a+1,'['),(b,b+1,']')])
        i+=1
    for a,b,value in sorted(edits,reverse=True):code=code[:a]+value+code[b:]
    return code,len(edits)//2

def function(code,name,new):
    m=re.search(r'fun '+name+r'\([^\n]*\)\s*\{',code)
    if not m:return code
    i=m.end();depth=1
    while depth:
        j=R.skip(code,i)
        if j!=i:i=j;continue
        if code[i]=='{':depth+=1
        elif code[i]=='}':depth-=1
        i+=1
    return code[:m.start()]+new+code[i:]

PRESET='''fun draw() {
    val radius = (20 + size * 60).toInt()
    val cx = 110 + (offset.x * 60).toInt()
    val cy = 90 - (offset.y * 50).toInt()
    val disc = fline {
        circle(cx, cy, radius)
        filled = true
        stroked = false
        color = Vector4f(tint)
    }
    val outline = fline {
        circle(cx, cy, radius)
        thickness = 6.0
        color = vec(1.0, 1.0, 1.0, 1.0)
    }
    // Keep the frame fixed while the controls move or resize the circle.
    val figure: List<FLine> = if (ring) [disc, outline] else [disc]
    lines(figure, channel = "main", w = 220.0, h = 180.0,
        framing = LinesFraming.fixed(0.0, 0.0, 220.0, 180.0))
}'''
SURFACE='''fun draw() {
    val radius = 10 + 40 * kotlin.math.log10(cutoff / 20.0) / 3.0
    val x = 150 + orbit.x * 120
    val y = 150 - orbit.y * 120
    val arm = fline {
        moveTo(150, 150)
        lineTo(x, y)
        color = vec(0.075, 0.38, 0.47, 1.0)
    }
    val disc = fline {
        circle(x, y, radius)
        filled = true
        stroked = false
        color = Vector4f(tint)
    }
    val figure: List<FLine> = if (armed) [arm, disc] else [arm]
    lines(figure, channel = "figure", w = 300.0, h = 300.0,
        framing = LinesFraming.fixed(0.0, 0.0, 300.0, 300.0))
    markdown("caption", "**$title** · cutoff `${cutoff.toInt()} Hz`")
}'''

def refine(code,path):
    # Both source boxes explicitly publish these inherited properties.
    if 'fromContext<SkyView>()' in code:code=code.replace('fromContext<SkyView>()','skyView')
    if path.endswith('streaming_diffusion.yaml'):code=code.replace('val owner: Renderer = fromContext()','val owner = renderer')
    if path.endswith('keyframe_autosweep.yaml') and 'val r: Renderer = fromContext()' in code:
        code=code.replace('val r: Renderer = fromContext()','val r = renderer')
        code=code.replace('val keys: KeyframeScope = fromContext()\n','')
        code=code.replace('val bundle: afield.graphics.scene.fabricated.StandardRenderer.Bundle = fromContext()','val ownedBundle = bundle')
        if 'val ownedBundle = bundle' in code:
            code=code.replace('bundle.postStack','ownedBundle.postStack').replace('bundle.scene','ownedBundle.scene')
    if 'Missing resetFlow; run Resources first' in code:
        a=code.index('// Compare a deterministic')
        tail=re.sub(r'\btravel\b','travelFlag',re.sub(r'\bresetFlow\b','reset',code[a:]))
        code='''// Capture the parent's resources so cleanup still owns this run's state.
val reset = resetFlow
val travelFlag = travel
val settings = [force, grainCell, strokeTime, flowWidth].map { it.snapshot() }

'''+tail
    if '<svg' in code and 'cutoff / 20.0' in code and 'fun draw()' in code:code=function(code,'draw',SURFACE)
    if '<svg' in code and 'var size by keys.slider' in code:code=function(code,'draw',PRESET)
    if '<svg' in code and 'fun showTrail' in code:
        code=function(code,'showTrail','''fun showTrail(v: Vector2f) {
    trail += Vector2f(v)
    val dots = fline {
        for (p in trail.takeLast(60)) circle(150 + p.x * 130, 150 - p.y * 130, 3)
        filled = true
        stroked = false
        color = vec(0.05, 0.78, 0.93, 1.0)
    }
    lines(dots, channel = "what the box received", w = 300.0, h = 300.0,
        framing = LinesFraming.fixed(0.0, 0.0, 300.0, 300.0))
    markdown("coordinates", "(${v.x.f2}, ${v.y.f2})")
}''')
    if '<svg' in code and '// A ring of points.' in code:
        a=code.index('val dots =');code=code[:a]+'''val dots = fline {
    for (i in 0 until count) {
        val angle = i * 2 * PI / count
        circle(80 + radius * cos(angle), 80 + radius * sin(angle), 5)
    }
    filled = true
    stroked = false
    color = vec(0.30, 0.67, 0.97, 1.0)
}
lines(dots, channel = "ring", w = 160.0, h = 160.0,
    framing = LinesFraming.fixed(0.0, 0.0, 160.0, 160.0))
'''
    # Static guide cards carry prose, not custom UI. Keep the job monitor's real HTML widget.
    if path.startswith('dev_docs/agents/transcripts/audio_ai_'):
        def guide(m):
            body=m[1]
            body=re.sub(r'<h2[^>]*>(.*?)</h2>',r'## \1\n',body,flags=re.S)
            body=re.sub(r'<p[^>]*>(.*?)</p>',r'\1\n\n',body,flags=re.S)
            body=re.sub(r'<b>(.*?)</b>',r'**\1**',body,flags=re.S)
            body=re.sub(r'<code>(.*?)</code>',r'`\1`',body,flags=re.S)
            body=re.sub(r'</?section[^>]*>','',body).strip()
            body='\n'.join(line.strip() for line in body.splitlines())
            assert not re.search(r'<\w',body),body
            return 'markdown("guide", """\n'+body+'\n""".trimIndent(), width = 1040)'
        code=re.sub(r'display\(\s*"guide",\s*"""(.*?)"""\.trimIndent\(\),?\s*\)',guide,code,flags=re.S)
    if 'ChatLogViews.outline(tree)' in code:
        code=re.sub(r'display\(\s*"outline",.*?\n\)', 'markdown("outline", firstTurn, width = 720)',code,flags=re.S)
    code=code.replace('display("main", "<pre>$grids</pre>")','markdown("main", "```text\\n$grids\\n```")')
    code=code.replace('display("<pre>" + answers + "</pre>")','markdown("```text\\n$answers\\n```")')
    code=code.replace('display("<pre>" + report + "</pre>")','markdown("```text\\n$report\\n```")')
    if '<table style="font-size:20px;border-spacing:24px 8px">' in code:
        code=re.sub(r'display\(\s*"main",\s*""".*?""",?\s*\)', '''markdown("main", """
| Query | Edge reads |
| --- | ---: |
| naming a path | $named |
| walking all ${all.size} slots | $everything |
""".trimIndent())''',code,flags=re.S)
    return code

def main():
    rows=json.loads((S.OUT/'bodies.json').read_text());paths={r['path'] for r in rows if r['language']=='kotlin' and (re.search(r'listOf\(|fromContext|<svg|<pre|<table|<section',r['code']) or 'ChatLogViews.outline(tree)' in r['code'])}
    changes=[];counts={'collectionLiterals':0,'fixedDecimalHelpers':0}
    for p in sorted(paths):
        path=S.ROOT/p;before=path.read_text();edits=[]
        for r in S.spans(p,before):
            if r['language']!='kotlin' or r.get('property')=='preamble':continue
            code=r['code'];plain=re.sub(r'^:::.*$','',code,flags=re.M)
            # Only paths from this delta; preserve typed/empty factories and embedded teaching data.
            try:new,n=literals(plain)
            except (ValueError,IndexError):n=0
            # Apply to the original source so fold labels remain byte-for-byte intact.
            if n:
                new,_=literals(code) if ':::' not in code else (None,0)
                if new is None:
                    parts=re.split(r'(^:::.*$)',code,flags=re.M)
                    for i in range(0,len(parts),2):parts[i]=literals(parts[i])[0]
                    new=''.join(parts)
            else:new=code
            new,k=R.decimals(new);counts['collectionLiterals']+=n;counts['fixedDecimalHelpers']+=k
            new=refine(new,p)
            if new==code:continue
            changes.append(dict(path=p,language='kotlin',beforeSha=S.digest(code),afterSha=S.digest(new),before=code,after=new))
            if r['kind']=='yaml':new=(('|\n' if new.endswith('\n') else '|-\n')+''.join(' '*r['indent']+l+'\n' if l else '\n' for l in new.rstrip('\n').splitlines())) if r['style']=='|' else json.dumps(new,ensure_ascii=False)
            edits.append((r['start'],r['end'],new))
        after=before
        for a,b,c in sorted(edits,reverse=True):after=after[:a]+c+after[b:]
        if after!=before:path.write_text(after)
    (S.OUT/'readability-changes.json').write_text(json.dumps(changes,indent=2)+'\n')
    (S.HERE/'revision-verification.json').write_text(json.dumps(dict(counts=counts,bodyEdits=len(changes),paths=sorted({c['path'] for c in changes})),indent=2)+'\n')
    print(counts,len(changes),'body edits')
if __name__=='__main__':main()
