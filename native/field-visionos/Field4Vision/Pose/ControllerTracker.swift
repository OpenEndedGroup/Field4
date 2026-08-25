import ARKit
import Foundation
import GameController
import OSLog
import simd

/// Reads tracked accessories — PSVR2 Sense controllers and anything else visionOS exposes as an
/// `Accessory` — and turns them into wire controllers for the pose uplink.
///
/// Two frameworks, joined at `Accessory(device:)`: **ARKit** supplies the 6DoF pose, held chirality
/// and tracking state; **GameController** supplies the buttons and axes of the very same device. They
/// are not parallel sources to be correlated by guesswork, which is what makes this tractable.
///
/// The pose is **predicted to the caller's timestamp**, not read as-of-now. The caller is already
/// rendering for a specific predicted display time, so sampling input at a different instant would
/// leave the ray a frame away from the image it acts on — a permanent, small aiming error that reads
/// as "the pointer feels off" rather than as anything findable.
///
/// Device-only in practice: `AccessoryTrackingProvider.isSupported` is false on the simulator, where
/// this reports no controllers and costs nothing.
final class ControllerTracker: @unchecked Sendable {
    private let log = Logger(subsystem: "com.field4.vision", category: "controllers")
    private let lock = NSLock()

    private var session: ARKitSession?
    private var provider: AccessoryTrackingProvider?
    private var running = false
    /// Identity of the GameController set the current provider was built for, so a connect or
    /// disconnect can be detected without rebuilding every frame.
    private var builtFor: Set<String> = []

    func start() {
        lock.lock()
        guard !running else { lock.unlock(); return }
        running = true
        lock.unlock()

        guard AccessoryTrackingProvider.isSupported else {
            log.notice("accessory tracking unsupported here (expected on the simulator)")
            return
        }
        // A provider's accessories are fixed at construction, so a controller connecting later means
        // a new provider and a new session run — hence the notifications rather than a one-shot build.
        for name in [NSNotification.Name.GCControllerDidConnect, .GCControllerDidDisconnect] {
            NotificationCenter.default.addObserver(forName: name, object: nil, queue: nil) { [weak self] _ in
                self?.rebuildIfNeeded()
            }
        }
        GCController.startWirelessControllerDiscovery {}
        rebuildIfNeeded()
    }

    func stop() {
        lock.lock()
        running = false
        session?.stop()
        session = nil
        provider = nil
        builtFor = []
        lock.unlock()
    }

    private func controllerKeys() -> Set<String> {
        Set(GCController.controllers().map { $0.productCategory + "#" + ($0.vendorName ?? "?") })
    }

    /// `Accessory(device:)` is `async throws`, so the whole rebuild runs in a Task. The lock is only
    /// ever taken between awaits, never across one.
    private func rebuildIfNeeded() {
        let devices = GCController.controllers()
        let keys = controllerKeys()
        lock.lock()
        let stale = running && keys != builtFor
        lock.unlock()
        guard stale else { return }

        Task { [weak self] in
            guard let self else { return }
            var accessories: [Accessory] = []
            for device in devices {
                do {
                    accessories.append(try await Accessory(device: device))
                } catch {
                    // Normal, not exceptional: a plain gamepad is a GCController but not a tracked
                    // accessory. Only its buttons would ever have been useful.
                    self.log.notice("not a trackable accessory: \(error.localizedDescription, privacy: .public)")
                }
            }

            // Scoped `withLock` rather than lock()/unlock(): the bare calls are unavailable from an
            // async context and are a hard error under the Swift 6 language mode.
            guard !accessories.isEmpty else {
                self.lock.withLock {
                    self.session?.stop(); self.session = nil; self.provider = nil; self.builtFor = keys
                }
                return
            }

            let newProvider = AccessoryTrackingProvider(accessories: accessories)
            let newSession = ARKitSession()
            let stillRunning = self.lock.withLock { () -> Bool in
                guard self.running else { return false }
                self.session?.stop()
                self.session = newSession
                self.provider = newProvider
                self.builtFor = keys
                return true
            }
            guard stillRunning else { return }

            do {
                try await newSession.run([newProvider])
                self.log.notice("accessory tracking running for \(accessories.count, privacy: .public) accessory(ies)")
            } catch {
                self.log.error("accessory session failed: \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    /// Wire controllers as of `displayTime` (the head's clock), or empty when nothing is tracked.
    func snapshot(at displayTime: TimeInterval) -> [PoseWireController] {
        lock.lock(); let provider = self.provider; lock.unlock()
        guard let provider else { return [] }

        // Buttons come from the GameController that *is* the accessory, matched by id.
        var inputs: [ObjectIdentifier: GCController] = [:]
        for c in GCController.controllers() { inputs[ObjectIdentifier(c)] = c }

        var out: [PoseWireController] = []
        for anchor in provider.latestAnchors {
            // Predict to the instant we are rendering for, falling back to the sample we have.
            let predicted = provider.predictAnchor(for: anchor, at: displayTime) ?? anchor
            let transform = predicted.originFromAnchorTransform
            let chirality: String
            switch predicted.heldChirality {
            case .left: chirality = "left"
            case .right: chirality = "right"
            default: chirality = "unspecified"
            }
            // `isTracked` alone would call an orientation-only anchor tracked, and a ray cast from a
            // position we do not actually have is worse than no ray: it looks plausible and is wrong.
            let positioned: Bool
            switch predicted.trackingState {
            case .positionOrientationTracked, .positionOrientationTrackedLowAccuracy: positioned = true
            default: positioned = false
            }

            var wire = PoseWireController(chirality: chirality, tracked: positioned,
                                          originFromGrip: PoseWire.floats(transform))
            if let pad = inputs.values.first(where: { $0.productCategory == predicted.accessory.name })?
                .physicalInputProfile ?? inputs.values.first?.physicalInputProfile {
                apply(pad, to: &wire)
            }
            out.append(wire)
        }
        return out
    }

    /// Read whichever of the candidate input names this profile actually has.
    ///
    /// Names are looked up rather than assumed: the exact profile a Sense controller presents is not
    /// something to hard-code from memory, and a missing name must degrade to "not pressed" instead of
    /// trapping on a nil subscript.
    private func apply(_ profile: GCPhysicalInputProfile, to wire: inout PoseWireController) {
        func button(_ names: [String]) -> (Float, Bool)? {
            for n in names {
                if let b = profile.buttons[n] { return (b.value, b.isPressed) }
            }
            return nil
        }
        if let (v, p) = button([GCInputRightTrigger, GCInputLeftTrigger]) {
            wire.trigger = v; wire.triggerPressed = p
        }
        if let (v, p) = button([GCInputRightShoulder, GCInputLeftShoulder]) {
            wire.squeeze = v; wire.squeezePressed = p
        }
        if let (_, p) = button([GCInputButtonA, GCInputButtonX]) {
            wire.select = p
        }
        if let stick = profile.dpads[GCInputLeftThumbstick] ?? profile.dpads[GCInputRightThumbstick] {
            wire.trackpadX = stick.xAxis.value
            wire.trackpadY = stick.yAxis.value
            wire.trackpadTouched = abs(stick.xAxis.value) > 0.01 || abs(stick.yAxis.value) > 0.01
        }
    }
}
