import CompositorServices
import Foundation

extension LayerRenderer.Clock.Instant {
    /// The compositor clock as an ARKit timestamp, matching Xcode's Compositor Services template.
    ///
    /// This is the clock everything time-related in this app shares: `queryDeviceAnchor(atTimestamp:)`
    /// takes it, the pose uplink stamps `predictedDisplayTime` and `sentAt` in it, and `PoseLatency`
    /// measures spans in it. That shared basis is what lets the head compute motion-to-photon from two
    /// of its own timestamps, with no agreement needed with the Mac's clock.
    ///
    /// It lives here, at internal visibility, because it started out `private` inside
    /// `LiveMetalRenderer.swift` and three files now need it. A second copy would be worse than it
    /// looks: two definitions that drifted would silently produce spans measured against different
    /// epochs, which reads as a plausible-but-wrong latency rather than as a build error.
    var timeInterval: TimeInterval {
        let components = LayerRenderer.Clock.Instant.epoch.duration(to: self).components
        let nanoseconds = TimeInterval(components.attoseconds / 1_000_000_000)
        return TimeInterval(components.seconds) + nanoseconds / TimeInterval(NSEC_PER_SEC)
    }
}
