// One shared poller for every visible AI4 chat and Welcome's Local panel.
const hosts = new Set();
let running = false;

function finite(value) { return typeof value === "number" && Number.isFinite(value); }

export function feedbackText(data, now = Date.now()) {
    if (!data?.enabled) return null;
    if (!data.available) return "Local · live status unavailable";
    const sample = data.sample;
    if (!sample || !finite(sample.sampledAtMs) || now - sample.sampledAtMs > 12000) {
        return "Local · status stale";
    }
    const states = {sleeping: "Sleeping", loading: "Loading model", ready: "Ready", working: "Processing request",
        generating: "Generating", unavailable: "Model status unavailable"};
    const parts = ["Local", states[sample.state] || "Status unknown"];
    if (sample.requestsQueued > 0) parts.push(`${sample.requestsQueued} queued`);
    if (sample.state === "generating" && finite(sample.generationTokensPerSecond)) {
        parts.push(`${sample.generationTokensPerSecond.toFixed(1)} tok/s`);
    }
    for (const gpu of sample.gpus || []) {
        const stats = [];
        if (finite(gpu.utilizationPercent)) stats.push(`${Math.round(gpu.utilizationPercent)}% load`);
        if (finite(gpu.temperatureC)) stats.push(`${Math.round(gpu.temperatureC)}°C`);
        if (finite(gpu.memoryUsedMiB) && finite(gpu.memoryTotalMiB)) {
            stats.push(`${(gpu.memoryUsedMiB / 1024).toFixed(1)}/${(gpu.memoryTotalMiB / 1024).toFixed(1)} GiB VRAM`);
        }
        if (stats.length) parts.push(`GPU ${gpu.index}: ${stats.join(" · ")}`);
    }
    if (sample.gpuUnavailable) parts.push("GPU stats unavailable");
    if (finite(sample.contextCapacity)) parts.push(`${Math.round(sample.contextCapacity / 1024)}K context window`);
    return parts.join(" · ");
}

async function poll() {
    for (const host of hosts) if (!host.isConnected) hosts.delete(host);
    if (!hosts.size) { running = false; return; }
    const visible = [...hosts].filter(host => !document.hidden && host.parentElement?.getClientRects().length);
    if (visible.length) {
        let data;
        try {
            const response = await fetch("/api/ai-local-feedback", {cache: "no-store", signal: AbortSignal.timeout(4500)});
            if (!response.ok) throw new Error("Status unavailable");
            data = await response.json();
        } catch (_) {
            // Do not reveal a Local indicator for cloud providers on a failed first probe.
            data = {enabled: visible.some(host => host.dataset.localEnabled === "true"), available: false};
        }
        const text = feedbackText(data);
        for (const host of hosts) {
            host.dataset.localEnabled = String(!!data.enabled);
            host.hidden = text === null;
            host.textContent = text || "";
            host.title = "GPU readings cover the inference machine's whole GPU, including other applications. Model activity covers all clients. Context window is capacity, not current usage.";
        }
    }
    setTimeout(poll, 3000);
}

export function mountLocalAiFeedback(parent) {
    const host = document.createElement("div");
    host.className = "local-ai-feedback";
    host.hidden = true;
    host.setAttribute("role", "status");
    host.style.cssText = "font-size:11px;line-height:1.6;opacity:.75;padding:4px 0;flex:none;overflow-wrap:anywhere";
    parent.appendChild(host);
    hosts.add(host);
    if (!running) { running = true; setTimeout(poll, 0); }
    return host;
}
