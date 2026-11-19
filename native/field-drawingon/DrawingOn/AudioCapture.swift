import AVFoundation
import UIKit

final class AudioCapture {
    private var engine: AVAudioEngine?
    private let work = DispatchQueue(label: "drawingon.audio", qos: .userInitiated)
    private let pendingBuffers = DispatchGroup()
    private var observers: [NSObjectProtocol] = []
    private var recordingID: String?
    var onState: ((Bool, String?) -> Void)?
    let journal: Journal

    init(journal: Journal) {
        self.journal = journal
        let center = NotificationCenter.default
        for name in [AVAudioSession.interruptionNotification, AVAudioSession.routeChangeNotification, .AVAudioEngineConfigurationChange] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] notification in
                guard let self, self.engine != nil else { return }
                if name == AVAudioSession.routeChangeNotification,
                   let raw = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                   AVAudioSession.RouteChangeReason(rawValue: raw) == .categoryChange { return }
                self.stop(reason: "Audio interrupted or input changed; tap Record to resume")
            })
        }
    }
    deinit { observers.forEach(NotificationCenter.default.removeObserver) }

    func start() {
        guard engine == nil else { return }
        AVAudioApplication.requestRecordPermission { granted in
            DispatchQueue.main.async {
                guard granted else { self.onState?(false, "Microphone access is disabled. Enable it in Settings."); return }
                guard UIApplication.shared.applicationState == .active else { self.onState?(false, nil); return }
                self.begin()
            }
        }
    }
    private func begin() {
        guard engine == nil else { return }
        let session = AVAudioSession.sharedInstance()
        let engine = AVAudioEngine()
        do {
            try session.setCategory(.record, mode: .measurement, options: [.allowBluetoothHFP])
            try session.setPreferredSampleRate(48000)
            try session.setActive(true)
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            guard format.sampleRate > 0, format.channelCount > 0, format.commonFormat == .pcmFormatFloat32 else {
                throw NSError(domain: "DrawingOn", code: 1, userInfo: [NSLocalizedDescriptionKey: "No Float32 microphone input is available"])
            }
            let id = UUID().uuidString
            recordingID = id
            journal.append("audio.start", ["recording": id, "sampleRate": format.sampleRate,
                "channels": format.channelCount, "encoding": "f32le-interleaved",
                "inputLatency": session.inputLatency, "ioBufferDuration": session.ioBufferDuration])
            input.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, when in
                guard let self else { return }
                self.pendingBuffers.enter()
                guard let channels = buffer.floatChannelData else { self.pendingBuffers.leave(); return }
                let frames = Int(buffer.frameLength), count = Int(format.channelCount)
                // Copy before returning the borrowed tap buffer. Encoding and disk/network I/O occur off the audio callback.
                var samples = [Float](repeating: 0, count: frames * count)
                for frame in 0..<frames {
                    for channel in 0..<count {
                        samples[frame * count + channel] = format.isInterleaved ? channels[0][frame * count + channel] : channels[channel][frame]
                    }
                }
                let pcm = samples.withUnsafeBytes { Data($0) }
                let host = when.isHostTimeValid ? AVAudioTime.seconds(forHostTime: when.hostTime) : ProcessInfo.processInfo.systemUptime
                let timestamp = self.journal.time(host)
                let sampleTime = when.isSampleTimeValid ? when.sampleTime : -1
                self.work.async {
                    self.journal.append("audio.buffer", ["recording": id, "frames": frames,
                        "sampleRate": format.sampleRate, "channels": count, "sampleTime": sampleTime,
                        "hostTimeValid": when.isHostTimeValid, "pcm": pcm.base64EncodedString()], at: timestamp)
                    self.pendingBuffers.leave()
                }
            }
            self.engine = engine
            engine.prepare(); try engine.start()
            onState?(true, nil)
        } catch {
            engine.inputNode.removeTap(onBus: 0); self.engine = nil
            journal.append("audio.error", ["message": error.localizedDescription])
            try? session.setActive(false)
            onState?(false, error.localizedDescription)
        }
    }
    func stop(reason: String = "user", completion: (() -> Void)? = nil) {
        guard let engine else {
            pendingBuffers.notify(queue: work) { self.journal.flush(completion) }
            return
        }
        engine.inputNode.removeTap(onBus: 0); engine.stop(); self.engine = nil
        let id = recordingID ?? ""; recordingID = nil
        let timestamp = journal.time()
        pendingBuffers.notify(queue: work) {
            self.journal.append("audio.stop", ["recording": id, "reason": reason], at: timestamp)
            self.journal.flush(completion)
        }
        try? AVAudioSession.sharedInstance().setActive(false)
        onState?(false, ["user", "background", "capture finished"].contains(reason) ? nil : reason)
    }
}
