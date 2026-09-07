// Pure capture-selection operations shared by the viewer and its regression checks.
export function selectedSpans(spans, range, lane = null) {
    // Include complete resumes whose start is in the brush. Never prorate exact allocation/CPU
    // counters: there is no evidence about where within a resume those bytes were allocated.
    return spans.filter(s => s.startMs >= range[0] && s.startMs <= range[1] && (lane === null || s.lane === lane));
}

export function profileSummary(spans) {
    const wall = spans.map(s => s.wallMs).sort((a, b) => a-b);
    const cpuKnown = spans.every(s => s.cpuMs >= 0);
    const allocationKnown = spans.every(s => s.allocatedBytes >= 0);
    return {
        count: spans.length,
        wallMs: wall.reduce((a, b) => a+b, 0),
        cpuMs: cpuKnown ? spans.reduce((a, s) => a+s.cpuMs, 0) : null,
        allocatedBytes: allocationKnown ? spans.reduce((a, s) => a+s.allocatedBytes, 0) : null,
        p95Ms: wall.length ? wall[Math.max(0, Math.ceil(wall.length*.95)-1)] : 0,
        maxMs: wall.at(-1) || 0
    };
}

export function mergeProfileSpans(existing, incoming, oldest) {
    const last = existing.at(-1)?.sequence ?? -1;
    return existing.filter(s => s.sequence >= oldest).concat(incoming.filter(s => s.sequence > last && s.sequence >= oldest));
}

export function mappedProfileLine(source,line) {
    const lines=text=>(text||"").replace(/\r\n/g,"\n").replace(/\n+$/,"").split("\n");
    const compiled=lines(source.code),original=lines(source.boxCode);
    return line>0&&line<=original.length&&compiled.length===original.length&&compiled[line-1]===original[line-1]?line:null;
}

// Only compiled lines with a verified direct mapping may be painted on editable box code.
export function profileBoxHeatmap(rows, sources) {
    const bySource = new Map();
    for (const row of rows) {
        const id = row.frame.sourceId;
        if (!bySource.has(id)) bySource.set(id, []);
        bySource.get(id).push(row);
    }
    return sources.filter(s => s.boxId && bySource.has(s.id)).map(source => ({
        boxId: source.boxId, property: source.property || "code", boxCode: source.boxCode,
        lines: bySource.get(source.id).flatMap(row => {
            const line = mappedProfileLine(source, row.frame.line);
            return line && row.weight > 0 ? [{line, weight: row.weight}] : [];
        })
    }));
}

// Identity matters as well as text: two different boxes can contain identical code.
export function profileHeatLines(sources, context) {
    if (!context) return [];
    const counts = new Map();
    for (const source of sources) {
        if (source.boxId !== context.uid || source.property !== context.property || source.boxCode !== context.text) continue;
        for (const {line, weight} of source.lines) counts.set(line, (counts.get(line) || 0) + weight);
    }
    const peak = Math.max(0, ...counts.values());
    return [...counts].filter(([,n]) => n > 0).map(([line, weight]) => ({
        line, weight, fraction: weight / peak, level: Math.max(1, Math.ceil(12 * weight / peak))
    })).sort((a,b) => a.line-b.line);
}

// Each stack contributes at most once to a row, even when recursion repeats a frame/class.
// Inclusive rows overlap: their weights must never be added together.
export function profileHotspots(samples, frames, range, {kind="cpu", group="method", self=false, search="", thread=null, spans=[], lane=null}={}) {
    const byId = new Map(frames.map(f=>[f.id,f]));
    const resumes = new Map();
    if(lane!==null) for(const s of spans) {
        if(!resumes.has(s.threadId))resumes.set(s.threadId,[]);
        resumes.get(s.threadId).push(s);
    }
    for(const list of resumes.values())list.sort((a,b)=>a.startMs-b.startMs);
    const owns = sample => {
        if(lane===null)return true;
        const list=resumes.get(sample.threadId)||[];
        let lo=0,hi=list.length;
        while(lo<hi){const mid=(lo+hi)>>>1;if(list[mid].startMs<=sample.timeMs)lo=mid+1;else hi=mid;}
        const s=list[lo-1];return s?.lane===lane&&sample.timeMs<=s.startMs+s.wallMs;
    };
    const rows=new Map();let total=0,count=0;
    for(const s of samples) {
        if(s.timeMs<range[0]||s.timeMs>range[1]||s.kind!==kind||(thread!==null&&s.threadId!==thread)||!owns(s))continue;
        total+=s.weight;count++;
        const seen=new Set();
        for(const id of (self?s.stack.slice(0,1):s.stack)) {
            const f=byId.get(id);if(!f)continue;
            const label=group==="sourceLine"?`${f.sourceId}:${f.line}`:group==="class"?f.className:group==="line"?`${f.className}.${f.method}:${f.line}`:`${f.className}.${f.method}`;
            if(seen.has(label)||!label.toLowerCase().includes(search.toLowerCase()))continue;
            seen.add(label);
            if(!rows.has(label))rows.set(label,{label,weight:0,count:0,frame:f});
            const r=rows.get(label);r.weight+=s.weight;r.count++;
        }
    }
    return {total,count,rows:[...rows.values()].sort((a,b)=>b.weight-a.weight)};
}
