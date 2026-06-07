
var lastDiv = null;

function notifyCategoryKey(cat) {
        if (!cat) return "";
        return cat.startsWith("*") ? cat.substring(1) : cat;
}

function sameNotifyCategory(a, b) {
        const aKey = notifyCategoryKey(a);
        const bKey = notifyCategoryKey(b);
        return aKey.length > 0 && aKey === bKey;
}

function formatNotifyAgo(ms) {
        const secs = Math.max(0, Math.floor(ms / 1000));
        if (secs < 60) return `${secs}s`;
        const mins = Math.floor(secs / 60);
        const rems = secs % 60;
        if (mins < 60) return `${mins}m ${rems}s`;
        const hours = Math.floor(mins / 60);
        const remm = mins % 60;
        if (hours < 24) return `${hours}h ${remm}m`;
        const days = Math.floor(hours / 24);
        const remh = hours % 24;
        return `${days}d ${remh}h`;
}

function updateNotifyTimeAgo(root = document) {
        const nodes = root.querySelectorAll(".notify-timeago");
        if (!nodes || nodes.length === 0) return;
        const now = Date.now();
        nodes.forEach((node) => {
                const raw = node.getAttribute("data-time");
                if (!raw) return;
                const ts = parseInt(raw, 10);
                if (Number.isNaN(ts)) return;
                const date = new Date(ts);
                const time = date.toLocaleTimeString([], { hour12: false });
                const ago = formatNotifyAgo(now - ts);
                node.textContent = `${time} (${ago} ago)`;
        });
}

function ensureNotifyTimeAgoTimer() {
        if (window.notifyTimeAgoTimer) return;
        window.notifyTimeAgoTimer = setInterval(() => {
                updateNotifyTimeAgo(document);
        }, 1000);
}

function stopNotifySpinnersForCategory(cat, exceptNode) {
        if (!cat) return;
        const bubbles = Array.from(document.getElementsByClassName("bubble"));
        for (const bubble of bubbles) {
                if (bubble === exceptNode) continue;
                if (!sameNotifyCategory(bubble.cat, cat)) continue;
                const spinners = bubble.querySelectorAll(".notify-spinner");
                spinners.forEach((spinner) => {
                        spinner.style.animation = "none";
                });
        }
}

var notify = (text, cat = "") => {
        const htmlText = text
                .replace(/<<spinner>>/g, '<span class="notify-spinner"></span>')
                .replace(/<<timeago:(\d+)>>/g, '<span class="notify-timeago" data-time="$1"></span>');

        function restackBubbles() {
                const bubbles = Array.from(document.getElementsByClassName("bubble"));
                if (bubbles.length === 0) return;
                const items = bubbles.map(node => {
                        const style = window.getComputedStyle(node);
                        const raw = node.style.bottom || style.bottom || "0px";
                        const bottom = parseFloat(raw) || 0;
                        return {node, bottom};
                });
                items.sort((a, b) => a.bottom - b.bottom);
                let base = 5;
                for (let item of items) {
                        const rect = item.node.getBoundingClientRect();
                        item.node.style.left = "5px";
                        item.node.style.bottom = `${base}px`;
                        base += rect.height + 1;
                }
        }

        const purgeCategory = cat && cat.startsWith("*");
        const bubblesForCategory = cat
                ? Array.from(document.getElementsByClassName("bubble")).filter((bubble) => sameNotifyCategory(bubble.cat, cat))
                : [];
        let categoryDiv = null;
        if (lastDiv && sameNotifyCategory(lastDiv.cat, cat) && document.body.contains(lastDiv)) {
                categoryDiv = lastDiv;
        } else if (bubblesForCategory.length > 0) {
                categoryDiv = bubblesForCategory[bubblesForCategory.length - 1];
        }
        if (purgeCategory) {
                for (let bubble of bubblesForCategory) {
                        if (bubble !== categoryDiv) {
                                if (bubble.parentNode) {
                                        bubble.parentNode.removeChild(bubble);
                                }
                        }
                }
                restackBubbles();
        }
        if (lastDiv && sameNotifyCategory(lastDiv.cat, cat) && !document.body.contains(lastDiv)) {
                lastDiv = null;
        }

        let newDiv;
        let noScroll = false;
        if (categoryDiv) {
                newDiv = categoryDiv;
                lastDiv = newDiv;
                newDiv.cat = cat;
                if (newDiv.innerHTML === htmlText) return;
                noScroll = true;
        } else {
                newDiv = document.createElement("div");
                lastDiv = newDiv;
                lastDiv.cat = cat;
                noScroll = false;
        }

        newDiv.style.position = "absolute";
        newDiv.style.zIndex = 100;
        if (!noScroll) newDiv.style.opacity = 0;
        else {
                newDiv.innerHTML = htmlText;
                updateNotifyTimeAgo(newDiv);
                ensureNotifyTimeAgoTimer();
                newDiv.style.opacity = 1;
                newDiv.style.color = "var(--field-cyan)";
                stopNotifySpinnersForCategory(cat, newDiv);
                restackBubbles();
                setTimeout(function () {
                        newDiv.style.opacity = "0.5";
                        newDiv.style.color = "rgba(255, 255, 255, 1)";
                }, 1000);
                return;
        }

        newDiv.style.maxWidth = "400px";
        newDiv.style.visibility = "hidden";
        newDiv.style.transition = "all 0.5s";
        newDiv.style.left = "5px";
        newDiv.classList = "bubble";
        newDiv.style.bottom = "5px";
        newDiv.style.color = "var(--field-cyan)";
        newDiv.innerHTML = htmlText;
        updateNotifyTimeAgo(newDiv);
        ensureNotifyTimeAgoTimer();
        newDiv.style.border = "1px solid black";
        document.body.appendChild(newDiv);
        stopNotifySpinnersForCategory(cat, newDiv);

        const rect = newDiv.getBoundingClientRect();

        const n = 0;
        let q = document.getElementsByClassName("bubble").length;
        for (let a of document.getElementsByClassName("bubble")) {
                if (!noScroll)
                        if (a !== newDiv) {
                                a.style.bottom =
                                    parseInt(a.style.bottom.replace("px", "")) + rect.height + 1 + "px";
                                a.style.border = "none";
                                a.style.opacity = 0.3;
                        }

                if (q >= 5) {
                        a.style.visibility = "hidden";
                        setTimeout(function () {
                                if (a.parentNode)
                                        a.parentNode.removeChild(a)
                        }, 1000)
                        q--;
                }
        }

        newDiv.style.visibility = "visible";
        restackBubbles();
        setTimeout(function () {
                newDiv.style.opacity = "1";
        }, 20);
        setTimeout(function () {
                newDiv.style.opacity = "0.5";
                newDiv.style.color = "rgba(255, 255, 255, 1)";
        }, 1000);
};
