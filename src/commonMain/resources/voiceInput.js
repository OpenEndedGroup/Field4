// Push-to-talk into AI4 chat.
//
// Hold the push-to-talk key (anywhere), or hold a chat's microphone button, and speak. The key is
// Right Option on macOS and Right Ctrl elsewhere: on Windows and on most non-US Linux layouts Right
// Alt is AltGr, which types characters. Set another with
// `localStorage.setItem("fieldVoiceKey", "<KeyboardEvent.code>")`, e.g. "F13" or "Pause". On release the clip
// goes to the server as `ai-transcribe` (local Whisper large-v3-turbo, `VoiceTranscriber.kt`), the
// transcript lands in the target chat and is sent as a dictated prompt, which tells the model to
// repair misheard identifiers from context.
//
// The target is `aiChatVoiceTarget()` in outputareas2.js: the last selected box with a chat, or the
// last chat used. Release with Shift held to put the transcript in the box without sending it.
//
// Whisper is primed with the conversation's own vocabulary (identifiers from the chat and the box's
// code, box names, and anything listed in localStorage `fieldVoiceVocabulary`), which is what turns
// "from context" into `fromContext` before the model ever sees it.

import { sendWithReply } from "./afield.mjs";

const SAMPLE_RATE = 16000;
const MIN_SECONDS = 0.3;          // shorter than this is a tap, not speech
const TAIL_MS = 150;              // keep recording briefly after release so the last word isn't clipped
const KEEP_MIC_OPEN_MS = 60000;   // reuse the stream for quick follow-ups, then release the mic
const IS_MAC = /Mac/.test(navigator.platform || navigator.userAgent);
const IS_WINDOWS = /Win/.test(navigator.platform || navigator.userAgent);
const HOTKEY_CODE = localStorage.getItem("fieldVoiceKey") || (IS_MAC ? "AltRight" : "ControlRight");
const MODIFIER_KEYS = new Set(["Shift", "Control", "Alt", "AltGraph", "Meta", "OS"]);
const HOTKEY_MIN_HOLD_MS = 250;

const WORKLET = `
class FieldVoiceCapture extends AudioWorkletProcessor {
  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (channel) this.port.postMessage(channel.slice(0));
    return true;
  }
}
registerProcessor("field-voice-capture", FieldVoiceCapture);
`;

const state = {
        stream: null,
        context: null,
        node: null,
        source: null,
        chunks: [],
        recording: false,
        target: null,
        releaseTimer: 0,
        warmed: false,
        hotkeyDownAt: 0,
        hotkeyChorded: false
};

function notify(message) {
        window.notify?.(message, "AI4 voice");
}

function setMicState(target, recording, transcribing) {
        const chat = target?.__aiChat;
        if (!chat) return;
        chat.micButton?.classList.toggle("recording", !!recording);
        chat.micButton?.classList.toggle("transcribing", !!transcribing);
        chat.root?.classList.toggle("ai-chat-voice-target", !!(recording || transcribing));
}

async function openMic() {
        window.clearTimeout(state.releaseTimer);
        if (state.stream && state.context) {
                if (state.context.state === "suspended") await state.context.resume();
                return;
        }
        const access = await window.fieldElectron?.microphoneAccess?.();
        if (access === "denied" || access === "restricted") {
                throw new Error("macOS is blocking Field4 from the microphone. Turn it on in System Settings → Privacy & Security → Microphone, then restart Field4.");
        }
        try {
                state.stream = await navigator.mediaDevices.getUserMedia({
                        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true }
                });
        } catch (error) {
                throw new Error(microphoneHelp(error));
        }
        state.context = new AudioContext({ sampleRate: SAMPLE_RATE });
        const url = URL.createObjectURL(new Blob([WORKLET], { type: "application/javascript" }));
        try {
                await state.context.audioWorklet.addModule(url);
        } finally {
                URL.revokeObjectURL(url);
        }
        state.source = state.context.createMediaStreamSource(state.stream);
        state.node = new AudioWorkletNode(state.context, "field-voice-capture");
        state.node.port.onmessage = (event) => {
                if (state.recording) state.chunks.push(event.data);
        };
        state.source.connect(state.node);
}

function microphoneHelp(error) {
        const name = error?.name || "";
        if (name === "NotFoundError" || name === "OverconstrainedError") {
                return IS_WINDOWS
                        ? "no microphone found. Check Settings → System → Sound → Input."
                        : IS_MAC ? "no microphone found. Check System Settings → Sound → Input."
                        : "no microphone found. Check that PipeWire/PulseAudio lists an input (`pactl list short sources`).";
        }
        if (name === "NotReadableError") {
                return "the microphone is in use or failed to start. Close other apps using it and try again.";
        }
        if (name !== "NotAllowedError") return error?.message || String(error);
        if (!window.fieldElectron) {
                return "this browser refused the microphone. Allow it for this site (the address-bar permission icon), or use the Field4 app.";
        }
        if (IS_WINDOWS) {
                return "Windows refused the microphone. Turn on Settings → Privacy & security → Microphone → \"Let desktop apps access your microphone\".";
        }
        if (IS_MAC) {
                return "the microphone was refused. If Field4 was started from a terminal, macOS may be asking on that terminal's behalf: check System Settings → Privacy & Security → Microphone.";
        }
        return "the microphone was refused. In a Flatpak or Snap, grant the app audio input; otherwise check that PipeWire/PulseAudio is running.";
}

function scheduleMicRelease() {
        window.clearTimeout(state.releaseTimer);
        state.releaseTimer = window.setTimeout(() => {
                if (state.recording) return;
                state.stream?.getTracks().forEach((track) => track.stop());
                state.context?.close();
                state.stream = state.context = state.node = state.source = null;
        }, KEEP_MIC_OPEN_MS);
}

function warmWorker() {
        if (state.warmed) return;
        state.warmed = true;
        sendWithReply("ai-transcribe-warm", {}, `ai-transcribe-warm-${Date.now()}`, (_message, response) => {
                if (response && !response.success) {
                        state.warmed = false;
                        notify(response.message || "Voice input is unavailable");
                }
                return "remove";
        });
}

async function start(target) {
        if (state.recording) return;
        target = target || window.aiChatVoiceTarget?.();
        if (!target) {
                notify("Select a box to talk to AI4");
                return;
        }
        state.target = target;
        state.chunks = [];
        state.recording = true;
        setMicState(target, true, false);
        warmWorker();
        try {
                await openMic();
        } catch (error) {
                state.recording = false;
                setMicState(target, false, false);
                notify(`Microphone unavailable: ${error?.message || error}`);
        }
}

function cancel() {
        if (!state.recording) return;
        state.recording = false;
        state.chunks = [];
        setMicState(state.target, false, false);
        scheduleMicRelease();
}

function stop(options = {}) {
        if (!state.recording) return;
        const target = state.target;
        window.setTimeout(() => {
                state.recording = false;
                scheduleMicRelease();
                const samples = concatenate(state.chunks);
                state.chunks = [];
                if (samples.length < MIN_SECONDS * SAMPLE_RATE) {
                        setMicState(target, false, false);
                        return;
                }
                transcribe(target, samples, options);
        }, TAIL_MS);
}

function concatenate(chunks) {
        const length = chunks.reduce((total, chunk) => total + chunk.length, 0);
        const out = new Float32Array(length);
        let offset = 0;
        for (const chunk of chunks) {
                out.set(chunk, offset);
                offset += chunk.length;
        }
        return out;
}

function base64OfFloats(samples) {
        // Float32Array is native-endian; every platform Field runs on is little-endian.
        const bytes = new Uint8Array(samples.buffer, samples.byteOffset, samples.byteLength);
        let binary = "";
        for (let i = 0; i < bytes.length; i += 0x8000) {
                binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
        }
        return btoa(binary);
}

// Identifiers worth priming Whisper with: camelCase, PascalCase with an inner capital, snake_case,
// calls (`name(`), and backticked spans. Recent and frequent first, capped well inside Whisper's
// ~224-token prompt.
function vocabularyFor(target) {
        const context = window.aiChatVoiceContext?.(target) || { history: "", code: "", boxNames: [] };
        const scores = new Map();
        const bump = (word, weight) => {
                if (!word || word.length < 3 || word.length > 40) return;
                scores.set(word, (scores.get(word) || 0) + weight);
        };
        const identifier = /\b([A-Za-z_][A-Za-z0-9_]*)\b(\s*\()?/g;
        const codeish = (word, call) =>
                call || /[a-z][A-Z]/.test(word) || /[A-Z][a-z]+[A-Z]/.test(word) || /_/.test(word);
        const scan = (text, weight) => {
                for (const match of text.matchAll(/`([^`\n]{2,40})`/g)) bump(match[1].replace(/\(\)$/, ""), weight * 2);
                for (const match of text.matchAll(identifier)) {
                        if (codeish(match[1], !!match[2])) bump(match[1], weight);
                }
        };
        const history = context.history.slice(-8000);
        const half = Math.floor(history.length / 2);
        scan(history.slice(0, half), 1);
        scan(history.slice(half), 2);   // the recent half of the conversation counts double
        scan((context.code || "").slice(0, 20000), 1);
        for (const name of context.boxNames.slice(0, 40)) bump(name.trim(), 1);
        const custom = (localStorage.getItem("fieldVoiceVocabulary") || "")
                .split(/[,\n]/).map((word) => word.trim()).filter(Boolean);
        const ranked = [...scores.entries()].sort((a, b) => b[1] - a[1]).map(([word]) => word);
        const words = [...new Set([...custom, ...ranked])].slice(0, 60);
        return words.length ? `Field, Kotlin, AI4. ${words.join(", ")}.` : "";
}

function transcribe(target, samples, options) {
        setMicState(target, false, true);
        const request = { pcm: base64OfFloats(samples), sampleRate: SAMPLE_RATE, vocabulary: vocabularyFor(target) };
        sendWithReply("ai-transcribe", request, `ai-transcribe-${Date.now()}-${Math.random()}`, (_message, response) => {
                setMicState(target, false, false);
                if (!response?.success) {
                        notify(response?.message || "Transcription failed");
                        return "remove";
                }
                deliver(target, (response.text || "").trim(), options);
                return "remove";
        });
}

function deliver(target, text, options) {
        const chat = target?.__aiChat;
        if (!text || !chat) return;
        const textarea = chat.textarea;
        const existing = textarea.value.trim();
        textarea.value = existing ? `${existing} ${text}` : text;
        if (options.insertOnly || typeof chat.submit !== "function") {
                textarea.focus();
                textarea.setSelectionRange(textarea.value.length, textarea.value.length);
                return;
        }
        chat.submit({ dictated: true });
}

// ---- hotkey: hold Right Option ----------------------------------------------
//
// Recording starts on key-down so the first word isn't lost. Any other non-modifier key pressed while
// it is held means it was a chord (Option-letter, Ctrl-C), so the take is dropped; so is a tap
// shorter than HOTKEY_MIN_HOLD_MS. Modifiers alone never cancel: Windows sends a synthetic Left Ctrl
// with AltGr, and Shift is how you ask for insert-without-sending.

window.addEventListener("keydown", (event) => {
        if (event.code === HOTKEY_CODE) {
                if (event.repeat || state.hotkeyDownAt) return;
                state.hotkeyDownAt = performance.now();
                state.hotkeyChorded = false;
                start();
                return;
        }
        if (state.hotkeyDownAt && !MODIFIER_KEYS.has(event.key)) {
                state.hotkeyChorded = true;
                cancel();
        }
}, true);

window.addEventListener("keyup", (event) => {
        if (event.code !== HOTKEY_CODE || !state.hotkeyDownAt) return;
        const held = performance.now() - state.hotkeyDownAt;
        state.hotkeyDownAt = 0;
        if (state.hotkeyChorded || held < HOTKEY_MIN_HOLD_MS) {
                cancel();
                return;
        }
        event.preventDefault();
        stop({ insertOnly: event.shiftKey });
}, true);

window.addEventListener("blur", () => {
        state.hotkeyDownAt = 0;
        cancel();
});

window.fieldVoice = {
        start,
        stop: (options) => stop(options || {}),
        cancel,
        vocabularyFor,
        hotkeyLabel: { AltRight: "Right Option", ControlRight: "Right Ctrl" }[HOTKEY_CODE] || HOTKEY_CODE
};
