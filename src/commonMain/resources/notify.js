
var lastDiv = null;

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

var notify = (text, cat = "") => {
        if (!document.getElementById("notify-spinner-style")) {
                const style = document.createElement("style");
                style.id = "notify-spinner-style";
                style.textContent = `
.notify-spinner {
        display: inline-block;
        width: 8px;
        height: 8px;
        margin-left: 6px;
        border-radius: 50%;
        background: #f3a24f;
        box-shadow: 0 0 6px rgba(243, 162, 79, 0.9);
        animation: notifyPulse 1s ease-in-out infinite;
        vertical-align: middle;
}
@keyframes notifyPulse {
        0% { transform: scale(0.7); opacity: 0.4; }
        80% { transform: scale(1.2); opacity: 1; }
        100% { transform: scale(0.7); opacity: 0.4; }
}
                `;
                document.head.appendChild(style);
        }

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
        if (purgeCategory) {
                const bubbles = Array.from(document.getElementsByClassName("bubble"));
                for (let bubble of bubbles) {
                        if (bubble.cat === cat && bubble !== lastDiv) {
                                if (bubble.parentNode) {
                                        bubble.parentNode.removeChild(bubble);
                                }
                        }
                }
                restackBubbles();
        }
        if (lastDiv && lastDiv.cat === cat && !document.body.contains(lastDiv)) {
                lastDiv = null;
        }

        let newDiv;
        let noScroll = false;
        if (lastDiv && lastDiv.cat && lastDiv.cat.length > 0 && lastDiv.cat === cat) {
                newDiv = lastDiv;
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
                restackBubbles();
                return;
        }

        newDiv.style.maxWidth = "400px";
        newDiv.style.visibility = "hidden";
        newDiv.style.transition = "all 0.5s";
        newDiv.style.left = "5px";
        newDiv.classList = "bubble";
        newDiv.style.bottom = "5px";
        newDiv.innerHTML = htmlText;
        updateNotifyTimeAgo(newDiv);
        ensureNotifyTimeAgoTimer();
        newDiv.style.border = "1px solid black";
        document.body.appendChild(newDiv);

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
        }, 1000);
};
