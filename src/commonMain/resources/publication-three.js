/** Bundle the canonical viewer's modules into local Blob URLs, including inside opaque sandboxes. */
const THREE_ROOT = 'vendor/three/0.184.0/';
const literal = value => JSON.stringify(value).replace(/</g,'\\u003c');
const moduleId = path => 'field-publication/' + path;

export function rewriteViewerImports(source, path, available) {
    function resolve(specifier) {
        const target = specifier === 'three' ? THREE_ROOT + 'three.module.js'
            : specifier.startsWith('three/addons/') ? THREE_ROOT + specifier.slice(6)
            : new URL(specifier,new URL(path,'https://publication.invalid/')).pathname.slice(1);
        if (!available.has(target)) throw new Error(`Unbundled viewer dependency: ${path} → ${specifier}`);
        return moduleId(target);
    }
    source = source.replace(/(^[ \t]*(?:import|export)\s+(?:[^'";]*?\s+from\s*)?)(['"])([^'"\r\n]+)\2/gm,
        (_match,prefix,quote,specifier) => prefix + quote + resolve(specifier) + quote);
    return source.replace(/(\bimport\s*\(\s*)(['"])([^'"\r\n]+)\2(\s*\))/g,
        (_match,prefix,quote,specifier,suffix) => prefix + quote + resolve(specifier) + quote + suffix);
}

/** One shared runtime file per publication; each view retains its own geometry and camera. */
export function prepareThreePublication(files) {
    const decoder = new TextDecoder(), html = decoder.decode(files.get('threejs_viewer.html'));
    const code = html.match(/<script type="module">([\s\S]*?)<\/script>/)?.[1];
    if (!code) throw new Error('Three.js viewer module is unavailable');
    const modules = new Map([['viewer.js',code]]);
    for (const [path,bytes] of files) {
        if ((path.startsWith(THREE_ROOT) || /^(threejs_|geometry_review_).*\.js$/.test(path)) && path.endsWith('.js')) {
            modules.set(path,decoder.decode(bytes));
        }
    }
    const names = new Set(modules.keys());
    const bundled = [...modules].map(([path,source]) => [moduleId(path),rewriteViewerImports(source,path,names)]);
    const runtime = `(()=>{const modules=${literal(bundled)},imports={},urls=[];
        for(const [id,source] of modules){const url=URL.createObjectURL(new Blob([source],{type:'text/javascript'}));imports[id]=url;urls.push(url)}
        const map=document.createElement('script');map.type='importmap';map.textContent=JSON.stringify({imports});document.head.append(map);
        import(${literal(moduleId('viewer.js'))}).catch(error=>{document.documentElement.dataset.captureError=error.message;document.body.textContent='Published view could not load: '+error.message});
        window.addEventListener('pagehide',()=>urls.forEach(url=>URL.revokeObjectURL(url)));})();`;
    // No original scripts or server font imports run in a publication.
    const template = html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi,'')
        .replace(/@import\s+url\(["']\/iosevka\.css["']\);?/g,'');
    return {runtime,template,sourcePaths:[...modules.keys()].filter(p=>p !== 'viewer.js').concat('threejs_viewer.html')};
}

export function threePublicationDocument(snapshot, prepared, fontCss) {
    const head = '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'self\' \'unsafe-inline\' blob:; style-src \'unsafe-inline\'; img-src data: blob:; font-src data:; connect-src \'none\'; base-uri \'none\'; form-action \'none\'">' +
        `<style>${fontCss}</style>`;
    const bootstrap = `<script>window.__fieldThreePublication=${literal(snapshot)};</script><script src="../three-publication-runtime.js"></script>`;
    return prepared.template.replace('<head>','<head>' + head).replace('</body>',bootstrap + '</body>');
}
