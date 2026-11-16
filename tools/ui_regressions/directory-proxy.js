// Independent browser, real drop handler/menu/RPC/tools/files. No model request.
const {sendWithReply} = await import('./afield.mjs');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const check = (condition, message) => { if (!condition) throw new Error(message); };
const wait = async (fn, message) => {
    for (let i = 0; i < 200; i++) { const value = fn(); if (value) return value; await delay(50); }
    throw new Error(message);
};
const fixture = await wait(() => [...document.querySelectorAll('.box')].find(b => b.name === 'DirectoryProxyFixture'), 'Missing fixture box');
const call = (name, args) => new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${name} timed out`)), 25000);
    sendWithReply('agent-ai-tool-call', {name, arguments: args, focusBox: fixture.uid}, `directory-proxy-${Math.random()}`, (_message, response) => {
        clearTimeout(timer);
        if (!response.ok || response.result?.success === false) reject(new Error(JSON.stringify(response)));
        else resolve(response.result);
        return 'remove';
    });
});
const state = window.__directoryProxyRegression;
if (state) {
    // The runner reran the Kotlin fixture to change the actual file, not a cached attachment.
    const reread = await call('AttachmentRequest', {file: state.attachmentRef});
    check(reread.content.includes('live updated text') && !reread.content.includes('initial text'), 'Proxy read did not observe the disk edit');
    check(reread.sourceHash !== state.sourceHash, 'Source hash did not change after the disk edit');
    const search = await call('WorkspaceSearchRequest', {scope: 'FILE_PROXY', proxyBox: state.proxyUid, query: 'live updated text'});
    check(search.complete && search.matches.length === 1 && search.matches[0].attachmentRef === state.attachmentRef, 'Live search did not return the updated file/ref');
    return {ok: true, actualDirectoryDropMenu: true, liveDiskUpdate: true, proxyUid: state.proxyUid, attachmentRef: state.attachmentRef, noModelRequest: true};
}
const marker = await wait(() => fixture.querySelector('#directory-proxy-fixture'), 'Missing disk fixture marker');
const root = new TextDecoder().decode(Uint8Array.from(atob(marker.dataset.rootBase64), c => c.charCodeAt(0)));
const before = new Set([...document.querySelectorAll('.box')].map(b => b.uid));
const file = new File([], root.split('/').pop(), {type: ''});
Object.defineProperty(file, 'path', {value: root});
const transfer = {files: [file], items: [{kind: 'file', webkitGetAsEntry: () => ({isDirectory: true, isFile: false})}], types: ['Files'], getData: () => ''};
const event = new MouseEvent('drop', {bubbles: true, cancelable: true, clientX: 620, clientY: 350});
Object.defineProperty(event, 'dataTransfer', {value: transfer});
// Assert classification never reads the folder as a File (the old extensionless-file path).
const reader = window.FileReader;
let readerCalls = 0;
window.FileReader = class { constructor() { readerCalls++; throw new Error('Directory must not use FileReader'); } };
let option;
try {
    document.body.dispatchEvent(event);
    option = await wait(() => [...document.querySelectorAll('.markingButton')].find(b => b.textContent.trim() === 'Create directory context proxy'), 'Directory drop menu did not appear');
    check(readerCalls === 0, 'Directory entered FileReader path');
    const labels = [...document.querySelectorAll('.markingButton')].map(b => b.textContent.trim());
    check(labels.includes('Copy path to clipboard'), 'Directory menu lost copy-path action');
    check(!labels.includes('Create proxy for copy in document folder'), 'Directory menu offers unsupported directory copy');
    option.dispatchEvent(new MouseEvent('mouseover', {bubbles: true}));
    option.dispatchEvent(new MouseEvent('mouseup', {bubbles: true, cancelable: true}));
} finally { window.FileReader = reader; }
const proxy = await wait(() => [...document.querySelectorAll('.box')].find(b => !before.has(b.uid) && b.classList.contains('file-proxy-box')), 'Menu did not create a file proxy box');
const catalogue = await call('AttachmentRequest', {file: `proxy:${proxy.uid}/`});
check(catalogue.content.includes('docs/readme.md') && catalogue.content.includes('src/Example.kt'), 'Catalogue lacks real source files');
for (const excluded of ['ignored.txt', 'Generated.kt', 'output.txt', '.hidden.txt']) {
    check(!catalogue.content.includes(excluded), `Catalogue exposed ${excluded}`);
}
const search = await call('WorkspaceSearchRequest', {scope: 'FILE_PROXY', proxyBox: proxy.uid, query: 'DirectoryProxyNeedle'});
check(search.complete && !search.stopReason, 'Small directory search unexpectedly partial');
check(search.matches.length === 2, `Expected exactly two eligible file matches: ${JSON.stringify(search)}`);
check(search.matches.some(m => m.path === 'docs/readme.md') && search.matches.some(m => m.path === 'src/Example.kt'), 'Search omitted source files or included excluded files');
for (const match of search.matches) {
    check(match.attachmentRef?.startsWith(`proxy:${proxy.uid}/`), 'Search lacks a proxy-scoped attachmentRef');
    const read = await call('AttachmentRequest', {file: match.attachmentRef});
    check(read.content.includes('DirectoryProxyNeedle'), 'Returned attachmentRef is not readable');
    if (match.path === 'docs/readme.md') {
        check(read.content.includes('initial text'), 'Initial disk contents differ');
        window.__directoryProxyRegression = {proxyUid: proxy.uid, attachmentRef: match.attachmentRef, sourceHash: read.sourceHash};
    }
}
check(window.__directoryProxyRegression, 'Missing readme reference for live-update phase');
return {ok: true, actualDirectoryDropMenu: true, catalogue: true, ignoredAndGeneratedExcluded: true, searchMatches: search.matches.length, returnedRefsRead: true, noModelRequest: true};
