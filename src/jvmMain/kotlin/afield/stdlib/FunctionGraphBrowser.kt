@file:afield.CompletionCategory("Function")

package afield.stdlib

import afield.Box

/** One swept value of a numeric node and the final signal it produces. */
data class FnGraphSweepPoint(
    val value: Double,
    val samples: DoubleArray
)

data class FnGraphTraceNode(
    val path: List<Int>,
    val role: String?,
    val op: String,
    val name: String?,
    val domain: Functions.Domain,
    val local: DoubleArray,
    val muted: DoubleArray,
    val effect: DoubleArray,
    val numeric: Boolean = false,
    val value: Double? = null,
    val sensitivity: List<FnGraphSweepPoint> = emptyList()
)

data class FnGraphTrace(
    val domain: Functions.Domain,
    val xs: DoubleArray,
    val final: DoubleArray,
    val nodes: List<FnGraphTraceNode>
)

/**
 * Samples every visible graph occurrence on one root time-base.
 *
 * `local` is the selected subgraph sampled directly at root x. `muted` is the
 * complete root with that occurrence replaced by a neutral signal. `effect` is
 * `final - muted`, so it includes all transformations between the occurrence
 * and the root. Effect is contextual rather than an additive decomposition.
 */
fun Functions.Fn.graphTrace(
    sampleDomain: Functions.Domain? = null,
    samples: Int = 512,
    maxDepth: Int = 12,
    maxNodes: Int = 32,
    sensitivitySteps: Int = 0,
    sensitivitySpan: Double = 1.0
): FnGraphTrace {
    require(maxDepth >= 0) { "maxDepth must be non-negative" }
    require(maxNodes > 0) { "maxNodes must be positive" }
    require(sensitivitySteps >= 0) { "sensitivitySteps must be non-negative" }
    val plotDomain = (sampleDomain ?: domain).finiteGraphBrowseDomain()
    val count = samples.coerceIn(16, 4096)
    val xs = DoubleArray(count) { index ->
        plotDomain.min + plotDomain.length * index.toDouble() / (count - 1).toDouble()
    }
    val final = sampleGraphBrowse(this, xs)
    val matches = graphMatches(maxDepth) { true }.take(maxNodes)
    val nodes = matches.map { match ->
        val replacement = graphBrowseMuteReplacement(match.context)
        val mutedFn = rewriteGraph(preserveSharing = false) { context, copy ->
            if (context.path == match.context.path) replacement else copy
        }
        val local = sampleGraphBrowse(match.function, xs)
        val muted = sampleGraphBrowse(mutedFn, xs)
        val effect = DoubleArray(count) { index ->
            val a = final[index]
            val b = muted[index]
            if (a.isFinite() && b.isFinite()) a - b else Double.NaN
        }
        val value = match.function.graphNode().numbers["value"]
        val numeric = "number" in match.function.graphNode().tags && value != null
        val sensitivity = if (numeric && sensitivitySteps > 0) {
            graphSweepFinal(match.context.path, value!!, sensitivitySpan, sensitivitySteps, xs)
        } else {
            emptyList()
        }
        FnGraphTraceNode(
            path = match.context.path,
            role = match.context.role,
            op = match.context.op,
            name = match.context.name,
            domain = match.context.domain,
            local = local,
            muted = muted,
            effect = effect,
            numeric = numeric,
            value = value,
            sensitivity = sensitivity
        )
    }
    return FnGraphTrace(plotDomain, xs, final, nodes)
}

/** Sweeps the numeric node at [path] across a range around [base] and samples the whole root each time. */
private fun Functions.Fn.graphSweepFinal(
    path: List<Int>,
    base: Double,
    span: Double,
    steps: Int,
    xs: DoubleArray
): List<FnGraphSweepPoint> {
    val radius = if (base == 0.0) span else kotlin.math.abs(base) * span
    val n = steps.coerceIn(2, 64)
    return (0 until n).map { index ->
        val value = base - radius + 2.0 * radius * index / (n - 1).toDouble()
        val swept = rewriteGraph(preserveSharing = false) { context, copy ->
            if (context.path == path) Functions.c(value, copy.domain) else copy
        }
        FnGraphSweepPoint(value, sampleGraphBrowse(swept, xs))
    }
}

/** One rewrite step: the signal before and after applying a single rule, on the shared axis. */
data class FnRewriteStage(
    val label: String,
    val before: DoubleArray,
    val after: DoubleArray,
    val delta: DoubleArray,
    val changed: Boolean
)

data class FnRewriteTrace(
    val domain: Functions.Domain,
    val xs: DoubleArray,
    val original: DoubleArray,
    val finalSamples: DoubleArray,
    val stages: List<FnRewriteStage>
)

/**
 * Samples the signal before and after each rule, applied cumulatively, so a rewrite
 * can be watched step by step on one time-base. Works with pattern rules ([fnRule])
 * and programmatic [FnGraphRule]s alike.
 */
fun Functions.Fn.graphRewriteTrace(
    rules: List<FnGraphRule>,
    sampleDomain: Functions.Domain? = null,
    samples: Int = 512
): FnRewriteTrace {
    val plotDomain = (sampleDomain ?: domain).finiteGraphBrowseDomain()
    val count = samples.coerceIn(16, 4096)
    val xs = DoubleArray(count) { index ->
        plotDomain.min + plotDomain.length * index.toDouble() / (count - 1).toDouble()
    }
    val original = sampleGraphBrowse(this, xs)
    var current = this
    val stages = rules.map { rule ->
        val before = current
        val run = current.rewriteWithRules(rule)
        current = run.function
        graphRewriteStage(rule.name, before, current, xs, run.changed)
    }
    return FnRewriteTrace(plotDomain, xs, original, sampleGraphBrowse(current, xs), stages)
}

/** String-DSL convenience: one stage per meaningful rule line, applied cumulatively with [Functions.Fn.rewrite]. */
fun Functions.Fn.graphRewriteTrace(
    spec: String,
    sampleDomain: Functions.Domain? = null,
    samples: Int = 512
): FnRewriteTrace {
    val plotDomain = (sampleDomain ?: domain).finiteGraphBrowseDomain()
    val count = samples.coerceIn(16, 4096)
    val xs = DoubleArray(count) { index ->
        plotDomain.min + plotDomain.length * index.toDouble() / (count - 1).toDouble()
    }
    val lines = spec.lineSequence()
        .map { it.substringBefore("//").substringBefore('#').trim() }
        .filter { it.isNotEmpty() && (it.contains("=>") || it.contains("->") || it.contains('→')) }
        .toList()
    val original = sampleGraphBrowse(this, xs)
    var current = this
    val stages = lines.map { line ->
        val before = current
        val beforeSig = before.graphSignature()
        current = current.rewrite(line)
        graphRewriteStage(line, before, current, xs, current.graphSignature() != beforeSig)
    }
    return FnRewriteTrace(plotDomain, xs, original, sampleGraphBrowse(current, xs), stages)
}

private fun graphRewriteStage(
    label: String,
    before: Functions.Fn,
    after: Functions.Fn,
    xs: DoubleArray,
    changed: Boolean
): FnRewriteStage {
    val beforeSamples = sampleGraphBrowse(before, xs)
    val afterSamples = sampleGraphBrowse(after, xs)
    val delta = DoubleArray(xs.size) { index ->
        val a = afterSamples[index]
        val b = beforeSamples[index]
        if (a.isFinite() && b.isFinite()) a - b else Double.NaN
    }
    return FnRewriteStage(label, beforeSamples, afterSamples, delta, changed)
}

private fun Functions.Domain.finiteGraphBrowseDomain(): Functions.Domain = when {
    isFinite && length > 0.0 -> this
    min.isFinite() -> Functions.Domain.of(min - 0.5, min + 0.5)
    else -> Functions.Domain.DEFAULT
}

private fun sampleGraphBrowse(fn: Functions.Fn, xs: DoubleArray): DoubleArray =
    DoubleArray(xs.size) { index ->
        runCatching {
            fn.evalSafe(xs[index], outside = Double.NaN, edge = Functions.EdgeMode.ZERO)
        }.getOrDefault(Double.NaN)
    }

private fun graphBrowseMuteReplacement(context: FnGraphCopyContext): Functions.Fn {
    val neutral = when {
        context.parentOp == "divide" && context.role == "right" -> 1.0
        else -> 0.0
    }
    return Functions.c(neutral, context.domain)
}

/** Interactive occurrence browser with final, local, muted, and contextual-effect traces. */
fun Functions.Fn.graphBrowseHtml(
    sampleDomain: Functions.Domain? = null,
    samples: Int = 512,
    heightPx: Int = 520,
    title: String = name ?: "Fn graph",
    maxDepth: Int = 12,
    maxNodes: Int = 32,
    sensitivitySteps: Int = 9
): String {
    val trace = graphTrace(sampleDomain, samples, maxDepth, maxNodes, sensitivitySteps)
    val nodeJson = trace.nodes.joinToString(",", "[", "]") { node ->
        val sensitivity = node.sensitivity.joinToString(",", "[", "]") { point ->
            """{"value":${if (point.value.isFinite()) point.value.toString() else "null"},"samples":${graphBrowseNumbers(point.samples)}}"""
        }
        """{"path":${graphBrowseString(graphBrowsePath(node.path))},"depth":${node.path.size},"role":${graphBrowseNullableString(node.role)},"op":${graphBrowseString(node.op)},"name":${graphBrowseNullableString(node.name)},"domain":${graphBrowseString(node.domain.toString())},"numeric":${node.numeric},"value":${node.value?.takeIf { it.isFinite() }?.toString() ?: "null"},"local":${graphBrowseNumbers(node.local)},"muted":${graphBrowseNumbers(node.muted)},"effect":${graphBrowseNumbers(node.effect)},"sensitivity":$sensitivity}"""
    }
    val frameHeight = heightPx.coerceAtLeast(360)
    val safeTitle = graphBrowseHtmlAttribute(title.ifBlank { name ?: "Fn graph" })
    val srcdoc = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<style>
html, body { margin:0; padding:0; background:#000; color:#d8d8d8; font-family:"Iosevka Web",Iosevka,monospace; overflow:hidden; }
* { box-sizing:border-box; }
.browser { height:${frameHeight}px; display:grid; grid-template-columns:260px minmax(0,1fr); border:1px solid #242424; background:#000; }
.side { min-width:0; border-right:1px solid #242424; display:flex; flex-direction:column; background:#050505; }
.side-head { padding:12px 12px 9px; border-bottom:1px solid #202020; }
.title { color:#f0f0f0; font-size:13px; font-weight:700; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.hint { margin-top:5px; color:#777; font-size:10px; line-height:1.35; }
.nodes { overflow:auto; padding:5px 0 12px; }
.node { width:100%; height:30px; display:flex; align-items:center; gap:6px; padding:0 9px; border:0; border-left:2px solid transparent; background:transparent; color:#aaa; font:11px "Iosevka Web",Iosevka,monospace; text-align:left; cursor:pointer; }
.node:hover { background:#101010; color:#eee; }
.node.compare { border-left-color:#ffb85a; color:#ffd9a0; }
.node.active { background:#151515; border-left-color:#48c9ff; color:#fff; }
.branch { color:#555; flex:0 0 auto; }
.role { color:#999; font-style:italic; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.op { color:#48c9ff; font-weight:700; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.dot { flex:0 0 auto; color:#555; }
.path { margin-left:auto; color:#555; font-size:9px; }
.main { min-width:0; display:flex; flex-direction:column; padding:10px 12px 12px; }
.toolbar { min-height:42px; display:flex; align-items:flex-start; gap:8px; }
.selection { min-width:0; flex:1; }
.selection-main { color:#eee; font-size:12px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.selection-detail { margin-top:4px; color:#777; font-size:10px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.modes { display:flex; gap:4px; flex-wrap:wrap; justify-content:flex-end; }
.mode { height:25px; padding:0 8px; border:1px solid #303030; border-radius:5px; background:#080808; color:#888; font:10px "Iosevka Web",Iosevka,monospace; cursor:pointer; }
.mode:hover { color:#eee; border-color:#555; }
.mode.active { color:#fff; border-color:#48c9ff; background:#101010; }
.stepper { display:none; align-items:center; gap:8px; margin:8px 0 0; }
.stepper input { flex:1; accent-color:#48c9ff; }
.stepper .step-label { color:#bbb; font-size:10px; white-space:nowrap; min-width:150px; }
.canvas-wrap { position:relative; flex:1; min-height:230px; border:1px solid #242424; border-radius:7px; overflow:hidden; background:#030303; }
canvas { display:block; width:100%; height:100%; cursor:crosshair; }
.legend { position:absolute; left:66px; top:9px; display:flex; gap:14px; color:#888; font-size:10px; pointer-events:none; }
.key::before { content:""; display:inline-block; width:16px; margin-right:5px; vertical-align:middle; border-top:2px solid #777; }
.key.final::before { border-color:#888; }
.key.selected::before { border-color:#48c9ff; }
.readout { position:absolute; right:8px; top:7px; padding:3px 6px; border:1px solid #282828; border-radius:4px; color:#aaa; background:#080808; font-size:10px; pointer-events:none; }
</style>
</head>
<body>
<div class="browser">
  <div class="side">
    <div class="side-head"><div class="title">$safeTitle</div><div class="hint">Click an occurrence; shift/⌘-click to overlay several. Every trace shares the final x-axis. Cascade steps a component up to the final shape; sensitivity sweeps a number.</div></div>
    <div class="nodes" id="nodes"></div>
  </div>
  <div class="main">
    <div class="toolbar">
      <div class="selection"><div class="selection-main" id="selectionMain"></div><div class="selection-detail" id="selectionDetail"></div></div>
      <div class="modes">
        <button class="mode" data-mode="local">local</button>
        <button class="mode active" data-mode="effect">effect</button>
        <button class="mode" data-mode="muted">muted final</button>
        <button class="mode" data-mode="cascade">cascade</button>
        <button class="mode" data-mode="sensitivity">sensitivity</button>
      </div>
    </div>
    <div class="stepper" id="stepper"><input type="range" id="stepRange" min="0" max="0" value="0"><span class="step-label" id="stepLabel"></span></div>
    <div class="canvas-wrap">
      <canvas id="plot"></canvas>
      <div class="legend"><span class="key final">final</span><span class="key selected" id="selectedLegend">effect</span></div>
      <div class="readout" id="readout">x --</div>
    </div>
  </div>
</div>
<script>
(function () {
  const payload = { xs:${graphBrowseNumbers(trace.xs)}, final:${graphBrowseNumbers(trace.final)}, nodes:$nodeJson };
  const nodesElement = document.getElementById("nodes");
  const canvas = document.getElementById("plot");
  const ctx = canvas.getContext("2d");
  const readout = document.getElementById("readout");
  const selectedLegend = document.getElementById("selectedLegend");
  const stepper = document.getElementById("stepper");
  const stepRange = document.getElementById("stepRange");
  const stepLabel = document.getElementById("stepLabel");
  const overlayColors = ["#ffb85a","#7ee081","#c68cff","#ff7a7a","#5ad1c9"];
  const dpr = Math.max(1, window.devicePixelRatio || 1);
  const margin = { left:54, right:16, top:30, bottom:34 };
  const state = { selected:Math.min(1, Math.max(0, payload.nodes.length - 1)), mode:"effect", pointer:null, step:0, compare:new Set() };

  function finite(v) { return typeof v === "number" && Number.isFinite(v); }
  function fmt(v) {
    if (!finite(v)) return "--";
    const a = Math.abs(v);
    if (a !== 0 && (a >= 10000 || a < 0.001)) return v.toExponential(2);
    return a >= 10 ? v.toFixed(2) : v.toFixed(3);
  }
  function rect() { return { x:margin.left, y:margin.top, w:Math.max(20,canvas.clientWidth-margin.left-margin.right), h:Math.max(20,canvas.clientHeight-margin.top-margin.bottom) }; }
  function activeNode() { return payload.nodes[state.selected] || { path:"/", depth:0, role:null, op:"root", name:null, domain:"", numeric:false, value:null, local:payload.final, muted:payload.final, effect:payload.final, sensitivity:[] }; }
  function nodeByPath(pathStr) { return payload.nodes.find(function(n){ return n.path===pathStr; }); }
  function prefixesOf(pathStr) {
    if (pathStr==="/") return ["/"];
    const parts=pathStr.split("/").filter(function(s){ return s.length>0; });
    const out=["/"]; let acc="";
    for (const p of parts) { acc+="/"+p; out.push(acc); }
    return out;
  }
  function cascadeStages() {
    const stages=[];
    for (const p of prefixesOf(activeNode().path)) { const n=nodeByPath(p); if (n) stages.push(n); }
    stages.reverse();
    return stages;
  }
  function clampStep(len) { return Math.max(0, Math.min(len-1, state.step)); }

  // Every series that will be drawn this frame, so ranges() and draw() agree.
  function drawnSeries() {
    const out=[{data:payload.final,color:"#858585",width:1.5,dash:[]}];
    if (state.mode==="cascade") {
      const stages=cascadeStages(), sel=clampStep(stages.length);
      stages.forEach(function(n,i){
        const isSel=i===sel, denom=Math.max(1,stages.length-1);
        out.push({data:n.local,color:isSel?"#48c9ff":"rgba(72,201,255,"+(0.18+0.42*i/denom).toFixed(2)+")",width:isSel?2.4:1.1,dash:[]});
      });
    } else if (state.mode==="sensitivity") {
      const sweep=activeNode().sensitivity||[], sel=clampStep(sweep.length);
      sweep.forEach(function(s,i){ const isSel=i===sel; out.push({data:s.samples,color:isSel?"#48c9ff":"rgba(72,201,255,0.16)",width:isSel?2.4:1.0,dash:[]}); });
    } else {
      const others=Array.from(state.compare).filter(function(idx){ return idx!==state.selected; });
      others.forEach(function(idx,k){ const n=payload.nodes[idx]; if (n) out.push({data:n[state.mode]||[],color:overlayColors[k%overlayColors.length],width:1.6,dash:[]}); });
      out.push({data:activeSeries(),color:"#48c9ff",width:2.1,dash:state.mode==="muted"?[5,4]:[]});
    }
    return out;
  }
  function activeSeries() { return activeNode()[state.mode] || []; }
  function ranges() {
    let y0=Infinity, y1=-Infinity;
    for (const s of drawnSeries()) for (const y of s.data) if (finite(y)) { y0=Math.min(y0,y); y1=Math.max(y1,y); }
    if (!finite(y0) || !finite(y1)) { y0=-1; y1=1; }
    if (y0===y1) { const p=Math.max(1,Math.abs(y0)*0.1); y0-=p; y1+=p; }
    const pad=(y1-y0)*0.08;
    return { x0:payload.xs[0], x1:payload.xs[payload.xs.length-1], y0:y0-pad, y1:y1+pad };
  }
  function xpx(x,r,g) { return r.x+(x-g.x0)/(g.x1-g.x0)*r.w; }
  function ypx(y,r,g) { return r.y+r.h-(y-g.y0)/(g.y1-g.y0)*r.h; }
  function nice(span,target) { const raw=Math.abs(span)/Math.max(1,target); if(!finite(raw)||raw===0)return 1; const p=Math.pow(10,Math.floor(Math.log10(raw))); const u=raw/p; return (u<=1?1:u<=2?2:u<=5?5:10)*p; }
  function ticks(a,b,n) { const s=nice(b-a,n), out=[]; for(let v=Math.ceil(a/s)*s;v<=b+s*0.5&&out.length<60;v+=s)out.push(v); return out; }
  function line(x1,y1,x2,y2,color,width,dash) { ctx.beginPath(); ctx.setLineDash(dash||[]); ctx.moveTo(x1,y1);ctx.lineTo(x2,y2);ctx.strokeStyle=color;ctx.lineWidth=width;ctx.stroke();ctx.setLineDash([]); }
  function curve(series,color,width,dash) {
    const r=rect(), g=ranges(); ctx.save();ctx.beginPath();ctx.rect(r.x,r.y,r.w,r.h);ctx.clip();ctx.beginPath();ctx.setLineDash(dash||[]);let open=false;
    for(let i=0;i<payload.xs.length;i++){const x=payload.xs[i],y=series[i];if(!finite(x)||!finite(y)){open=false;continue;}const px=xpx(x,r,g),py=ypx(y,r,g);if(!open){ctx.moveTo(px,py);open=true;}else ctx.lineTo(px,py);}
    ctx.strokeStyle=color;ctx.lineWidth=width;ctx.stroke();ctx.restore();ctx.setLineDash([]);
  }
  function draw() {
    const w=canvas.clientWidth,h=canvas.clientHeight,r=rect(),g=ranges();ctx.clearRect(0,0,w,h);ctx.fillStyle="#030303";ctx.fillRect(0,0,w,h);
    ctx.font='10px "Iosevka Web", monospace';ctx.fillStyle="#666";ctx.textAlign="center";ctx.textBaseline="top";
    for(const x of ticks(g.x0,g.x1,8)){const px=xpx(x,r,g);line(px,r.y,px,r.y+r.h,"#181818",1);ctx.fillText(fmt(x),px,r.y+r.h+7);}
    ctx.textAlign="right";ctx.textBaseline="middle";
    for(const y of ticks(g.y0,g.y1,6)){const py=ypx(y,r,g);line(r.x,py,r.x+r.w,py,"#181818",1);ctx.fillText(fmt(y),r.x-7,py);}
    if(g.y0<=0&&g.y1>=0)line(r.x,ypx(0,r,g),r.x+r.w,ypx(0,r,g),"#333",1);
    const series=drawnSeries();
    for(const s of series) curve(s.data,s.color,s.width,s.dash);
    if(state.pointer!==null){const i=state.pointer,px=xpx(payload.xs[i],r,g);line(px,r.y,px,r.y+r.h,"#555",1);readout.textContent=pointerReadout(i);}
  }
  function pointerReadout(i) {
    if (state.mode==="cascade") { const st=cascadeStages(); const s=st[clampStep(st.length)]; return "x "+fmt(payload.xs[i])+"  final "+fmt(payload.final[i])+"  "+(s?s.op:"")+" "+fmt(s?s.local[i]:NaN); }
    if (state.mode==="sensitivity") { const sw=activeNode().sensitivity||[]; const s=sw[clampStep(sw.length)]; return s?("x "+fmt(payload.xs[i])+"  value "+fmt(s.value)+"  final "+fmt(s.samples[i])):"select a numeric node"; }
    return "x "+fmt(payload.xs[i])+"  final "+fmt(payload.final[i])+"  "+state.mode+" "+fmt(activeSeries()[i]);
  }
  function updateStepper() {
    if (state.mode==="cascade") {
      const st=cascadeStages(); stepper.style.display="flex"; stepRange.max=Math.max(0,st.length-1); const s=clampStep(st.length); stepRange.value=s;
      stepLabel.textContent="stage "+(s+1)+"/"+st.length+"  "+(st[s]?((st[s].role?st[s].role+" — ":"")+st[s].op):"");
    } else if (state.mode==="sensitivity") {
      const sw=activeNode().sensitivity||[];
      if (sw.length) { stepper.style.display="flex"; stepRange.max=sw.length-1; const s=clampStep(sw.length); stepRange.value=s; stepLabel.textContent="value "+fmt(sw[s].value)+" ("+(s+1)+"/"+sw.length+")"; }
      else { stepper.style.display="none"; }
    } else { stepper.style.display="none"; }
  }
  function refreshNodeClasses() {
    document.querySelectorAll(".node").forEach(function(el,i){ el.classList.toggle("active",i===state.selected); el.classList.toggle("compare",state.compare.has(i)&&i!==state.selected); });
  }
  function select(index) {
    state.selected=index; state.step=0; const n=activeNode(); refreshNodeClasses();
    document.getElementById("selectionMain").textContent=(n.role?n.role+" — ":"")+n.op;
    document.getElementById("selectionDetail").textContent=n.path+"   "+n.domain+(n.name?"   “"+n.name+"”":"")+(n.numeric?"   value "+fmt(n.value):"");
    updateStepper(); draw();
  }
  function toggleCompare(index) { if (state.compare.has(index)) state.compare.delete(index); else state.compare.add(index); refreshNodeClasses(); draw(); }
  payload.nodes.forEach(function(n,index){
    const b=document.createElement("button");b.className="node";b.style.paddingLeft=(9+n.depth*13)+"px";
    b.innerHTML='<span class="branch"></span>'+(n.role?'<span class="role"></span>':'')+'<span class="op"></span>'+(n.numeric?'<span class="dot">◈</span>':'')+'<span class="path"></span>';
    b.querySelector(".branch").textContent=n.depth?'└':'●';
    if(n.role)b.querySelector(".role").textContent=n.role+" —";
    b.querySelector(".op").textContent=n.op;b.querySelector(".path").textContent=n.path;
    b.addEventListener("click",function(e){ if(e.metaKey||e.ctrlKey||e.shiftKey) toggleCompare(index); else select(index); });
    nodesElement.appendChild(b);
  });
  document.querySelectorAll(".mode").forEach(function(b){ b.addEventListener("click",function(){ state.mode=b.dataset.mode; state.step=0; document.querySelectorAll(".mode").forEach(function(x){ x.classList.toggle("active",x===b); }); selectedLegend.textContent=state.mode; updateStepper(); draw(); }); });
  stepRange.addEventListener("input",function(){ state.step=+stepRange.value; updateStepper(); draw(); });
  canvas.addEventListener("pointermove",function(event){const r=canvas.getBoundingClientRect(),p=rect(),x=event.clientX-r.left;state.pointer=Math.max(0,Math.min(payload.xs.length-1,Math.round((x-p.x)/Math.max(1,p.w)*(payload.xs.length-1))));draw();});
  canvas.addEventListener("pointerleave",function(){state.pointer=null;readout.textContent="x --";draw();});
  function resize(){const r=canvas.getBoundingClientRect();canvas.width=Math.max(1,Math.round(r.width*dpr));canvas.height=Math.max(1,Math.round(r.height*dpr));ctx.setTransform(dpr,0,0,dpr,0,0);draw();}
  window.addEventListener("resize",resize);resize();select(state.selected);
})();
</script>
</body>
</html>
""".trimIndent()

    return """
<iframe
    class="field-fn-graph-browser-frame"
    title="$safeTitle"
    srcdoc="${graphBrowseHtmlAttribute(srcdoc)}"
    data-afield-intrinsic-width="980"
    data-afield-intrinsic-height="$frameHeight"
    style="border:none;padding:0;margin:0;display:block;width:100%;height:${frameHeight}px;background:#000;"
></iframe>
""".trimIndent()
}

@JvmName("browseGraphCurrentBox")
fun Functions.Fn.browseGraph(
    channel: String = "graph",
    sampleDomain: Functions.Domain? = null,
    samples: Int = 512,
    heightPx: Int = 520,
    title: String = name ?: "Fn graph",
    maxDepth: Int = 12,
    maxNodes: Int = 32
): Functions.Fn {
    val box = Box.context.get() ?: throw IllegalStateException("Fn.browseGraph requires an active Box context")
    box.display(channel, graphBrowseHtml(sampleDomain, samples, heightPx, title, maxDepth, maxNodes))
    return this
}

fun Functions.Fn.graphBrowseHtml(
    sampleDomain: ClosedRange<*>,
    samples: Int = 512,
    heightPx: Int = 520,
    title: String = name ?: "Fn graph",
    maxDepth: Int = 12,
    maxNodes: Int = 32
): String = graphBrowseHtml(Functions.domain(sampleDomain) as Functions.Domain?, samples, heightPx, title, maxDepth, maxNodes)

@JvmName("browseGraphCurrentBoxRange")
fun Functions.Fn.browseGraph(
    sampleDomain: ClosedRange<*>,
    channel: String = "graph",
    samples: Int = 512,
    heightPx: Int = 520,
    title: String = name ?: "Fn graph",
    maxDepth: Int = 12,
    maxNodes: Int = 32
): Functions.Fn = browseGraph(channel, Functions.domain(sampleDomain), samples, heightPx, title, maxDepth, maxNodes)

/** Steppable before/after/delta lanes for a rewrite, all on the shared time-base. */
fun FnRewriteTrace.rewriteBrowseHtml(
    heightPx: Int = 460,
    title: String = "Fn rewrite"
): String {
    val stageJson = stages.joinToString(",", "[", "]") { stage ->
        """{"label":${graphBrowseString(stage.label)},"changed":${stage.changed},"before":${graphBrowseNumbers(stage.before)},"after":${graphBrowseNumbers(stage.after)},"delta":${graphBrowseNumbers(stage.delta)}}"""
    }
    val frameHeight = heightPx.coerceAtLeast(320)
    val safeTitle = graphBrowseHtmlAttribute(title.ifBlank { "Fn rewrite" })
    val srcdoc = """
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<style>
html, body { margin:0; padding:0; background:#000; color:#d8d8d8; font-family:"Iosevka Web",Iosevka,monospace; overflow:hidden; }
* { box-sizing:border-box; }
.rw { height:${frameHeight}px; display:flex; flex-direction:column; border:1px solid #242424; background:#000; padding:10px 12px 12px; }
.head { display:flex; align-items:baseline; gap:10px; }
.title { color:#f0f0f0; font-size:13px; font-weight:700; }
.hint { color:#777; font-size:10px; }
.stage-label { margin:8px 0 6px; color:#eee; font-size:11px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.stage-label .idx { color:#48c9ff; font-weight:700; }
.stage-label .noop { color:#886; }
.stepper { display:flex; align-items:center; gap:8px; }
.stepper input { flex:1; accent-color:#48c9ff; }
.legend { display:flex; gap:14px; color:#888; font-size:10px; margin:4px 0 0; }
.key::before { content:""; display:inline-block; width:16px; margin-right:5px; vertical-align:middle; border-top:2px solid #777; }
.key.before::before { border-color:#777; border-top-style:dashed; }
.key.after::before { border-color:#48c9ff; }
.key.delta::before { border-color:#ffb85a; }
.canvas-wrap { position:relative; flex:1; min-height:200px; border:1px solid #242424; border-radius:7px; overflow:hidden; background:#030303; margin-top:6px; }
canvas { display:block; width:100%; height:100%; cursor:crosshair; }
.readout { position:absolute; right:8px; top:7px; padding:3px 6px; border:1px solid #282828; border-radius:4px; color:#aaa; background:#080808; font-size:10px; pointer-events:none; }
</style>
</head>
<body>
<div class="rw">
  <div class="head"><div class="title">$safeTitle</div><div class="hint">Each step applies one rule cumulatively. Dashed = before, cyan = after, orange = delta.</div></div>
  <div class="stepper"><input type="range" id="stepRange" min="0" max="0" value="0"><span class="stage-label" id="stageLabel"></span></div>
  <div class="legend"><span class="key before">before</span><span class="key after">after</span><span class="key delta">delta</span></div>
  <div class="canvas-wrap"><canvas id="plot"></canvas><div class="readout" id="readout">x --</div></div>
</div>
<script>
(function () {
  const payload = { xs:${graphBrowseNumbers(xs)}, original:${graphBrowseNumbers(original)}, stages:$stageJson };
  const canvas=document.getElementById("plot"), ctx=canvas.getContext("2d");
  const readout=document.getElementById("readout"), stepRange=document.getElementById("stepRange"), stageLabel=document.getElementById("stageLabel");
  const dpr=Math.max(1,window.devicePixelRatio||1), margin={left:54,right:16,top:16,bottom:34};
  const state={step:0,pointer:null};
  stepRange.max=Math.max(0,payload.stages.length-1);
  function finite(v){return typeof v==="number"&&Number.isFinite(v);}
  function fmt(v){ if(!finite(v))return "--"; const a=Math.abs(v); if(a!==0&&(a>=10000||a<0.001))return v.toExponential(2); return a>=10?v.toFixed(2):v.toFixed(3); }
  function stage(){ return payload.stages[state.step]||{label:"(no rules)",before:payload.original,after:payload.original,delta:payload.original.map(function(){return 0;})}; }
  function rect(){ return {x:margin.left,y:margin.top,w:Math.max(20,canvas.clientWidth-margin.left-margin.right),h:Math.max(20,canvas.clientHeight-margin.top-margin.bottom)}; }
  function series(){ const s=stage(); return [s.before,s.after,s.delta]; }
  function ranges(){ let y0=Infinity,y1=-Infinity; for(const arr of series()) for(const y of arr) if(finite(y)){y0=Math.min(y0,y);y1=Math.max(y1,y);} if(!finite(y0)||!finite(y1)){y0=-1;y1=1;} if(y0===y1){const p=Math.max(1,Math.abs(y0)*0.1);y0-=p;y1+=p;} const pad=(y1-y0)*0.08; return {x0:payload.xs[0],x1:payload.xs[payload.xs.length-1],y0:y0-pad,y1:y1+pad}; }
  function xpx(x,r,g){return r.x+(x-g.x0)/(g.x1-g.x0)*r.w;}
  function ypx(y,r,g){return r.y+r.h-(y-g.y0)/(g.y1-g.y0)*r.h;}
  function nice(span,t){const raw=Math.abs(span)/Math.max(1,t);if(!finite(raw)||raw===0)return 1;const p=Math.pow(10,Math.floor(Math.log10(raw)));const u=raw/p;return (u<=1?1:u<=2?2:u<=5?5:10)*p;}
  function ticks(a,b,n){const s=nice(b-a,n),out=[];for(let v=Math.ceil(a/s)*s;v<=b+s*0.5&&out.length<60;v+=s)out.push(v);return out;}
  function ln(x1,y1,x2,y2,c,w,d){ctx.beginPath();ctx.setLineDash(d||[]);ctx.moveTo(x1,y1);ctx.lineTo(x2,y2);ctx.strokeStyle=c;ctx.lineWidth=w;ctx.stroke();ctx.setLineDash([]);}
  function curve(arr,c,w,d){const r=rect(),g=ranges();ctx.save();ctx.beginPath();ctx.rect(r.x,r.y,r.w,r.h);ctx.clip();ctx.beginPath();ctx.setLineDash(d||[]);let open=false;for(let i=0;i<payload.xs.length;i++){const x=payload.xs[i],y=arr[i];if(!finite(x)||!finite(y)){open=false;continue;}const px=xpx(x,r,g),py=ypx(y,r,g);if(!open){ctx.moveTo(px,py);open=true;}else ctx.lineTo(px,py);}ctx.strokeStyle=c;ctx.lineWidth=w;ctx.stroke();ctx.restore();ctx.setLineDash([]);}
  function draw(){
    const w=canvas.clientWidth,h=canvas.clientHeight,r=rect(),g=ranges();ctx.clearRect(0,0,w,h);ctx.fillStyle="#030303";ctx.fillRect(0,0,w,h);
    ctx.font='10px "Iosevka Web", monospace';ctx.fillStyle="#666";ctx.textAlign="center";ctx.textBaseline="top";
    for(const x of ticks(g.x0,g.x1,8)){const px=xpx(x,r,g);ln(px,r.y,px,r.y+r.h,"#181818",1);ctx.fillText(fmt(x),px,r.y+r.h+7);}
    ctx.textAlign="right";ctx.textBaseline="middle";
    for(const y of ticks(g.y0,g.y1,6)){const py=ypx(y,r,g);ln(r.x,py,r.x+r.w,py,"#181818",1);ctx.fillText(fmt(y),r.x-7,py);}
    if(g.y0<=0&&g.y1>=0)ln(r.x,ypx(0,r,g),r.x+r.w,ypx(0,r,g),"#333",1);
    const s=stage();curve(s.delta,"#ffb85a",1.4,[2,3]);curve(s.before,"#888",1.6,[5,4]);curve(s.after,"#48c9ff",2.1,[]);
    if(state.pointer!==null){const i=state.pointer,px=xpx(payload.xs[i],r,g);ln(px,r.y,px,r.y+r.h,"#555",1);readout.textContent="x "+fmt(payload.xs[i])+"  before "+fmt(s.before[i])+"  after "+fmt(s.after[i])+"  Δ "+fmt(s.delta[i]);}
  }
  function updateLabel(){ const s=stage(); stageLabel.innerHTML='<span class="idx">'+(state.step+1)+'/'+Math.max(1,payload.stages.length)+'</span> '+escapeHtml(s.label)+(s.changed===false?' <span class="noop">(no-op)</span>':''); }
  function escapeHtml(t){ return String(t).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;"); }
  stepRange.addEventListener("input",function(){state.step=+stepRange.value;updateLabel();draw();});
  canvas.addEventListener("pointermove",function(event){const r=canvas.getBoundingClientRect(),p=rect(),x=event.clientX-r.left;state.pointer=Math.max(0,Math.min(payload.xs.length-1,Math.round((x-p.x)/Math.max(1,p.w)*(payload.xs.length-1))));draw();});
  canvas.addEventListener("pointerleave",function(){state.pointer=null;readout.textContent="x --";draw();});
  function resize(){const r=canvas.getBoundingClientRect();canvas.width=Math.max(1,Math.round(r.width*dpr));canvas.height=Math.max(1,Math.round(r.height*dpr));ctx.setTransform(dpr,0,0,dpr,0,0);draw();}
  window.addEventListener("resize",resize);updateLabel();resize();
})();
</script>
</body>
</html>
""".trimIndent()

    return """
<iframe
    class="field-fn-graph-browser-frame"
    title="$safeTitle"
    srcdoc="${graphBrowseHtmlAttribute(srcdoc)}"
    data-afield-intrinsic-width="980"
    data-afield-intrinsic-height="$frameHeight"
    style="border:none;padding:0;margin:0;display:block;width:100%;height:${frameHeight}px;background:#000;"
></iframe>
""".trimIndent()
}

/** Compute and display before/after rewrite lanes for a plain-DSL [spec] in the current box. */
fun Functions.Fn.browseRewrite(
    spec: String,
    channel: String = "rewrite",
    sampleDomain: Functions.Domain? = null,
    samples: Int = 512,
    heightPx: Int = 460,
    title: String = name?.let { "rewrite $it" } ?: "Fn rewrite"
): Functions.Fn {
    val box = Box.context.get() ?: throw IllegalStateException("Fn.browseRewrite requires an active Box context")
    box.display(channel, graphRewriteTrace(spec, sampleDomain, samples).rewriteBrowseHtml(heightPx, title))
    return this
}

/** Display before/after rewrite lanes for programmatic [rules] in the current box. */
fun Functions.Fn.browseRewrite(
    rules: List<FnGraphRule>,
    channel: String = "rewrite",
    sampleDomain: Functions.Domain? = null,
    samples: Int = 512,
    heightPx: Int = 460,
    title: String = name?.let { "rewrite $it" } ?: "Fn rewrite"
): Functions.Fn {
    val box = Box.context.get() ?: throw IllegalStateException("Fn.browseRewrite requires an active Box context")
    box.display(channel, graphRewriteTrace(rules, sampleDomain, samples).rewriteBrowseHtml(heightPx, title))
    return this
}

private fun graphBrowsePath(path: List<Int>): String = if (path.isEmpty()) "/" else path.joinToString("/", prefix = "/")

private fun graphBrowseNumbers(values: DoubleArray): String =
    values.joinToString(",", "[", "]") { if (it.isFinite()) it.toString() else "null" }

private fun graphBrowseString(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch.code < 32) append("\\u").append(ch.code.toString(16).padStart(4, '0')) else append(ch)
        }
    }
    append('"')
}

private fun graphBrowseNullableString(value: String?): String = value?.let(::graphBrowseString) ?: "null"

private fun graphBrowseHtmlAttribute(value: String): String = buildString(value.length) {
    value.forEach { ch ->
        append(
            when (ch) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> ch
            }
        )
    }
}
