import Foundation
import Observation

@MainActor
@Observable
final class FieldConnection {
    enum Status: Equatable {
        case checking, reachable, unavailable(String)
    }

    private(set) var server: ServerConfig
    private(set) var recent: [String]
    private(set) var status: Status = .checking
    private(set) var probeRevision = 0
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        server = ServerConfig.launchOverride
            ?? defaults.string(forKey: "fieldHost").flatMap { ServerConfig(address: $0) }
            ?? .default
        recent = defaults.stringArray(forKey: "fieldHostHistory") ?? []
    }

    func select(_ server: ServerConfig) {
        self.server = server
        defaults.set(server.baseURL.absoluteString, forKey: "fieldHost")
        checkAgain()
    }

    func checkAgain() {
        status = .checking
        probeRevision += 1
    }

    func monitor(revision: Int) async {
        let target = server
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 4
        configuration.timeoutIntervalForResource = 6
        let transport = URLSession(configuration: configuration, delegate: FieldServerTrustDelegate(), delegateQueue: nil)
        defer { transport.invalidateAndCancel() }

        while !Task.isCancelled, revision == probeRevision {
            let next: Status
            do {
                let (data, response) = try await transport.data(from: target.baseURL.appendingPathComponent("health"))
                let body = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
                if let http = response as? HTTPURLResponse, http.statusCode != 200 {
                    next = .unavailable("Health check returned HTTP \(http.statusCode).")
                } else if response.url?.host != target.baseURL.host || response.url?.port != target.baseURL.port ||
                            response.url?.scheme != target.baseURL.scheme || body != "ok" {
                    next = .unavailable("This address did not answer as a Field host.")
                } else {
                    next = .reachable
                }
            } catch {
                next = .unavailable(error.localizedDescription)
            }

            guard !Task.isCancelled, revision == probeRevision else { return }
            if status != next { status = next }
            if next == .reachable {
                let address = target.baseURL.absoluteString
                if recent.first != address {
                    recent = Array(([address] + recent.filter { $0 != address }).prefix(8))
                    defaults.set(recent, forKey: "fieldHostHistory")
                }
            }
            try? await Task.sleep(for: .seconds(6))
        }
    }
}
