
let canvas, ctx

const DEBUG_MARKING = false;
const markLog = (...args) => {
        if (DEBUG_MARKING) console.info(...args);
};

var originatingMouseDown = null

var masterMousedownCallbackTable = {}

const forceClearMarkingMenu = () => {
        try {
                document.querySelectorAll(".markingButton, .markingButtonBackground").forEach(el => {
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
                        if (live) {
                                let is = closestOption(event.x, event.y)
                                markLog("window move -- compute closest ", event.x,  event.y, is)
                                if (is)
                                {
                                        for(var d of liveDivs) {
                                                d.mouseIsOver = false
                                                d.classList.remove("markingHover")
                                        }
                                        is.mouseIsOver = true
                                        is.classList.add("markingHover")
                                }
                        }
                        else {
                                markLog("window mousemove, but not live ")
                        }
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
                        if (event.key === "Escape")
                                if (mainFinish) {
                                        mainFinish();
                                        event.stopPropagation();
                                }
                });

                window.addEventListener("mouseup", (event) => {
                        markLog("master mouseup event ", event, live)
                        if (live) {
                                const best = closestOption(event.x, event.y);
                                if (best && best.finish) {
                                        best.finish(event);
                                } else {
                                        missingMouseupHandler(event);
                                }
                        } else {
                                missingMouseupHandler(event);
                        }
                        // Force-clear UI even if finish callbacks fail or live is stale
                        forceClearMarkingMenu()
                })
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

const goMarkingMenu = (originx, originy, menus, originatingEvent, cancelCallback) => {
        let rr = 10;
        let r = 120;

        let debounce = Date.now();

        canvas.style.pointerEvents = "auto";
        canvas.classList.add("fadeToBlack")

        let finish = (selected) => {
                let foundOver = false
                live = false
                divs.forEach((d) => {
                        if (d.mouseIsOver) {
                                markLog("found element with mouse over, calling finish on it")
                                mainFinish = null
                                if (d.finish) {
                                        was = d.finish
                                        d.finish = null
                                        was()
                                }
                                d.mouseIsOver = false
                                foundOver = true
                        } else {

                        }
                        if (d === selected) {
                                setTimeout(() => {
                                        if (document.body.contains(d)) document.body.removeChild(d);
                                        if (d.subDiv)
                                                if (document.body.contains(d.subDiv))
                                                        document.body.removeChild(d.subDiv);
                                }, 250);
                        } else {
                                if (document.body.contains(d)) document.body.removeChild(d);
                                if (d.subDiv) {
                                        if (document.body.contains(d.subDiv))
                                                document.body.removeChild(d.subDiv);
                                }
                        }
                });
                ctx.clearRect(0, 0, canvas.width, canvas.height);
                canvas.style.pointerEvents = "none";
                canvas.classList.remove("fadeToBlack")
                contextMenuFinish = null;
                mainFinish = null;
                live = false;
                if (!foundOver) {
                        if (cancelCallback) cancelCallback()
                }
                forceClearMarkingMenu()
        };
        mainFinish = finish;

        if (contextMenuFinish) {
                contextMenuFinish();
        }

        contextMenuFinish = finish;

        missingMouseupHandler = () => {
                if (mainFinish != null) {
                        let was = mainFinish
                        mainFinish = null
                        was()
                        live = false
                        forceClearMarkingMenu()
                }
        }

        let divs = [];

        drawCircle(ctx, originx, originy, rr, "rgba(255,255,255,0.5)");
        let d = makeDivAt(
            "&nbsp;&nbsp;&nbsp;",
            originx,
            originy,
            originx,
            originy,
            rr,
            (event) => {
                    markLog(" -- main finish on center")
                    if (mainFinish) mainFinish(null);
                    cancelCallback()
                    live = false
            },
        );
        d.title = "Cancel (Esc)";
        divs.push(d);

        for (let m of menus) {
                let [cx, cy] = polarToRect(originx, originy, (m.radius ?? 1) * r, m.angle);
                let d = makeDivAt(
                    m.text,
                    cx,
                    cy,
                    originx,
                    originy,
                    rr,
                    (event) => {

                            m.callback(originatingEvent);

                            if (mainFinish) mainFinish(d);
                            live = false

                    },
                    divs,
                );
                divs.push(d);
        }
        live = true
        liveDivs = divs
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

        newDiv.addEventListener("mouseover", () => {
                newDiv.mouseIsOver = true;
                newDiv.classList.add("markingHover")
        });

        newDiv.subDiv.addEventListener("mouseover", () => {
                newDiv.mouseIsOver = true;
                newDiv.classList.add("markingHover")
        });

        newDiv.addEventListener("mouseout", () => {
                newDiv.mouseIsOver = false;
                newDiv.classList.remove("markingHover")
        });

        newDiv.subDiv.addEventListener("mouseout", () => {
                newDiv.mouseIsOver = false;
                newDiv.classList.remove("markingHover")
        });

        newDiv.finish = (e) => {
                callbackUp(e);
        };

        if (text.indexOf("<") == -1) {
                newDiv.addEventListener("mouseup", () => {
                        markLog("got a mouse up")
                        newDiv.finish()
                });
                newDiv.subDiv.addEventListener("mouseup", () => {
                        markLog("got a mouse up")
                        newDiv.finish()
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
