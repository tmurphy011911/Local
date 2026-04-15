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
    @Published var weather: [Int32: WeatherSnapshot] = [:]

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
            self?.weather = snapshot.weather
        }
    }
}

// MARK: - Colors

private enum C {
    static let ink     = Color(hex2: 0x1A1523)
    static let ink2    = Color(hex2: 0x4A3F5C)
    static let ink3    = Color(hex2: 0x8B7FA0)
    static let snow    = Color(hex2: 0xFAF8FC)
    static let pebble  = Color(hex2: 0xE0DCE8)
    static let mist    = Color(hex2: 0xF0EDF6)
    static let coral   = Color(hex2: 0xFF6B6B)
    static let sage    = Color(hex2: 0x4ECBA0)
    static let sky     = Color(hex2: 0x5B9CF6)
    static let sun     = Color(hex2: 0xFFB347)
}

private extension Color {
    init(hex2: UInt) {
        self.init(
            red: Double((hex2 >> 16) & 0xFF) / 255.0,
            green: Double((hex2 >> 8) & 0xFF) / 255.0,
            blue: Double(hex2 & 0xFF) / 255.0
        )
    }
}

private let dayColors: [Color] = [
    Color(hex2: 0xFF6B6B), Color(hex2: 0x4ECBA0), Color(hex2: 0xFFB830),
    Color(hex2: 0x6B9FFF), Color(hex2: 0xCC6BFF), Color(hex2: 0xFF6BB5),
    Color(hex2: 0x6BFFD4),
]

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
            chatHeader

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

                        if !state.streamingText.isEmpty {
                            aiMessageBubble(state.streamingText)
                                .id("streaming")
                        }

                        if state.isSearching {
                            searchingIndicator.id("searching")
                        }

                        if state.isTyping {
                            typingIndicator.id("typing")
                        }

                        // Bottom anchor
                        Color.clear.frame(height: 1).id("bottom")
                    }
                    .padding(.horizontal, 14)
                    .padding(.top, 4)
                    .padding(.bottom, 8)
                }
                .onChange(of: state.messages.count) { _ in
                    withAnimation(.easeOut(duration: 0.2)) {
                        proxy.scrollTo("bottom", anchor: .bottom)
                    }
                }
                .onChange(of: state.streamingText) { _ in
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
                .onChange(of: state.isSearching) { _ in
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
            }

            // Plan success overlay
            if state.showPlanSuccess {
                planSuccessBanner
            }

            // Quick replies
            if !state.quickReplies.isEmpty {
                quickRepliesRow
            }

            // Input bar
            inputBar
        }
        .background(C.snow)
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
                        .foregroundColor(C.ink3)
                }

                Spacer().frame(width: 8)

                Text("Yocal the Local")
                    .font(.system(size: 18, weight: .black))
                    .foregroundColor(C.ink)

                Spacer()

                Button(action: { ChatBridge.shared.resetChat?() }) {
                    Text("Clear chat")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(C.ink3)
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 14)

            Divider().background(C.pebble.opacity(0.5))
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
                    .foregroundColor(C.coral)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(LinearGradient(colors: [C.coral, Color(hex2: 0xE85D5D)], startPoint: .leading, endPoint: .trailing))
        }
    }

    // MARK: - Message Row

    private func messageRow(_ message: ChatMessageSnapshot) -> some View {
        HStack {
            if message.isUser { Spacer(minLength: 60) }

            if message.isUser {
                Text(message.text)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(C.ink)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(C.mist)
                    .clipShape(RoundedRectangle(cornerRadius: 18))
            } else {
                aiMessageBubble(message.text)
            }

            if !message.isUser { Spacer(minLength: 40) }
        }
    }

    private func aiMessageBubble(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text("\u{2728}")
                .font(.system(size: 14))
                .frame(width: 20)

            // Render markdown-like text (bold, bullets)
            markdownText(text)
        }
    }

    private func markdownText(_ text: String) -> some View {
        // Split into paragraphs and handle bold (**text**) and bullets (- text)
        let lines = text.components(separatedBy: "\n")
        return VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                if line.hasPrefix("- ") {
                    HStack(alignment: .top, spacing: 6) {
                        Text("•")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundColor(C.ink)
                        richText(String(line.dropFirst(2)))
                    }
                } else {
                    richText(line)
                }
            }
        }
    }

    private func richText(_ text: String) -> Text {
        // Handle **bold** markers
        var result = Text("")
        var remaining = text
        while let boldStart = remaining.range(of: "**") {
            let before = String(remaining[remaining.startIndex..<boldStart.lowerBound])
            if !before.isEmpty {
                result = result + Text(before).font(.system(size: 15, weight: .medium)).foregroundColor(C.ink)
            }
            remaining = String(remaining[boldStart.upperBound...])
            if let boldEnd = remaining.range(of: "**") {
                let boldText = String(remaining[remaining.startIndex..<boldEnd.lowerBound])
                result = result + Text(boldText).font(.system(size: 15, weight: .bold)).foregroundColor(C.ink)
                remaining = String(remaining[boldEnd.upperBound...])
            }
        }
        if !remaining.isEmpty {
            result = result + Text(remaining).font(.system(size: 15, weight: .medium)).foregroundColor(C.ink)
        }
        return result
    }

    // MARK: - Plan Card

    private func planCardRow(_ message: ChatMessageSnapshot) -> some View {
        let days = message.planSnapshot ?? state.days
        if days.isEmpty { return AnyView(EmptyView()) }

        return AnyView(
            Group {
                if message.isSuperseded {
                    ZStack {
                        multiDayPlanCard(days: days, showSave: false)
                            .opacity(0.3)
                        Text("Updated below")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(C.ink3)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(.white)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(C.pebble, lineWidth: 1))
                    }
                } else {
                    multiDayPlanCard(days: days, showSave: !days.allSatisfy { $0.isApproved })
                }
            }
        )
    }

    private func multiDayPlanCard(days: [DaySnapshot], showSave: Bool) -> some View {
        TabView {
            ForEach(Array(days.enumerated()), id: \.offset) { index, day in
                dayCardPage(day: day, dayIndex: index, totalDays: days.count, showSave: showSave)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: days.count > 1 ? .automatic : .never))
        .frame(height: dayCardHeight(days))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(C.pebble, lineWidth: 1.5))
    }

    private func dayCardHeight(_ days: [DaySnapshot]) -> CGFloat {
        let maxSlots = days.map { $0.slots.count }.max() ?? 1
        // Header(80) + weather(30) + timeline(30) + slots(90*n) + save(60) + padding
        return CGFloat(80 + 30 + 30 + maxSlots * 90 + 80)
    }

    private func dayCardPage(day: DaySnapshot, dayIndex: Int, totalDays: Int, showSave: Bool) -> some View {
        let color = dayColors[dayIndex % dayColors.count]
        let weatherData = state.weather[Int32(dayIndex)]

        return VStack(spacing: 0) {
            // Day header
            VStack(alignment: .leading, spacing: 4) {
                if totalDays > 1 {
                    Text("DAY \(dayIndex + 1) OF \(totalDays)")
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
            .background(color)

            // Weather
            if let w = weatherData {
                HStack(spacing: 6) {
                    Text("\(w.tempHigh)°F")
                        .font(.system(size: 12, weight: .heavy))
                        .foregroundColor(C.coral)
                    Text(w.description_)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(C.coral)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
                .background(C.coral.opacity(0.08))
            }

            // Timeline dots
            if day.slots.count > 1 {
                HStack(spacing: 0) {
                    ForEach(Array(day.slots.enumerated()), id: \.element.id) { i, slot in
                        VStack(spacing: 2) {
                            Circle()
                                .fill(i == 0 ? color : C.pebble)
                                .frame(width: 8, height: 8)
                            Text(slot.time)
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(i == 0 ? C.ink : C.ink3)
                        }
                        if i < day.slots.count - 1 {
                            Spacer()
                            Rectangle().fill(C.pebble).frame(height: 1)
                            Spacer()
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }

            // Slot cards — horizontal scroll
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(day.slots, id: \.id) { slot in
                        slotCard(slot, color: color)
                    }
                }
                .padding(.horizontal, 12)
            }

            Spacer()

            // Save button
            if showSave {
                Button(action: { ChatBridge.shared.sendMessage?("Looks good, add to My Plans") }) {
                    Text("Save to My Plans")
                        .font(.system(size: 14, weight: .heavy))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(C.sage)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .padding(12)
            }
        }
        .background(.white)
    }

    private func slotCard(_ slot: SlotSnapshot, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            // Image
            if let urlStr = slot.imageUrl, let url = URL(string: urlStr) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().scaledToFill()
                            .frame(width: 220, height: 120)
                            .clipped()
                    default:
                        color.opacity(0.15)
                            .frame(width: 220, height: 120)
                    }
                }
                .frame(width: 220, height: 120)
                .clipped()
            } else {
                color.opacity(0.15)
                    .frame(width: 220, height: 120)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(slot.name)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(C.ink)
                    .lineLimit(1)
                if !slot.detail.isEmpty {
                    Text(slot.detail)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(C.ink3)
                        .lineLimit(2)
                }
                if let rating = slot.venueRating as? Double, rating > 0 {
                    HStack(spacing: 2) {
                        Image(systemName: "star.fill")
                            .font(.system(size: 10))
                            .foregroundColor(C.sun)
                        Text(String(format: "%.1f", rating))
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(C.ink2)
                    }
                }
            }
            .padding(10)
        }
        .frame(width: 220)
        .background(.white)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(C.pebble, lineWidth: 1))
        .onTapGesture {
            if let eventId = slot.eventId {
                ChatBridge.shared.onEventTap?(eventId, slot.imageUrl)
            } else {
                ChatBridge.shared.onVenueTap?(slot.name)
            }
        }
    }

    // MARK: - Plan Success

    private var planSuccessBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 18))
                .foregroundColor(C.sage)
            Text("Plan saved!")
                .font(.system(size: 14, weight: .heavy))
                .foregroundColor(C.sage)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(C.sage.opacity(0.1))
    }

    // MARK: - Indicators

    private var searchingIndicator: some View {
        HStack(spacing: 8) {
            ProgressView()
                .scaleEffect(0.8)
            Text("Searching for events...")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(C.ink3)
        }
        .padding(.vertical, 8)
    }

    private var typingIndicator: some View {
        HStack(spacing: 4) {
            ForEach(0..<3, id: \.self) { _ in
                Circle()
                    .fill(C.pebble)
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
                    Button(action: { ChatBridge.shared.sendMessage?(reply.action) }) {
                        Text(reply.label)
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(reply.isGreen ? .white : C.ink)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(reply.isGreen ? C.sage : .white)
                            .clipShape(Capsule())
                            .overlay(Capsule().stroke(C.pebble, lineWidth: reply.isGreen ? 0 : 1.5))
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
                    .background(C.mist)
                    .clipShape(RoundedRectangle(cornerRadius: 24))
                    .submitLabel(.send)
                    .onSubmit { sendCurrentMessage() }

                Button(action: sendCurrentMessage) {
                    Image(systemName: "paperplane.fill")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundColor(.white)
                        .frame(width: 40, height: 40)
                        .background(state.inputEnabled && !inputText.isEmpty ? C.coral : C.pebble)
                        .clipShape(Circle())
                }
                .disabled(!state.inputEnabled || inputText.isEmpty)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .padding(.bottom, 4)
        }
        .background(C.snow)
    }

    private func sendCurrentMessage() {
        guard !inputText.isEmpty, state.inputEnabled else { return }
        let msg = inputText
        inputText = ""
        ChatBridge.shared.sendMessage?(msg)
    }
}
