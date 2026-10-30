// Shared Field annotation inbox. Hosts supply geometry/transport; threads and controls are identical.
const styleText = `        .field-review-inbox {
            --ink: #e8f0fb; --muted: #a0a6ae; --accent: #62d7ff; color: var(--ink); line-height: 1.4; font-weight: 300;
            position: absolute; right: 12px; bottom: 38px; z-index: 11; width: min(420px, calc(100% - 24px));
            max-height: calc(100% - 110px); overflow: auto; background: #000; border-radius: 8px; padding: 16px;
            font-size: 12px; user-select: none; -webkit-user-select: none;
        }
        .field-review-inbox :focus { outline: none; box-shadow: none; }
        .field-review-inbox button, .field-review-inbox textarea { font: inherit; border: 0; border-radius: 6px; padding: 8px 10px; background: #242424; color: var(--ink); }
        .field-review-inbox textarea { width: 100%; display: block; margin: 6px 0 12px; resize: vertical; user-select: text; -webkit-user-select: text; }
        .field-review-inbox button { cursor: pointer; }
        .field-review-inbox button:focus-visible { color: var(--accent); }
        .field-review-inbox button:disabled { opacity: .4; cursor: default; }
        .field-review-inbox .review-primary { background: var(--accent); color: #000; }
        .field-review-inbox .review-primary:focus-visible { background: #b4edff; }
        .field-review-inbox .review-copy { user-select: text; -webkit-user-select: text; white-space: pre-wrap; overflow-wrap: anywhere; }
        .field-review-inbox .review-meta, .review-counts { color: var(--muted); }
        .field-review-inbox .review-error { color: #ff7070; }
        .field-review-inbox .review-question { color: var(--accent); }
        .field-review-inbox .review-finding { background: #141414; border-radius: 6px; padding: 10px 12px; margin: 8px 0 12px; }
        .field-review-inbox p { margin: 8px 0 12px; }
        .field-review-inbox summary { padding: 10px 0; cursor: pointer; overflow-wrap: anywhere; }
        .field-review-inbox details + details { margin-top: 8px; }
        .field-review-inbox .review-entry { margin: 12px 0; padding: 8px 12px; background: #141414; border-radius: 6px; }
        .field-review-inbox a { display: inline-block; margin: 8px 12px 8px 0; color: var(--accent); }
        .field-review-inbox .review-evidence { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; margin: 14px 0 18px; }
        .field-review-inbox .review-evidence-link {
            display: flex; flex-direction: column; gap: 10px; min-width: 0; margin: 0; padding: 10px;
            background: #181818; border-radius: 6px; text-decoration: none;
        }
        .field-review-inbox .review-evidence-link:hover, .field-review-inbox .review-evidence-link:focus-visible { background: #282828; color: #b4edff; }
        .field-review-inbox .review-evidence-link img { display: block; width: 100%; aspect-ratio: 8 / 5; object-fit: contain; background: #000; border-radius: 4px; }
        .field-review-inbox .review-evidence-link img[hidden] { display: none; }
        .field-review-inbox .review-evidence-link span { padding: 0 2px 2px; }
        .field-review-inbox label { display: block; margin-top: 12px; }
        .field-review-inbox input[type=checkbox] { accent-color: var(--accent); }
        .review-actions { display: flex; flex-wrap: wrap; gap: 6px; }`;
export function mountReviewInbox(root, options) {
    root.classList.add('field-review-inbox');
    if(!document.querySelector('style[data-field-review-inbox]')) {
        const style=document.createElement('style');style.dataset.fieldReviewInbox='';style.textContent=styleText;document.head.append(style);
    }
    root.innerHTML="<div class=\"review-actions\"><button type=\"button\" id=\"review-refresh\">Refresh</button><button type=\"button\" id=\"review-close\" class=\"field-panel-icon\" title=\"Close\" aria-label=\"Close\">×</button></div>\n    <label><input type=\"checkbox\" id=\"review-completed\"> Include resolved</label>\n    <p id=\"review-counts\"></p>\n    <p id=\"review-error\" class=\"review-copy review-error\" aria-live=\"polite\"></p>\n    <div id=\"review-threads\"></div>";
    const find=id=>root.querySelector('#'+id);
    const reviewThreadsEl=find('review-threads'),reviewErrorEl=find('review-error');
    const completed=find('review-completed'),counts=find('review-counts');counts.classList.add('review-counts');
const reviewDrafts=new Map(), reviewExpanded=new Set();
let reviewLoadSerial=0;
function reviewText(parent,tag,text,className='') {
    const el=document.createElement(tag);el.textContent=text;el.className=className;parent.append(el);return el;
}
async function reviewLoadThreads(focusNote = null, expectedView = null) {
    const serial=++reviewLoadSerial;
    reviewErrorEl.textContent='';
    try {
        const inbox=await options.load(completed.checked || !!focusNote);if(serial!==reviewLoadSerial)return false;
        if(expectedView && inbox.viewId!==expectedView)return false;
        if(focusNote) {
            const target=inbox.notes.find(note=>note.id===focusNote);
            if(!target)throw Error('The requested note is no longer in this view.');
            if(target.status==='resolved')completed.checked=true;
            reviewExpanded.add(focusNote);
        }
        const notes=inbox.notes.filter(note=>completed.checked || note.status!=='resolved');
        const current=inbox.notes.filter(n=>n.status!=='resolved' && n.artifactDigest===inbox.revision && n.finding);
        const count=kind=>current.filter(n=>n.finding.kind===kind).length;
        const stale=inbox.notes.filter(n=>n.status!=='resolved' && n.finding && n.artifactDigest!==inbox.revision).length;
        const findings=[['error','error','errors'],['warning','to inspect','to inspect'],['question','decision','decisions']].filter(([kind])=>count(kind)).map(([kind,one,many])=>`${count(kind)} ${count(kind)===1?one:many}`);
        if(stale)findings.push(`${stale} from earlier geometry`);
        counts.textContent=[`${inbox.openCount} open · ${inbox.resolvedCount} resolved`,...findings].join(' · ');
        reviewThreadsEl.replaceChildren();
        if(!notes.length)reviewText(reviewThreadsEl,'p','No open notes. Include resolved to read completed threads.','review-copy');
        for(const note of notes) {
            const card=document.createElement('details');card.dataset.noteId=note.id;card.open=reviewExpanded.has(note.id);reviewThreadsEl.append(card);
            card.ontoggle=()=>{if(card.open)reviewExpanded.add(note.id);else reviewExpanded.delete(note.id);};
            const finding=note.finding, earlier=note.artifactDigest!==inbox.revision;
            const kindLabel={error:'Error',warning:'Needs inspection',question:'Decision needed'}[finding?.kind];
            const prefix=kindLabel?`${kindLabel} · ${earlier?'earlier geometry':finding.stage} · `:'';
            reviewText(card,'summary',`${prefix}${note.status||'open'} · ${note.text.slice(0,80)}${note.text.length>80?'…':''} · ${(note.replies||[]).length} ${(note.replies||[]).length===1?'reply':'replies'}`,finding?.kind==='error'&&!earlier?'review-error':'');
            reviewText(card,'p',note.text,'review-copy');
            if(kindLabel) {
                const context=reviewText(card,'div','','review-finding');
                reviewText(context,'p',finding.consequence,'review-copy');
                reviewText(context,'p',finding.prompt,'review-copy review-question');
                reviewText(context,'p',earlier?'This finding needs rechecking against the current geometry. Its original evidence is retained.':'Replies and closing this discussion do not change build checks.','review-copy review-meta');
            }
            const provenance=kindLabel?reviewText(card,'details','','review-meta'):card;
            if(kindLabel) {
                reviewText(provenance,'summary','Evidence details');
                reviewText(provenance,'p',`Reported by ${finding.producer} · ${finding.stage} · run ${finding.runId}`,'review-copy review-meta');
            }
            reviewText(provenance,'p',`Recorded ${note.createdAt||''} · geometry ${String(note.artifactDigest).slice(0,12)}`,'review-copy review-meta');
            // Use the inbox's revision while the canvas is still loading. The
            // host revalidates on show; a loading canvas is not earlier geometry.
            if(options.canLocate ? options.canLocate(note) : !earlier) {
                const show=reviewText(card,'button','Show feature');show.type='button';
                show.onclick=async()=>{try {await options.show(note);}catch(error){reviewErrorEl.textContent=String(error);}};
            } else reviewText(card,'p',earlier?'Earlier geometry. Its saved evidence remains available.':'This feature is not available in the current view. Its saved evidence remains available.','review-copy review-meta');
            let evidenceGallery=null;
            if(note.evidence)for(const kind of ['context','crop']) {
                const hash=note.evidence[kind]?.sha256;if(!/^[0-9a-f]{64}$/.test(hash||''))continue;
                if(!evidenceGallery)evidenceGallery=reviewText(card,'div','','review-evidence');
                const title=kind==='context'?'Context image':'Close crop';
                const link=reviewText(evidenceGallery,'a','','review-evidence-link');
                link.href=options.imageUrl(note,kind,hash);link.target='_blank';link.rel='noopener';
                link.draggable=false;link.setAttribute('aria-label',`Open full-size ${title.toLowerCase()}`);
                const preview=document.createElement('img');preview.alt='';preview.loading='lazy';preview.decoding='async';preview.draggable=false;
                preview.onerror=()=>{preview.hidden=true;};preview.src=link.href;link.append(preview);
                reviewText(link,'span',title);
            }
            const entries=[...(note.replies||[]).map(e=>({...e,reply:true})),...(note.history||[])].sort((a,b)=>a.version-b.version);
            for(const entry of entries) {
                const block=reviewText(card,'div','', 'review-entry');
                reviewText(block,'p',`${entry.actor} · ${entry.reply?'reply':entry.status} · ${entry.at}`,'review-copy review-meta');
                reviewText(block,'p',entry.reply?entry.text:entry.summary,'review-copy');
                reviewText(block,'p',`Geometry ${String(entry.checkedRevision).slice(0,12)}`,'review-copy review-meta');
            }
            const label=reviewText(card,'label',kindLabel?'Your response or finding':'Reply or resolution');
            const input=document.createElement('textarea');input.rows=3;input.maxLength=16000;input.setAttribute('aria-label','Reply to annotation');
            input.value=reviewDrafts.get(note.id)||'';input.oninput=()=>reviewDrafts.set(note.id,input.value);label.append(input);
            const actions=reviewText(card,'div','','review-actions');
            const reply=reviewText(actions,'button','Add reply','review-primary'), status=reviewText(actions,'button',note.status==='resolved'?'Reopen':kindLabel?'Close discussion':'Resolve');
            reply.type=status.type='button';
            const feedback=reviewText(card,'p','','review-copy review-error');feedback.setAttribute('aria-live','polite');
            async function write(asReply) {
                const text=input.value.trim();if(!text){feedback.textContent='Write a reply or explain the resolution first.';return;}
                reply.disabled=status.disabled=true;feedback.textContent='';
                const request={id:note.id,actor:'Viewer user',checkedRevision:inbox.revision,expectedVersion:note.version||0,
                    ...(asReply?{reply:text}:{status:note.status==='resolved'?'open':'resolved',summary:text})};
                try {
                    await options.write(request);
                    reviewDrafts.delete(note.id);await reviewLoadThreads();
                }catch(error){feedback.textContent=String(error);reply.disabled=status.disabled=false;}
            }
            reply.onclick=()=>write(true);status.onclick=()=>write(false);
        }
        return true;
    } catch(error) {if(serial===reviewLoadSerial)reviewErrorEl.textContent=String(error);return false;}
}

    async function open(noteId = null, viewId = null){
        const loaded=await reviewLoadThreads(noteId,viewId);
        if(loaded || !viewId){root.hidden=false;root.classList.remove('hidden');}
        if(loaded && noteId) {
            const card=[...reviewThreadsEl.children].find(card=>card.dataset.noteId===noteId);
            if(card)root.scrollTop=Math.max(0,card.offsetTop-12);
        }
        return loaded;
    }
    function close(){root.hidden=true;root.classList.add('hidden');}
    find('review-close').onclick=close;
    root.addEventListener('keydown',e=>{if(e.key==='Escape'&&!e.defaultPrevented&&!/^(INPUT|TEXTAREA)$/.test(e.target.tagName)){e.stopPropagation();close();}});
    find('review-refresh').onclick=()=>reviewLoadThreads();
    completed.onchange=()=>reviewLoadThreads();
    window.addEventListener('message',async event=>{
        const q=event.data;
        if(event.origin!==location.origin || event.source!==window.parent || q?.type!=='field-review-open')return;
        if(!['requestId','viewId','noteId'].every(key=>typeof q[key]==='string' && q[key].length>0 && q[key].length<=256))return;
        if(await open(q.noteId,q.viewId))event.source.postMessage({type:'field-review-opened',requestId:q.requestId,ok:true},event.origin);
    });
    return {open,close,refresh:reviewLoadThreads};
}
