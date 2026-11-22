import {classifyGuideLink} from './documentGuideModel.js';

export function queryPassageLinks(states, {query = '', boxUid, passageId, direction, kind} = {}) {
    const frames = [...states].filter(state => state.box.isConnected && !state.box.classList.contains('tombstoned'));
    const framesByUid = new Map(frames.map(frame => [frame.box.uid, frame]));
    const boxes = new Map([...document.querySelectorAll('.box:not(.tombstoned)')].map(box => [box.uid, box]));
    const passages = new Map();
    for (const state of frames) for (const anchor of state.runSources) {
        passages.set(anchor.id, {anchor, state});
    }
    const name = uid => boxes.get(uid)?.name || uid || 'Missing source';
    const tokens = String(query).toLowerCase().trim().split(/\s+/).filter(Boolean);
    const items = [];
    const add = entry => {
        if ((passageId && entry.passageId !== passageId) || (kind && entry.kind !== kind)) return;
        if (boxUid && !(direction === 'source' ? entry.destinationUid === boxUid :
            direction === 'backlink' ? entry.sourceUid === boxUid :
            entry.sourceUid === boxUid || entry.destinationUid === boxUid)) return;
        if (!tokens.every(token => [entry.text, entry.passageId, entry.sourceName, entry.destinationName, entry.kind,
            entry.lost ? 'lost missing' : 'live'].join(' ').toLowerCase().includes(token))) return;
        items.push(entry);
    };
    const quote = (anchor, state) => {
        if (!anchor || !state || anchor.lost) return anchor?.excerpt || '';
        const value = (state.box.contents || '').replace(/\r\n?/g, '\n');
        if (anchor.start < 0 || anchor.end > value.length ||
            value.slice(anchor.start, anchor.start + anchor.excerpt.length) !== anchor.excerpt) return anchor.excerpt;
        return value.slice(anchor.start, anchor.end);
    };
    for (const state of frames) {
        const destinationUid = state.box.uid;
        const destinationName = name(destinationUid);
        const note = state.passageAnchor;
        if (note) add({kind:'note', passageId:note.id, sourceUid:note.sourceUid, sourceName:name(note.sourceUid),
            destinationUid, destinationName, text:quote(note, framesByUid.get(note.sourceUid)),
            lost:note.lost || !boxes.has(note.sourceUid), occurrence:0});
        const counts = new Map();
        for (const link of state.preview.querySelectorAll('a[href^="field:"]')) {
            const href = link.getAttribute('href');
            const decoded = classifyGuideLink(href);
            if (decoded.kind !== 'passage' && decoded.kind !== 'box') continue;
            const occurrence = counts.get(href) || 0;
            counts.set(href, occurrence + 1);
            const common = {href, occurrence, destinationUid, destinationName};
            if (decoded.kind === 'passage') {
                const source = passages.get(decoded.id);
                const sourceUid = source?.state.box.uid;
                add({...common, kind:'transclusion', passageId:decoded.id, sourceUid, sourceName:name(sourceUid),
                    text:quote(source?.anchor, source?.state), lost:!source || source.anchor.lost});
            } else {
                add({...common, kind:'box link', sourceUid:decoded.uid, sourceName:name(decoded.uid),
                    text:link.textContent, lost:!boxes.has(decoded.uid), property:decoded.property, line:decoded.line});
            }
        }
    }
    return items;
}
