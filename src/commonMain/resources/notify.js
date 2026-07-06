
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
                let base = 10;
                const startup = document.querySelector(".field-startup-progress:not(.field-startup-progress-done)");
                if (startup) {
                        base += startup.getBoundingClientRect().height + 6;
                }
                for (const node of bubbles) {
                        if (!document.body.contains(node)) continue;
                        const rect = node.getBoundingClientRect();
                        node.style.bottom = `${base}px`;
                        base += rect.height + 6;
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
        let updatingExisting = false;
        if (categoryDiv) {
                newDiv = categoryDiv;
                lastDiv = newDiv;
                newDiv.cat = cat;
                if (newDiv.innerHTML === htmlText) return;
                updatingExisting = true;
        } else {
                newDiv = document.createElement("div");
                lastDiv = newDiv;
                lastDiv.cat = cat;
        }

        newDiv.className = "bubble field-toast";
        newDiv.innerHTML = htmlText;
        updateNotifyTimeAgo(newDiv);
        ensureNotifyTimeAgoTimer();
        if (!document.body.contains(newDiv)) {
                document.body.appendChild(newDiv);
        }
        stopNotifySpinnersForCategory(cat, newDiv);

        let q = document.getElementsByClassName("bubble").length;
        for (let a of document.getElementsByClassName("bubble")) {
                if (a !== newDiv) {
                        a.classList.add("field-toast-stale");
                }

                        if (q >= 5) {
                                a.classList.add("field-toast-exit");
                                setTimeout(function () {
                                if (a.parentNode)
                                        a.parentNode.removeChild(a)
                                restackBubbles();
                        }, 220)
                        q--;
                }
        }

        newDiv.classList.remove("field-toast-stale", "field-toast-exit");
        restackBubbles();
        setTimeout(function () {
                newDiv.classList.add("field-toast-visible");
        }, 20);
        setTimeout(function () {
                if (updatingExisting) {
                        newDiv.classList.add("field-toast-stale");
                }
        }, 1000);
};
