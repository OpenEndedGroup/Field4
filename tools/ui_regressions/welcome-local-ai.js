// Exercise real welcome-page controls and model-discovery failure without saving user settings.
const waitFor = async (check, message) => {
    for (let i = 0; i < 100; i++) {
        if (check()) return;
        await new Promise(resolve => setTimeout(resolve, 100));
    }
    throw new Error(message);
};
const check = (value, message) => { if (!value) throw new Error(message); };
try {
    const frame = document.createElement("iframe");
    frame.src = "/welcome";
    frame.style.cssText = "position:fixed;inset:0;width:100%;height:100%;border:0;z-index:2147483000";
    document.body.appendChild(frame);
    await waitFor(() => frame.contentDocument?.getElementById("ai-settings-summary") &&
        !/checking/i.test(frame.contentDocument.getElementById("ai-settings-summary").textContent), "Welcome did not load");
    const d = frame.contentDocument;
    const panel = d.getElementById("ai-settings-details");
    panel.open = true;
    d.querySelector('[data-ai-provider="local"]').click();
    const endpoint = d.getElementById("local-ai-base-url");
    const model = d.getElementById("ai-flagship-model");
    endpoint.value = "http://127.0.0.1:1/v1";
    model.value = "qwen3.8-27b";
    endpoint.dispatchEvent(new Event("input", {bubbles: true}));
    model.dispatchEvent(new Event("input", {bubbles: true}));
    await frame.contentWindow.loadWelcomeDocs();
    check(!d.getElementById("local-ai-controls").hidden, "Refresh discarded the selected Local provider");
    check(endpoint.value === "http://127.0.0.1:1/v1" && model.value === "qwen3.8-27b", "Refresh overwrote a draft");
    check(d.getElementById("openai-platform-key").closest(".ai-settings-controls").hidden, "Local asks for a cloud key");
    d.querySelector('[data-ai-provider="openrouter"]').click();
    d.querySelector('[data-ai-provider="local"]').click();
    check(model.value === "qwen3.8-27b", "Switching providers lost the local model draft");
    model.value = "";
    d.getElementById("save-ai-settings").click();
    check(d.getElementById("ai-settings-status").textContent.includes("Enter the local"), "Missing-model save was not rejected");
    model.value = "qwen3.8-27b";
    d.getElementById("check-local-ai").click();
    await waitFor(() => d.getElementById("local-ai-status").textContent.includes("Could not reach"), "Connection failure was not shown");
    check(model.value === "qwen3.8-27b", "Failed connection cleared the model");
    panel.scrollIntoView({block: "start"});
    return {ok: true, checks: ["local controls", "optional key", "drafts survive refresh", "provider switch", "required model", "connection failure"]};
} catch (error) {
    return {ok: false, reason: String(error?.message || error)};
}
