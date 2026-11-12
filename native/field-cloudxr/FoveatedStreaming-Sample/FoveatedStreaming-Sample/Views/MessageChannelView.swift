/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
A view that provides UI for testing message channels.
*/

import SwiftUI
#if !targetEnvironment(simulator)
import FoveatedStreaming
#endif

struct DeveloperSettingsView: View {
#if !targetEnvironment(simulator)
    @Environment(MessageChannelModel.self) var messageChannelModel
    
    @State var isShowingSendMessageErrorAlert = false
    @State var sendMessageError: String? = nil
    
#endif
    
    var body: some View {
        NavigationStack {
            VStack {
#if !targetEnvironment(simulator)
                @Bindable var messageChannelModel = messageChannelModel
                // Select channel section.
                VStack(alignment: .leading, spacing: 12) {
                    Text("Select Channel")
                        .font(.headline)
                    
                    HStack {
                        Picker("Channel", selection: $messageChannelModel.selectedChannelId) {
                            Text("None")
                                .tag(FoveatedStreamingSession.MessageChannel.ID?.none)
                            
                            Divider()
                            
                            ForEach(Array(messageChannelModel.availableChannels.map({ $0.key }).enumerated()), id: \.element) { index, channelId in
                                Text("Channel \(index + 1)")
                                    .tag(channelId as FoveatedStreamingSession.MessageChannel.ID?)
                            }
                        }
                        Spacer()
                        if let selectedChannel = messageChannelModel.selectedChannel {
                            Text(selectedChannel.channelStatus == .ready ? "Ready" : "Closed")
                                .foregroundColor(selectedChannel.channelStatus == .ready ? .green : .red)
                                .fontWeight(.semibold)
                        }
                    }
                }
                .padding()
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                
                // Send message section.
                VStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Send Message")
                            .font(.headline)
                        
                        HStack {
                            TextField("Enter message...", text: $messageChannelModel.messageToSend)
                                .textFieldStyle(.roundedBorder)
                            
                            Button("Send") {
                                guard let message = messageChannelModel.messageToSend.data(using: .utf8),
                                      let selectedChannel = messageChannelModel.selectedChannel else {
                                    return
                                }
                                do {
                                    try sendMessage(message: message, channel: selectedChannel)
                                } catch {
                                    sendMessageError = error.localizedDescription
                                    isShowingSendMessageErrorAlert = true
                                }
                            }
                            .disabled(messageChannelModel.messageToSend.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                    }.disabled(messageChannelModel.selectedChannel == nil || messageChannelModel.selectedChannel?.channelStatus == .closed)
                }
                .padding()
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
                
                // Received messages section.
                VStack(alignment: .leading, spacing: 12) {
                    Text("Received Messages")
                        .font(.headline)
                    Spacer()
                    if let selectedChannelId = messageChannelModel.selectedChannelId,
                       let receivedMessages = messageChannelModel.receivedMessagesByChannelId[selectedChannelId] {
                        ScrollViewReader { proxy in
                            List {
                                ForEach(Array(receivedMessages.enumerated()), id: \.offset) { index, message in
                                    Text(message)
                                        .truncationMode(.tail)
                                }
                            }
                            .onChange(of: receivedMessages.count, initial: true) { _, _ in
                                if let lastIndex = receivedMessages.indices.last {
                                    withAnimation {
                                        proxy.scrollTo(lastIndex, anchor: .bottom)
                                    }
                                }
                            }
                        }
                    } else {
                        Text("No messages received")
                            .foregroundColor(.secondary)
                            .italic()
                            .frame(maxWidth: .infinity, minHeight: 100)
                    }
                    Spacer()
                }
                .padding()
                .background(.background, in: RoundedRectangle(cornerRadius: 12))
                
                Button("Disconnect") {
                    messageChannelModel.selectedChannel?.disconnect()
                }
                .foregroundColor(.red)
                .disabled(messageChannelModel.selectedChannel == nil || messageChannelModel.selectedChannel?.channelStatus == .closed)
#else
                Text("Message channels are unsupported in the simulator.")
                    .italic()
                    .padding()
#endif
            }
            .navigationTitle("Message Channel Testing")
            .toolbarRole(.navigationStack)
#if !targetEnvironment(simulator)
            .padding()
            .alert("Failed to send message: \(sendMessageError ?? "Unknown Reason")", isPresented: $isShowingSendMessageErrorAlert) {
                Button("OK") { }
            }
#endif
        }
    }
    
#if !targetEnvironment(simulator)
    private func sendMessage(message: Data, channel: FoveatedStreamingSession.MessageChannel) throws {
        try channel.sendMessage(message)
    }
#endif
}

#Preview(windowStyle: .automatic, traits: .fixedLayout(width: 480, height: 680)) {
    DeveloperSettingsView()
}
