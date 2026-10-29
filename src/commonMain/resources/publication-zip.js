/** ZIP32 writer. Browser-native deflate compresses text; Blob parts avoid a second archive copy. */
const encoder = new TextEncoder();
const crcTable = Uint32Array.from({length: 256}, (_, n) => {
    for (let k = 0; k < 8; k++) n = (n >>> 1) ^ ((n & 1) ? 0xedb88320 : 0);
    return n >>> 0;
});
export function crc32(bytes) {
    let crc = 0xffffffff;
    for (const byte of bytes) crc = (crc >>> 8) ^ crcTable[(crc ^ byte) & 255];
    return (crc ^ 0xffffffff) >>> 0;
}
export async function zipFiles(files, {compress = true} = {}) {
    if (files.size > 65535) throw new Error('Too many files for a publication');
    const parts = [], directory = [];
    let offset = 0, directorySize = 0;
    for (const [path, value] of files) {
        if (path.startsWith('/') || /[\\\0]/.test(path) || path.split('/').some(p => !p || p === '..' || p === '.')) throw new Error(`Invalid archive path: ${path}`);
        const name = encoder.encode(path), bytes = typeof value === 'string' ? encoder.encode(value) : value;
        if (name.length > 65535 || offset + bytes.length > 0xffffffff) throw new Error('Publication exceeds ZIP32 limits');
        let stored = bytes, method = 0;
        if (compress && /\.(html|js|css|json|edn|md|svg)$/.test(path) && typeof CompressionStream !== 'undefined') {
            try {
                const stream = new Blob([bytes]).stream().pipeThrough(new CompressionStream('deflate-raw'));
                const deflated = new Uint8Array(await new Response(stream).arrayBuffer());
                if (deflated.length < bytes.length) { stored = deflated; method = 8; }
            } catch (_) { /* A browser without raw deflate still produces a valid stored ZIP. */ }
        }
        const local = new Uint8Array(30), lv = new DataView(local.buffer), crc = crc32(bytes);
        lv.setUint32(0, 0x04034b50, true); lv.setUint16(4, 20, true); lv.setUint16(6, 0x800, true);
        lv.setUint16(8, method, true);
        lv.setUint16(12, 33, true); // 1980-01-01, DOS date
        lv.setUint32(14, crc, true); lv.setUint32(18, stored.length, true); lv.setUint32(22, bytes.length, true);
        lv.setUint16(26, name.length, true);
        const central = new Uint8Array(46), cv = new DataView(central.buffer);
        cv.setUint32(0, 0x02014b50, true); cv.setUint16(4, 20, true); cv.setUint16(6, 20, true);
        cv.setUint16(8, 0x800, true); cv.setUint16(14, 33, true); cv.setUint32(16, crc, true);
        cv.setUint16(10, method, true);
        cv.setUint32(20, stored.length, true); cv.setUint32(24, bytes.length, true);
        cv.setUint16(28, name.length, true); cv.setUint32(42, offset, true);
        parts.push(local, name, stored); directory.push(central, name);
        offset += local.length + name.length + stored.length; directorySize += central.length + name.length;
    }
    if (offset + directorySize > 0xffffffff) throw new Error('Publication exceeds ZIP32 limits');
    const end = new Uint8Array(22), ev = new DataView(end.buffer);
    ev.setUint32(0, 0x06054b50, true); ev.setUint16(8, files.size, true); ev.setUint16(10, files.size, true);
    ev.setUint32(12, directorySize, true); ev.setUint32(16, offset, true);
    return new Blob([...parts, ...directory, end], {type:'application/zip'});
}
