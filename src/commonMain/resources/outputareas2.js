
// helper: try to resize iframe to its content, call cb({ width, height }) if it works
function sizeIframeToContent(iframe, cb) {
        function measure() {
                let doc;
                try {
                        doc = iframe.contentDocument || iframe.contentWindow.document;
                } catch (e) {
                        // cross-origin: we can't inspect it
                        cb(null);
                        return;
                }
                if (!doc) {
                        cb(null);
                        return;
                }

                const body = doc.body;
                const html = doc.documentElement;

                const height = Math.max(body.scrollHeight, html.scrollHeight);
                const width  = Math.max(body.scrollWidth,  html.scrollWidth)-5;

                iframe.style.height = height + 'px';
                iframe.style.width  = width  + 'px';

                cb({ width, height });
        }

        // if it's already loaded, measure immediately, otherwise wait
        try {
                const doc = iframe.contentDocument || iframe.contentWindow.document;
                if (doc && doc.readyState === 'complete') {
                        measure();
                } else {
                        iframe.addEventListener('load', () => measure(), { once: true });
                }
        } catch (e) {
                // cross-origin: can't even touch contentDocument
                cb(null);
        }
}


function disclosureInterface(container) {
        if (container.__disclosureAPI) return container.__disclosureAPI;
        const disclosures = {};

        function animateOpen(d) {
                const { wrap, cw } = d;

                cw.style.display = 'block';

                const onlyChild =
                    cw.children.length === 1 ? cw.children[0] : null;

                // if single child is an iframe, try to use its "natural" size
                if (onlyChild && onlyChild.tagName === 'IFRAME') {
                        const iframe = onlyChild;

                        sizeIframeToContent(iframe, (size) => {
                                // if we couldn't measure (cross-origin), fall back to normal behavior
                                const targetHeight = size
                                    ? size.height + 'px'
                                    : cw.scrollHeight + 'px';

                                // start collapsed
                                cw.style.height = '0px';

                                // force reflow so the transition will apply
                                void cw.offsetHeight;

                                cw.style.transition = 'height 0.2s ease';

                                requestAnimationFrame(() => {
                                        cw.style.height = targetHeight;
                                        // width based on content width
                                        wrap.style.width = (cw.scrollWidth + 2) + 'px';
                                });

                                // when done, set height to auto so it can grow/shrink later
                                cw.addEventListener('transitionend', function once(e) {
                                        if (e.target !== cw || e.propertyName !== 'height') return;
                                        cw.removeEventListener('transitionend', once);
                                        cw.style.height = 'auto';
                                        cw.style.transition = '';
                                });
                        });

                        return; // don't run the fallback below
                }

                // === fallback: original behavior when not a single iframe ===

                const height = cw.scrollHeight + 'px';
                cw.style.height = '0px';
                void cw.offsetHeight; // force reflow

                cw.style.transition = 'height 0.2s ease';

                requestAnimationFrame(() => {
                        cw.style.height = height;
                        wrap.style.width = (cw.scrollWidth + 2) + 'px';
                });

                cw.addEventListener('transitionend', function once(e) {
                        if (e.target !== cw || e.propertyName !== 'height') return;
                        cw.removeEventListener('transitionend', once);
                        cw.style.height = 'auto';
                        cw.style.transition = '';
                });
        }

        function animateClose(d) {
                const {wrap, cw} = d;
                const height = cw.scrollHeight + 'px';
                cw.style.height = height;
                requestAnimationFrame(() => {
                        // cw.style.transition = 'height 0.2s ease';
                        cw.style.height = '0px';
                        cw.style.display = 'none';
                        cw.style.transition = '';
                        wrap.style.width = 'auto';
                });
                // cw.addEventListener('transitionend', function once() {
                //         cw.removeEventListener('transitionend', once);
                // });
        }

        function upsertDisclosure(id, title, content) {
                let d = disclosures[id];
                if (!d) {
                        const wrap = document.createElement('div');
                        wrap.className = 'disclosure';
                        const hdr = document.createElement('div');
                        hdr.className = 'disclosure-header';
                        const arr = document.createElement('div');
                        arr.className = 'arrow';
                        const ttl = document.createElement('div');
                        ttl.className = 'title';
                        ttl.textContent = title;
                        hdr.append(arr, ttl);
                        if (content === null) hdr.classList.add('hide-arrow', 'no-hover');
                        const cw = document.createElement('div');
                        cw.className = 'disclosure-content';

                        cw.innerHTML = content || '';

                        hdr.addEventListener('mouseenter', () => wrap.classList.add('hover'));
                        hdr.addEventListener('mouseleave', () => wrap.classList.remove('hover'));
                        hdr.addEventListener('mousedown', (e) => {
                                if (!e.target.closest('input, label') && !hdr.classList.contains('no-hover')) toggle(id);
                        });
                        hdr.addEventListener('click', e => {
                                // if (!e.target.closest('input, label') && !hdr.classList.contains('no-hover')) toggle(id);
                        });
                        wrap.append(hdr, cw);
                        container.appendChild(wrap);
                        d = {wrap, hdr, cw, arr};
                        disclosures[id] = d;
                        return cw
                } else {
                        const {wrap, hdr, cw} = d;
                        hdr.querySelector('.title').textContent = title;
                        if (content === null) {
                                hdr.classList.add('hide-arrow', 'no-hover');
                                cw.innerHTML = '';
                                close(id);
                        } else {
                                hdr.classList.remove('hide-arrow', 'no-hover');
                                cw.innerHTML = content;
                                // if (wrap.classList.contains('open')) wrap.style.width = (cw.scrollWidth -2) + 'px';
                        }
                        return cw
                }
        }

        function setContent(id, content) {
                const d = disclosures[id];
                if (!d) return;
                return upsertDisclosure(id, d.hdr.querySelector('.title').textContent, content);
        }

        function toggle(id) {
                const d = disclosures[id];
                if (!d) return;
                d.wrap.classList.contains('open') ? close(id) : open(id);
        }

        function open(id) {
                const d = disclosures[id];
                if (d.hdr.classList.contains('no-hover')) return;
                d.wrap.classList.add('open');
                animateOpen(d);
        }

        function close(id) {
                const d = disclosures[id];
                d.wrap.classList.remove('open');
                animateClose(d);
        }

        const api = {upsertDisclosure, setContent, toggle, open, close};
        container.__disclosureAPI = api;
        return api;
}
