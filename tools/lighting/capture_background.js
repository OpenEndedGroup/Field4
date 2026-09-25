// Playwright can expand a box crop beyond the viewport. Paint that extra document
// area with Field's actual background instead of Chromium's default white canvas.
(() => {
    const background = getComputedStyle(document.body).backgroundColor;
    document.documentElement.style.backgroundColor = background;
    // Match the frame-synchronous recorder: omit the animated WebGL backdrop,
    // which Chromium can composite as white in clipped headless screenshots.
    const style = document.createElement('style');
    style.textContent = '.field-webgl-background { display:none !important; }';
    document.head.appendChild(style);
    return {background};
})()
