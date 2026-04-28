import SwiftUI
import ComposeApp

// MARK: - Observable State

class PlanChatState: ObservableObject {
    @Published var messages: [PlanChatMessageSnapshot] = []
    @Published var isLoading: Bool = false
    @Published var isYocalTyping: Bool = false
    @Published var streamingText: String = ""
    @Published var pendingConfirmationSummary: String? = nil

    func update(from snapshot: PlanChatStateSnapshot) {
        DispatchQueue.main.async { [weak self] in
            self?.messages = snapshot.messages
            self?.isLoading = snapshot.isLoading
            self?.isYocalTyping = snapshot.isYocalTyping
            self?.streamingText = snapshot.streamingText
            self?.pendingConfirmationSummary = snapshot.pendingConfirmationSummary
        }
    }
}

// MARK: - Colors

private enum PC {
    static let ink    = Color(pcHex: 0x1A1523)
    static let ink2   = Color(pcHex: 0x4A3F5C)
    static let ink3  = Color(pcHex: 0x8B7FA0)
    static let snow  = Color(pcHex: 0xFAF8FC)
    static let pebble = Color(pcHex: 0xE0DCE8)
    static let mist  = Color(pcHex: 0xF0EDF6)
    static let coral = Color(pcHex: 0xFF6B6B)
    static let yocalBg = Color(pcHex: 0xFFF2EF)
}

private extension Color {
    init(pcHex: UInt) {
        self.init(
            red: Double((pcHex >> 16) & 0xFF) / 255.0,
            green: Double((pcHex >> 8) & 0xFF) / 255.0,
            blue: Double(pcHex & 0xFF) / 255.0
        )
    }
}

// MARK: - Fonts (reuse Nunito + Fraunces)

private enum PF {
    static func display(_ size: CGFloat, _ weight: Font.Weight = .black) -> Font {
        Font.custom("Fraunces", size: size).weight(weight)
    }
    static func body(_ size: CGFloat, _ weight: Font.Weight = .medium) -> Font {
        Font.custom("Nunito", size: size).weight(weight)
    }
}

private let reactionEmojis: [String] = ["👍", "❤️", "🔥", "😂", "🎉", "🤔", "😍", "👏"]

private let planAvatarPalette: [Color] = [
    Color(pcHex: 0xFF6B6B), Color(pcHex: 0x4ECBA0), Color(pcHex: 0xFFB830),
    Color(pcHex: 0x6B9FFF), Color(pcHex: 0xCC6BFF), Color(pcHex: 0xFF6BB5),
    Color(pcHex: 0x6BFFD4), Color(pcHex: 0x9F6BFF), Color(pcHex: 0xFF9F6B),
    Color(pcHex: 0x6BCCFF),
]

private func colorFor(userId: String) -> Color {
    var hash: Int = 0
    for char in userId.unicodeScalars { hash = (hash &* 31) &+ Int(char.value) }
    let idx = ((hash % planAvatarPalette.count) + planAvatarPalette.count) % planAvatarPalette.count
    return planAvatarPalette[idx]
}

// MARK: - Plan Chat View

struct PlanChatView: View {
    @StateObject private var state = PlanChatState()
    @State private var inputText: String = ""
    @FocusState private var inputFocused: Bool
    @State private var pickerForMessageId: String? = nil
    @State private var showEditAppliedSuccess: Bool = false
    let planTitle: String
    let prePopulated: String?
    let onDismiss: () -> Void

    init(planTitle: String, prePopulated: String?, onDismiss: @escaping () -> Void) {
        self.planTitle = planTitle
        self.prePopulated = prePopulated
        self.onDismiss = onDismiss
    }

    var body: some View {
        VStack(spacing: 0) {
            header

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        if state.messages.isEmpty && !state.isLoading {
                            emptyState
                                .padding(.top, 60)
                        }
                        ForEach(state.messages, id: \.id) { msg in
                            messageRow(msg)
                        }
                        if state.isYocalTyping || !state.streamingText.isEmpty {
                            yocalTypingRow
                        }
                        Color.clear.frame(height: 1).id("bottom")
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                }
                .onChange(of: state.messages.count) { _ in
                    withAnimation(.easeOut(duration: 0.2)) {
                        proxy.scrollTo("bottom", anchor: .bottom)
                    }
                }
                .onChange(of: state.streamingText) { _ in
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
            }

            if state.pendingConfirmationSummary != nil {
                confirmationBar
            }

            inputBar
        }
        .background(PC.snow)
        .overlay {
            if showEditAppliedSuccess {
                editAppliedOverlay
                    .transition(.opacity.combined(with: .scale(scale: 0.9)))
            }
        }
        .onAppear {
            PlanChatBridge.shared.onStateUpdate = { snapshot in
                state.update(from: snapshot)
            }
            PlanChatBridge.shared.onPlanEditApplied = {
                DispatchQueue.main.async {
                    withAnimation(.easeOut(duration: 0.25)) {
                        showEditAppliedSuccess = true
                    }
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.1) {
                        onDismiss()
                    }
                }
            }
            if let pre = prePopulated, !pre.isEmpty {
                inputText = pre
            }
        }
    }

    // MARK: Success overlay (Bo applied edit → auto-dismiss)

    private var editAppliedOverlay: some View {
        ZStack {
            Color.black.opacity(0.25).ignoresSafeArea()
            VStack(spacing: 12) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 44, weight: .bold))
                    .foregroundColor(PC.coral)
                Text("Plan updated")
                    .font(PF.display(20, .black))
                    .foregroundColor(PC.ink)
            }
            .padding(.horizontal, 28)
            .padding(.vertical, 22)
            .background(Color.white)
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .shadow(color: .black.opacity(0.15), radius: 20, y: 8)
        }
    }

    // MARK: Header

    private var header: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(planTitle)
                        .font(PF.display(17, .black))
                        .foregroundColor(PC.ink)
                        .lineLimit(1)
                    Text("Editing with Bo")
                        .font(PF.body(11, .semibold))
                        .foregroundColor(PC.ink3)
                }
                Spacer()
                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(PC.ink2)
                        .frame(width: 30, height: 30)
                        .background(PC.mist)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
            Divider().background(PC.pebble)
        }
        .background(Color.white)
    }

    // MARK: Empty state

    private var emptyState: some View {
        HStack {
            Spacer()
            Text("Ask Bo to make changes — replace a stop, swap times, add an event.")
                .font(PF.body(13, .semibold))
                .foregroundColor(PC.ink3)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
            Spacer()
        }
    }

    // MARK: Message row

    /// Per-plan chat is now Bo-only. Style matches the main Bo chat (ChatView):
    ///   - User messages: mist pill on the right
    ///   - Bo messages: sparkle icon + "Bo" label + inline text on the left (no bubble)
    /// Reactions are still supported for human messages, but Bo doesn't get reactions.
    @ViewBuilder
    private func messageRow(_ msg: PlanChatMessageSnapshot) -> some View {
        HStack(alignment: .top, spacing: 0) {
            if msg.isCurrentUser {
                Spacer(minLength: 60)
                userMessage(msg)
            } else if msg.isYocal {
                boMessage(msg)
                Spacer(minLength: 40)
            } else {
                // Other human user (rare in V1 — group chat is killed) keeps the
                // avatar + bubble pattern so it doesn't look like Bo.
                otherHumanMessage(msg)
                Spacer(minLength: 40)
            }
        }
    }

    private func userMessage(_ msg: PlanChatMessageSnapshot) -> some View {
        Text(msg.text)
            .font(PF.body(15, .semibold))
            .foregroundColor(PC.ink)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(PC.mist)
            .clipShape(RoundedRectangle(cornerRadius: 18))
            .frame(maxWidth: 280, alignment: .trailing)
    }

    private func boMessage(_ msg: PlanChatMessageSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text("Bo")
                .font(PF.body(11, .bold))
                .foregroundColor(PC.coral)
                .padding(.leading, 28) // line up with text after the sparkle
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "sparkles")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(PC.coral)
                    .frame(width: 20)
                    .padding(.top, 3)
                Text(msg.text)
                    .font(PF.body(15, .semibold))
                    .foregroundColor(PC.ink)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: 280, alignment: .leading)
    }

    @ViewBuilder
    private func otherHumanMessage(_ msg: PlanChatMessageSnapshot) -> some View {
        HStack(alignment: .top, spacing: 8) {
            avatar(msg: msg)
            VStack(alignment: .leading, spacing: 3) {
                Text(msg.senderName)
                    .font(PF.body(11, .bold))
                    .foregroundColor(PC.ink3)
                    .padding(.leading, 2)
                Text(msg.text)
                    .font(PF.body(14, .semibold))
                    .foregroundColor(PC.ink)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: 18))
                    .fixedSize(horizontal: false, vertical: true)
                    .onLongPressGesture {
                        let impact = UIImpactFeedbackGenerator(style: .light)
                        impact.impactOccurred()
                        pickerForMessageId = pickerForMessageId == msg.id ? nil : msg.id
                    }
                if pickerForMessageId == msg.id { emojiPicker(messageId: msg.id) }
                if !msg.reactions.isEmpty { reactionsRow(msg) }
            }
        }
        .frame(maxWidth: 280, alignment: .leading)
    }

    @ViewBuilder
    private func reactionsRow(_ msg: PlanChatMessageSnapshot) -> some View {
        HStack(spacing: 4) {
            ForEach(msg.reactions, id: \.emoji) { r in
                Button(action: {
                    PlanChatBridge.shared.toggleReaction?(msg.id, r.emoji)
                }) {
                    HStack(spacing: 2) {
                        Text(r.emoji).font(.system(size: 11))
                        Text("\(r.count)")
                            .font(PF.body(11, .bold))
                            .foregroundColor(r.reactedByMe ? PC.coral : PC.ink)
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(r.reactedByMe ? PC.coral.opacity(0.1) : Color.white)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(r.reactedByMe ? PC.coral : PC.pebble, lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private func emojiPicker(messageId: String) -> some View {
        HStack(spacing: 6) {
            ForEach(reactionEmojis, id: \.self) { emoji in
                Button(action: {
                    PlanChatBridge.shared.toggleReaction?(messageId, emoji)
                    pickerForMessageId = nil
                }) {
                    Text(emoji)
                        .font(.system(size: 20))
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Color.white)
        .overlay(RoundedRectangle(cornerRadius: 22).stroke(PC.pebble, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 22))
    }

    @ViewBuilder
    private func avatar(msg: PlanChatMessageSnapshot) -> some View {
        if msg.isYocal {
            Image(systemName: "sparkles")
                .font(.system(size: 14, weight: .bold))
                .foregroundColor(PC.coral)
                .frame(width: 28, height: 28)
                .background(PC.coral.opacity(0.15))
                .clipShape(Circle())
        } else {
            let first = msg.senderName.first.map { String($0).uppercased() } ?? "?"
            Text(first)
                .font(PF.body(12, .black))
                .foregroundColor(.white)
                .frame(width: 28, height: 28)
                .background(colorFor(userId: msg.userId))
                .clipShape(Circle())
        }
    }

    // MARK: Yocal typing

    private var yocalTypingRow: some View {
        // Match the finalized boMessage style so the row doesn't visually
        // "switch" from bubble → no-bubble when the stream completes.
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 3) {
                Text("Bo")
                    .font(PF.body(11, .bold))
                    .foregroundColor(PC.coral)
                    .padding(.leading, 28)
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "sparkles")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(PC.coral)
                        .frame(width: 20)
                        .padding(.top, 3)
                    Text(state.streamingText.isEmpty ? "Thinking…" : state.streamingText)
                        .font(PF.body(15, .semibold))
                        .foregroundColor(state.streamingText.isEmpty ? PC.ink3 : PC.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: 280, alignment: .leading)
            Spacer(minLength: 40)
        }
    }

    // MARK: Confirmation bar

    private var confirmationBar: some View {
        VStack(spacing: 0) {
            Divider().background(PC.pebble)
            HStack(spacing: 8) {
                Button(action: { PlanChatBridge.shared.cancelChange?() }) {
                    Text("Cancel")
                        .font(PF.body(14, .bold))
                        .foregroundColor(PC.ink)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(PC.mist)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)

                Button(action: { PlanChatBridge.shared.confirmChange?() }) {
                    Text("Confirm change")
                        .font(PF.body(14, .heavy))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(PC.coral)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(PC.yocalBg)
        }
    }

    // MARK: Input

    private var inputBar: some View {
        VStack(spacing: 0) {
            Divider().background(PC.pebble)
            HStack(alignment: .bottom, spacing: 8) {
                TextField("Message Bo...", text: $inputText, axis: .vertical)
                    .focused($inputFocused)
                    .lineLimit(1...4)
                    .font(PF.body(14, .semibold))
                    .foregroundColor(PC.ink)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(PC.snow)
                    .overlay(RoundedRectangle(cornerRadius: 20).stroke(PC.pebble, lineWidth: 1.5))
                    .clipShape(RoundedRectangle(cornerRadius: 20))
                    .submitLabel(.send)
                    .onSubmit { sendIfValid() }

                Button(action: sendIfValid) {
                    Image(systemName: "paperplane.fill")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(.white)
                        .frame(width: 40, height: 40)
                        .background(inputText.trimmingCharacters(in: .whitespaces).isEmpty ? PC.pebble : PC.coral)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .disabled(inputText.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color.white)
        }
    }

    private func sendIfValid() {
        let trimmed = inputText.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return }
        PlanChatBridge.shared.sendMessage?(trimmed)
        inputText = ""
    }
}

// MARK: - Bubble shape (asymmetric corner)

private struct BubbleShape: Shape {
    let isUser: Bool

    func path(in rect: CGRect) -> Path {
        let topLeft: CGFloat = 16
        let topRight: CGFloat = 16
        let bottomRight: CGFloat = isUser ? 4 : 16
        let bottomLeft: CGFloat = isUser ? 16 : 4

        var path = Path()
        path.move(to: CGPoint(x: rect.minX + topLeft, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - topRight, y: rect.minY))
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX, y: rect.minY + topRight),
            control: CGPoint(x: rect.maxX, y: rect.minY)
        )
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - bottomRight))
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX - bottomRight, y: rect.maxY),
            control: CGPoint(x: rect.maxX, y: rect.maxY)
        )
        path.addLine(to: CGPoint(x: rect.minX + bottomLeft, y: rect.maxY))
        path.addQuadCurve(
            to: CGPoint(x: rect.minX, y: rect.maxY - bottomLeft),
            control: CGPoint(x: rect.minX, y: rect.maxY)
        )
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + topLeft))
        path.addQuadCurve(
            to: CGPoint(x: rect.minX + topLeft, y: rect.minY),
            control: CGPoint(x: rect.minX, y: rect.minY)
        )
        return path
    }
}
