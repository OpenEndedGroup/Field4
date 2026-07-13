import Foundation

/// field-encode — the Mac (VideoToolbox) sender co-process. Reads newline-delimited JSON
/// commands on stdin, streams fragmented HEVC/H.264 over UDP, emits JSON events on stdout.
/// See WIRE_FORMAT.md for the protocol. A JVM `ProcessBuilder` is the intended driver.

func log(_ message: String) {
    FileHandle.standardError.write(Data("field-encode: \(message)\n".utf8))
}

let engine = Engine()

// Zero-stdin smoke test:
//   field-encode --selftest --dest 127.0.0.1:9000 [--frames 120 --w 1280 --h 720 --fps 60 --kbps 20000 --codec hevc]
let argv = CommandLine.arguments
if argv.contains("--selftest") {
    func arg(_ flag: String, _ fallback: String) -> String {
        if let i = argv.firstIndex(of: flag), i + 1 < argv.count { return argv[i + 1] }
        return fallback
    }
    let test = Command.SelfTest(
        width: Int(arg("--w", "1280")) ?? 1280,
        height: Int(arg("--h", "720")) ?? 720,
        fps: Int(arg("--fps", "60")) ?? 60,
        bitrateKbps: Int(arg("--kbps", "20000")) ?? 20000,
        dest: arg("--dest", "127.0.0.1:9000"),
        codec: arg("--codec", "hevc"),
        frames: Int(arg("--frames", "120")) ?? 120,
        idrIntervalMs: 1000)
    log("selftest -> \(test.dest) (\(test.width)x\(test.height) @\(test.fps), \(test.frames ?? 0) frames)")
    engine.handle(.selftest(test))
    exit(0)
}

log("ready (stdin JSON line protocol)")

while let line = readLine(strippingNewline: true) {
    let trimmed = line.trimmingCharacters(in: .whitespaces)
    if trimmed.isEmpty { continue }
    do {
        let cmd = try JSONDecoder().decode(Command.self, from: Data(trimmed.utf8))
        engine.handle(cmd)
    } catch {
        log("parse error: \(error) — line: \(trimmed)")
    }
}

log("stdin closed, exiting")
