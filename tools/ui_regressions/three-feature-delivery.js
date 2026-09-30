const frame=[...document.querySelectorAll('iframe')].find(f=>new URL(f.src,document.baseURI).searchParams.get('uid')?.endsWith('_features'));
const w=frame.contentWindow;
for(let i=0;i<100;i++) {
    const inbox=await (await w.fetch('/three-review?uid='+new URL(w.location.href).searchParams.get('uid'))).json();
    const note=inbox.notes.find(n=>n.selection.region?.angle===25);
    if(inbox.selection?.selection.kind==='region' && note) {
        if(!note.evidence || JSON.stringify(note).includes('base64,'))return {ok:false,reason:'Images must be retained outside note JSON'};
        for(const kind of ['context','crop']) {
            const image=note.evidence[kind],response=await w.fetch(image.url);
            if(!response.ok)return {ok:false,reason:kind+' PNG not served'};
            const bytes=await response.arrayBuffer();
            const hash=Array.from(new Uint8Array(await w.crypto.subtle.digest('SHA-256',bytes))).map(v=>v.toString(16).padStart(2,'0')).join('');
            if(hash!==image.sha256)return {ok:false,reason:kind+' identity differs'};
        }
        const forbidden=await w.fetch('/three-review-image?uid='+inbox.viewId+'&sha256='+'0'.repeat(64));
        if(forbidden.status!==404)return {ok:false,reason:'Unreferenced image was served'};
        if(w.document.getElementById('feature-save').disabled)return {ok:false,reason:'Save remained disabled after acknowledgement'};
        const url='/three-review-note?uid='+encodeURIComponent(inbox.viewId);
        const change={id:note.id,status:'resolved',summary:'Verified the selected front surface and both saved images.',actor:'browser-regression',checkedRevision:inbox.revision,expectedVersion:note.version};
        const write=body=>w.fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
        const agentText='Finding: <em>this is literal text</em>\nKeep the original surface fixture for the next agent.';
        const reply=await write({id:note.id,reply:agentText,actor:'first-agent',checkedRevision:inbox.revision,expectedVersion:0});
        if(!reply.ok)return {ok:false,reason:'Agent reply failed: '+await reply.text()};
        change.expectedVersion=1;
        const closed=await write(change);
        if(!closed.ok)return {ok:false,reason:'Resolution failed: '+await closed.text()};
        const open=await (await w.fetch('/three-review?uid='+inbox.viewId)).json();
        if(open.notes.some(n=>n.id===note.id)||open.resolvedCount!==1)return {ok:false,reason:'Resolved note stayed in default inbox'};
        const all=await (await w.fetch('/three-review?uid='+inbox.viewId+'&includeResolved=true')).json();
        const retained=all.notes.find(n=>n.id===note.id);
        if(retained?.history.length!==1||retained.replies?.[0].text!==agentText||retained.evidence.crop.sha256!==note.evidence.crop.sha256)return {ok:false,reason:'Resolution lost original evidence'};
        if((await write({...change,status:'open'})).status!==409)return {ok:false,reason:'Stale competing update accepted'};
        if(!(await write({...change,status:'open',expectedVersion:2,summary:'Reopened for follow-up review.'})).ok)return {ok:false,reason:'Reopen failed'};
        w.document.getElementById('feature-inbox').click();
        let card;
        for(let attempt=0;attempt<100;attempt++) {
            card=[...w.document.querySelectorAll('#review-threads details')].find(el=>el.dataset.noteId===note.id);
            if(card)break;await new Promise(r=>setTimeout(r,50));
        }
        if(!card)return {ok:false,reason:'Notes panel did not load'};
        card.open=true;
        const previews=[...card.querySelectorAll('.review-evidence-link img')];
        if(previews.length!==2)return {ok:false,reason:'Missing context and crop previews'};
        await Promise.all(previews.map(image=>image.decode()));
        if(previews.some(image=>!image.naturalWidth||w.getComputedStyle(image).objectFit!=='contain'))return {ok:false,reason:'Evidence preview is missing or cropped'};
        const links=[...card.querySelectorAll('.review-evidence-link')];
        if(links.some(link=>link.target!=='_blank'||w.getComputedStyle(link).padding!=='10px'))return {ok:false,reason:'Evidence link spacing or full-size target differs'};

        if(!card.textContent.includes(agentText)||card.querySelector('em'))return {ok:false,reason:'Agent reply missing or rendered as unsafe HTML'};
        if(w.getComputedStyle(card.querySelector('.review-copy')).userSelect!=='text'||w.getComputedStyle(card.querySelector('summary')).userSelect!=='none')return {ok:false,reason:'Thread text selection differs'};
        const draft=card.querySelector('textarea');draft.value='Second finding, recorded from the viewer.';draft.dispatchEvent(new w.Event('input'));
        card.querySelector('.review-primary').click();
        let current;
        for(let attempt=0;attempt<100;attempt++) {
            const updated=await (await w.fetch('/three-review?uid='+inbox.viewId)).json();current=updated.notes.find(n=>n.id===note.id);
            if(current?.replies?.length===2)break;await new Promise(r=>setTimeout(r,50));
        }
        if(current?.replies?.length!==2||current.version!==4||current.status!=='open')return {ok:false,reason:'Viewer reply did not append independently of status'};
        return {ok:true,manualRectangle:true,fieldDelivery:true,contextAndCrop:true,resolveHistoryAndReopen:true,agentAndViewerReplies:true,threadTextCopyable:true};
    }
    await new Promise(r=>setTimeout(r,100));
}
return {ok:false,reason:'Mouse-drawn note did not reach Field'};
