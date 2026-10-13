const $ = id => document.getElementById(id);
const labels = {open:'Open', patch:'Patch proposed', preserve:'Preserve', adapter:'Adapter needed', question:'Question', stale:'Changed since decision'};
let current = null, selected = null, requestVersion = 0, busy = false;
const text = (tag, value, className) => { const node = document.createElement(tag); node.textContent = value; if(className) node.className = className; return node; };
function error(value='') { $('error').textContent = value; $('error').hidden = !value; }
async function api(path, body) {
    const response = await fetch(path, body ? {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)} : {});
    const raw = await response.text();
    if(!response.ok) throw new Error(raw || `Request failed (${response.status})`);
    return JSON.parse(raw);
}
async function guarded(fn) { try {error(); await fn();} catch(e) {error(e.message);} }
function options(node, values) {
    const value = node.value; node.replaceChildren();
    for(const [id,label] of values) {const option = document.createElement('option');option.value=id;option.textContent=label;node.append(option);}
    if([...node.options].some(o=>o.value===value)) node.value=value;
}
function renderStatus(data, job) {
    busy = !!job.running;
    $('job-state').textContent = job.running ? `${job.name} is running…` : job.name ? `${job.name}: ${job.exitCode===0?'finished':'failed — see operation log'}` : '';
    $('job-state').classList.toggle('busy', busy);
    $('log').textContent = job.logTail || 'No operation yet.';
    $('import-progress').textContent=job.running ? `${job.name} is running. ${job.logTail.slice(-600)}` : job.exitCode ? job.logTail.slice(-1000) : '';
    for(const id of ['import-button','replan','rehearse','run-agent']) $(id).disabled = busy;
    const exists=!!data?.pass;
    $('import-panel').hidden=exists;
    for(const id of ['scope-panel','actions-panel','review-panel','review-body','delivery']) $(id).hidden=!exists;
    if(!exists) return;
    current=data;
    $('revision').textContent=`Plan ${data.pass.revision} · ${data.pass.id}`;
    $('scope').replaceChildren(...data.roots.map(r=>{
        const box=document.createElement('div');box.append(text('h3',r.kind==='repo'?'Repository worktree':`Workspace copy ${r.id}`),text('div',`${r.ready} ready · ${r.held} held candidates`,'metrics'),text('p',`${r.files.toLocaleString()} files scanned`,'muted'),text('p',r.path,'path'));return box;
    }));
    $('recipe').textContent=JSON.stringify(data.recipe,null,2);
    $('branch').textContent=`Integration branch: ${data.pass.branch}`;
    options($('batch'),Object.entries(data.batches));
    options($('root'),[['','All roots'],...data.roots.map(r=>[r.id,r.kind==='repo'?'Repository':`Workspace copy ${r.id}`])]);
    $('counts').textContent=`${data.items.length} review groups · ${data.counts.question||0} questions`;
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
    if(item.stale) $('detail').append(text('p','Source has changed since this plan. These are the planned excerpts; replan before recording a preservation decision.','notice'));
    item.snippets.forEach((s,index)=>{
        const node=document.createElement('details');node.className='finding';node.open=index<3;
        node.append(text('summary',`${s.kind} · ${s.path}`),text('p',s.detail),text('small',s.location),text('pre',s.excerpt ?? 'The planned source is no longer available. Replan to capture current source.'));
        $('detail').append(node);
    });
    const diff=document.createElement('details');diff.className='finding';diff.append(text('summary',`Proposed changes (${item.changes.length} files)`));
    for(const c of item.changes) diff.append(text('h3',c.destination===c.path?c.path:`${c.path} → ${c.destination}`),text('pre',c.diff||'Directory move only.'));
    if(!item.changes.length) diff.append(text('p','Reference-only finding: the mechanical pass proposed no edit.','muted'));
    $('detail').append(diff);
    const form=document.createElement('form');form.className='decision';
    const select=document.createElement('select');select.id='item-disposition';select.setAttribute('aria-label','Decision for this group');
    options(select,Object.entries(labels).filter(([key])=>key!=='stale'));select.value=item.decision?.state||'open';
    const reason=document.createElement('textarea');reason.id='item-reason';reason.required=true;reason.placeholder='What changed, what should stay, or what decision is needed?';reason.setAttribute('aria-label','Reason for decision');reason.value=item.decision?.reason||'';
    const save=text('button','Record decision');save.type='submit';
    form.append(text('h3','Decision for this group'),text('p','A recorded decision does not release held files. Patches must be replanned and verified.','muted'),select,reason,save);
    form.onsubmit=e=>{e.preventDefault();guarded(async()=>{save.disabled=true;try{await api('/api/refactor-pass',{action:'decide',item:id,state:select.value,reason:reason.value});await refresh();await showItem(id);}finally{save.disabled=false;}});};
    $('detail').append(form);
}
async function refresh() {
    const result=await api('/api/refactor-pass');if(result.error) throw new Error(result.error);renderStatus(result.data,result.job);
    const terminals=await api('/api/terminal');$('sessions').replaceChildren();
    for(const session of terminals.filter(t=>t.kind==='refactor-batch'&&t.running)) {const b=text('button',`Attach: ${session.batch}`);b.onclick=()=>openTerminal(session.id);$('sessions').append(b);}
}
function openTerminal(id) {const url=`/terminal.html?id=${encodeURIComponent(id)}`;$('terminal').src=url;$('terminal-link').href=url;$('terminal-pane').hidden=false;}
$('hide-terminal').onclick=()=>{$('terminal-pane').hidden=true;};
$('import-form').onsubmit=e=>{e.preventDefault();guarded(async()=>{await api('/api/refactor-pass',{action:'create',plan:$('plan-path').value.trim()});await refresh();});};
for(const action of ['replan','rehearse']) $(action).onclick=()=>guarded(async()=>{await api('/api/refactor-pass',{action});await refresh();});
$('refresh').onclick=()=>guarded(refresh);
$('mechanical').addEventListener('toggle',()=>{if($('mechanical').open) guarded(async()=>{const result=await api('/api/refactor-pass?changes=1');if(result.error) throw new Error(result.error);options($('change-list'),[['','Choose a file'],...result.data.map(c=>[c.id,`${c.held?'HELD':'READY'} · root ${c.root} · ${c.path}`])]);});});
$('change-list').onchange=()=>guarded(async()=>{if(!$('change-list').value)return;const result=await api(`/api/refactor-pass?change=${$('change-list').value}`);if(result.error)throw new Error(result.error);$('change-diff').textContent=result.data.diff||`${result.data.path} → ${result.data.destination}`;});
for(const id of ['batch','root','decision-filter','search']) $(id).addEventListener(id==='search'?'input':'change',renderItems);
$('run-agent').onclick=()=>guarded(async()=>{const session=await api('/api/terminal',{kind:'refactor-batch',batch:$('batch').value,text:$('direction').value});openTerminal(session.id);await refresh();});
$('plan-path').value=new URLSearchParams(location.search).get('plan')||'';
await guarded(refresh);
setInterval(()=>guarded(refresh),8000);
