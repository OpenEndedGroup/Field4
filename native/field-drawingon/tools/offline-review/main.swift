import Foundation

let help = """
Drawing On offline review (macOS; no network or third-party runtime required)

Usage: tools/offline-review/review CAPTURE.ndjson [options]

  --output DIRECTORY          New/empty output folder (default CAPTURE.review)
  --title TEXT                Override the capture's saved title
  --interval SECONDS          Maximum interval within drawing/speech activity (15)
  --max-frames COUNT          Limit images with even coverage, range 2…1000 (120)
  --columns COUNT             Contact sheet columns (3)
  --rows COUNT                Contact sheet rows per page (3)
  --tile-width PIXELS          Contact sheet tile width (420)
  --frame-width PIXELS         Individual viewport image width (1280)
  --vad-threshold DBFS        Minimum speech-candidate RMS threshold (-40)
  --vad-margin DB             Threshold above estimated noise floor (10)
  --min-speech SECONDS        Reject shorter energy bursts (0.25)
  --silence SECONDS           Split after this much quiet (0.5)
  --pad SECONDS               Speech segment context on each side (0.2)
  --max-speech SECONDS        Split long speech into bounded clips (25)
  --stt none|openai|whisper.cpp   Optional installed, local STT backend (none)
  --stt-executable PATH       Existing whisper / whisper-cli binary
  --stt-model PATH            Existing local .pt / ggml model; never downloaded
  --language CODE            STT language (auto; use en, fr, etc. to constrain)
  --help                     Show this help

Outputs PNG contact sheets + viewport frames, 16 kHz mono speech WAVs, raw STT
JSON, index.html, and manifest.json with capture-time alignment and provenance.
Energy VAD is a heuristic, not a speech model. It can mistake café noise for
speech or miss quiet speech. Drawings remain represented when the mic is off.
The source recording is never changed. Existing nonempty output is refused.
"""

func parseArguments(_ arguments: [String]) throws -> (URL, URL, ReviewOptions) {
    var options = ReviewOptions(), input: String?, output: String?
    var index = 0
    while index < arguments.count {
        let arg = arguments[index]
        if arg == "--help" || arg == "-h" { print(help); exit(0) }
        if !arg.hasPrefix("--") {
            guard input == nil else { throw ReviewFailure("Only one input capture is accepted") }
            input = arg; index += 1; continue
        }
        guard index+1 < arguments.count else { throw ReviewFailure("Missing value after \(arg)") }
        let value = arguments[index+1]
        func number() throws -> Double { guard let n = Double(value), n.isFinite else { throw ReviewFailure("Invalid number for \(arg)") }; return n }
        func integer() throws -> Int { guard let n = Int(value) else { throw ReviewFailure("Invalid integer for \(arg)") }; return n }
        switch arg {
        case "--output": output = value
        case "--title": options.title = value
        case "--interval": options.interval = try number()
        case "--max-frames": options.maxFrames = try integer()
        case "--columns": options.columns = try integer()
        case "--rows": options.rows = try integer()
        case "--tile-width": options.tileWidth = try integer()
        case "--frame-width": options.frameWidth = try integer()
        case "--vad-threshold": options.thresholdDB = try number()
        case "--vad-margin": options.marginDB = try number()
        case "--min-speech": options.minSpeech = try number()
        case "--silence": options.silence = try number()
        case "--pad": options.pad = try number()
        case "--max-speech": options.maxSpeech = try number()
        case "--stt": options.stt = value
        case "--stt-executable": options.sttExecutable = value
        case "--stt-model": options.sttModel = value
        case "--language": options.language = value
        default: throw ReviewFailure("Unknown option \(arg); use --help")
        }
        index += 2
    }
    guard let input else { throw ReviewFailure("Supply a capture file; use --help for options") }
    guard options.interval >= 0.25, options.interval <= 3600, (2...1000).contains(options.maxFrames),
          (1...8).contains(options.columns), (1...8).contains(options.rows), (120...1600).contains(options.tileWidth),
          (128...4096).contains(options.frameWidth), (-100...0).contains(options.thresholdDB), (0...60).contains(options.marginDB),
          options.minSpeech > 0, options.minSpeech <= options.maxSpeech, options.silence >= 0.02, options.silence <= 10,
          options.pad >= 0, options.pad <= 5, options.maxSpeech >= 1, options.maxSpeech <= 300,
          ["none", "openai", "whisper.cpp"].contains(options.stt), !options.language.isEmpty else { throw ReviewFailure("Option outside its supported range; use --help") }
    if options.stt != "none" {
        guard let model = options.sttModel, FileManager.default.fileExists(atPath: NSString(string: model).expandingTildeInPath) else { throw ReviewFailure("STT requires --stt-model pointing to an existing local model file. Nothing will be downloaded.") }
        options.sttModel = NSString(string: model).expandingTildeInPath
        let executable = options.sttExecutable ?? (options.stt == "openai" ? "whisper" : "whisper-cli")
        if executable.contains("/") {
            options.sttExecutable = NSString(string: executable).expandingTildeInPath
        } else {
            options.sttExecutable = (ProcessInfo.processInfo.environment["PATH"] ?? "").split(separator: ":").map { String($0)+"/"+executable }.first { FileManager.default.isExecutableFile(atPath: $0) }
        }
        guard let executable = options.sttExecutable, FileManager.default.isExecutableFile(atPath: executable) else { throw ReviewFailure("Local STT executable not found; set --stt-executable") }
    }
    let source = URL(fileURLWithPath: NSString(string: input).expandingTildeInPath).standardizedFileURL
    guard FileManager.default.isReadableFile(atPath: source.path) else { throw ReviewFailure("Cannot read \(source.path)") }
    let destination = output.map { URL(fileURLWithPath: NSString(string: $0).expandingTildeInPath) } ?? source.deletingPathExtension().appendingPathExtension("review")
    return (source, destination.standardizedFileURL, options)
}

func transcribe(_ wav: URL, output: URL, options: ReviewOptions, captureStart: Double) throws -> [String: Any] {
    let process = Process(); process.executableURL = URL(fileURLWithPath: options.sttExecutable!)
    let stem = wav.deletingPathExtension().lastPathComponent
    let rawURL: URL
    if options.stt == "openai" {
        process.arguments = [wav.path, "--model", options.sttModel!, "--output_dir", output.path, "--output_format", "json", "--fp16", "False", "--verbose", "False", "--device", "cpu", "--threads", "4", "--condition_on_previous_text", "False"]
        if options.language != "auto" { process.arguments! += ["--language", options.language] }
        rawURL = output.appendingPathComponent(stem+".json")
    } else {
        let prefix = output.appendingPathComponent(stem)
        process.arguments = ["-m", options.sttModel!, "-f", wav.path, "-oj", "-of", prefix.path, "-l", options.language]
        rawURL = prefix.appendingPathExtension("json")
    }
    var environment = ProcessInfo.processInfo.environment
    environment["HF_HUB_OFFLINE"] = "1"; environment["TRANSFORMERS_OFFLINE"] = "1"; environment["HF_DATASETS_OFFLINE"] = "1"
    process.environment = environment
    let logURL = output.appendingPathComponent(stem+".log")
    FileManager.default.createFile(atPath: logURL.path, contents: nil)
    let log = try FileHandle(forWritingTo: logURL); defer { try? log.close() }
    process.standardOutput = log; process.standardError = log
    try process.run(); process.waitUntilExit()
    guard process.terminationStatus == 0 else { throw ReviewFailure("STT exited \(process.terminationStatus); see transcripts/\(logURL.lastPathComponent)") }
    guard let raw = try JSONSerialization.jsonObject(with: Data(contentsOf: rawURL)) as? [String: Any] else { throw ReviewFailure("STT returned invalid JSON") }
    let original = raw["segments"] as? [[String: Any]] ?? raw["transcription"] as? [[String: Any]] ?? []
    let segments: [[String: Any]] = original.map { segment in
        let offsets = segment["offsets"] as? [String: Double]
        let start = segment["start"] as? Double ?? (offsets?["from"] ?? 0)/1000
        let end = segment["end"] as? Double ?? (offsets?["to"] ?? 0)/1000
        var result = segment
        result["localStart"] = start; result["localEnd"] = end
        result["start"] = captureStart+start; result["end"] = captureStart+end
        return result
    }
    let text = raw["text"] as? String ?? original.compactMap { $0["text"] as? String }.joined(separator: " ")
    return ["backend": options.stt, "text": text.trimmingCharacters(in: .whitespacesAndNewlines), "segments": segments,
            "raw": "transcripts/"+rawURL.lastPathComponent, "log": "transcripts/"+logURL.lastPathComponent,
            "captureOffset": captureStart, "language": raw["language"] as Any? ?? NSNull()]
}

func makeReview(input: URL, output: URL, options: ReviewOptions) throws -> Bool {
    let fm = FileManager.default
    if fm.fileExists(atPath: output.path), !(try fm.contentsOfDirectory(atPath: output.path)).isEmpty { throw ReviewFailure("Output directory is not empty; choose a new --output directory") }
    for path in ["", "frames", "audio", "transcripts", "assets"] { try fm.createDirectory(at: output.appendingPathComponent(path), withIntermediateDirectories: true) }
    let repo = URL(fileURLWithPath: ProcessInfo.processInfo.environment["DRAWINGON_REPO_ROOT"] ?? URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().path)
    try registerReviewFont(root: repo, output: output)
    let capture = try ReviewCapture(input: input, assetsDirectory: output.appendingPathComponent("assets"))
    let title = options.title ?? capture.title
    var speech = speechSpans(capture.audio, options: options), sttFailed = false
    for index in speech.indices {
        let span = speech[index], run = capture.audio[span.run]
        let start = max(0, Int(((span.start-run.start)*AudioRun.rate).rounded()))
        let end = min(run.samples.count, Int(((span.end-run.start)*AudioRun.rate).rounded()))
        let path = String(format: "audio/speech-%04d.wav", index+1)
        try writeWAV(run.samples[start..<max(start,end)], to: output.appendingPathComponent(path))
        speech[index].audioPath = path
        if options.stt != "none" {
            fputs("Transcribing \(index+1)/\(speech.count) locally…\n", stderr)
            do { speech[index].transcript = try transcribe(output.appendingPathComponent(path), output: output.appendingPathComponent("transcripts"), options: options, captureStart: span.start) }
            catch {
                sttFailed = true; capture.warnings.append(error.localizedDescription)
                speech[index].transcript = ["error": error.localizedDescription, "backend": options.stt]
            }
        }
    }
    let selection = chooseFrames(capture, speech: speech, options: options)
    if selection.candidates > selection.frames.count { capture.warnings.append("Selected \(selection.frames.count) of \(selection.candidates) candidate states with even timeline coverage; increase --max-frames for more detail") }
    if !capture.events.contains(where: { $0.type == "canvas.viewport" || ($0.data["viewport"] as? [String: Any])?["width"] != nil }) { capture.warnings.append("No recorded viewport; used the documented 1194×834 point fallback") }
    if capture.events.first(where: { $0.type == "canvas.appearance" })?.time ?? .infinity > 0.25 {
        capture.warnings.append("No initial appearance anchor; legacy history before its first canvas.appearance assumes the app's dark default")
    }
    var state = ReviewState(), next = 0, frames: [[String: Any]] = []
    for (index, choice) in selection.frames.enumerated() {
        while next < capture.events.count, capture.events[next].sequence <= choice.sequence { try state.apply(capture.events[next]); next += 1 }
        let image = try renderFrame(state, assets: capture.assets, width: options.frameWidth)
        let path = String(format: "frames/frame-%04d.png", index+1)
        try writePNG(image, to: output.appendingPathComponent(path))
        let start = index == 0 ? 0 : selection.frames[index-1].time
        let end = max(choice.time, start)
        let overlapping = speech.enumerated().filter { $0.element.end > start && $0.element.start < end }
        let text = overlapping.compactMap { $0.element.transcript?["text"] as? String }.filter { !$0.isEmpty }.joined(separator: " ")
        frames.append(["id": index+1, "time": choice.time, "throughSequence": choice.sequence, "range": ["start": start, "end": end],
                       "image": path, "reasons": choice.reasons, "viewport": state.metadata(), "text": text,
                       "speechIDs": overlapping.map { $0.offset+1 }, "pixels": ["width": image.width, "height": image.height]])
    }
    let speechJSON: [[String: Any]] = speech.enumerated().map { index, span in
        ["id": index+1, "start": span.start, "end": span.end, "run": span.run+1, "audio": span.audioPath,
         "thresholdDBFS": span.thresholdDB, "noiseEstimateDBFS": span.noiseDB,
         "transcript": span.transcript as Any? ?? NSNull()]
    }
    let sheets = try renderContactSheets(frames: frames, output: output, title: title, options: options)
    let runs: [[String: Any]] = capture.audio.enumerated().map { index, run in
        ["id": index+1, "recording": run.recording, "start": run.start, "end": run.end,
         "sampleRate": AudioRun.rate, "samples": run.samples.count, "sourceFirstSequence": run.sourceFirstSequence,
         "sourceLastSequence": run.sourceLastSequence, "fallbackClock": run.fallbackClock]
    }
    let manifest: [String: Any] = [
        "version": 1, "generator": "drawingon-offline-review/1", "title": title,
        "source": ["path": input.path, "session": capture.session, "lastSequence": capture.lastSequence, "completeBytes": capture.sourceBytes, "duration": capture.duration],
        "clockAnchors": capture.clockAnchors,
        "replay": ["ordering": "sequence", "visualTimePolicy": "prefix maximum of visual event timestamps; late audio is indexed separately", "predictions": "omitted", "viewport": "recorded screen crop; no automatic framing", "renderer": "Core Graphics; app replay model and Field buffer decoder"],
        "frameRanges": "range is the preceding input interval; the image is a snapshot at time/range.end through throughSequence, not a state held for the whole range",
        "selection": ["interval": options.interval, "candidateStates": selection.candidates, "selectedStates": frames.count, "maxFrames": options.maxFrames],
        "vad": ["method": "adaptive RMS energy, 20ms windows; not a learned speech detector", "minimumDBFS": options.thresholdDB, "noiseMarginDB": options.marginDB, "minimumSpeech": options.minSpeech, "silence": options.silence, "padding": options.pad, "maximumClip": options.maxSpeech],
        "transcription": ["backend": options.stt, "model": options.sttModel as Any? ?? NSNull(), "language": options.language, "failed": sttFailed],
        "audioProcessing": ["output": "16 kHz mono PCM16 WAV", "downsamplingFilter": "4th-order Butterworth low-pass at 6720 Hz before linear resampling", "gapPolicy": "split at recording/clock/format changes or gaps over 2 ms; never concatenate missing capture time"],
        "audioRuns": runs, "speech": speechJSON, "frames": frames, "contactSheets": sheets, "warnings": capture.warnings
    ]
    try JSONSerialization.data(withJSONObject: manifest, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]).write(to: output.appendingPathComponent("manifest.json"), options: .atomic)
    try writeReviewHTML(frames: frames, speech: speechJSON, sheets: sheets, output: output, title: title)
    print("\(frames.count) frames · \(sheets.count) contact sheets · \(speech.count) speech candidates")
    print(output.appendingPathComponent("index.html").path)
    for warning in capture.warnings { fputs("Warning: \(warning)\n", stderr) }
    return !sttFailed
}

do {
    let (input, output, options) = try parseArguments(Array(CommandLine.arguments.dropFirst()))
    exit(try makeReview(input: input, output: output, options: options) ? 0 : 2)
} catch {
    fputs("drawingon-review: \(error.localizedDescription)\n", stderr)
    exit(1)
}
