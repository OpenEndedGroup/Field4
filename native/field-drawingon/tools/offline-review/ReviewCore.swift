import Foundation

struct ReviewFailure: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
struct ReviewEvent {
    let sequence: Int
    let time: Double
    let type: String
    let data: [String: Any]
    var affectsDrawing: Bool {
        ["canvas.viewport", "canvas.transform", "canvas.appearance", "layers.state", "stroke.begin", "stroke.samples", "stroke.correct", "stroke.end", "stroke.visibility", "field.reference"].contains(type)
    }
}
struct AudioRun {
    let recording: String
    let start: Double
    var samples: [Float]
    var sourceFirstSequence: Int
    var sourceLastSequence: Int
    var fallbackClock: Bool
    static let rate = 16_000.0
    var end: Double { start + Double(samples.count) / Self.rate }
}
struct SpeechSpan {
    var start: Double
    var end: Double
    var run: Int
    var thresholdDB: Double
    var noiseDB: Double
    var audioPath = ""
    var transcript: [String: Any]? = nil
}
struct FrameChoice {
    var sequence: Int
    var time: Double
    var reasons: [String]
}
struct ReviewOptions {
    var interval = 15.0
    var maxFrames = 120
    var columns = 3
    var rows = 3
    var tileWidth = 420
    var frameWidth = 1280
    var thresholdDB = -40.0
    var marginDB = 10.0
    var minSpeech = 0.25
    var silence = 0.5
    var pad = 0.2
    var maxSpeech = 25.0
    var stt = "none"
    var sttExecutable: String?
    var sttModel: String?
    var language = "auto"
    var title: String?
}
private struct AudioLowPass {
    var b0: Double, b1: Double, b2: Double, a1: Double, a2: Double
    var z1 = 0.0, z2 = 0.0
    init(rate: Double, q: Double) {
        let omega = 2 * Double.pi * 6720 / rate, alpha = sin(omega) / (2*q), a0 = 1+alpha
        b0 = (1-cos(omega))/2/a0; b1 = 2*b0; b2 = b0
        a1 = -2*cos(omega)/a0; a2 = (1-alpha)/a0
    }
    mutating func next(_ sample: Float) -> Float {
        let x = Double(sample), y = b0*x+z1
        z1 = b1*x-a1*y+z2; z2 = b2*x-a2*y
        return Float(y)
    }
}
final class ReviewCapture {
    var events: [ReviewEvent] = []
    var audio: [AudioRun] = []
    var warnings: [String] = []
    var session = ""
    var title = "Drawing On capture"
    var duration = 0.0
    var lastSequence = 0
    var sourceBytes: UInt64 = 0
    var clockAnchors: [[String: Any]] = []
    let assets: FieldReferenceAssets

    init(input: URL, assetsDirectory: URL) throws {
        assets = try FieldReferenceAssets(directory: assetsDirectory)
        let reader = try LineReader(input)
        var pendingRun: Int?
        var previousSourceEnd = 0.0
        var previousRecording = ""
        var previousRate = 0.0
        var previousChannels = 0
        var previousSampleTime: Int64?
        var previousFrames = 0
        var lowPass: [AudioLowPass] = []
        while let line = try reader.next() {
            guard let raw = try JSONSerialization.jsonObject(with: line) as? [String: Any],
                  raw["v"] as? Int == 1, let sequence = raw["seq"] as? Int, sequence == lastSequence + 1,
                  let eventSession = raw["session"] as? String, !eventSession.isEmpty,
                  let time = raw["t"] as? Double, time.isFinite, time >= 0,
                  let type = raw["type"] as? String, let data = raw["data"] as? [String: Any] else {
                throw ReviewFailure("Invalid capture event after sequence \(lastSequence); input was not changed")
            }
            if session.isEmpty { session = eventSession }
            guard eventSession.lowercased() == session.lowercased() else { throw ReviewFailure("Capture contains more than one session") }
            lastSequence = sequence; duration = max(duration, time)
            if type == "capture.finish", let label = data["title"] as? String { title = label }
            if type == "session.clock" { clockAnchors.append(["sequence": sequence, "time": time, "clock": data]) }
            if type == "field.asset" { _ = try assets.ingest(data); continue }
            if type == "audio.buffer" {
                guard let rate = data["sampleRate"] as? Double, rate.isFinite, rate >= 8_000, rate <= 384_000,
                      let channels = data["channels"] as? Int, (1...64).contains(channels),
                      let frames = data["frames"] as? Int, frames >= 0, frames <= 1_000_000,
                      let encoded = data["pcm"] as? String, let pcm = Data(base64Encoded: encoded), pcm.count == frames * channels * 4 else {
                    throw ReviewFailure("Invalid PCM audio at sequence \(sequence)")
                }
                guard frames > 0 else { continue }
                let recording = data["recording"] as? String ?? "unlabelled"
                let sampleTime = (data["sampleTime"] as? NSNumber)?.int64Value
                let usableSampleTime = sampleTime.flatMap { $0 >= 0 ? $0 : nil }
                let contiguousCounter = usableSampleTime == nil || previousSampleTime == nil || usableSampleTime == previousSampleTime! + Int64(previousFrames)
                let gap = time - previousSourceEnd
                let contiguous = pendingRun != nil && recording == previousRecording && rate == previousRate && channels == previousChannels &&
                    abs(gap) <= max(0.002, 2 / rate) && contiguousCounter
                if !contiguous { lowPass = rate > AudioRun.rate ? [AudioLowPass(rate: rate, q: 0.5411961), AudioLowPass(rate: rate, q: 1.306563)] : [] }
                let mono: [Float] = try pcm.withUnsafeBytes { raw in
                    try (0..<frames).map { frame in
                        var sum: Float = 0
                        for channel in 0..<channels {
                            let value = Float(bitPattern: UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: (frame * channels + channel) * 4, as: UInt32.self)))
                            guard value.isFinite else { throw ReviewFailure("Non-finite PCM audio at sequence \(sequence)") }
                            sum += min(1, max(-1, value))
                        }
                        var sample = sum / Float(channels)
                        for section in lowPass.indices { sample = lowPass[section].next(sample) }
                        return sample
                    }
                }
                if !contiguous {
                    if pendingRun != nil && gap < -0.002 { warnings.append("Overlapping/out-of-order audio at sequence \(sequence) kept as a separate run") }
                    audio.append(AudioRun(recording: recording, start: time, samples: [], sourceFirstSequence: sequence, sourceLastSequence: sequence, fallbackClock: data["hostTimeValid"] as? Bool == false))
                    pendingRun = audio.count - 1
                }
                let at = pendingRun!
                // Resample on the run's absolute output grid: buffer boundaries do not accumulate drift.
                let first = audio[at].samples.count
                let count = max(first, Int(((time + Double(frames) / rate - audio[at].start) * AudioRun.rate).rounded()))
                if count > first {
                    for index in first..<count {
                        let source = (audio[at].start + Double(index) / AudioRun.rate - time) * rate
                        let low = min(frames - 1, max(0, Int(floor(source))))
                        let high = min(frames - 1, low + 1)
                        let fraction = Float(min(1, max(0, source - Double(low))))
                        audio[at].samples.append(mono[low] + (mono[high] - mono[low]) * fraction)
                    }
                }
                audio[at].sourceLastSequence = sequence
                audio[at].fallbackClock = audio[at].fallbackClock || data["hostTimeValid"] as? Bool == false
                previousSourceEnd = time + Double(frames) / rate
                previousRecording = recording; previousRate = rate; previousChannels = channels
                previousSampleTime = usableSampleTime; previousFrames = frames
                duration = max(duration, previousSourceEnd)
            } else {
                if ["audio.stop", "audio.start", "session.suspend", "session.resume", "session.clock"].contains(type) { pendingRun = nil; previousSampleTime = nil }
                // Audio/predictions/hover are retained in the source, not copied into the render index.
                if type != "stroke.prediction", !type.hasPrefix("pencil."), !type.hasPrefix("audio.") {
                    events.append(ReviewEvent(sequence: sequence, time: time, type: type, data: data))
                }
            }
        }
        sourceBytes = reader.offset
        let size = (try FileManager.default.attributesOfItem(atPath: input.path)[.size] as? NSNumber)?.uint64Value ?? reader.offset
        if reader.offset < size { warnings.append("Ignored an incomplete final line (\(size - reader.offset) bytes); input was not changed") }
        guard lastSequence > 0 else { throw ReviewFailure("Capture has no complete events") }
        if let metadata = try? Data(contentsOf: input.deletingPathExtension().appendingPathExtension("json")),
           let object = try? JSONSerialization.jsonObject(with: metadata) as? [String: Any], let label = object["title"] as? String { title = label }
        if audio.contains(where: \.fallbackClock) { warnings.append("Some microphone buffers use callback time rather than a valid host clock; no guessed latency correction was applied") }
    }
}

func speechSpans(_ audio: [AudioRun], options: ReviewOptions) -> [SpeechSpan] {
    var result: [SpeechSpan] = []
    let window = 320 // 20 ms at 16 kHz
    for (runIndex, run) in audio.enumerated() {
        guard !run.samples.isEmpty else { continue }
        var energies: [Double] = []
        for start in stride(from: 0, to: run.samples.count, by: window) {
            let end = min(run.samples.count, start + window)
            let mean = run.samples[start..<end].reduce(0.0) { $0 + Double($1) * Double($1) } / Double(end - start)
            energies.append(10 * log10(max(1e-12, mean)))
        }
        let sorted = energies.sorted()
        // Cap the adaptive floor so a continuously spoken run is not classified entirely as noise.
        let noise = min(-45, sorted[min(sorted.count - 1, sorted.count / 5)])
        let threshold = max(options.thresholdDB, noise + options.marginDB)
        let endThreshold = threshold - 4
        var active: Int?, lastVoiced = 0
        var raw: [(Double, Double)] = []
        func finish() {
            guard let start = active else { return }
            let begin = Double(start * window) / AudioRun.rate
            let end = min(Double((lastVoiced + 1) * window) / AudioRun.rate, run.end - run.start)
            if end - begin >= options.minSpeech { raw.append((max(0, begin - options.pad), min(run.end - run.start, end + options.pad))) }
            active = nil
        }
        for (index, db) in energies.enumerated() {
            if db >= (active == nil ? threshold : endThreshold) {
                if active == nil { active = index }
                lastVoiced = index
            } else if active != nil, Double((index - lastVoiced) * window) / AudioRun.rate >= options.silence { finish() }
        }
        finish()
        var merged: [(Double, Double)] = []
        for span in raw {
            if let previous = merged.last, span.0 <= previous.1 { merged[merged.count - 1].1 = max(previous.1, span.1) }
            else { merged.append(span) }
        }
        for span in merged {
            var start = span.0
            while start < span.1 {
                let end = min(span.1, start + options.maxSpeech)
                result.append(SpeechSpan(start: run.start + start, end: run.start + end, run: runIndex, thresholdDB: threshold, noiseDB: noise))
                start = end
            }
        }
    }
    return result.sorted { $0.start < $1.start }
}

func chooseFrames(_ capture: ReviewCapture, speech: [SpeechSpan], options: ReviewOptions) -> (frames: [FrameChoice], candidates: Int) {
    var availableTime = 0.0
    let visual = capture.events.filter(\.affectsDrawing).map { event in
        availableTime = max(availableTime, event.time)
        return ReviewEvent(sequence: event.sequence, time: availableTime, type: event.type, data: event.data)
    }
    guard !visual.isEmpty else { return ([FrameChoice(sequence: capture.lastSequence, time: capture.duration, reasons: ["final"])], 1) }
    var choices: [FrameChoice] = []
    func at(_ time: Double, _ reason: String) {
        let event = visual.last { $0.time <= time + 0.000001 }
        choices.append(FrameChoice(sequence: event?.sequence ?? 0, time: max(0, time), reasons: [reason]))
    }
    // Activity clusters preserve silent drawing and state changes even when the microphone is off.
    var clusterEnd = visual[0], clusterStart = visual[0].time
    for event in visual.dropFirst() {
        if event.time - clusterEnd.time > 1.5 || event.time - clusterStart >= options.interval {
            choices.append(FrameChoice(sequence: clusterEnd.sequence, time: clusterEnd.time, reasons: ["drawing activity"]))
            clusterStart = event.time
        }
        clusterEnd = event
    }
    choices.append(FrameChoice(sequence: clusterEnd.sequence, time: clusterEnd.time, reasons: ["final drawing state"]))
    for span in speech {
        at(span.start, "speech start")
        at(span.end, "speech end")
        if span.end - span.start > options.interval {
            var time = span.start + options.interval
            while time < span.end { at(time, "during speech"); time += options.interval }
        }
    }
    choices.sort { $0.sequence == $1.sequence ? $0.time < $1.time : $0.sequence < $1.sequence }
    var deduplicated: [FrameChoice] = []
    for choice in choices {
        if let last = deduplicated.last, last.sequence == choice.sequence {
            deduplicated[deduplicated.count - 1].reasons = Array(Set(last.reasons + choice.reasons)).sorted()
            deduplicated[deduplicated.count - 1].time = max(last.time, choice.time)
        } else { deduplicated.append(choice) }
    }
    let count = deduplicated.count
    if count > options.maxFrames {
        let indices = (0..<options.maxFrames).map { Int((Double($0) * Double(count - 1) / Double(options.maxFrames - 1)).rounded()) }
        deduplicated = indices.map { deduplicated[$0] }
    }
    return (deduplicated, count)
}

struct ReviewState {
    var scene = Scene()
    var viewport: [String: Any] = [:]
    var width = 1194.0
    var height = 834.0
    var light = false
    var opacity = 0.24
    var lineWidth = 1.8
    var appearanceRecorded = false
    mutating func apply(_ event: ReviewEvent) throws {
        let data = event.data
        if event.type == "canvas.appearance" {
            appearanceRecorded = true
            light = data["light"] as? Bool ?? light
            opacity = data["opacity"] as? Double ?? opacity
            lineWidth = data["width"] as? Double ?? lineWidth
        }
        if event.type == "canvas.viewport" { viewport = data }
        else if ["canvas.transform", "stroke.begin"].contains(event.type), let nested = data["viewport"] as? [String: Any] { viewport = nested }
        if let w = viewport["width"] as? Double, let h = viewport["height"] as? Double { width = w; height = h }
        if let transform = data["transform"], ["canvas.viewport", "canvas.transform", "stroke.begin"].contains(event.type) {
            scene.transform = try JSONDecoder().decode(DrawingTransform.self, from: JSONSerialization.data(withJSONObject: transform))
        }
        guard width.isFinite, height.isFinite, width >= 1, height >= 1, width <= 20_000, height <= 20_000,
              [scene.transform.a, scene.transform.b, scene.transform.c, scene.transform.d, scene.transform.tx, scene.transform.ty, opacity, lineWidth].allSatisfy(\.isFinite),
              opacity >= 0, opacity <= 1, lineWidth > 0, lineWidth < 1000 else { throw ReviewFailure("Invalid viewport or appearance at sequence \(event.sequence)") }
        scene.apply(event.type, data)
    }
    func metadata() -> [String: Any] {
        ["screen": ["x": 0, "y": 0, "width": width, "height": height], "recordedViewport": viewport,
         "viewportSource": viewport.isEmpty ? "fallback" : "recorded", "appearanceSource": appearanceRecorded ? "recorded" : "assumed dark default",
         "transform": jsonObject(scene.transform), "light": light, "visibleLayers": scene.visibleLayers,
         "layers": jsonObject(scene.layers), "solo": scene.solo as Any? ?? NSNull(),
         "visibleStrokeIDs": scene.strokes.filter { $0.visible && !$0.cancelled && scene.visibleLayers.contains($0.layer) }.map(\.id),
         "referenceLayers": scene.references.keys.sorted()]
    }
}

func writeWAV(_ samples: ArraySlice<Float>, to url: URL) throws {
    var data = Data()
    func ascii(_ s: String) { data.append(contentsOf: s.utf8) }
    func u16(_ v: UInt16) { var n = v.littleEndian; withUnsafeBytes(of: &n) { data.append(contentsOf: $0) } }
    func u32(_ v: UInt32) { var n = v.littleEndian; withUnsafeBytes(of: &n) { data.append(contentsOf: $0) } }
    guard samples.count <= (Int(UInt32.max) - 36) / 2 else { throw ReviewFailure("Audio segment exceeds WAV size limit") }
    ascii("RIFF"); u32(UInt32(36 + samples.count * 2)); ascii("WAVEfmt "); u32(16); u16(1); u16(1)
    u32(16_000); u32(32_000); u16(2); u16(16); ascii("data"); u32(UInt32(samples.count * 2))
    for sample in samples { u16(UInt16(bitPattern: Int16(max(-32768, min(32767, Int((Double(sample) * 32767).rounded())))))) }
    try data.write(to: url, options: .atomic)
}
