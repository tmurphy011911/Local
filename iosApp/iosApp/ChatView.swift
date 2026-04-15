import SwiftUI
import ComposeApp

// MARK: - Observable State

class ChatState: ObservableObject {
    @Published var messages: [ChatMessageSnapshot] = []
    @Published var quickReplies: [QuickReplySnapshot] = []
    @Published var streamingText: String = ""
    @Published var isTyping: Bool = false
    @Published var isSearching: Bool = false
    @Published var isBusy: Bool = false
    @Published var inputEnabled: Bool = true
    @Published var showPlanSuccess: Bool = false
    @Published var days: [DaySnapshot] = []

    func update(from snapshot: ChatStateSnapshot) {
        DispatchQueue.main.async { [weak self] in
            self?.messages = snapshot.messages
            self?.quickReplies = snapshot.quickReplies
            self?.streamingText = snapshot.streamingText
            self?.isTyping = snapshot.isTyping
            self?.isSearching = snapshot.isSearching
            self?.isBusy = snapshot.isBusy
            self?.inputEnabled = snapshot.inputEnabled
            self?.showPlanSuccess = snapshot.showPlanSuccess
            self?.days = snapshot.days
        }
    }
}

// MARK: - Chat View

struct ChatView: View {
    @StateObject private var state = ChatState()
    @State private var inputText = ""
    @FocusState private var inputFocused: Bool
    let onDismiss: () -> Void
    let showUpgradeBanner: Bool
    let onUpgrade: () -> Void

    init(onDismiss: @escaping () -> Void, showUpgradeBanner: Bool = false, onUpgrade: @escaping () -> Void = {}) {
        self.onDismiss = onDismiss
        self.showUpgradeBanner = showUpgradeBanner
        self.onUpgrade = onUpgrade
    }

    var body: some View {
        VStack(spacing: 0) {
            // Header
            chatHeader

            // Upgrade banner
            if showUpgradeBanner {
                upgradeBanner
            }

            // Messages
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        ForEach(state.messages, id: \.id) { message in
                            if message.isPlanCard {
                                planCardRow(message)
                            } else {
                                messageRow(message)
                            }
                        }

                        // Streaming text
                        if !state.streamingText.isEmpty {
                            aiMessageBubble(state.streamingText)
                                .id("streaming")
                        }

                        // Searching indicator
                        if state.isSearching {
                            searchingIndicator
                                .id("searching")
                        }

                        // Typing indicator
                        if state.isTyping {
                            typingIndicator
                                .id("typing")
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.top, 4)
                    .padding(.bottom, 60)
                }
                .onChange(of: state.messages.count) { _ in
                    withAnimation {
                        proxy.scrollTo(state.messages.last?.id ?? "streaming", anchor: .bottom)
                    }
                }
                .onChange(of: state.streamingText) { _ in
                    proxy.scrollTo("streaming", anchor: .bottom)
                }
            }

            // Quick replies
            if !state.quickReplies.isEmpty {
                quickRepliesRow
            }

            // Input bar
            inputBar
        }
        .background(Color(hex: 0xFAF8FC))
        .onAppear {
            ChatBridge.shared.onStateUpdate = { snapshot in
                state.update(from: snapshot)
            }
        }
    }

    // MARK: - Header

    private var chatHeader: some View {
        VStack(spacing: 0) {
            HStack {
                Button(action: onDismiss) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(Color(hex: 0x8B7FA0))
                }
                .frame(width: 22, height: 22)

                Spacer().frame(width: 8)

                Text("Yocal the Local")
                    .font(.system(size: 18, weight: .black, design: .default))
                    .foregroundColor(Color(hex: 0x1A1523))

                Spacer()

                Button(action: { ChatBridge.shared.resetChat?() }) {
                    Text("Clear chat")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(Color(hex: 0x8B7FA0))
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 14)

            Divider().background(Color(hex: 0xE0DCE8).opacity(0.5))
        }
    }

    // MARK: - Upgrade Banner

    private var upgradeBanner: some View {
        Button(action: onUpgrade) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Build personalized and shareable plans with Yocal")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.white)
                    Text("Plans start at just $0.99/month")
                        .font(.system(size: 11, weight: .heavy))
                        .foregroundColor(.white.opacity(0.85))
                }

                Spacer()

                Text("Upgrade now")
                    .font(.system(size: 11, weight: .black))
                    .foregroundColor(Color(hex: 0xFF6B6B))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(
                LinearGradient(colors: [Color(hex: 0xFF6B6B), Color(hex: 0xE85D5D)], startPoint: .leading, endPoint: .trailing)
            )
        }
    }

    // MARK: - Messages

    private func messageRow(_ message: ChatMessageSnapshot) -> some View {
        HStack {
            if message.isUser { Spacer() }

            if message.isUser {
                Text(message.text)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(Color(hex: 0x1A1523))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(Color(hex: 0xF0EDF6))
                    .clipShape(RoundedRectangle(cornerRadius: 18))
                    .frame(maxWidth: 280, alignment: .trailing)
            } else {
                aiMessageBubble(message.text)
            }

            if !message.isUser { Spacer() }
        }
    }

    private func aiMessageBubble(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            // Sparkle icon
            Text("\u{2728}")
                .font(.system(size: 14))
                .frame(width: 20)

            Text(LocalizedStringKey(text))
                .font(.system(size: 15, weight: .medium))
                .foregroundColor(Color(hex: 0x1A1523))
                .lineSpacing(6)
        }
    }

    // MARK: - Plan Card (simplified for now)

    private func planCardRow(_ message: ChatMessageSnapshot) -> some View {
        let days = message.planSnapshot ?? state.days
        if days.isEmpty { return AnyView(EmptyView()) }

        return AnyView(
            VStack(alignment: .leading, spacing: 8) {
                if message.isSuperseded {
                    ZStack {
                        planCardContent(days: days, dayIndex: message.dayIndex, showSave: false)
                            .opacity(0.3)
                        Text("Updated below")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(Color(hex: 0x8B7FA0))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(.white)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color(hex: 0xE0DCE8), lineWidth: 1))
                    }
                } else {
                    planCardContent(days: days, dayIndex: message.dayIndex, showSave: !days.allSatisfy { $0.isApproved })
                }
            }
        )
    }

    private func planCardContent(days: [DaySnapshot], dayIndex: Int, showSave: Bool) -> some View {
        VStack(spacing: 0) {
            // Day header
            let day = days.indices.contains(dayIndex) ? days[dayIndex] : days.first
            if let day = day {
                VStack(alignment: .leading, spacing: 4) {
                    if days.count > 1 {
                        Text("DAY \(dayIndex + 1) OF \(days.count)")
                            .font(.system(size: 10, weight: .heavy))
                            .foregroundColor(.white.opacity(0.8))
                    }
                    Text(day.label)
                        .font(.system(size: 18, weight: .black))
                        .foregroundColor(.white)
                    Text("\(day.slots.count) \(day.slots.count == 1 ? "stop" : "stops")")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.white.opacity(0.8))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .background(Color(hex: 0xFF6B6B))

                // Slots
                ForEach(Array(day.slots.enumerated()), id: \.element.id) { _, slot in
                    slotRow(slot)
                }
            }

            if showSave {
                Button(action: { ChatBridge.shared.sendMessage?("Looks good, add to My Plans") }) {
                    Text("Save to My Plans")
                        .font(.system(size: 14, weight: .heavy))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(Color(hex: 0x4ECBA0))
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .padding(12)
            }
        }
        .background(.white)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(hex: 0xE0DCE8), lineWidth: 1.5))
    }

    private func slotRow(_ slot: SlotSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(slot.time)
                .font(.system(size: 13, weight: .heavy))
                .foregroundColor(Color(hex: 0xFF6B6B))

            HStack(spacing: 10) {
                if let url = slot.imageUrl, let imageUrl = URL(string: url) {
                    AsyncImage(url: imageUrl) { phase in
                        switch phase {
                        case .success(let image):
                            image.resizable().scaledToFill()
                                .frame(width: 48, height: 48)
                                .clipShape(RoundedRectangle(cornerRadius: 8))
                        default:
                            RoundedRectangle(cornerRadius: 8)
                                .fill(Color(hex: 0xF0EDF6))
                                .frame(width: 48, height: 48)
                        }
                    }
                    .frame(width: 48, height: 48)
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(slot.name)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(Color(hex: 0x1A1523))
                    if !slot.detail.isEmpty {
                        Text(slot.detail)
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundColor(Color(hex: 0x8B7FA0))
                            .lineLimit(2)
                    }
                    if let rating = slot.venueRating, rating > 0 {
                        HStack(spacing: 2) {
                            Image(systemName: "star.fill")
                                .font(.system(size: 10))
                                .foregroundColor(Color(hex: 0xFFB347))
                            Text(String(format: "%.1f", rating))
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(Color(hex: 0x4A3F5C))
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .onTapGesture {
            if let eventId = slot.eventId {
                ChatBridge.shared.onEventTap?(eventId, slot.imageUrl)
            } else {
                ChatBridge.shared.onVenueTap?(slot.name)
            }
        }
    }

    // MARK: - Indicators

    private var searchingIndicator: some View {
        HStack(spacing: 8) {
            ProgressView()
                .scaleEffect(0.8)
            Text("Searching for events...")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(Color(hex: 0x8B7FA0))
        }
        .padding(.vertical, 8)
    }

    private var typingIndicator: some View {
        HStack(spacing: 4) {
            ForEach(0..<3, id: \.self) { i in
                Circle()
                    .fill(Color(hex: 0xE0DCE8))
                    .frame(width: 8, height: 8)
            }
        }
        .padding(.vertical, 8)
    }

    // MARK: - Quick Replies

    private var quickRepliesRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(state.quickReplies, id: \.label) { reply in
                    Button(action: {
                        ChatBridge.shared.sendMessage?(reply.action)
                    }) {
                        Text(reply.label)
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(reply.isGreen ? .white : Color(hex: 0x1A1523))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(reply.isGreen ? Color(hex: 0x4ECBA0) : .white)
                            .clipShape(Capsule())
                            .overlay(Capsule().stroke(Color(hex: 0xE0DCE8), lineWidth: reply.isGreen ? 0 : 1.5))
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
        }
    }

    // MARK: - Input Bar

    private var inputBar: some View {
        VStack(spacing: 0) {
            Divider()
            HStack(spacing: 10) {
                TextField("What are you looking for?", text: $inputText)
                    .font(.system(size: 15, weight: .medium))
                    .focused($inputFocused)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(Color(hex: 0xF0EDF6))
                    .clipShape(RoundedRectangle(cornerRadius: 24))
                    .onSubmit { sendCurrentMessage() }

                Button(action: sendCurrentMessage) {
                    Image(systemName: "paperplane.fill")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(.white)
                        .frame(width: 40, height: 40)
                        .background(state.inputEnabled && !inputText.isEmpty ? Color(hex: 0xFF6B6B) : Color(hex: 0xE0DCE8))
                        .clipShape(Circle())
                }
                .disabled(!state.inputEnabled || inputText.isEmpty)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
        }
        .background(Color(hex: 0xFAF8FC))
    }

    private func sendCurrentMessage() {
        guard !inputText.isEmpty, state.inputEnabled else { return }
        let msg = inputText
        inputText = ""
        ChatBridge.shared.sendMessage?(msg)
    }
}

// MARK: - Color Extension (if not already defined elsewhere)

private extension Color {
    init(hex: UInt) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0
        )
    }
}
