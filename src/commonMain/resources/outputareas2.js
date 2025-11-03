
function disclosureInterface(container) {
        if (container.__disclosureAPI) return container.__disclosureAPI;
        const disclosures = {};

        function animateOpen(d) {
                const {wrap, cw} = d;
                cw.style.display = 'block';
                const height = cw.scrollHeight + 'px';
                cw.style.height = '0px';
                requestAnimationFrame(() => {
                        // cw.style.transition = 'height 0.2s ease';
                        cw.style.height = height;
                        cw.style.height = 'auto';
                        cw.style.transition = '';
                        // animate width
                        wrap.style.width = (cw.scrollWidth + 2) + 'px';
                });
                // cw.addEventListener('transitionend', function once() {
                //         cw.removeEventListener('transitionend', once);
                // });
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
                                if (!e.target.closest('input, label') && !hdr.classList.contains('no-hover')) toggle(id);
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
