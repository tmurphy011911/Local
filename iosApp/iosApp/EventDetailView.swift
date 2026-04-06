import SwiftUI
import UIKit

// MARK: - Event Data Model (Swift side)

struct EventDetailData {
    let id: String
    let title: String
    let description: String?
    let venueDescription: String?
    let imageUrl: String?
    let startAt: String?
    let venueName: String?
    let venueAddress: String?
    let venueCity: String
    let venueLat: Double
    let venueLng: Double
    let isFree: Bool
    let priceDisplay: String
    let ticketUrl: String?
    let sourceUrl: String?
    let category: String
    let subcategory: String?
    let source: String?
    var showDirections: Bool = false

    var ctaUrl: String? { ticketUrl ?? sourceUrl }
    var hasLocation: Bool { venueLat != 0 && venueLng != 0 }

    var categoryDisplay: String {
        let raw = subcategory ?? category
        return raw.split(separator: "_").map { word in
            word.prefix(1).uppercased() + word.dropFirst().lowercased()
        }.joined(separator: " ")
    }

    var descriptionSource: String {
        guard let src = source?.lowercased() else { return venueName ?? "Venue" }
        if src == "ticketmaster" { return "Ticketmaster" }
        if let name = venueName { return name }
        return src.prefix(1).uppercased() + src.dropFirst()
    }

    var ctaLabel: String {
        if isFree { return "RSVP" }
        if ticketUrl != nil { return "Get Tickets" }
        return "More Info"
    }
}

// MARK: - Colors

private enum AppColor {
    static let coral     = Color(hex: 0xFF6B6B)
    static let coralDk   = Color(hex: 0xE85D5D)
    static let coralLt   = Color(hex: 0xFFF0F0)
    static let ink       = Color(hex: 0x1A1523)
    static let ink2      = Color(hex: 0x4A3F5C)
    static let ink3      = Color(hex: 0x8B7FA0)
    static let snow      = Color(hex: 0xFAF8FC)
    static let pebble    = Color(hex: 0xE0DCE8)
    static let sky       = Color(hex: 0x5B9CF6)
    static let skyLt     = Color(hex: 0xEEF6FF)
    static let sage      = Color(hex: 0x4ECBA0)
    static let mist      = Color(hex: 0xF0EDF6)
    static let white     = Color.white
}

// MARK: - Fonts

private enum AppFont {
    static func heading(_ size: CGFloat, weight: Font.Weight = .bold) -> Font {
        return .system(size: size, weight: weight, design: .rounded)
    }

    static func body(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        return .system(size: size, weight: weight, design: .rounded)
    }
}

private extension Color {
    init(hex: UInt) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0
        )
    }
}

// MARK: - Countdown

private struct Countdown {
    var days: Int = 0
    var hours: Int = 0
    var minutes: Int = 0
    var seconds: Int = 0
    var isLive: Bool = false
    var isPast: Bool = false
    var hasDate: Bool = false
}

private func computeCountdown(startAt: String?) -> Countdown {
    guard let startAt = startAt else { return Countdown() }

    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    guard let eventDate = formatter.date(from: startAt) ?? {
        // Try without fractional seconds
        let f2 = ISO8601DateFormatter()
        f2.formatOptions = [.withInternetDateTime]
        return f2.date(from: startAt)
    }() else { return Countdown() }

    let totalSeconds = Int(eventDate.timeIntervalSinceNow)

    if totalSeconds >= -14400 && totalSeconds <= 0 {
        return Countdown(isLive: true, hasDate: true)
    } else if totalSeconds < -14400 {
        return Countdown(isPast: true, hasDate: true)
    } else {
        let d = totalSeconds / 86400
        let h = (totalSeconds % 86400) / 3600
        let m = (totalSeconds % 3600) / 60
        let s = totalSeconds % 60
        return Countdown(days: d, hours: h, minutes: m, seconds: s, hasDate: true)
    }
}

// MARK: - Date Formatting

private func formatDate(_ isoDate: String) -> String {
    let isMidnightUtc = isoDate.contains("T00:00:00")

    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    guard let date = formatter.date(from: isoDate) ?? {
        let f2 = ISO8601DateFormatter()
        f2.formatOptions = [.withInternetDateTime]
        return f2.date(from: isoDate)
    }() else { return isoDate }

    let cal = Calendar.current
    let months = ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"]

    if isMidnightUtc {
        // Use UTC components
        var utcCal = Calendar.current
        utcCal.timeZone = TimeZone(identifier: "UTC")!
        let comps = utcCal.dateComponents([.year, .month, .day], from: date)
        let month = months[(comps.month ?? 1) - 1]
        return "\(month) \(comps.day ?? 1), \(comps.year ?? 2026)"
    }

    let comps = cal.dateComponents([.year, .month, .day, .hour, .minute], from: date)
    let month = months[(comps.month ?? 1) - 1]
    let day = comps.day ?? 1
    let year = comps.year ?? 2026
    let hour = comps.hour ?? 0
    let minute = comps.minute ?? 0
    let amPm = hour < 12 ? "AM" : "PM"
    let hour12 = hour == 0 ? 12 : (hour > 12 ? hour - 12 : hour)
    let minuteStr = String(format: "%02d", minute)
    return "\(month) \(day), \(year) \u{00B7} \(hour12):\(minuteStr) \(amPm)"
}

// MARK: - Recurrence Detection

private func detectRecurrence(title: String, description: String?, category: String, startAt: String?) -> String? {
    let text = "\(title) \(description ?? "")".lowercased()
    let isAlwaysOn = category == "always_on"

    let dayPatterns: [(String, String)] = [
        ("sunday", "Happens Every Sunday"),
        ("monday", "Happens Every Monday"),
        ("tuesday", "Happens Every Tuesday"),
        ("wednesday", "Happens Every Wednesday"),
        ("thursday", "Happens Every Thursday"),
        ("friday", "Happens Every Friday"),
        ("saturday", "Happens Every Saturday"),
    ]

    for (day, label) in dayPatterns {
        if text.contains("every \(day)") || text.contains("\(day)s") ||
            (text.contains(day) && (text.contains("weekly") || text.contains("every week"))) {
            return label
        }
    }

    if text.contains("weekly") || text.contains("every week") { return "Happens Weekly" }
    if text.contains("monthly") || text.contains("every month") ||
        text.contains("first friday") || text.contains("first saturday") ||
        text.contains("last friday") || text.contains("second saturday") ||
        text.contains("third thursday") { return "Happens Monthly" }
    if text.contains("daily") || text.contains("every day") || text.contains("everyday") { return "Happens Daily" }
    if isAlwaysOn && startAt == nil { return "Available Anytime" }
    if isAlwaysOn && startAt != nil { return "Recurring Event" }

    return nil
}

// MARK: - EventDetailView

struct EventDetailView: View {
    let event: EventDetailData
    let onDismiss: () -> Void
    var onFavorite: ((String) -> Void)? = nil

    @State private var countdown: Countdown
    @State private var isFavorited = false
    @State private var mapsExpanded = false
    @State private var rideExpanded = false
    @State private var shareCopied = false

    init(event: EventDetailData, onDismiss: @escaping () -> Void, onFavorite: ((String) -> Void)? = nil) {
        self.event = event
        self.onFavorite = onFavorite
        self.onDismiss = onDismiss
        self._countdown = State(initialValue: computeCountdown(startAt: event.startAt))
    }

    var body: some View {
        GeometryReader { geo in
            VStack(spacing: 0) {
                ScrollView(.vertical, showsIndicators: false) {
                    VStack(spacing: 0) {
                        heroImage
                        contentSection
                    }
                    .frame(width: geo.size.width)
                }
                ctaBar
                    .frame(width: geo.size.width)
            }
        }
        .background(AppColor.snow)
        .onReceive(Timer.publish(every: 1, on: .main, in: .common).autoconnect()) { _ in
            countdown = computeCountdown(startAt: event.startAt)
        }
        .onChange(of: shareCopied) { copied in
            if copied {
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                    shareCopied = false
                }
            }
        }
    }

    // MARK: - Hero Image

    private var heroImage: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                // Background
                Color.black

                // Image — constrained to geo width so .fill doesn't overflow
                if let urlStr = event.imageUrl, let url = URL(string: urlStr) {
                    AsyncImage(url: url) { phase in
                        switch phase {
                        case .success(let image):
                            image.resizable().scaledToFill()
                                .frame(width: geo.size.width, height: 280)
                                .clipped()
                        case .failure:
                            placeholderGradient
                        case .empty:
                            AppColor.mist
                        @unknown default:
                            placeholderGradient
                        }
                    }
                } else {
                    placeholderGradient
                }

                // Gradient fade at bottom
                VStack {
                    Spacer()
                    LinearGradient(colors: [.clear, AppColor.snow], startPoint: .top, endPoint: .bottom)
                        .frame(height: 120)
                }

                // Category pill — bottom left
                VStack {
                    Spacer()
                    Text(event.categoryDisplay)
                        .font(AppFont.body(11, weight: .heavy))
                        .foregroundColor(AppColor.ink2)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(AppColor.white)
                        .clipShape(Capsule())
                        .overlay(Capsule().stroke(AppColor.pebble, lineWidth: 1.5))
                        .padding(.bottom, 16)
                }
                .padding(.leading, 20)

                // Back button — top left
                heroIconButton(systemName: "arrow.left") { onDismiss() }
                    .padding(.leading, 16)
                    .padding(.top, 52)

                // Share + Favorite — top right, horizontal
                VStack {
                    HStack(spacing: 8) {
                        Spacer()
                        heroIconButton(systemName: "square.and.arrow.up") {
                            UIPasteboard.general.string = event.ctaUrl ?? "https://localapp.live/e/\(event.id)"
                            shareCopied = true
                        }
                        heroIconButton(systemName: isFavorited ? "heart.fill" : "heart") {
                            let wasFavorited = isFavorited
                            isFavorited.toggle()
                            if !wasFavorited {
                                onFavorite?(event.id)
                            }
                        }
                    }
                    .padding(.trailing, 16)
                    .padding(.top, 52)
                    Spacer()
                }
            }
        }
        .frame(height: 280)
    }

    private var placeholderGradient: some View {
        let (start, end) = categoryGradientColors(event.subcategory ?? event.category)
        return LinearGradient(
            colors: [start, end],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }

    private func categoryGradientColors(_ category: String) -> (Color, Color) {
        switch category.lowercased() {
        case "music": return (Color(hex: 0xF5F0FF), Color(hex: 0xEDE0FF))
        case "sports": return (Color(hex: 0xEEF5FF), Color(hex: 0xDCEEFF))
        case "arts": return (Color(hex: 0xEDFAF5), Color(hex: 0xD5F5E8))
        case "comedy", "food": return (Color(hex: 0xFFF8EC), Color(hex: 0xFFF0CC))
        case "outdoors": return (Color(hex: 0xEDFAF5), Color(hex: 0xD5F5E8))
        case "nightlife": return (Color(hex: 0xF5F0FF), Color(hex: 0xEDE0FF))
        default: return (Color(hex: 0xFFF0EE), Color(hex: 0xFFE0DC))
        }
    }

    private var placeholderImage: some View {
        ZStack {
            placeholderGradient
            Image(systemName: categorySystemIcon(event.subcategory ?? event.category))
                .font(.system(size: 48))
                .foregroundColor(AppColor.ink3.opacity(0.3))
        }
        .frame(height: 280)
    }

    private func categorySystemIcon(_ category: String) -> String {
        switch category.lowercased() {
        case "music": return "music.note"
        case "sports": return "sportscourt"
        case "arts": return "paintpalette"
        case "comedy": return "theatermasks"
        case "food": return "fork.knife"
        case "outdoors": return "leaf"
        case "nightlife": return "moon.stars"
        default: return "calendar"
        }
    }

    private func heroIconButton(systemName: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(AppFont.body(14, weight: .semibold))
                .foregroundColor(AppColor.ink)
                .frame(width: 36, height: 36)
                .background(AppColor.white.opacity(0.9))
                .clipShape(Circle())
                .overlay(Circle().stroke(AppColor.pebble, lineWidth: 1.5))
        }
    }

    // MARK: - Content

    private var contentSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Title
            Text(event.title)
                .font(AppFont.heading(24, weight: .bold))
                .foregroundColor(AppColor.ink)
                .lineSpacing(6)

            Spacer().frame(height: 14)

            // Detect recurrence first
            let recurrence = detectRecurrence(
                title: event.title,
                description: event.description,
                category: event.category,
                startAt: event.startAt
            )
            let isRecurring = recurrence != nil

            // Countdown — skip for recurring events
            if countdown.hasDate && !isRecurring {
                countdownBanner
                Spacer().frame(height: 16)
            }

            // Recurring indicator
            if let recurrence = recurrence {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.clockwise")
                        .font(AppFont.body(12, weight: .bold))
                        .foregroundColor(AppColor.sky)
                    Text(recurrence)
                        .font(AppFont.body(12, weight: .heavy))
                        .foregroundColor(AppColor.sky)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(AppColor.skyLt)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(AppColor.sky, lineWidth: 1.5))

                Spacer().frame(height: 10)
            }

            // Date — skip for recurring events
            if let startAt = event.startAt, !isRecurring {
                infoRow(
                    systemName: "calendar",
                    iconColor: AppColor.coral,
                    text: formatDate(startAt)
                )
                Spacer().frame(height: 10)
            }

            // Venue
            if let venueName = event.venueName {
                let venueText = event.venueAddress != nil
                    ? "\(venueName) \u{00B7} \(event.venueAddress!)"
                    : venueName
                infoRow(
                    systemName: "mappin.and.ellipse",
                    iconColor: AppColor.sky,
                    text: venueText
                )
                Spacer().frame(height: 10)
            }

            // Price
            if !event.priceDisplay.isEmpty {
                infoRow(
                    systemName: "clock",
                    iconColor: event.isFree ? AppColor.sage : AppColor.ink2,
                    text: event.priceDisplay,
                    textColor: event.isFree ? AppColor.sage : AppColor.ink
                )
                Spacer().frame(height: 16)
            }

            // Description
            if let desc = event.description, !desc.isEmpty {
                Text("Description from \(event.descriptionSource)")
                    .font(AppFont.body(11, weight: .heavy))
                    .foregroundColor(AppColor.ink3)
                    .tracking(0.3)

                Spacer().frame(height: 6)

                Text(desc)
                    .font(AppFont.body(14, weight: .semibold))
                    .foregroundColor(AppColor.ink2)
                    .lineSpacing(8)
            }

            // Venue description
            if let venueDesc = event.venueDescription, !venueDesc.isEmpty, let venueName = event.venueName {
                Spacer().frame(height: 20)

                Text("About \(venueName)")
                    .font(AppFont.body(11, weight: .heavy))
                    .foregroundColor(AppColor.ink3)
                    .tracking(0.3)

                Spacer().frame(height: 6)

                Text(venueDesc)
                    .font(AppFont.body(14, weight: .semibold))
                    .foregroundColor(AppColor.ink2)
                    .lineSpacing(8)
            }

            Spacer().frame(height: 24)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
    }

    // MARK: - Countdown Banner

    private var countdownBanner: some View {
        ZStack(alignment: .bottom) {
            // Shadow layer
            RoundedRectangle(cornerRadius: 16)
                .fill(AppColor.coralDk)
                .offset(y: 4)

            // Main banner
            ZStack {
                RoundedRectangle(cornerRadius: 16)
                    .fill(AppColor.coral)
                RoundedRectangle(cornerRadius: 16)
                    .stroke(AppColor.coralDk, lineWidth: 1.5)

                Group {
                    if countdown.isLive {
                        HStack(spacing: 8) {
                            Circle()
                                .fill(.white)
                                .frame(width: 8, height: 8)
                            Text("Happening now")
                                .font(AppFont.heading(18, weight: .bold))
                                .foregroundColor(.white)
                            Spacer()
                        }
                    } else if countdown.isPast {
                        HStack {
                            Text("This event has passed")
                                .font(AppFont.body(14, weight: .semibold))
                                .foregroundColor(.white.opacity(0.78))
                            Spacer()
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("STARTS IN")
                                .font(AppFont.body(10, weight: .black))
                                .foregroundColor(.white.opacity(0.75))
                                .tracking(1)

                            HStack(alignment: .bottom, spacing: 4) {
                                countdownUnit(value: countdown.days, label: "days")
                                countdownSep
                                countdownUnit(value: countdown.hours, label: "hrs")
                                countdownSep
                                countdownUnit(value: countdown.minutes, label: "min")
                                countdownSep
                                countdownUnit(value: countdown.seconds, label: "sec")
                                Spacer()
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 14)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private func countdownUnit(value: Int, label: String) -> some View {
        VStack(spacing: 0) {
            Text(String(format: "%02d", value))
                .font(.system(size: 28, weight: .black))
                .foregroundColor(.white)
                .tracking(-0.5)
            Text(label)
                .font(.system(size: 9, weight: .heavy))
                .foregroundColor(.white.opacity(0.75))
                .tracking(0.3)
        }
    }

    private var countdownSep: some View {
        Text("·")
            .font(.system(size: 20, weight: .black))
            .foregroundColor(.white.opacity(0.6))
            .padding(.bottom, 14)
    }

    // MARK: - Info Row

    private func infoRow(systemName: String, iconColor: Color, text: String, textColor: Color = AppColor.ink2) -> some View {
        HStack(alignment: .top, spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 8)
                    .fill(AppColor.mist)
                RoundedRectangle(cornerRadius: 8)
                    .stroke(AppColor.pebble, lineWidth: 1.5)
                Image(systemName: systemName)
                    .font(AppFont.body(13, weight: .medium))
                    .foregroundColor(iconColor)
            }
            .frame(width: 32, height: 32)

            Text(text)
                .font(AppFont.body(13, weight: .bold))
                .foregroundColor(textColor)
                .lineSpacing(5)
                .padding(.top, 6)
        }
    }

    // MARK: - CTA Bar

    private var ctaBar: some View {
        VStack(spacing: 10) {
            // Secondary row — only from Plans
            if event.showDirections {
                HStack(spacing: 8) {
                    if event.hasLocation {
                        secondaryButton(
                            systemName: "mappin.and.ellipse",
                            label: mapsExpanded ? "Close" : "Directions",
                            isActive: mapsExpanded
                        ) {
                            withAnimation(.easeInOut(duration: 0.2)) {
                                mapsExpanded.toggle()
                                if mapsExpanded { rideExpanded = false }
                            }
                        }
                    }

                    if event.hasLocation {
                        secondaryButton(
                            systemName: "car.fill",
                            label: rideExpanded ? "Close" : "Get a Ride",
                            isActive: rideExpanded
                        ) {
                            withAnimation(.easeInOut(duration: 0.2)) {
                                rideExpanded.toggle()
                                if rideExpanded { mapsExpanded = false }
                            }
                        }
                    }
                }

            }

            // Expandable maps
            if mapsExpanded {
                HStack(spacing: 8) {
                    expandedOption(label: "Apple Maps") {
                        let urlStr = "https://maps.apple.com/?q=\(event.venueName ?? "")&ll=\(event.venueLat),\(event.venueLng)"
                        if let url = URL(string: urlStr.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? urlStr) {
                            UIApplication.shared.open(url)
                        }
                    }
                    expandedOption(label: "Google Maps") {
                        if let url = URL(string: "https://maps.google.com/?q=\(event.venueLat),\(event.venueLng)") {
                            UIApplication.shared.open(url)
                        }
                    }
                }
                .transition(.move(edge: .top).combined(with: .opacity))
            }

            // Expandable ride
            if rideExpanded {
                HStack(spacing: 8) {
                    expandedOption(label: "Uber") {
                        let urlStr = "https://m.uber.com/ul/?action=setPickup&pickup=my_location&dropoff[latitude]=\(event.venueLat)&dropoff[longitude]=\(event.venueLng)&dropoff[nickname]=\(event.venueName ?? "")"
                        if let url = URL(string: urlStr.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? urlStr) {
                            UIApplication.shared.open(url)
                        }
                    }
                    expandedOption(label: "Lyft") {
                        if let url = URL(string: "https://lyft.com/ride?id=lyft&destination[lat]=\(event.venueLat)&destination[lng]=\(event.venueLng)") {
                            UIApplication.shared.open(url)
                        }
                    }
                }
                .transition(.move(edge: .top).combined(with: .opacity))
            }

            // Primary CTA
            if event.ctaUrl != nil {
                primaryButton(label: event.ctaLabel, isPrimary: event.ticketUrl != nil) {
                    if let urlStr = event.ctaUrl, let url = URL(string: urlStr) {
                        UIApplication.shared.open(url)
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 24)
        .background(AppColor.white)
        .overlay(alignment: .top) {
            Rectangle().fill(AppColor.mist).frame(height: 1)
        }
    }

    private func primaryButton(label: String, isPrimary: Bool, action: @escaping () -> Void) -> some View {
        let bgColor = isPrimary ? AppColor.coral : AppColor.ink2
        let shadowColor = isPrimary ? AppColor.coralDk : Color(hex: 0x0D0B12)

        return Button(action: action) {
            Text(label)
                .font(AppFont.heading(16, weight: .heavy))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(bgColor)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(shadowColor, lineWidth: 2))
                .shadow(color: shadowColor.opacity(0.4), radius: 0, x: 0, y: 4)
        }
        .buttonStyle(.plain)
    }

    private func secondaryButton(systemName: String, label: String, isActive: Bool, action: @escaping () -> Void) -> some View {
        let bgColor = isActive ? AppColor.coralLt : AppColor.snow
        let borderColor = isActive ? AppColor.coral : AppColor.pebble
        let contentColor = isActive ? AppColor.coral : AppColor.ink2

        return Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: systemName)
                    .font(AppFont.body(14, weight: .medium))
                    .foregroundColor(contentColor)
                Text(label)
                    .font(AppFont.body(10, weight: .heavy))
                    .foregroundColor(contentColor)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .background(bgColor)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(borderColor, lineWidth: 1.5))
        }
        .buttonStyle(.plain)
    }

    private func expandedOption(label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(AppFont.body(13, weight: .heavy))
                .foregroundColor(AppColor.ink)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(AppColor.snow)
                .clipShape(RoundedRectangle(cornerRadius: 10))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(AppColor.pebble, lineWidth: 1.5))
        }
        .buttonStyle(.plain)
    }
}
