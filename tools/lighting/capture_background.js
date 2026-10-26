// Playwright can expand a box crop beyond the viewport. Paint that extra document
// area with Field's actual background instead of Chromium's default white canvas.
(() => {
    const background = getComputedStyle(document.body).backgroundColor;
    document.documentElement.style.backgroundColor = background;
    // Keep the WebGL backdrop: it is what the app shows. Hiding it exposed the CSS
    // fallback, whose SVG grain is stretched to the window and reads as coarse speckle.
    return {background};
})()
