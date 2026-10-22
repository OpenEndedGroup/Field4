(() => {
  const frame = document.getElementById('slide');
  function fit() {
    const scale = Math.min(innerWidth / 1920, innerHeight / 1080);
    frame.style.transform = `scale(${scale})`;
    frame.style.left = `${(innerWidth - 1920 * scale) / 2}px`;
    frame.style.top = `${(innerHeight - 1080 * scale) / 2}px`;
  }
  addEventListener('resize', fit);
  fit();
  window.renderPresentation = state => new Promise((resolve, reject) => {
    const timer = setTimeout(() => finish(new Error('The audience slide did not finish loading')), 12000);
    function finish(error) {
      clearTimeout(timer);
      frame.removeEventListener('load', ready);
      if (error) reject(error); else resolve();
    }
    async function ready() {
      try {
        const slide = frame.contentDocument;
        await slide.fonts.load('112px "Iosevka Web"');
        await Promise.all(Array.from(slide.images, image => new Promise((resolve, reject) => {
          const loaded = () => image.naturalWidth ? resolve() : reject(new Error(`Could not load slide image: ${image.src}`));
          if (image.complete) loaded();
          else { image.addEventListener('load', loaded, {once: true}); image.addEventListener('error', loaded, {once: true}); }
        })));
        // The outgoing snapshot covers this page. An occluded frame may receive no animation
        // callbacks; load resources and force layout without waiting for a visible paint.
        void slide.body.offsetHeight;
        finish();
      } catch (error) { finish(error); }
    }
    frame.addEventListener('load', ready, {once: true});
    const html = state.content?.type === 'html' ? state.content.html : '';
    // HTML/CSS slides share local fonts and media. Scripts stay disabled; complete webpages use a native guest view.
    frame.srcdoc = `<!doctype html><html lang="en"><head><meta charset="utf-8">
      <base href="${state.baseUrl}"><link rel="stylesheet" href="${location.origin}/iosevka.css">
      <style>
      *{box-sizing:border-box}html,body{margin:0;width:1920px;height:1080px;overflow:hidden;background:#000;color:#eee}
      body{font-family:'Iosevka Web',Iosevka,monospace;font-size:40px;line-height:1.45;padding:120px 140px;display:flex;align-items:center}
      main{width:100%}h1{font-size:112px;line-height:1.08;font-weight:400;margin:0 0 48px;letter-spacing:-.03em}
      h2{font-size:72px;font-weight:400;line-height:1.15}p{margin:24px 0}li{margin:16px 0}a{color:inherit}code,pre{font-family:inherit}img,video,svg{max-width:100%}
      hr{border:0;border-top:2px solid #999;margin:48px 0}.muted{color:#999}.columns{display:flex;gap:80px}.columns>*{flex:1}
      </style></head><body><main>${html}</main></body></html>`;
  });
})();
