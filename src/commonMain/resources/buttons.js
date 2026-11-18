
let canvas, ctx

const DEBUG_MARKING = false;
const markLog = (...args) => {
        if (DEBUG_MARKING) console.info(...args);
};

var originatingMouseDown = null

var masterMousedownCallbackTable = {}
let activeMarkingMenu = null;
let submenuHoverTimer = null;
let liveDivs = [];
const handledMarkingMouseups = new WeakSet();

const forceClearMarkingMenu = () => {
        clearTimeout(submenuHoverTimer);
        submenuHoverTimer = null;
        activeMarkingMenu?.releaseWidget?.();
        activeMarkingMenu = null;
        liveDivs = [];
        missingMouseupHandler = () => {};
        try {
                document.querySelectorAll(".markingButton, .markingButtonBackground, .markingWidgetPanel").forEach(el => {
                        if (el.parentElement) el.parentElement.removeChild(el);
                });
        } catch (e) {
                console.warn("error cleaning marking menu", e);
        }
        if (ctx && canvas) {
                ctx.clearRect(0, 0, canvas.width, canvas.height);
                canvas.style.pointerEvents = "none";
                canvas.classList.remove("fadeToBlack");
        }
        contextMenuFinish = null;
        mainFinish = null;
        live = false;
};

function initMenuSupport() {
        canvas = document.getElementById("overlaycanvas");
        ctx = canvas.getContext("2d");

        var first = true
        const resizeCanvas = () => {
                const ratio = window.devicePixelRatio || 1;
                canvas.width = window.innerWidth * ratio;
                canvas.height = window.innerHeight * ratio;
                canvas.style.width = window.innerWidth + "px";
                canvas.style.height = window.innerHeight + "px";

                canvas.style.position = "absolute";
                canvas.style.pointerEvents = "none";

                ctx.scale(ratio, ratio);

                if (!first) return
                first = false

                canvas.addEventListener("mousedown", (event) => {
                        if (mainFinish) {
                                was = mainFinish
                                mainFinish = null
                                was();
                                event.stopPropagation();
                        }
                });
                canvas.addEventListener("mouseup", (event) => {
                        markLog("canvas mouseup")
                });

                canvas.addEventListener("mousemove", (event) => {
                        // if (live)
                        //         console.log(" canvas move 1 ", event)
                });

                window.addEventListener("mousemove", (event) => {
                        if (live) activeMarkingMenu?.move(event);
                });

                canvas.addEventListener("drag", (event) => {
                        // if (live)
                        //         console.log(" canvas drag 1 ", event, live)
                });

                window.addEventListener("drag", (event) => {
                        // if (live)
                        //         console.log(" window drag ", event, live)
                });


                window.addEventListener("mousedown", (event) => {
                        markLog("master mousedown event ", event)
                        originatingMouseDown = event
                        if (live) activeMarkingMenu?.press(event);

                        import("./afield.mjs").then( (afield) => {
                                event.target.classList.forEach(x => {
                                        let cb = masterMousedownCallbackTable[x]
                                        if (cb) {
                                                afield.send("perform-action", {
                                                        uid_action: cb,
                                                        uid_currentSelection: [currentSelectedCode[0].uid]
                                                })
                                        }
                                })
                        })
                        // if (live)
                        //         console.log(" window drag ", event, live)
                });

                window.addEventListener("keydown", (event) => {
                        if (!activeMarkingMenu) return;
                        if (event.key === "Escape") {
                                event.preventDefault(); event.stopImmediatePropagation();
                                activeMarkingMenu.back();
                        } else if (event.key === "Enter" && !event.target.closest?.('.markingWidgetPanel,input,textarea,select,[contenteditable="true"]')) {
                                event.preventDefault(); event.stopImmediatePropagation();
                                activeMarkingMenu.choose(event);
                        }
                }, true);

                window.addEventListener("mouseup", (event) => {
                        if (handledMarkingMouseups.has(event)) return;
                        if (live && activeMarkingMenu) activeMarkingMenu.choose(event);
                        else missingMouseupHandler(event);
                });
        };
        resizeCanvas();
        window.addEventListener("resize", resizeCanvas);

        setTimeout(() => {
                document.body.removeChild(canvas);
                document.body.prepend(canvas);

                ctx.fillStyle = "rgba(0,0,0,0)";
                ctx.fillRect(0, 0, window.innerWidth, window.innerHeight);
        }, 100);

        window.addEventListener("load", (event) => {
                document.body.style.margin = "0";
                document.body.style.overflow = "hidden";
        });
}

const polarToRect = (centerx, centery, r, angle) => {
        return [
                centerx + r * Math.sin((Math.PI * angle) / 180),
                centery + r * Math.cos((Math.PI * angle) / 180),
        ];
};

let contextMenuFinish = null;
let mainFinish = null;
let live = false

// Entries may have `children` instead of a callback. A short hover opens a sub-menu;
// releasing on its parent also opens it, and Back/Escape returns one level.
const goMarkingMenu = (originx, originy, menus, originatingEvent, cancelCallback) => {
        if (activeMarkingMenu) activeMarkingMenu.close(true);
        else forceClearMarkingMenu();
        const rr = 10, radius = 120;
        const stack = [{menus, x: originx, y: originy}];
        let generation = 0, pointer = {x: originx, y: originy}, level, closed = false;
        const childrenOf = entry => Array.isArray(entry?.children) && entry.children.length ? entry.children : null;
        const opensLevel = entry => childrenOf(entry) || typeof entry?.mount === 'function';
        let widgetPanel = null, releaseWidget = null;
        const clearHover = () => { clearTimeout(submenuHoverTimer); submenuHoverTimer = null; };
        const session = {
                releaseWidget() {
                        const release = releaseWidget; releaseWidget = null;
                        try { release?.(); } finally { widgetPanel?.remove(); widgetPanel = null; }
                },
                close(cancelled = false) {
                        if (closed) return;
                        closed = true;
                        forceClearMarkingMenu();
                        if (cancelled) cancelCallback?.();
                },
                back() {
                        if (stack.length === 1) session.close(true);
                        else { stack.pop(); show(true); }
                },
                press(event) {
                        level.ready = true;
                        if (Number.isFinite(event?.clientX)) pointer = {x: event.clientX, y: event.clientY};
                },
                move(event) {
                        if (widgetPanel) return;
                        pointer = {x: event.clientX, y: event.clientY};
                        if (!level.ready && Math.hypot(pointer.x - level.openedAt.x, pointer.y - level.openedAt.y) >= 12) level.ready = true;
                        const best = level.ready ? closestOption(pointer.x, pointer.y) : level.center;
                        for (const div of liveDivs) {
                                div.mouseIsOver = div === best;
                                div.classList.toggle('markingHover', div === best);
                        }
                        if (best !== level.hovered) { clearHover(); level.hovered = best; }
                        if (!opensLevel(best?.markingEntry)) return;
                        const rect = best.getBoundingClientRect();
                        if (pointer.x < rect.left - 10 || pointer.x > rect.right + 10 || pointer.y < rect.top - 10 || pointer.y > rect.bottom + 10) { clearHover(); return; }
                        if (submenuHoverTimer) return;
                        const current = generation;
                        submenuHoverTimer = setTimeout(() => {
                                if (activeMarkingMenu === session && generation === current && level.hovered === best) enter(best);
                        }, 180);
                },
                choose(event) {
                        if (widgetPanel) return true;
                        if (event && handledMarkingMouseups.has(event)) return true;
                        if (!level.ready) { if (event) handledMarkingMouseups.add(event); return true; }
                        const x = Number.isFinite(event?.clientX) ? event.clientX : pointer.x;
                        const y = Number.isFinite(event?.clientY) ? event.clientY : pointer.y;
                        const best = closestOption(x, y);
                        if (!best) { session.close(true); return false; }
                        return best.finish(event);
                }
        };
        function enter(div) {
                if (!opensLevel(div.markingEntry)) return;
                const rect = div.getBoundingClientRect();
                // Leave room for six labels near canvas edges. The new level is disarmed until
                // the pointer moves, so releasing where its parent was cannot pick a leaf.
                const mx = Math.min(320, window.innerWidth / 2), my = Math.min(200, window.innerHeight / 2);
                stack.push({menus: childrenOf(div.markingEntry), widget: div.markingEntry,
                        x: Math.max(mx, Math.min(window.innerWidth - mx, rect.x + rect.width / 2)),
                        y: Math.max(my, Math.min(window.innerHeight - my, rect.y + rect.height / 2))});
                show(true);
        }
        function show(disarm = false) {
                clearHover(); generation++; session.releaseWidget();
                for (const div of liveDivs) { div.remove(); div.subDiv?.remove(); }
                liveDivs = [];
                ctx.clearRect(0, 0, canvas.width, canvas.height);
                const frame = stack[stack.length - 1], current = generation;
                level = {ready: !disarm, openedAt: {...pointer}, hovered: null};
                if (typeof frame.widget?.mount === 'function') {
                        const entry = frame.widget;
                        const panel = document.createElement('section');
                        panel.className = 'markingWidgetPanel outputArea';
                        panel.setAttribute('role', 'dialog'); panel.setAttribute('aria-label', entry.text);
                        const width = Math.min(entry.outputWidth || 360, window.innerWidth - 24);
                        const height = Math.min(entry.outputHeight || 240, window.innerHeight - 76);
                        panel.style.width = width + 'px';
                        panel.style.left = Math.max(12, Math.min(window.innerWidth - width - 12, frame.x - width / 2)) + 'px';
                        panel.style.top = Math.max(12, Math.min(window.innerHeight - height - 64, frame.y - height / 2)) + 'px';
                        const header = document.createElement('header');
                        const back = document.createElement('button'); back.textContent = 'Back'; back.title = 'Back (Esc)';
                        back.addEventListener('click', () => session.back());
                        const title = document.createElement('span'); title.textContent = entry.text;
                        const close = document.createElement('button'); close.textContent = 'Close';
                        close.addEventListener('click', () => session.close(true));
                        header.append(back, title, close);
                        const host = document.createElement('div'); host.className = 'markingWidgetBody'; host.style.height = height + 'px';
                        panel.append(header, host);
                        for (const type of ['mousedown','click','dblclick','wheel','keydown','keyup','contextmenu']) {
                                panel.addEventListener(type, event => event.stopPropagation());
                        }
                        document.body.append(panel); widgetPanel = panel;
                        try { releaseWidget = entry.mount(host); }
                        catch (error) { host.textContent = error.message || String(error); }
                        back.focus({preventScroll: true});
                        return;
                }
                const center = makeDivAt(stack.length > 1 ? 'Back' : '&nbsp;&nbsp;&nbsp;', frame.x, frame.y,
                        frame.x, frame.y, rr, event => {
                                if (event) handledMarkingMouseups.add(event);
                                if (activeMarkingMenu !== session || generation !== current) return false;
                                if (!level.ready) return true;
                                session.back(); return !closed;
                        });
                center.title = stack.length > 1 ? 'Back (Esc)' : 'Cancel (Esc)';
                center.dataset.markingBack = stack.length > 1 ? 'true' : 'false';
                level.center = center; liveDivs.push(center);
                drawCircle(ctx, frame.x, frame.y, rr, 'rgba(255,255,255,0.5)');
                for (const entry of frame.menus) {
                        const children = opensLevel(entry);
                        const [cx, cy] = polarToRect(frame.x, frame.y, (entry.radius ?? 1) * radius, entry.angle);
                        let div;
                        div = makeDivAt(entry.text + (children ? ' ›' : ''), cx, cy, frame.x, frame.y, rr, event => {
                                if (event) handledMarkingMouseups.add(event);
                                if (activeMarkingMenu !== session || generation !== current) return false;
                                if (!level.ready) return true;
                                if (children) { enter(div); return true; }
                                // Close before running the callback; it may itself open another menu.
                                session.close(false);
                                entry.callback?.(originatingEvent);
                                return false;
                        }, liveDivs);
                        div.markingEntry = entry;
                        div.dataset.markingDepth = String(stack.length - 1);
                        if (children) { div.dataset.markingSubmenu = 'true'; div.setAttribute('aria-haspopup', 'menu'); }
                        liveDivs.push(div);
                }
        }
        activeMarkingMenu = session;
        mainFinish = () => session.close(true);
        contextMenuFinish = mainFinish;
        missingMouseupHandler = event => {
                const current = generation;
                // interact.js can deliver dragend before the native mouseup. Let that mouseup
                // choose first, and only fall back if it never arrived for this menu level.
                setTimeout(() => {
                        if (activeMarkingMenu === session && generation === current) session.choose(event);
                }, 0);
        };
        canvas.style.pointerEvents = 'auto'; canvas.classList.add('fadeToBlack');
        live = true; show();
};

const closestOption = (x, y) => {
        let best = 100000;
        let bestOption = null;
        for (let a of document.getElementsByClassName("markingButton")) {
                //console.log(" considering ", a, a.optionIs);
                const r = a.getBoundingClientRect();
                const lx = r.x + r.width / 2;
                const ly = r.y + r.height / 2;
                const d = (x - lx) * (x - lx) + (y - ly) * (y - ly);
                if (d < best) {
                        best = d;
                        bestOption = a;
                }
        }
        return bestOption;
};

const overlaps = (rect1, rect2) => {
        let B = -10;
        return !(
            rect1.right - B < rect2.left ||
            rect1.left + B > rect2.right ||
            rect1.bottom - B < rect2.top ||
            rect1.top + B > rect2.bottom
        );
};
const checkAndResolveColisions = (divs, newDiv, rect, originx, originy) => {
        divs.forEach((d) => {
                let r = d.getBoundingClientRect();
                while (overlaps(r, rect)) {
                        //console.log(" collision detected ", r, rect);
                        rect.x =
                            (rect.x + rect.width / 2 - originx) * 1.25 + originx - rect.width / 2;
                        rect.y =
                            (rect.y + rect.height / 2 - originy) * 1.25 + originy - rect.height / 2;

                        newDiv.style.left = `${rect.x}px`;
                        newDiv.style.top = `${rect.y}px`;
                }
        });
        return [rect.x + rect.width / 2, rect.y + rect.height / 2];
};

let missingMouseupHandler = () => {
}

const provideMissingMouseup = (event) => {
        missingMouseupHandler(event)
}

const makeDivAt = (text, x, y, originx, originy, r, callbackUp, divs = []) => {
        const newDiv = document.createElement("div");
        newDiv.style.position = "absolute";
        newDiv.style.zIndex = 1000;
        newDiv.style.opacity = 0;
        newDiv.style.maxWidth = "400px";
        newDiv.style.visibility = "hidden";
        newDiv.style.transition = "opacity 0.5s";
        newDiv.style.left = `${x}px`;
        newDiv.classList = "markingButton";
        newDiv.style.top = `${y}px`;
        newDiv.innerHTML = text;
        newDiv.oncontextmenu = "return false;";
        document.body.appendChild(newDiv);

        let rect = newDiv.getBoundingClientRect();
        newDiv.style.left = `${x - rect.width / 2}px`;
        newDiv.style.top = `${y - rect.height / 2}px`;

        newDiv.style.visibility = "visible";
        rect = newDiv.getBoundingClientRect();

        if (divs.length > 0)
                [x, y] = checkAndResolveColisions(divs, newDiv, rect, originx, originy);

        if (true) {
                const backingDiv = document.createElement("div");
                backingDiv.style.position = "absolute";
                backingDiv.style.zIndex = 900;
                backingDiv.style.opacity = 0;
                backingDiv.style.maxWidth = "400px";
                backingDiv.style.visibility = "hidden";
                backingDiv.style.transition = "opacity 0.25s";
                let R = 20;
                backingDiv.style.left = `${x - R}px`;
                backingDiv.classList = "markingButtonBackground";
                backingDiv.style.border = "0px";

                backingDiv.style.top = `${y - R}px`;
                backingDiv.oncontextmenu = "return false;";
                document.body.appendChild(backingDiv);

                backingDiv.style.opacity = 1.0;
                backingDiv.style.left = `${rect.x - R/2}px`;
                backingDiv.style.top = `${rect.y - R/2}px`;
                backingDiv.style.width = `${rect.width + R}px`;
                backingDiv.style.height = `${rect.height + R}px`;

                backingDiv.style.visibility = "visible";
                newDiv.subDiv = backingDiv;
        }

        // The button's mouseup and the menu-wide finish path can both reach this option.
        let didFinish = false;
        newDiv.finish = (e) => {
                if (didFinish) return;
                didFinish = true;
                const keepOpen = callbackUp(e);
                if (keepOpen === true) didFinish = false;
                return keepOpen;
        };

        if (text.indexOf("<") == -1) {
                newDiv.addEventListener("mouseup", event => {
                        markLog("got a mouse up")
                        newDiv.finish(event)
                });
                newDiv.subDiv.addEventListener("mouseup", event => {
                        markLog("got a mouse up")
                        newDiv.finish(event)
                });
        }

        var [x1, y1] = [originx, originy];
        // drawCircle(ctx, x1,y1, r, "rgba(0,0,0,0.5)")
        var [x1, y1, x2, y2] = trimLineByRectangle(
            x1,
            y1,
            x,
            y,
            rect.x,
            rect.y,
            rect.width,
            rect.height,
        );
        var [x1, y1] = standOffLine(x1, y1, r, x2, y2);

        markLog("arrow segment", x1, y1, x2, y2);
        ctx.beginPath();
        ctx.moveTo(x1, y1);
        ctx.lineTo(x2, y2);
        ctx.strokeStyle = "rgba(255,255,255,0.5)";
        ctx.stroke();

        setTimeout(function () {
                newDiv.style.opacity = "1";
        }, 0);
        return newDiv;
};

// --------------- drawing utils

function trimLineByRectangle(x1, y1, x2, y2, rectX, rectY, rectW, rectH) {
        function lineIntersect(x1, y1, x2, y2, x3, y3, x4, y4) {
                let denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
                if (denom === 0) return null;

                let t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom;
                let u = -((x1 - x2) * (y1 - y3) - (y1 - y2) * (x1 - x3)) / denom;

                if (t >= 0 && t <= 1 && u >= 0 && u <= 1) {
                        return {
                                x: x1 + t * (x2 - x1),
                                y: y1 + t * (y2 - y1),
                        };
                }
                return null;
        }

        let edges = [
                {x1: rectX, y1: rectY, x2: rectX + rectW, y2: rectY},
                {
                        x1: rectX + rectW,
                        y1: rectY,
                        x2: rectX + rectW,
                        y2: rectY + rectH,
                },
                {x1: rectX, y1: rectY + rectH, x2: rectX + rectW, y2: rectY + rectH},
                {
                        x1: rectX,
                        y1: rectY,
                        x2: rectX,
                        y2: rectY + rectH,
                },
        ];

        for (let edge of edges) {
                let intersection = lineIntersect(
                    x1,
                    y1,
                    x2,
                    y2,
                    edge.x1,
                    edge.y1,
                    edge.x2,
                    edge.y2,
                );
                if (intersection) {
                        return [x1, y1, intersection.x, intersection.y];
                }
        }

        return [x1, y1, x2, y2];
}

function drawCircle(ctx, x, y, radius, fillColor) {
        ctx.beginPath(); // Start a new path
        ctx.arc(x, y, radius, 0, Math.PI * 2); // Draw a circle
        ctx.fillStyle = fillColor; // Set the fill color for the circle
        ctx.fill(); // Fill the circle with the specified color
}

function standOffLine(x1, y1, r, x2, y2) {
        let d = r / Math.sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1));
        return [x1 + (x2 - x1) * d, y1 + (y2 - y1) * d];
}

window.menuSupportHasFinishedImporting = true
