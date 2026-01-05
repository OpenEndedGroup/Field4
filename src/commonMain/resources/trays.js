// trays.js
(function () {
        'use strict';

        const SIDES = ['left', 'right', 'top', 'bottom'];
        const MIN_SIZE = 150;
        const MAX_SIZE = 900;
        const DEFAULT_SIZE = 360;

        const trayState = Object.create(null); // side -> { el, visible, size, title }

        function installStyles() {
                console.info("installing tray styles")
                if (document.getElementById('field-tray-style')) return;

                const style = document.createElement('style');
                style.id = 'field-tray-style';
                style.type = 'text/css';
                style.textContent = `
.field-tray {
    --field-tray-size: ${DEFAULT_SIZE}px;
    position: fixed;
    z-index: 250;
    color: #fff;
    font-family: "Iosevka Web", system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    pointer-events: none;
    opacity: 0;
    transform: translate3d(0,0,0);
    transition: transform 0.18s ease-out, opacity 0.18s ease-out;
}

/* base inner chrome */
.field-tray__chrome {
    position: absolute;
    inset: 0;
    background: rgba(10, 10, 10, 0.92);
    border-radius: 4px;
    border: 1px solid rgba(255, 255, 255, 0.06);
    box-shadow: 0 0 30px rgba(0, 0, 0, 0.8);
    display: flex;
    flex-direction: column;
    overflow: hidden;
}

/* header */
.field-tray__header {
    flex: 0 0 26px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 8px 0 10px;
    gap: 6px;
    // background: linear-gradient(to bottom, rgba(255,255,255,0.06), rgba(255,255,255,0.02));
    // border-bottom: 1px solid rgba(255,255,255,0.08);
    font-size: 10px;
    letter-spacing: 0.06em;
    text-transform: uppercase;
}

.field-tray__title {
    flex: 1 1 auto;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
    opacity: 0.8;
}

.field-tray__close {
    flex: 0 0 auto;
    border: none;
    border-radius: 3px;
    padding: 2px 6px;
    background: rgba(0,0,0,1.0);
    color: inherit;
    cursor: pointer;
    font-size: 11px;
    line-height: 1;
    opacity: 0.8;
    transition: background 0.12s ease-out, opacity 0.12s ease-out, transform 0.12s ease-out;
}

.field-tray__close:hover {
    background: rgba(255,255,255,0.2);
    opacity: 1;
    transform: translateY(-0.5px);
}

.field-tray__close:active {
    transform: translateY(0.5px);
}

/* body + iframe */
.field-tray__body {
    position: relative;
    flex: 1 1 auto;
    display: flex;
    min-height: 0;
}

.field-tray__frame {
    border: 0;
    width: 100%;
    height: 100%;
    background: #111;
}

/* resize handles */
.field-tray__resize-handle {
    position: absolute;
    background: transparent;
    z-index: 2;
}

.field-tray__resize-handle::before {
    content: "";
    position: absolute;
    inset: 0;
    opacity: 0;
    transition: opacity 0.12s ease-out;
    pointer-events: none;
}

.field-tray__resize-handle:hover::before {
    opacity: 0.4;
}

/* side-specific handles */
.field-tray__resize-handle--left,
.field-tray__resize-handle--right {
    top: 0;
    bottom: 0;
    width: 8px;
    cursor: ew-resize;
}

.field-tray__resize-handle--left {
    right: -4px;
}

.field-tray__resize-handle--right {
    left: -4px;
}

.field-tray__resize-handle--top,
.field-tray__resize-handle--bottom {
    left: 0;
    right: 0;
    height: 8px;
    cursor: ns-resize;
}

.field-tray__resize-handle--top {
    bottom: -4px;
}

.field-tray__resize-handle--bottom {
    top: -4px;
}

/* we tint the handle slightly depending on orientation */
.field-tray__resize-handle--left::before,
.field-tray__resize-handle--right::before {
    // background: linear-gradient(to right, transparent, rgba(255,255,255,0.12), transparent);
}

.field-tray__resize-handle--top::before,
.field-tray__resize-handle--bottom::before {
    // background: linear-gradient(to bottom, transparent, rgba(255,255,255,0.12), transparent);
}

.field-tray--resizing .field-tray__frame {
    pointer-events: none; /* let mouse events go to the parent doc while dragging */
}

/* Trays per side */
.field-tray--left,
.field-tray--right {
    top: 0;
    bottom: 0;
    width: var(--field-tray-size);
}

.field-tray--left {
    left: 0;
    transform: translateX(-101%);
}

.field-tray--left.field-tray--visible {
    transform: translateX(0);
}

.field-tray--right {
    right: 0;
    transform: translateX(101%);
}

.field-tray--right.field-tray--visible {
    transform: translateX(0);
}

.field-tray--top,
.field-tray--bottom {
    left: 0;
    right: 0;
    height: var(--field-tray-size);
}

.field-tray--top {
    top: 0;
    transform: translateY(-101%);
}

.field-tray--top.field-tray--visible {
    transform: translateY(0);
}

.field-tray--bottom {
    bottom: 0;
    transform: translateY(101%);
}

.field-tray--bottom.field-tray--visible {
    transform: translateY(0);
}

/* visibility / pointer-events */
.field-tray--visible {
    opacity: 1;
    pointer-events: auto;
}

body.field-tray-dragging {
    user-select: none;
    cursor: default;
}
        `;
                document.head.appendChild(style);
        }

        function clampSize(size, side) {
                const maxByViewport = (side === 'left' || side === 'right')
                    ? Math.min(MAX_SIZE, window.innerWidth - 80)
                    : Math.min(MAX_SIZE, window.innerHeight - 80);

                return Math.max(MIN_SIZE, Math.min(size, maxByViewport));
        }

        function applySize(tray, side, size) {
                const clamped = clampSize(size, side);
                tray.style.setProperty('--field-tray-size', clamped + 'px');
                trayState[side].size = clamped;
        }

        function createTrayElement(side) {
                const tray = document.createElement('div');
                tray.className = 'field-tray field-tray--' + side;
                tray.dataset.side = side;

                tray.innerHTML = '' +
                    '<div class="field-tray__chrome">' +
                    '  <div class="field-tray__header">' +
                    '    <span class="field-tray__title"></span>' +
                    '    <button class="field-tray__close" type="button" title="Close tray">X</button>' +
                    '  </div>' +
                    '  <div class="field-tray__body">' +
                    '    <iframe class="field-tray__frame" src="about:blank" loading="lazy"></iframe>' +
                    '    <div class="field-tray__resize-handle field-tray__resize-handle--' + side + '"></div>' +
                    '  </div>' +
                    '</div>';

                document.body.appendChild(tray);

                const closeBtn = tray.querySelector('.field-tray__close');
                closeBtn.addEventListener('click', function () {
                        closeTray(side);
                });

                attachResizeHandler(tray, side);

                trayState[side] = {
                        el: tray,
                        visible: false,
                        size: DEFAULT_SIZE,
                        title: ''
                };

                applySize(tray, side, DEFAULT_SIZE);

                return tray;
        }

        function ensureTray(side) {
                if (!SIDES.includes(side)) {
                        throw new Error('FieldTrays: invalid side "' + side + '". Expected one of ' + SIDES.join(', '));
                }

                if (trayState[side] && trayState[side].el && document.body.contains(trayState[side].el)) {
                        return trayState[side].el;
                }

                return createTrayElement(side);
        }

        function attachResizeHandler(tray, side) {
                const handle = tray.querySelector('.field-tray__resize-handle');
                if (!handle) return;

                let dragging = false;
                let startPos = 0;
                let startSize = 0;

                function applyDelta(e) {
                        let delta;
                        if (side === 'left') {
                                delta = e.clientX - startPos;
                                applySize(tray, side, startSize + delta);
                        } else if (side === 'right') {
                                delta = startPos - e.clientX;
                                applySize(tray, side, startSize + delta);
                        } else if (side === 'top') {
                                delta = e.clientY - startPos;
                                applySize(tray, side, startSize + delta);
                        } else if (side === 'bottom') {
                                delta = startPos - e.clientY;
                                applySize(tray, side, startSize + delta);
                        }
                }

                function stopDragging() {
                        if (!dragging) return;
                        dragging = false;

                        document.body.classList.remove('field-tray-dragging');
                        tray.classList.remove('field-tray--resizing');   // re-enable iframe pointer events

                        window.removeEventListener('mousemove', onMouseMove);
                        window.removeEventListener('mouseup', onMouseUp);
                        window.removeEventListener('blur', onWindowBlur);
                }

                function onMouseMove(e) {
                        if (!dragging) return;
                        applyDelta(e);
                }

                function onMouseUp() {
                        stopDragging();
                }

                function onWindowBlur() {
                        // covers the case where mouseup happens outside the window
                        stopDragging();
                }

                handle.addEventListener('mousedown', function (e) {
                        e.preventDefault();
                        e.stopPropagation();

                        dragging = true;

                        document.body.classList.add('field-tray-dragging');
                        tray.classList.add('field-tray--resizing');      // temporarily kill iframe pointer events

                        if (side === 'left' || side === 'right') {
                                startPos = e.clientX;
                                startSize = tray.getBoundingClientRect().width;
                        } else {
                                startPos = e.clientY;
                                startSize = tray.getBoundingClientRect().height;
                        }

                        window.addEventListener('mousemove', onMouseMove);
                        window.addEventListener('mouseup', onMouseUp);
                        window.addEventListener('blur', onWindowBlur);
                });
        }

        function openTray(side, url, options) {
                options = options || {};

                const tray = ensureTray(side);
                const state = trayState[side];

                if (typeof options.size === 'number') {
                        applySize(tray, side, options.size);
                }

                if (typeof options.title === 'string') {
                        setTitle(side, options.title);
                } else if (url && !state.title) {
                        setTitle(side, url);
                }

                if (url) {
                        setUrl(side, url);
                }

                tray.classList.add('field-tray--visible');
                state.visible = true;
        }

        function closeTray(side) {
                const state = trayState[side];
                if (!state || !state.el) return;
                state.el.classList.remove('field-tray--visible');
                state.visible = false;
        }

        function toggleTray(side, url, options) {
                const state = trayState[side];
                if (state && state.visible) {
                        closeTray(side);
                } else {
                        openTray(side, url, options);
                }
        }

        function setUrl(side, url) {
                const tray = ensureTray(side);
                const iframe = tray.querySelector('.field-tray__frame');
                if (!iframe) {
                        throw new Error('FieldTrays: iframe element missing for side "' + side + '".');
                }
                iframe.src = url;
        }

        function setTitle(side, title) {
                const tray = ensureTray(side);
                const titleSpan = tray.querySelector('.field-tray__title');
                if (titleSpan) {
                        titleSpan.textContent = title || '';
                }
                if (trayState[side]) {
                        trayState[side].title = title || '';
                }
        }

        function getState(side) {
                if (side) {
                        return trayState[side] ? { ...trayState[side] } : null;
                }
                const copy = {};
                SIDES.forEach(function (s) {
                        if (trayState[s]) copy[s] = { ...trayState[s] };
                });
                return copy;
        }

        // Install CSS immediately
        installStyles();

        const api = {
                open: openTray,
                close: closeTray,
                toggle: toggleTray,
                setUrl: setUrl,
                setTitle: setTitle,
                getState: getState
        };

        if (!window.FieldTrays) {
                window.FieldTrays = api;
        } else {
                console.warn('FieldTrays is already defined on window; keeping existing value.');
        }
})();
