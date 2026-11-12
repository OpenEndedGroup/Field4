/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
An observable model that maintains message channel state.
*/

#if !targetEnvironment(simulator)
import SwiftUI
import FoveatedStreaming

@MainActor
@Observable
class MessageChannelModel {
    let session: FoveatedStreamingSession
    var availableChannels: [FoveatedStreamingSession.MessageChannel.ID: FoveatedStreamingSession.MessageChannel] = [:]
    var receivedMessagesByChannelId: [FoveatedStreamingSession.MessageChannel.ID: [String]] = [:]
    var messageToSend: String = "Hello!"
    var selectedChannelId: FoveatedStreamingSession.MessageChannel.ID? = nil
    
    var selectedChannel: FoveatedStreamingSession.MessageChannel? {
        if let selectedChannelId {
            availableChannels[selectedChannelId]
        } else {
            nil
        }
    }
    
    init(session: FoveatedStreamingSession) {
        self.session = session
        // Listen for changes to the available message channels.
        monitorAvailableChannels()
    }
    
    private func monitorAvailableChannels() {
        withObservationTracking {
            for channelId in session.availableMessageChannels {
                setUpMessageChannel(for: channelId)
            }
        } onChange: {
            Task { @MainActor in
                self.monitorAvailableChannels()
            }
        }
    }
    
    private func setUpMessageChannel(for channelId: FoveatedStreamingSession.MessageChannel.ID) {
        // Skip channels that are already set up.
        guard availableChannels[channelId] == nil,
              let messageChannel = session.messageChannel(for: channelId) else {
            return
        }
        // Save the channel.
        availableChannels[channelId] = messageChannel
        // Start receiving messages for the channel.
        startReceivingMessages(for: messageChannel)
    }
    
    private func startReceivingMessages(for channel: FoveatedStreamingSession.MessageChannel) {
        // Initialize an array to store messages the channel receives.
        receivedMessagesByChannelId[channel.id] = []
        // Dispatch a task to asynchronously append new messages as the channel receives them.
        Task {
            for await message in channel.receivedMessageStream {
                if let messageString = String(data: message, encoding: .utf8) {
                    receivedMessagesByChannelId[channel.id]?.append(messageString)
                }
            }
        }
    }
}
#endif
