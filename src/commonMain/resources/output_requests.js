// Output-iframe request/reply over the same /ws transport as Field's viewers and inspectors.
// A lost connection never silently replays a possibly accepted edit.
export function outputRequests(binding, {base = document.baseURI, timeout = 30000} = {}) {
    const url = new URL('/ws', base);
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    const pending = new Map();
    let socket = null, connecting = null, closed = false, serial = 0;
    function fail(error) {
        for (const p of pending.values()) { clearTimeout(p.timer); p.reject(error); }
        pending.clear();
    }
    function connect() {
        if (closed) return Promise.reject(Error('Output connection is closed'));
        if (socket?.readyState === WebSocket.OPEN) return Promise.resolve(socket);
        if (connecting) return connecting;
        connecting = new Promise((resolve, reject) => {
            const ws = new WebSocket(url); socket = ws;
            const timer = setTimeout(() => { reject(Error('Field connection timed out')); ws.close(); }, timeout);
            ws.addEventListener('open', () => { clearTimeout(timer); connecting = null; resolve(ws); });
            ws.addEventListener('message', event => {
                let message; try { message = JSON.parse(event.data); } catch { return; }
                const p = pending.get(message.name); if (!p) return;
                pending.delete(message.name); clearTimeout(p.timer);
                const result = message.payload;
                if (result?.ok) p.resolve(result.data);
                else p.reject(Error(result?.error || 'Field request failed'));
            });
            ws.addEventListener('error', () => ws.close());
            ws.addEventListener('close', () => {
                clearTimeout(timer);
                const current = socket === ws;
                if (current) { socket = null; connecting = null; }
                const error = Error('Field connection lost. Check the current state before retrying an edit.');
                reject(error); if (current) fail(error);
            });
        });
        return connecting;
    }
    return {
        async request(data = null) {
            const ws = await connect();
            return new Promise((resolve, reject) => {
                const replyTo = `output-reply-${++serial}`;
                const timer = setTimeout(() => {
                    pending.delete(replyTo);
                    reject(Error('Field request timed out. Check the current state before retrying an edit.'));
                }, timeout);
                pending.set(replyTo, {resolve, reject, timer});
                try { ws.send(JSON.stringify({name: 'output-request', replyTo, payload: {binding, data}})); }
                catch (error) { pending.delete(replyTo); clearTimeout(timer); reject(error); }
            });
        },
        close() { closed = true; fail(Error('Output connection is closed')); socket?.close(); }
    };
}
