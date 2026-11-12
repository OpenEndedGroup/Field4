/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
Views displaying controls for connecting to a streaming endpoint to start streaming.
*/

import SwiftUI
import RealityKit
import Network
#if targetEnvironment(simulator)
import FoveatedStreamingSimulator
#else
import FoveatedStreaming
#endif

private enum ConnectionMode {
    case systemDiscovered
    case local
    case remote
    
    var description: String {
        switch self {
            case .systemDiscovered:
                "Discover streams from nearby computers on your network"
            case .local:
                "Enter the IP address and port"
            case .remote:
                "Select the remote server"
        }
    }
    
    var systemImage: String {
        switch self {
            case .systemDiscovered:
                "wifi"
            case .local:
                "desktopcomputer"
            case .remote:
                "network"
        }
    }
}

struct StreamConnectionView: View {
    @Environment(StreamActions.self) var streamActions
    
    @AppStorage("ipAddress") private var ipAddress: String = "192.168.1.10"
    @AppStorage("port") private var port: Int = 55000
    @AppStorage("serverName") private var serverName: String = "Example Endpoint"
    
    @ScaledMetric var scaledWidth = 360
    @ScaledMetric var scaledHeight = 390
    @ScaledMetric var scaleFactor = 1
    
    @State private var connectionMode: ConnectionMode = .systemDiscovered
    @State private var connectionTask: Task<Void, Error>? = nil

    var isConnecting: Bool {
        connectionTask != nil
    }
    
    var networkIPAddress: IPAddress? {
        IPv4Address(ipAddress)
    }
    
    var networkPort: NWEndpoint.Port? {
        NWEndpoint.Port(String(port))
    }
    
    var isIPAddressAndPortValid: Bool {
        networkPort != nil && networkIPAddress != nil
    }
    
    var remoteEndpoints: [String: String]? {
        guard let path = Bundle.main.path(forResource: "Info", ofType: "plist"),
              let plist = NSDictionary(contentsOfFile: path) else {
            return nil
        }
        
        // Access the `ApprovedStreamingEndpoints` dictionary.
        if let endpoints = plist["ApprovedStreamingEndpoints"] as? [String: String] {
           return endpoints
        }
        
        return nil
    }
    
    var body: some View {
        ZStack(spacing: 24) {
            VStack {
                Text("Connect to Stream")
                    .font(.largeTitle)
                    .fontWeight(.bold)
                    .multilineTextAlignment(.center)
                
                Picker("Connection Mode", selection: $connectionMode) {
                    Text("Automatic")
                        .tag(ConnectionMode.systemDiscovered)
                    
                    Text("Local IP")
                        .tag(ConnectionMode.local)
                    
                    Text("Remote")
                        .tag(ConnectionMode.remote)
                }
                .pickerStyle(.segmented)
                .disabled(isConnecting)
                
                VStack {
                    VStack(spacing: 16) {
                        Image(systemName: connectionMode.systemImage)
                            .font(.headline.scaled(by: 3))
                        
                        if isConnecting {
                            HStack {
                                ProgressView()

                                Text("Connecting...")
                                    .font(.headline.scaled(by: 1.25))
                            }
                        } else {
                            Text(connectionMode.description)
                                .font(.headline.scaled(by: 1.25))
                                .multilineTextAlignment(.center)
                        }
                    }
                    
                    Spacer()
                    
                    Group {
                        if connectionMode == .local {
                            Grid {
                                GridRow {
                                    TextField("255.255.255.255", text: $ipAddress)
                                        .textFieldStyle(.roundedBorder)
                                        .autocorrectionDisabled(true)
                                        .keyboardType(.decimalPad)
                                        .textInputAutocapitalization(.never)
                                        .searchDictationBehavior(.inline(activation: .onLook))
                                        .gridCellColumns(3)
                                    
                                    TextField("55000", value: $port, format: .number.grouping(.never))
                                        .textFieldStyle(.roundedBorder)
                                        .keyboardType(.numberPad)
                                        .gridCellColumns(2)
                                }
                            }
                        } else if connectionMode == .remote {
                            if let remoteEndpoints {
                                Picker("Remote Endpoint", selection: $serverName) {
                                    ForEach(Array(remoteEndpoints.keys.sorted()), id: \.self) { key in
                                        Text(key)
                                            .tag(key)
                                    }
                                } currentValueLabel: {
                                    Text(serverName + String(repeating: " ", count: 100))
                                }
                                .pickerStyle(.menu)
                            }
                        }
                    }
                    .disabled(isConnecting)
                       
                    Button {
                        if !isConnecting {
                            connectionTask = Task { @MainActor in
                                defer {
                                    connectionTask = nil
                                }
                                switch connectionMode {
                                    case .systemDiscovered:
                                        try await streamActions.connect(.systemDiscovered)
                                    case .local:
                                        guard let networkIPAddress, let networkPort else { return }
                                        try await streamActions.connect(.local(ipAddress: networkIPAddress, port: networkPort))
                                    case .remote:
                                        try await streamActions.connect(.remote(serverName: serverName,
                                                                                signalingHeaders: ["test-header": "my-test"]))
                                }
                            }
                        } else {
                            connectionTask?.cancel()
                        }
                    } label: {
                        Text(isConnecting ? "Cancel" : "Connect")
                            .frame(maxWidth: .infinity)
                            .padding()
                        
                    }
                    .tint(isConnecting ? .red : .blue )
                    .disabled(connectionMode == .local && !isIPAddressAndPortValid)
                }
                .padding()
                .background(.background, in: RoundedRectangle(cornerRadius: 36))
            }
        }
        .padding()
        .frame(width: scaledWidth, height: (390 + scaledHeight) / 2)
        .glassBackgroundEffect()
        .animation(.easeInOut, value: connectionTask)
        .setImmersivePresentationBehaviors()
    }
}

#Preview {
    StreamConnectionView()
        .environment(
            StreamActions(
                connect: { _ in
                    try await Task.sleep(nanoseconds: .max)
                },
                pause: {},
                resume: {},
                disconnect: {}
            )
        )
}
