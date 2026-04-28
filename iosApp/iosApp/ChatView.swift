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
    @Published var weather: [KotlinInt: WeatherSnapshot] = [:]

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

// MARK: - Fonts
// Fraunces = display (headers). Nunito = body (chat text). Both are bundled via
// iosApp folder (synchronized group) and declared in Info.plist UIAppFonts.

private enum F {
    static func display(_ size: CGFloat, _ weight: Font.Weight = .black) -> Font {
        Font.custom("Fraunces", size: size).weight(weight)
    }
    static func body(_ size: CGFloat, _ weight: Font.Weight = .medium) -> Font {
        Font.custom("Nunito", size: size).weight(weight)
    }
}

// MARK: - Searching Phrases (rotating gerunds)

private let searchingPhrases: [String] = [
    "Flambéing...", "Parallel parking...", "Consulting the locals...",
    "Polishing the itinerary...", "Taste-testing...", "Checking the vibe...",
    "Dodging tourists...", "Asking the bartender...", "Finding the secret menu...",
    "Warming up the car...", "Scouting rooftops...", "Bribing the maître d'...",
    "Googling parking...", "Judging the Yelp reviews...", "Texting the group chat...",
    "Stretching before the walk...", "Charging the phone...", "Picking the playlist...",
    "Ironing the outfit...", "Checking the weather...", "Memorizing the wine list...",
    "Practicing small talk...", "Feeding the meter...", "Adjusting the GPS...",
    "Calling an audible...", "Haggling...", "Eavesdropping on regulars...",
    "Reading the room...", "Shaking the cocktail...", "Folding the napkin...",
    "Buttering the bread...", "Sampling the charcuterie...", "Tipping generously...",
    "Sneaking into VIP...", "Befriending the bouncer...", "Splitting the check...",
    "Flagging the waiter...", "Double-checking the address...", "Refreshing the page...",
    "Stalking their Instagram...", "Manifesting good weather...", "Summoning the Uber...",
    "Hydrating...", "Pregaming...", "Limbering up...", "Coordinating outfits...",
    "Sniffing the cork...", "Judging the font on the menu...",
    "Pretending to read the specials...", "Complimenting the décor...",
    "Warming the engine...", "Negotiating the dress code...", "Pocketing the mints...",
    "Claiming the booth...", "Decoding the cocktail menu...", "Rating the ambiance...",
    "Swiping for inspiration...", "Checking the tide chart...", "Waxing the surfboard...",
    "Alphabetizing the beer list...", "Arguing with the GPS...",
    "Avoiding eye contact with mimes...", "Borrowing a lighter...",
    "Calculating the tip...", "Canceling other plans...", "Checking coat pockets...",
    "Choosing the right sneakers...", "Clearing the camera roll...",
    "Comparing happy hours...", "Confirming the dress code...", "Convincing the group...",
    "Counting the steps...", "Crossing fingers...", "Cueing the DJ...",
    "Debugging the playlist...", "Defrosting the windshield...", "Deleting bad selfies...",
    "Doing recon...", "Double-knotting the laces...", "Downloading the map offline...",
    "Drafting the toast...", "Dry-cleaning the jacket...", "Dusting off the dance moves...",
    "Eating a snack first...", "Estimating walk time...", "Eyeing the dessert menu...",
    "Fact-checking the specials...", "Faking a French accent...", "Finding an outlet...",
    "Fixing the hair...", "Flipping through records...", "Forging a shortcut...",
    "Gargling mouthwash...", "Getting the inside scoop...", "Grabbing the umbrella...",
    "Guarding the parking spot...", "Hailing a cab...", "Hiding the shopping bags...",
    "Holding the elevator...", "Hunting for street parking...", "Ignoring the calorie count...",
    "Improvising...", "Inspecting the glassware...", "Jaywalking responsibly...",
    "Keeping it casual...", "Layering appropriately...", "Learning the specials...",
    "Licking the spoon...", "Loosening the belt...",
    "Making a fashionably late entrance...", "Marinating the chicken...",
    "Microwaving nothing...", "Mispronouncing the entrée...", "Nabbing the window seat...",
    "Nodding at the sommelier...", "Opening all the tabs...", "Ordering water first...",
    "Overthinking the appetizer...", "Pacing the block...", "Peeling the orange...",
    "Photographing the food...", "Picking the right emoji...", "Pivoting plans...",
    "Plating the garnish...", "Popping the cork...", "Powering through the menu...",
    "Pre-buttering the rolls...", "Procrastinating productively...",
    "Queuing strategically...", "Rearranging the chairs...",
    "Recalibrating expectations...", "Rehearsing the order...", "Reloading the gift card...",
    "Renaming the group chat...", "Repressing FOMO...", "Reviewing the fire exits...",
    "Rolling up the sleeves...", "Rotating the cheese board...", "Saving the corner seat...",
    "Scanning for celebrities...", "Seasoning to taste...", "Sharpening the steak knife...",
    "Shushing the table...", "Silencing the phone...", "Skimming the cocktail bible...",
    "Slicing the sourdough...", "Smelling the candles...", "Sneaking in snacks...",
    "Sorting by price...", "Squeezing the lemon...", "Straightening the tie...",
    "Surviving the wait list...", "Tasting the olive oil...", "Testing the acoustics...",
    "Toasting the bread...", "Turning on flashlight to view the menu...",
    "Uncorking the Malbec...", "Updating the story...", "Vibing...",
    "Whispering to the chef...", "Almost there...",
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

                        // Mutually exclusive, matching Compose priority:
                        // searching (idiom) > streaming text > typing dots.
                        if state.isSearching {
                            searchingIndicator.id("searching")
                        } else if !state.streamingText.isEmpty {
                            aiMessageBubble(state.streamingText)
                                .id("streaming")
                        } else if state.isTyping {
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
                    // Collapse the keyboard as soon as a plan card arrives so the
                    // generated plan is fully visible without the user dismissing.
                    if state.messages.last?.isPlanCard == true {
                        inputFocused = false
                    }
                }
                .onChange(of: state.streamingText) { _ in
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
                .onChange(of: state.isSearching) { _ in
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
            }

            // Quick replies (hidden during celebration)
            if !state.quickReplies.isEmpty && !state.showPlanSuccess {
                quickRepliesRow
            }

            // Input bar (hidden during celebration)
            if !state.showPlanSuccess {
                inputBar
            }
        }
        .background(C.snow)
        .overlay {
            if state.showPlanSuccess {
                PlanCelebrationOverlay(
                    days: state.days,
                    onSharePlan: {
                        // Fire the system share sheet for the plan we just
                        // saved. Do NOT clear the success overlay — the user
                        // can still tap View Plans or Home after sharing.
                        ChatBridge.shared.sharePlan?()
                    },
                    onViewPlans: {
                        // Switch the native tab bar to Plans (index 2), clear
                        // the success state in Kotlin, then dismiss the chat
                        // sheet so the user lands on the Plans tab.
                        TabBarBridge.shared.onTabSelected?(KotlinInt(integerLiteral: 2))
                        ChatBridge.shared.clearSuccessState?()
                        onDismiss()
                    }
                )
            }
        }
        .onAppear {
            ChatBridge.shared.onStateUpdate = { snapshot in
                state.update(from: snapshot)
            }
        }
    }

    // MARK: - Header

    private var chatHeader: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Button(action: onDismiss) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundColor(C.ink3)
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.plain)

                Text("Plan with Bo")
                    .font(F.display(22, .black))
                    .foregroundColor(C.ink)

                Spacer()

                Button(action: { ChatBridge.shared.resetChat?() }) {
                    Text("Start over")
                        .font(F.body(13, .bold))
                        .foregroundColor(C.ink3)
                        .frame(height: 28)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 18)
            .padding(.top, 12)
            .padding(.bottom, 10)

            Divider().background(C.pebble.opacity(0.5))
        }
    }

    // MARK: - Upgrade Banner

    private var upgradeBanner: some View {
        Button(action: onUpgrade) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Build personalized and shareable plans with Bo")
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
                    .font(F.body(15, .semibold))
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
        VStack(alignment: .leading, spacing: 3) {
            Text("Bo")
                .font(F.body(11, .bold))
                .foregroundColor(C.coral)
                .padding(.leading, 28) // line up with text after the sparkle
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "sparkles")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(C.coral)
                    .frame(width: 20)
                    .padding(.top, 3)
                markdownText(text)
            }
        }
    }

    private func markdownText(_ text: String) -> some View {
        let lines = text.components(separatedBy: "\n")
        return VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                if line.hasPrefix("- ") {
                    HStack(alignment: .top, spacing: 6) {
                        Text("•")
                            .font(F.body(15))
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
        var result = Text("")
        var remaining = text
        while let boldStart = remaining.range(of: "**") {
            let before = String(remaining[remaining.startIndex..<boldStart.lowerBound])
            if !before.isEmpty {
                result = result + Text(before).font(F.body(15)).foregroundColor(C.ink)
            }
            remaining = String(remaining[boldStart.upperBound...])
            if let boldEnd = remaining.range(of: "**") {
                let boldText = String(remaining[remaining.startIndex..<boldEnd.lowerBound])
                result = result + Text(boldText).font(F.body(15, .bold)).foregroundColor(C.ink)
                remaining = String(remaining[boldEnd.upperBound...])
            }
        }
        if !remaining.isEmpty {
            result = result + Text(remaining).font(F.body(15)).foregroundColor(C.ink)
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
                        MultiDayPlanCardView(days: days, weather: state.weather, showSave: false)
                            .opacity(0.3)
                        Text("Updated below")
                            .font(F.body(13, .bold))
                            .foregroundColor(C.ink3)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(.white)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(C.pebble, lineWidth: 1))
                    }
                } else {
                    MultiDayPlanCardView(days: days, weather: state.weather, showSave: !days.allSatisfy { $0.isApproved })
                }
            }
        )
    }

    // MARK: - Plan Success

    // planSuccessBanner replaced by PlanCelebrationOverlay (fullscreen)

    // MARK: - Indicators

    private var searchingIndicator: some View {
        SearchingIndicatorView()
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
                    Button(action: { ChatBridge.shared.onQuickReply?(reply.label, reply.action) }) {
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
                    .foregroundColor(C.ink)
                    .tint(C.coral)
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

// MARK: - Multi-Day Plan Card
// State-driven day switching via header arrows (not a pager).
// Slot pager within a day tracks position and updates the timeline + dot indicator.

private struct MultiDayPlanCardView: View {
    let days: [DaySnapshot]
    let weather: [KotlinInt: WeatherSnapshot]
    let showSave: Bool

    @State private var dayIndex: Int = 0
    @State private var slotPosition: [Int: Int] = [:]

    // Clamp to handle the case where `days` shrinks (plan update) while
    // dayIndex still points at a now-missing trailing day.
    private var safeDayIndex: Int {
        guard !days.isEmpty else { return 0 }
        return max(0, min(dayIndex, days.count - 1))
    }
    private var day: DaySnapshot { days[safeDayIndex] }
    private var color: Color { dayColors[safeDayIndex % dayColors.count] }
    private var activeSlot: Int { slotPosition[safeDayIndex] ?? 0 }

    var body: some View {
        if days.isEmpty {
            EmptyView()
        } else {
            content
        }
    }

    private var content: some View {
        VStack(spacing: 0) {
            dayHeader
            weatherBar
            timelineRow
            slotPager
            slotDots
            if showSave { saveButton }
        }
        .background(.white)
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(C.pebble, lineWidth: 1.5))
        .shadow(color: C.ink.opacity(0.06), radius: 8, x: 0, y: 3)
        .padding(.horizontal, 4)
        .padding(.bottom, 4)
    }

    // MARK: Header with DAY X OF Y + arrow controls

    private var dayHeader: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                if days.count > 1 {
                    Text("DAY \(dayIndex + 1) OF \(days.count)")
                        .font(F.body(10, .black))
                        .tracking(0.8)
                        .foregroundColor(.white.opacity(0.7))
                }
                Spacer()
                HStack(spacing: 16) {
                    if safeDayIndex > 0 {
                        Button {
                            let target = max(0, safeDayIndex - 1)
                            withAnimation(.easeOut(duration: 0.2)) { dayIndex = target }
                        } label: {
                            Image(systemName: "arrow.left")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(.white)
                        }
                        .contentShape(Rectangle())
                    }
                    if safeDayIndex < days.count - 1 {
                        Button {
                            let target = min(days.count - 1, safeDayIndex + 1)
                            withAnimation(.easeOut(duration: 0.2)) { dayIndex = target }
                        } label: {
                            Image(systemName: "arrow.right")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(.white)
                        }
                        .contentShape(Rectangle())
                    }
                }
            }
            Text(dayLabelText(day.label))
                .font(F.display(16, .black))
                .foregroundColor(.white)
            Text("\(day.slots.count) \(day.slots.count == 1 ? "stop" : "stops")")
                .font(F.body(10, .semibold))
                .foregroundColor(.white.opacity(0.7))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(color)
    }

    private func dayLabelText(_ raw: String) -> String {
        let cleaned = raw
            .replacingOccurrences(of: "Today ", with: "")
            .replacingOccurrences(of: "Tomorrow ", with: "")
        return cleaned.isEmpty ? "Your Plan" : cleaned
    }

    // MARK: Weather

    @ViewBuilder
    private var weatherBar: some View {
        if let w = weather[KotlinInt(value: Int32(dayIndex))] {
            Text("\(w.tempHigh)°F  \(w.description_)")
                .font(F.body(11, .bold))
                .foregroundColor(Color(hex2: 0x2C5F9E))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 6)
                .background(Color(hex2: 0xE8F1FF))
        }
    }

    // MARK: Timeline — dots + times, active grows + bolds

    private var timelineRow: some View {
        HStack(spacing: 0) {
            ForEach(Array(day.slots.enumerated()), id: \.element.id) { index, slot in
                let isActive = activeSlot == index
                HStack(spacing: 4) {
                    Circle()
                        .fill(isActive ? color : C.pebble)
                        .frame(width: isActive ? 8 : 6, height: isActive ? 8 : 6)
                    Text(slot.time)
                        .font(F.body(isActive ? 12 : 10, isActive ? .heavy : .semibold))
                        .foregroundColor(isActive ? C.ink : C.ink3)
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                }
                .layoutPriority(1)
                if index < day.slots.count - 1 {
                    Rectangle()
                        .fill(C.pebble.opacity(0.4))
                        .frame(height: 1)
                        .padding(.horizontal, 4)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 10)
        .padding(.bottom, 6)
        .animation(.easeInOut(duration: 0.2), value: activeSlot)
    }

    // MARK: Slot pager — paging scroll with peek, reports current slot

    private var slotPager: some View {
        let binding = Binding<Int?>(
            get: { slotPosition[safeDayIndex] ?? 0 },
            set: { new in slotPosition[safeDayIndex] = new ?? 0 }
        )
        // Card width = container - 48 so the next card peeks 24pt on the right.
        // Leading margin = 12, trailing = 36 so the last card snaps flush with no
        // phantom peek. viewAligned snaps each card's leading edge to the 12pt
        // content margin — never any left-side bleed.
        return ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 12) {
                ForEach(Array(day.slots.enumerated()), id: \.element.id) { idx, slot in
                    SlotCardView(slot: slot, color: color)
                        .containerRelativeFrame(.horizontal) { width, _ in width - 48 }
                        .id(idx)
                }
            }
            .scrollTargetLayout()
        }
        .contentMargins(.leading, 12, for: .scrollContent)
        .contentMargins(.trailing, 36, for: .scrollContent)
        .scrollTargetBehavior(.viewAligned)
        .scrollPosition(id: binding)
    }

    // MARK: Slot pagination dots

    @ViewBuilder
    private var slotDots: some View {
        if day.slots.count > 1 {
            HStack(spacing: 8) {
                ForEach(Array(day.slots.enumerated()), id: \.offset) { index, _ in
                    let selected = activeSlot == index
                    Capsule()
                        .fill(selected ? color : C.pebble)
                        .frame(width: selected ? 20 : 8, height: 8)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 10)
            .padding(.bottom, 6)
            .animation(.easeInOut(duration: 0.25), value: activeSlot)
        }
    }

    // MARK: Save CTA

    private var saveButton: some View {
        Button {
            // Direct approve — runs the gate + save in Kotlin without adding a
            // "Looks good…" bubble to the chat. On gate failure the paywall is
            // presented; on success the celebration/success state fires.
            ChatBridge.shared.approvePlan?()
        } label: {
            Text("Save to My Plans")
                .font(F.body(14, .heavy))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(C.sage)
                .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .padding(12)
    }
}

// MARK: - Slot Card

private struct SlotCardView: View {
    let slot: SlotSnapshot
    let color: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Group {
                if let urlStr = slot.imageUrl, let url = URL(string: urlStr) {
                    AsyncImage(url: url) { phase in
                        switch phase {
                        case .success(let image):
                            image.resizable().scaledToFill()
                        default:
                            color.opacity(0.15)
                        }
                    }
                } else {
                    color.opacity(0.15)
                }
            }
            .frame(height: 100)
            .frame(maxWidth: .infinity)
            .clipped()

            VStack(alignment: .leading, spacing: 3) {
                Text(slot.name)
                    .font(F.body(13, .bold))
                    .foregroundColor(C.ink)
                    .lineLimit(1)
                Text(slot.detail.isEmpty ? " " : slot.detail)
                    .font(F.body(11, .semibold))
                    .foregroundColor(C.ink3)
                    .lineLimit(2)
                    .frame(height: 32, alignment: .topLeading)
                HStack(spacing: 2) {
                    if let rating = slot.venueRating as? Double, rating > 0 {
                        Image(systemName: "star.fill")
                            .font(.system(size: 10))
                            .foregroundColor(C.sun)
                        Text(String(format: "%.1f", rating))
                            .font(F.body(11, .bold))
                            .foregroundColor(C.ink2)
                    }
                }
                .frame(height: 14)
            }
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .frame(height: 200)
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
}

// MARK: - Searching Indicator (rotating gerunds, fade transition)

private struct SearchingIndicatorView: View {
    @State private var phraseIndex: Int = Int.random(in: 0..<searchingPhrases.count)
    @State private var opacity: Double = 1.0

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "sparkles")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(C.coral)
                .frame(width: 20)
                .padding(.top, 3)
            Text(searchingPhrases[phraseIndex % searchingPhrases.count])
                .font(F.body(14, .semibold))
                .foregroundColor(C.ink3)
                .opacity(opacity)
        }
        .padding(.vertical, 2)
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                withAnimation(.easeIn(duration: 0.2)) { opacity = 0 }
                try? await Task.sleep(nanoseconds: 220_000_000)
                phraseIndex += 1
                withAnimation(.easeOut(duration: 0.4)) { opacity = 1 }
            }
        }
    }
}

// MARK: - Plan Celebration Overlay (fullscreen "Plan On!" takeover)

private struct PlanCelebrationOverlay: View {
    let days: [DaySnapshot]
    let onSharePlan: () -> Void
    let onViewPlans: () -> Void

    @State private var checkScale: CGFloat = 0
    @State private var ringScale: CGFloat = 0.2
    @State private var ringOpacity: Double = 0
    @State private var titleScale: CGFloat = 0
    @State private var subtitleOpacity: Double = 0
    @State private var cardOffset: CGFloat = 80
    @State private var cardOpacity: Double = 0
    @State private var ctaOffset: CGFloat = 60
    @State private var ctaOpacity: Double = 0

    private var dateSubtitle: String {
        guard !days.isEmpty else { return "" }
        if days.count == 1 {
            let label = days[0].label
            let totalStops = days[0].slots.count
            return "\(label) · \(totalStops) \(totalStops == 1 ? "stop" : "stops")"
        }
        let first = days.first?.label ?? ""
        let last = days.last?.label ?? ""
        let totalStops = days.flatMap { $0.slots }.count
        return "\(first) – \(last) · \(totalStops) stops"
    }

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 1.0, green: 0.42, blue: 0.42),
                    Color(red: 1.0, green: 0.31, blue: 0.31),
                    Color(red: 0.91, green: 0.26, blue: 0.24),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            Circle()
                .fill(.white.opacity(0.08))
                .frame(width: 200, height: 200)
                .offset(x: 100, y: -60)
            Circle()
                .fill(.black.opacity(0.06))
                .frame(width: 240, height: 240)
                .offset(x: -80, y: 300)

            VStack(spacing: 0) {
                Spacer().frame(height: 50)

                // Check circle with ring
                ZStack {
                    Circle()
                        .stroke(.white.opacity(0.6), lineWidth: 3)
                        .frame(width: 90, height: 90)
                        .scaleEffect(ringScale)
                        .opacity(ringOpacity)
                    Circle()
                        .fill(.white)
                        .frame(width: 80, height: 80)
                        .scaleEffect(checkScale)
                        .overlay(
                            Image(systemName: "checkmark")
                                .font(.system(size: 36, weight: .bold))
                                .foregroundColor(C.coral)
                                .scaleEffect(checkScale)
                        )
                }

                Spacer().frame(height: 14)

                Text("Plan On!")
                    .font(F.display(34, .black))
                    .foregroundColor(.white)
                    .scaleEffect(titleScale)

                Spacer().frame(height: 6)

                Text(dateSubtitle.uppercased())
                    .font(F.body(12, .bold))
                    .tracking(0.5)
                    .foregroundColor(.white.opacity(0.75))
                    .opacity(subtitleOpacity)

                Spacer().frame(height: 20)

                // Plan summary card — full rich plan card (same as chat)
                if !days.isEmpty {
                    MultiDayPlanCardView(days: days, weather: [:], showSave: false)
                        .offset(y: cardOffset)
                        .opacity(cardOpacity)
                        .padding(.horizontal, 14)
                }

                Spacer()

                // CTAs — match Compose: Share Plan, View Plans, Home
                VStack(spacing: 8) {
                    Button(action: onSharePlan) {
                        HStack(spacing: 9) {
                            Image(systemName: "square.and.arrow.up")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundColor(C.coral)
                            Text("Share Plan")
                                .font(F.display(15, .heavy))
                                .foregroundColor(C.coral)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 15)
                        .background(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                    }

                    Button(action: onViewPlans) {
                        Text("View Plans")
                            .font(F.display(14, .heavy))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 13)
                            .background(.white.opacity(0.2))
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                            .overlay(
                                RoundedRectangle(cornerRadius: 14)
                                    .stroke(.white.opacity(0.4), lineWidth: 1.5)
                            )
                    }

                }
                .offset(y: ctaOffset)
                .opacity(ctaOpacity)
                .padding(.horizontal, 18)
                .padding(.bottom, 32)
            }
        }
        .task { await runAnimations() }
    }

    // MARK: - Animations

    private func runAnimations() async {
        // Medium impact feedback — fires as each piece floats onto the screen.
        // 5 hits total: check, title, subtitle, card, CTAs.
        let haptic = UIImpactFeedbackGenerator(style: .medium)
        haptic.prepare()

        try? await Task.sleep(nanoseconds: 300_000_000)
        withAnimation(.easeOut(duration: 0.8)) {
            ringScale = 3.2
            ringOpacity = 0.6
        }
        withAnimation(.easeOut(duration: 0.7).delay(0.1)) {
            ringOpacity = 0
        }

        try? await Task.sleep(nanoseconds: 100_000_000)
        haptic.impactOccurred()
        withAnimation(.spring(response: 0.5, dampingFraction: 0.6)) {
            checkScale = 1
        }

        try? await Task.sleep(nanoseconds: 450_000_000)
        haptic.impactOccurred()
        withAnimation(.spring(response: 0.5, dampingFraction: 0.6)) {
            titleScale = 1
        }

        try? await Task.sleep(nanoseconds: 250_000_000)
        haptic.impactOccurred()
        withAnimation(.easeOut(duration: 0.4)) {
            subtitleOpacity = 1
        }

        try? await Task.sleep(nanoseconds: 200_000_000)
        haptic.impactOccurred()
        withAnimation(.easeOut(duration: 0.5)) {
            cardOffset = 0
            cardOpacity = 1
        }

        try? await Task.sleep(nanoseconds: 300_000_000)
        haptic.impactOccurred()
        withAnimation(.easeOut(duration: 0.4)) {
            ctaOffset = 0
            ctaOpacity = 1
        }
    }
}
