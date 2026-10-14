const $ = id => document.getElementById(id);
const labels = {open:'Open', patch:'Patch proposed', preserve:'Preserve', adapter:'Adapter needed', question:'Question', stale:'Changed since decision'};
let current = null, selected = null, requestVersion = 0, busy = false;
const text = (tag, value, className) => { const node = document.createElement(tag); node.textContent = value; if(className) node.className = className; return node; };
const attentionCount = value => text('span', value, value > 0 ? 'attention' : '');
function error(value='') { $('error').textContent = value; $('error').hidden = !value; }
let displayedJob = {}, startingAction = null;
function renderJob(job = {}) {
    displayedJob = job;
    busy = !!job.running;
    $('job-panel').className = `job-panel ${busy ? 'running' : job.name ? job.exitCode === 0 ? 'ok' : 'failed' : 'idle'}`;
    $('job-title').textContent = job.name ? `${job.name} · ${job.starting ? 'starting…' : busy ? 'running' : job.exitCode === 0 ? 'finished' : 'failed'}` : 'No operation yet';
    const lines = (job.logTail || '').trimEnd().split(/[\r\n]+/);
    // CLI status JSON belongs in the log, not the compact progress line.
    $('job-last').textContent = lines.filter(line => line && line.length < 500 && !line.startsWith('{')).at(-1) || (job.name && !busy ? job.exitCode === 0 ? 'Operation completed.' : 'See the log for details.' : '');
    $('job-file').textContent = job.logFile || '';
    const log = $('job-log'), atEnd = log.scrollTop + log.clientHeight >= log.scrollHeight - 20;
    log.textContent = job.logTail || 'No output yet.';
    if(atEnd) log.scrollTop = log.scrollHeight;
    $('cancel-job').hidden = !busy || !!job.starting;
    for(const id of ['import-button','start-button','resume-button','replan','rehearse','run-agent','session-agent','close-session','prepare','restore','continue-review','validate','land','publish','save-checks']) $(id).disabled = busy;
    if(current) renderDelivery(current, false);
    renderJobTime();
}
function renderJobTime() {
    const j = displayedJob;
    const end = j.running ? Date.now() : j.finishedAtEpochMs || j.startedAtEpochMs;
    const seconds = Math.max(0, Math.floor((end - j.startedAtEpochMs) / 1000));
    $('job-run').textContent = j.startedAtEpochMs ? `${Math.floor(seconds / 60)}m ${Math.floor(seconds % 60)}s` : '';
}
async function api(path, body) {
    const operation = path === '/api/refactor-pass' && body && !['configure','decide','cancel'].includes(body.action);
    if(operation) { startingAction = body.action; renderJob({name:body.action,running:true,starting:true,startedAtEpochMs:Date.now()}); }
    try {
        const response = await fetch(path, body ? {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)} : {});
        const raw = await response.text();
        if(!response.ok) throw new Error(raw || `Request failed (${response.status})`);
        const result = JSON.parse(raw);
        if(operation || (path === '/api/refactor-pass' && body?.action === 'cancel')) renderJob(result);
        return result;
    } catch(e) {
        if(operation) renderJob({name:body.action,running:false,exitCode:1,logTail:e.message});
        throw e;
    } finally { if(operation) startingAction = null; }
}
function renderDiff(node, patch) {
    node.classList.add('patch');
    const lines = patch.split('\n');
    node.replaceChildren(...lines.map((line, index) => {
        const kind = /^(---|\+\+\+|diff |index )/.test(line) ? 'meta'
            : line.startsWith('@@') ? 'hunk'
            : line.startsWith('+') ? 'add'
            : line.startsWith('-') ? 'del' : 'context';
        return text('span', line + (index < lines.length - 1 ? '\n' : ''), kind);
    }));
    return node;
}
function renderChange(node, change) {
    node.replaceChildren();
    if(change.compactChanges?.length) {
        node.append(text('p', `${change.compactChanges.length} text edits in long source lines · unchanged text omitted below.`, 'muted'));
        for(const edit of change.compactChanges) {
            const pre = text('pre', '', 'patch');
            pre.append(text('span', '− ' + edit.before + '\n', 'del'), text('span', '+ ' + edit.after, 'add'));
            node.append(pre);
        }
        const raw = document.createElement('details');
        raw.append(text('summary', 'Full diff'), renderDiff(document.createElement('pre'), change.diff));
        node.append(raw);
    } else node.append(renderDiff(document.createElement('pre'), change.diff || `${change.path} → ${change.destination}`));
    return node;
}
function renderExcerpt(node, source, references = []) {
    const pre = text('pre', '');
    const counts = new Map();
    let cursor = 0;
    for(const reference of references) {
        const {start, end, name, destination} = reference;
        if(start < cursor || end <= start || source.slice(start, end) !== name) continue;
        pre.append(document.createTextNode(source.slice(cursor, start)));
        const mark = text('mark', name, 'old-reference');
        mark.title = destination ? `Recipe proposes ${destination}` : 'Unmapped reference: review its destination';
        pre.append(mark);
        const entry = counts.get(name) || {destination, count: 0};
        entry.count++;
        counts.set(name, entry);
        cursor = end;
    }
    pre.append(document.createTextNode(source.slice(cursor)));
    if(counts.size) {
        const legend=text('div','','reference-legend');
        legend.append(text('strong','Highlighted references · migration mapping, not an applied edit:'));
        for(const [name, {destination, count}] of counts) {
            const row=text('div','');
            row.append(text('code',name,'old-name'),document.createTextNode(' → '),text('code',destination||'choose a destination',destination?'':'attention'),text('small',` · ${count} shown`));
            legend.append(row);
        }
        node.append(legend);
    }
    node.append(pre);
}

async function guarded(fn) { try {error(); await fn();} catch(e) {error(e.message);} }
function options(node, values) {
    const value = node.value; node.replaceChildren();
    for(const [id,label] of values) {const option = document.createElement('option');option.value=id;option.textContent=label;node.append(option);}
    if([...node.options].some(o=>o.value===value)) node.value=value;
}
function renderStatus(data, job) {
    if(!startingAction) renderJob(job);
    $('import-progress').textContent = '';
    const exists=!!data?.pass;
    $('import-panel').hidden=exists;
    for(const id of ['scope-panel','actions-panel','review-panel','review-body','delivery']) $(id).hidden=!exists;
    if(!exists) {
        current=null;
        $('saved-sessions').replaceChildren(...(data?.sessions||[]).map(s=>{
            const b=text('button',`${s.id} · ${s.phase}`);b.onclick=()=>guarded(async()=>{await api('/api/refactor-pass',{action:'resume',session:s.directory});await refresh();});return b;
        }));
        return;
    }
    if(current && current.pass.plan!==data.pass.plan) {
        selected=null; ++requestVersion;
        $('detail').replaceChildren(text('p','The plan has changed. Choose a remaining review group, or inspect proposed changes above for files that are now ready.','empty'));
        $('mechanical').open=false;
        $('change-list').replaceChildren();
        $('change-diff').replaceChildren(text('p','Open this section to load the proposed files.','muted'));
    }
    const changedSession=current?.pass.id!==data.pass.id;
    current=data;
    $('revision').textContent=`Plan ${data.pass.revision} · ${data.pass.id}`;
    $('scope').replaceChildren(...data.roots.map(r=>{
        const metrics=text('div',`${r.ready} ready · `,'metrics');
        metrics.append(attentionCount(r.held),document.createTextNode(' held candidates'));
        const box=document.createElement('div');box.append(text('h3',r.kind==='repo'?'Repository worktree':`Workspace copy ${r.id}`),metrics,text('p',`${r.files.toLocaleString()} files scanned`,'muted'),text('p',r.path,'path'));return box;
    }));
    $('recipe').textContent=JSON.stringify(data.recipe,null,2);
    $('branch').textContent=`Session worktree: ${data.pass.worktree} · delivery branch: ${data.pass.branch}`;
    renderDelivery(data, changedSession);
    options($('batch'),Object.entries(data.batches));
    options($('root'),[['','All roots'],...data.roots.map(r=>[r.id,r.kind==='repo'?'Repository':`Workspace copy ${r.id}`])]);
    $('counts').replaceChildren(attentionCount(data.items.length),document.createTextNode(` review groups · ${data.counts.question||0} questions`));
    const v=data.pass.verification;
    $('verification').replaceChildren(text('p', v ? `Rehearsal recorded ${new Date(v.at * 1000).toLocaleString()} for this plan: ${Object.values(v.results).reduce((n,r)=>n+r.restored.changedFiles,0)} changed files; all scanned text verified after restoration at that time. Compilation and binding equivalence have not been checked.` : 'No completed rehearsal for this plan. Imported plans must be replanned against the pass copies first.', 'muted check'));
    renderItems();
}
function renderItems() {
    if(!current) return;
    const query=$('search').value.toLowerCase();
    const items=current.items.filter(i=>i.batch===$('batch').value && (!$('root').value || i.root===$('root').value) && (!$('decision-filter').value || i.state===$('decision-filter').value) && (i.group.toLowerCase().includes(query)||i.paths.some(p=>p.toLowerCase().includes(query))));
    $('items').replaceChildren(...items.map(i=>{
        const button=document.createElement('button');button.className='item'+(selected===i.id?' selected':'');button.dataset.item=i.id;
        button.append(text('span',i.group.replace(/^(file|document):/,''),'item-title'),text('small',`${current.roots.find(r=>r.id===i.root)?.kind==='repo'?'Repository':`Workspace ${i.root}`} · ${i.findingCount} findings · ${labels[i.state]}`));
        button.onclick=()=>guarded(()=>showItem(i.id));return button;
    }));
    if(!items.length) $('items').append(text('p','No groups match these filters.','empty'));
}
async function showItem(id) {
    selected=id;renderItems();const version=++requestVersion;
    const result=await api(`/api/refactor-pass?item=${encodeURIComponent(id)}`);
    if(version!==requestVersion) return;
    if(result.error) throw new Error(result.error);
    const item=result.data;
    $('detail').replaceChildren(text('h2',item.group.replace(/^(file|document):/,'')),text('p',`${item.findings.length} findings · ${item.candidateFiles.length} proposed file edits`,'muted'),text('p',item.workingRoot,'path'));
    if(!item.changes.length) {
        const explanation=text('div','','reference-legend');
        explanation.append(text('strong','Review only — no file edit proposed'),text('p',item.noProposalReason));
        $('detail').append(explanation);
    }
    if(item.stale) $('detail').append(text('p','Source has changed since this plan. These are the planned excerpts; replan before recording a preservation decision.','notice'));
    item.snippets.forEach((s,index)=>{
        const node=document.createElement('details');node.className='finding';node.open=index<3;
        node.append(text('summary',`${s.kind} · ${s.path}`),text('p',s.detail),text('small',s.location));
        renderExcerpt(node,s.excerpt ?? 'The planned source is no longer available. Replan to capture current source.',s.references);
        $('detail').append(node);
    });
    const diff=document.createElement('details');diff.className='finding';diff.append(text('summary',`Proposed changes (${item.changes.length} files)`));
    for(const c of item.changes) diff.append(text('h3',c.destination===c.path?c.path:`${c.path} → ${c.destination}`),renderChange(document.createElement('div'),c));
    if(item.changes.length) $('detail').append(diff);
    const form=document.createElement('form');form.className='decision';
    const select=document.createElement('select');select.id='item-disposition';select.setAttribute('aria-label','Decision for this group');
    options(select,Object.entries(labels).filter(([key])=>key!=='stale'));select.value=item.decision?.state||'open';
    const reason=document.createElement('textarea');reason.id='item-reason';reason.required=true;reason.placeholder='What changed, what should stay, or what decision is needed?';reason.setAttribute('aria-label','Reason for decision');reason.value=item.decision?.reason||'';
    const save=text('button','Record decision');save.type='submit';
    form.append(text('h3','Decision for this group'),text('p','Replan after editing. Preserve records an intentional exception; a group with held edits must still be resolved before delivery.','muted'),select,reason,save);
    form.onsubmit=e=>{e.preventDefault();guarded(async()=>{save.disabled=true;try{await api('/api/refactor-pass',{action:'decide',item:id,state:select.value,reason:reason.value});await refresh();await showItem(id);}finally{save.disabled=false;}});};
    if((current.delivery?.phase||'review')==='review') $('detail').append(form);
    else $('detail').append(text('p','Review decisions are frozen during delivery. Use the session terminal for fixes, then rerun verification.','muted'));
}
async function refresh() {
    const result=await api('/api/refactor-pass');
    if(!startingAction) renderJob(result.job);
    if(result.error) { if(result.job?.running) return; throw new Error(result.error); }
    renderStatus(result.data,result.job);
    const terminals=await api('/api/terminal');$('sessions').replaceChildren();
    for(const session of terminals.filter(t=>t.kind==='refactor-batch'&&t.running)) {const b=text('button',`Attach: ${session.batch}`);b.onclick=()=>openTerminal(session.id);$('sessions').append(b);}
}
function openTerminal(id) {const url=`/terminal.html?id=${encodeURIComponent(id)}`;$('terminal-link').href=url;$('terminal-pane').hidden=false;window.open(url,`field-terminal-${id}`,'noopener');}
$('import-form').onsubmit=e=>{e.preventDefault();guarded(async()=>{await api('/api/refactor-pass',{action:'create',plan:$('plan-path').value.trim()});await refresh();});};
for(const action of ['replan','rehearse']) $(action).onclick=()=>guarded(async()=>{await api('/api/refactor-pass',{action});await refresh();});
$('toggle-log').onclick=()=>{const d=$('job-detail');d.hidden=!d.hidden;$('toggle-log').textContent=d.hidden?'Log':'Hide log';$('toggle-log').setAttribute('aria-expanded',String(!d.hidden));};
$('cancel-job').onclick=()=>guarded(async()=>{await api('/api/refactor-pass',{action:'cancel'});await refresh();});
$('refresh').onclick=()=>guarded(refresh);
$('mechanical').addEventListener('toggle',()=>{if($('mechanical').open) guarded(async()=>{const result=await api('/api/refactor-pass?changes=1');if(result.error) throw new Error(result.error);options($('change-list'),[['','Choose a file'],...result.data.map(c=>[c.id,`${c.held?'HELD':'READY'} · root ${c.root} · ${c.path}`])]);});});
$('change-list').onchange=()=>guarded(async()=>{if(!$('change-list').value)return;const result=await api(`/api/refactor-pass?change=${$('change-list').value}`);if(result.error)throw new Error(result.error);renderChange($('change-diff'),result.data);});
for(const id of ['batch','root','decision-filter','search']) $(id).addEventListener(id==='search'?'input':'change',renderItems);
$('run-agent').onclick=()=>guarded(async()=>{const session=await api('/api/terminal',{kind:'refactor-batch',batch:$('batch').value,text:$('direction').value});openTerminal(session.id);await refresh();});

const defaultChecks=[
    {name:'Compile JVM and browser',kind:'build',argv:['./gradlew','compileKotlinJvm','compileKotlinJs']},
    {name:'JVM tests',kind:'test',argv:['./gradlew','jvmTest']},
    {name:'Runtime smoke catalog',kind:'runtime',argv:['uv','run','--group','backdoor','python','tools/run_field_tests.py']}
];
function renderDelivery(data, changedSession) {
    const d=data.delivery||{phase:'review',blockers:[]};const reviewing=d.phase==='review';
    $('delivery-state').textContent=`Session: ${d.phase}${d.error?' · '+d.error:''}`;
    $('blocker-disclosure').hidden=!d.blockers?.length;
    $('blocker-count').textContent=`${d.blockers?.length||0} remaining delivery blockers · review details`;
    $('delivery-blockers').replaceChildren(...(d.blockers||[]).map(b=>text('p',b)));
    for(const id of ['replan','rehearse','run-agent']) $(id).disabled=busy||!reviewing;
    $('prepare').disabled=busy||!reviewing||!!d.blockers?.length;
    $('restore').disabled=busy||!['applying','prepared','failed','checking','verified'].includes(d.phase);
    $('continue-review').disabled=busy||!['prepared','failed','checking','verified'].includes(d.phase);
    $('validate').disabled=busy||!['prepared','failed','checking','verified'].includes(d.phase);
    $('land').disabled=busy||d.phase!=='verified';
    $('publish').disabled=busy||!['verified','queued'].includes(d.phase)||!data.roots.some(r=>r.kind==='workspace');
    $('session-agent').disabled=busy||['queued','published','applying'].includes(d.phase);
    if(changedSession) $('checks').value=JSON.stringify(data.checks?.length?data.checks:defaultChecks,null,2);
    $('check-results').replaceChildren(...(d.checks||[]).map(c=>text('p',`${c.exitCode===0?'Passed':'Failed'} · ${c.name} · ${c.log}`,'path')));
    $('delivery-receipt').hidden=!d.queueReceipt;$('delivery-receipt').textContent=d.queueReceipt||'';
    $('publications').replaceChildren(...(d.publications||[]).map(p=>text('p',`Published workspace ${p.root}: ${p.path}`,'path')));
    options($('publish-root'),data.roots.filter(r=>r.kind==='workspace').map(r=>[r.id,`Workspace ${r.id}`]));
}
$('start-form').onsubmit=e=>{e.preventDefault();guarded(async()=>{
    let recipe;
    if($('new-recipe').value.trim()) recipe=JSON.parse($('new-recipe').value);
    else {
        const packages={};
        for(const line of $('mappings').value.split('\n').map(s=>s.trim()).filter(Boolean)) {
            const pair=line.split(/\s*(?:→|->)\s*/);
            if(pair.length!==2||!pair[0]||!pair[1]||packages[pair[0]]) throw new Error('Use one distinct old → new package pair per line.');
            packages[pair[0]]=pair[1];
        }

        recipe={schema:1,id:'refactor-'+Date.now(),description:$('purpose').value,packages,symbols:{},splitPackages:[],rewriteComments:true,
            markdownProse:$('markdown-paths').value.split(',').map(s=>s.trim()).filter(Boolean),workspaceMarkdownProse:$('workspace-markdown').checked?['*.md']:[]};
    }
    await api('/api/refactor-pass',{action:'start',recipe:JSON.stringify(recipe),workspaces:$('workspace-paths').value.split('\n').map(s=>s.trim()).filter(Boolean)});await refresh();
});};
$('resume-form').onsubmit=e=>{e.preventDefault();guarded(async()=>{await api('/api/refactor-pass',{action:'resume',session:$('resume-path').value.trim()});await refresh();});};
for(const action of ['prepare','restore','continue-review','land']) $(action).onclick=()=>guarded(async()=>{await api('/api/refactor-pass',{action});await refresh();});
$('validate').onclick=()=>guarded(async()=>{await api('/api/refactor-pass',{action:'configure',checks:JSON.stringify(JSON.parse($('checks').value))});await api('/api/refactor-pass',{action:'validate'});await refresh();});
$('close-session').onclick=()=>guarded(async()=>{await api('/api/refactor-pass',{action:'close'});await refresh();});
$('save-checks').onclick=()=>guarded(async()=>{const checks=JSON.parse($('checks').value);await api('/api/refactor-pass',{action:'configure',checks:JSON.stringify(checks)});await refresh();});
$('session-agent').onclick=()=>guarded(async()=>{const s=await api('/api/terminal',{kind:'refactor-batch',batch:'session',text:$('direction').value});openTerminal(s.id);await refresh();});
$('delivery-review').onclick=()=>guarded(async()=>{const r=await api('/api/refactor-pass?delivery=1');if(r.error)throw new Error(r.error);const d=r.data;
    $('delivery-diff').replaceChildren(text('h3','Repository changes since session creation'),renderDiff(document.createElement('pre'),d.diff||'No tracked changes.'),text('p',d.untracked.length?'New files: '+d.untracked.join(', '):'No untracked files.','path'),text('p',`Full repository diff: ${d.fullDiff}${d.truncated?' · display truncated':''}`,'path'),...d.workspaces.map(w=>{const node=document.createElement('details');node.append(text('summary',`${w.count} changed files · ${w.path}`),text('p',`Compared with the original at review time: ${w.source}`,'path'));for(const c of w.changes)node.append(text('h3',c.path),renderDiff(document.createElement('pre'),c.diff));if(w.count>w.changes.length)node.append(text('p','First 200 files shown; inspect the workspace directory for the rest.'));return node;}));
});
$('publish-form').onsubmit=e=>{e.preventDefault();guarded(async()=>{await api('/api/refactor-pass',{action:'publish',root:$('publish-root').value,destination:$('publish-path').value.trim()});await refresh();});};

$('plan-path').value=new URLSearchParams(location.search).get('plan')||'';
await guarded(refresh);
let polling = false;
setInterval(async()=>{if(polling || startingAction) return;polling=true;try{await guarded(refresh);}finally{polling=false;}},3000);
setInterval(renderJobTime,1000);
