// trays.js
(function () {
        'use strict';

        const SIDES = ['left', 'right', 'top', 'bottom'];
        const MIN_SIZE = 150;
        const MAX_SIZE = 900;
        const DEFAULT_SIZE = 360;

        const trayState = Object.create(null); // side -> { el, visible, size, title }

        function installStyles() {
                // Tray chrome lives in field.css so theme variables cascade normally.
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
