/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
A framework which makes `FoveatedStreamingSession.Status` and `FoveatedStreamingSession.DisconnectReason` available in the simulator.
*/

#if targetEnvironment(simulator)
import Foundation
import Network

@MainActor
@Observable
final public class FoveatedStreamingSession: Identifiable {
    public enum Status: Sendable, Equatable, CustomStringConvertible {
        case initialized
        case connecting
        case connected
        case disconnected(FoveatedStreamingSession.DisconnectReason)
        case disconnecting
        case paused
        case pausing
        case resuming
        public var description: String { "" }
    }

    public struct DisconnectReason: LocalizedError, Equatable, Sendable {
        public static var appInitiatedDisconnect: FoveatedStreamingSession.DisconnectReason { return .init(.appInitiatedDisconnect) }
        public static var endpointInitiatedDisconnect: FoveatedStreamingSession.DisconnectReason { return .init(.endpointInitiatedDisconnect) }
        public static var unauthorized: FoveatedStreamingSession.DisconnectReason { return .init(.unauthorized) }
        public static var unavailable: FoveatedStreamingSession.DisconnectReason { return .init(.unavailable) }
        public static var simulatorTestDisconnect: FoveatedStreamingSession.DisconnectReason { return .init(.simulatorTestDisconnect) }
        public var errorDescription: String? { self == .simulatorTestDisconnect ? "Simulator disconnection test." : "" }
        
        private var value: Value
        
        private init(_ value: Value) {
            self.value = value
        }
        
        private enum Value {
            case appInitiatedDisconnect
            case unauthorized
            case endpointInitiatedDisconnect
            case unavailable
            case simulatorTestDisconnect
        }
    }
    
    public struct Endpoint {
        public static var systemDiscovered: Self { .init(.systemDiscovered) }
        public static func local(ipAddress: IPAddress, port: NWEndpoint.Port) -> Self { .init(.local(ipAddress: ipAddress, port: port)) }
        public static func remote(serverName: String, signalingHeaders: [String: String]) -> Self {
            .init(.remote(serverName: serverName, signalingHeaders: signalingHeaders))
        }
        
        private var value: Value

        private init(_ value: Value) {
            self.value = value
        }
    
        private enum Value {
            case systemDiscovered
            case local(ipAddress: IPAddress, port: NWEndpoint.Port)
            case remote(serverName: String, signalingHeaders: [String: String])
        }
    }

    public init() {}

    @MainActor final public var status: FoveatedStreamingSession.Status = .initialized
}
#endif
