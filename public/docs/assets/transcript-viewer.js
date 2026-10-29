/* One document beside its gallery entry. Ordinary links still work without JavaScript. */
(() => {
    let panel = null, activeLink = null;
    const reducedMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches;
    function entryHeading(link) {
        const article = link.closest('.md-content__inner') || link.closest('article');
        return [...article.querySelectorAll('h3')].filter(h =>
            h.compareDocumentPosition(link) & Node.DOCUMENT_POSITION_FOLLOWING).at(-1);
    }
    function close(restore = false) {
        panel?.remove(); panel = null;
        document.documentElement.classList.remove('field-transcript-open');
        activeLink?.removeAttribute('aria-expanded');
        if (restore && activeLink?.isConnected) {
            activeLink.focus({preventScroll:true});
            activeLink.scrollIntoView({block:'nearest',inline:'nearest',behavior:reducedMotion() ? 'auto' : 'smooth'});
        }
        activeLink = null;
    }
    function position() {
        if (!panel || !activeLink) return;
        if (innerWidth < 900) {
            panel.classList.add('field-transcript-viewer--below');
            activeLink.closest('p').after(panel);
            panel.style.removeProperty('left'); panel.style.removeProperty('top');
            return;
        }
        panel.classList.remove('field-transcript-viewer--below');
        document.body.append(panel);
        const article = activeLink.closest('.md-content__inner') || activeLink.closest('article');
        const heading = entryHeading(activeLink);
        const entry = heading?.closest('.field-sticky-section[data-level="3"]') || heading || activeLink.closest('p');
        const rect = article.getBoundingClientRect(), entryRect = entry.getBoundingClientRect();
        panel.style.left = `${scrollX + rect.right + 28}px`;
        panel.style.top = `${scrollY + entryRect.top}px`;
    }
    document.addEventListener('click', event => {
        if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        if (!document.querySelector('[data-field-transcript-gallery]')) return;
        const link = event.target.closest('a[href]');
        if (!link || panel?.contains(link)) return;
        const url = new URL(link.href);
        if (url.origin !== location.origin || !url.pathname.endsWith('/offline.html') || !url.searchParams.get('file')?.startsWith('assets/agent_transcripts/')) return;
        event.preventDefault(); close(); activeLink = link;
        url.searchParams.set('focus','outputs');
        link.setAttribute('aria-expanded','true');
        const heading = entryHeading(link)?.cloneNode(true);
        heading?.querySelector('.headerlink')?.remove();
        const name = heading?.textContent.trim() || url.searchParams.get('file').split('/').at(-2);
        panel = document.createElement('aside'); panel.className = 'field-transcript-viewer';
        panel.setAttribute('aria-label',`${name} document`);
        const header = document.createElement('div'); header.className = 'field-transcript-viewer__header';
        const label = document.createElement('span'); label.textContent = name;
        const separate = document.createElement('a'); separate.href = url.href; separate.target = '_blank'; separate.rel = 'noopener';
        separate.textContent = '↗'; separate.setAttribute('aria-label','Open document separately'); separate.title = 'Open separately';
        const dismiss = document.createElement('button'); dismiss.type = 'button'; dismiss.textContent = '×';
        dismiss.setAttribute('aria-label','Close document'); dismiss.addEventListener('click',()=>close(true));
        header.append(label,separate,dismiss);
        const frame = document.createElement('iframe'); frame.src = url.href; frame.title = `${name} — Field document`;
        frame.setAttribute('allowfullscreen','');
        panel.append(header,frame); document.body.append(panel);
        document.documentElement.classList.add('field-transcript-open');
        position();
        panel.scrollIntoView({block:'start',inline:'end',behavior:reducedMotion() ? 'auto' : 'smooth'});
    });
    document.addEventListener('keydown',event=>{if(event.key==='Escape' && panel)close(true);});
    window.addEventListener('resize',position);
    if (typeof document$ !== 'undefined') document$.subscribe(()=>close());
})();
