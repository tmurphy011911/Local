import SwiftUI
import UIKit
import ComposeApp

// MARK: - Venue Data Model (Swift side)

struct VenueUpcomingEvent: Identifiable {
    let id: String
    let title: String
    let startAt: String?
    let category: String?
    let subcategory: String?
    let imageUrl: String?
    let isFree: Bool
    let priceDisplay: String?
    let dateDisplay: String?
}

struct VenueDetailData {
    let name: String
    let address: String?
    let city: String
    let lat: Double
    let lng: Double
    let photoUrl: String?
    let description: String?
    let category: String?
    let googleRating: Double?
    let website: String?
    let distanceMiles: Double?
    let upcomingEventCount: Int
    let upcomingEvents: [VenueUpcomingEvent]
    let reservationUrl: String?

    var hasLocation: Bool { lat != 0 && lng != 0 }

    var categoryDisplay: String {
        guard let raw = category else { return "" }
        return raw.split(separator: "_").map { word in
            word.prefix(1).uppercased() + word.dropFirst().lowercased()
        }.joined(separator: " ")
    }

    var subtitleParts: String {
        var parts: [String] = []
        if let cat = category, !cat.isEmpty {
            parts.append(categoryDisplay)
        }
        parts.append(city)
        if let dist = distanceMiles {
            parts.append(formatDistance(dist))
        }
        return parts.joined(separator: " \u{00B7} ")
    }

    private func formatDistance(_ miles: Double) -> String {
        if miles < 0.1 { return "Nearby" }
        if miles < 10 { return String(format: "%.1f mi", miles) }
        return String(format: "%.0f mi", miles)
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
    static let sun       = Color(hex: 0xFFB347)
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

// MARK: - VenueDetailView

struct VenueDetailView: View {
    let venue: VenueDetailData
    let onDismiss: () -> Void

    @State private var mapsExpanded = false
    @State private var selectedDateFilter: DateFilter = .all

    enum DateFilter: String, CaseIterable {
        case all = "All"
        case today = "Today"
        case tomorrow = "Tomorrow"
        case thisWeek = "This Week"
        case thisWeekend = "Weekend"
    }

    private func filteredEvents() -> [VenueUpcomingEvent] {
        let cal = Calendar.current
        let now = Date()
        let todayStart = cal.startOfDay(for: now)

        return venue.upcomingEvents.filter { event in
            guard let startAt = event.startAt, let date = parseISO(startAt) else { return true }
            switch selectedDateFilter {
            case .all: return true
            case .today:
                return cal.isDateInToday(date)
            case .tomorrow:
                return cal.isDateInTomorrow(date)
            case .thisWeek:
                let weekEnd = cal.date(byAdding: .day, value: 7, to: todayStart)!
                return date >= todayStart && date < weekEnd
            case .thisWeekend:
                let weekday = cal.component(.weekday, from: date)
                let isWeekend = weekday == 1 || weekday == 7 // Sun or Sat
                let weekEnd = cal.date(byAdding: .day, value: 7, to: todayStart)!
                return isWeekend && date >= todayStart && date < weekEnd
            }
        }
    }

    private func parseISO(_ str: String) -> Date? {
        let fmt = ISO8601DateFormatter()
        fmt.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = fmt.date(from: str) { return d }
        fmt.formatOptions = [.withInternetDateTime]
        return fmt.date(from: str)
    }

    private func eventMonthDay(_ event: VenueUpcomingEvent) -> (month: String, day: String, weekday: String) {
        guard let startAt = event.startAt, let date = parseISO(startAt) else {
            return ("", "", "")
        }
        let mf = DateFormatter()
        mf.dateFormat = "MMM"
        let df = DateFormatter()
        df.dateFormat = "d"
        let wf = DateFormatter()
        wf.dateFormat = "EEE"
        return (mf.string(from: date).uppercased(), df.string(from: date), wf.string(from: date).uppercased())
    }

    var body: some View {
        GeometryReader { geo in
            VStack(spacing: 0) {
                ScrollView(.vertical, showsIndicators: false) {
                    VStack(spacing: 0) {
                        heroImage(width: geo.size.width)
                        contentSection
                    }
                    .frame(width: geo.size.width)
                }
                ctaBar
                    .frame(width: geo.size.width)
            }
        }
        .background(AppColor.snow)
    }

    // MARK: - Hero Image

    private func heroImage(width: CGFloat) -> some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                // Background
                Color.black

                // Image
                if let urlStr = venue.photoUrl, let url = URL(string: urlStr) {
                    AsyncImage(url: url) { phase in
                        switch phase {
                        case .success(let image):
                            image.resizable().scaledToFill()
                                .frame(width: geo.size.width, height: 200)
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
                        .frame(height: 80)
                }

                // Category pill — bottom left
                if let cat = venue.category, !cat.isEmpty {
                    VStack {
                        Spacer()
                        Text(venue.categoryDisplay)
                            .font(.system(size: 11, weight: .heavy))
                            .foregroundColor(AppColor.ink2)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(AppColor.white)
                            .clipShape(Capsule())
                            .overlay(Capsule().stroke(AppColor.pebble, lineWidth: 1.5))
                            .padding(.bottom, 16)
                    }
                    .padding(.leading, 20)
                }

                // Top buttons
                HStack {
                    // Back button — top left
                    heroIconButton(systemName: "arrow.left") { onDismiss() }

                    Spacer()

                    // Share button — top right
                    heroIconButton(systemName: "square.and.arrow.up") {
                        let encodedName = venue.name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? venue.name
                        let shareUrl = "https://localapp.live/v/\(encodedName)"
                        ShareBridge.shared.presentShareSheet?(venue.name, shareUrl)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 52)
            }
        }
        .frame(height: 200)
    }

    private var placeholderGradient: some View {
        LinearGradient(
            colors: [Color(hex: 0xFFE8E0), Color(hex: 0xFFF0E8)],
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }

    private func heroIconButton(systemName: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 14, weight: .semibold))
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
            // Venue name
            Text(venue.name)
                .font(.system(size: 24, weight: .bold))
                .foregroundColor(AppColor.ink)
                .lineSpacing(6)

            Spacer().frame(height: 4)

            // Subtitle: Category · City · Distance
            if !venue.subtitleParts.isEmpty {
                Text(venue.subtitleParts)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(AppColor.ink3)
            }

            Spacer().frame(height: 14)

            // Google Rating
            if let rating = venue.googleRating, rating > 0 {
                HStack(spacing: 4) {
                    starsView(rating: rating)
                    Text(String(format: "%.1f", rating))
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(AppColor.ink2)
                }

                Spacer().frame(height: 14)
            }

            // Description
            if let desc = venue.description, !desc.isEmpty {
                Text(desc)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(AppColor.ink2)
                    .lineSpacing(8)

                Spacer().frame(height: 16)
            }

            // Upcoming Events — compact list with date filter
            if !venue.upcomingEvents.isEmpty {
                Spacer().frame(height: 8)

                Text("Upcoming Events")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(AppColor.ink)

                Spacer().frame(height: 10)

                // Date filter chips — matches plan detail day tabs style
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(DateFilter.allCases, id: \.self) { filter in
                            Text(filter.rawValue.uppercased())
                                .font(.system(size: 12, weight: .heavy))
                                .foregroundColor(selectedDateFilter == filter ? .white : AppColor.ink3)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 8)
                                .background(selectedDateFilter == filter ? AppColor.coral : AppColor.snow)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                                .onTapGesture { selectedDateFilter = filter }
                        }
                    }
                }

                Spacer().frame(height: 12)

                // Event list
                let events = filteredEvents()
                if events.isEmpty {
                    Text("No events for this period")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(AppColor.ink3)
                        .padding(.vertical, 16)
                        .frame(maxWidth: .infinity)
                } else {
                    ForEach(events) { event in
                        eventListRow(event)
                    }
                }
            } else if venue.upcomingEventCount > 0 {
                Spacer().frame(height: 8)

                Text("Upcoming Events")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(AppColor.ink)

                Spacer().frame(height: 8)

                Text("\(venue.upcomingEventCount) upcoming event\(venue.upcomingEventCount == 1 ? "" : "s") at this venue")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(AppColor.ink3)
                    .padding(.vertical, 12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .background(AppColor.mist)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }

            Spacer().frame(height: 32)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
    }

    private func starsView(rating: Double) -> some View {
        HStack(spacing: 2) {
            ForEach(1...5, id: \.self) { star in
                let fill: Double = min(max(rating - Double(star - 1), 0), 1)
                Image(systemName: fill >= 0.75 ? "star.fill" : (fill >= 0.25 ? "star.leadinghalf.filled" : "star"))
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(AppColor.sun)
            }
        }
    }

    // MARK: - Upcoming Event Row

    private func upcomingEventRow(_ event: VenueUpcomingEvent) -> some View {
        HStack(spacing: 10) {
            // Thumbnail
            if let urlStr = event.imageUrl, let url = URL(string: urlStr) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().scaledToFill()
                            .frame(width: 56, height: 56)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    default:
                        eventPlaceholder(event)
                    }
                }
                .frame(width: 56, height: 56)
            } else {
                eventPlaceholder(event)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(event.title)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(AppColor.ink)
                    .lineLimit(2)

                if let dateDisplay = event.dateDisplay, !dateDisplay.isEmpty {
                    Text(dateDisplay)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(AppColor.ink3)
                }
            }

            Spacer()

            // Price badge
            if event.isFree {
                Text("Free")
                    .font(.system(size: 9, weight: .heavy))
                    .foregroundColor(Color(hex: 0x2E7D32))
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .background(Color(hex: 0xE8F5E9))
                    .clipShape(Capsule())
                    .overlay(Capsule().stroke(Color(hex: 0xA5D6A7), lineWidth: 1))
            } else if let price = event.priceDisplay, !price.isEmpty {
                Text(price)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(AppColor.ink3)
            }
        }
        .padding(10)
        .background(AppColor.white)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(AppColor.pebble, lineWidth: 1.5))
        .padding(.bottom, 4)
    }

    private func eventListRow(_ event: VenueUpcomingEvent) -> some View {
        let md = eventMonthDay(event)
        return HStack(spacing: 14) {
            // Date block
            VStack(spacing: 0) {
                Text(md.month)
                    .font(.system(size: 10, weight: .heavy))
                    .foregroundColor(AppColor.coral)
                Text(md.day)
                    .font(.system(size: 20, weight: .black))
                    .foregroundColor(AppColor.ink)
                    .lineSpacing(0)
                Text(md.weekday)
                    .font(.system(size: 10, weight: .bold))
                    .foregroundColor(AppColor.ink3)
            }
            .frame(width: 44)

            // Event info
            VStack(alignment: .leading, spacing: 3) {
                Text(event.title)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(AppColor.ink)
                    .lineLimit(2)

                HStack(spacing: 6) {
                    if let dateDisplay = event.dateDisplay, !dateDisplay.isEmpty {
                        Text(dateDisplay)
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundColor(AppColor.ink3)
                    }

                    // Category pill
                    let rawCat = event.subcategory ?? event.category ?? ""
                    let displayCat = rawCat.lowercased() == "always_on" ? "Event" : (rawCat.prefix(1).uppercased() + rawCat.dropFirst())
                    if !displayCat.isEmpty {
                        Text(displayCat)
                            .font(.system(size: 9, weight: .heavy))
                            .foregroundColor(AppColor.ink2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(AppColor.mist)
                            .clipShape(Capsule())
                    }
                }
            }

            Spacer()

            // Price / Free badge
            if event.isFree {
                Text("Free")
                    .font(.system(size: 10, weight: .heavy))
                    .foregroundColor(Color(hex: 0x2D9F73))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color(hex: 0xE8F5E9))
                    .clipShape(Capsule())
            } else if let price = event.priceDisplay, !price.isEmpty {
                Text(price)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(AppColor.ink3)
            }
        }
        .padding(.vertical, 12)
        .padding(.horizontal, 14)
        .background(AppColor.white)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(AppColor.pebble, lineWidth: 1))
        .padding(.bottom, 4)
        .onTapGesture {
            EventDetailBridge.shared.showEventDetail?(
                event.id, event.title, nil, nil,
                event.imageUrl, event.startAt,
                nil, nil, "", 0.0, 0.0,
                KotlinBoolean(value: event.isFree), event.priceDisplay ?? "",
                nil, nil,
                event.category ?? "", event.subcategory,
                nil,
                0, 0, 0, 0, event.imageUrl
            )
        }
    }

    private func eventAlbumCard(_ event: VenueUpcomingEvent) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            // Image — fall back to venue photo when event has no image
            ZStack(alignment: .topLeading) {
                if let urlStr = event.imageUrl ?? venue.photoUrl, let url = URL(string: urlStr) {
                    AsyncImage(url: url) { phase in
                        switch phase {
                        case .success(let image):
                            image.resizable().scaledToFill()
                                .frame(height: 90)
                                .clipped()
                        default:
                            AppColor.mist.frame(height: 90)
                        }
                    }
                    .frame(height: 90)
                    .clipped()
                } else {
                    AppColor.mist
                        .frame(height: 90)
                }

                // Category tag — skip "always_on"
                let rawCat = event.subcategory ?? event.category ?? ""
                let displayCat = rawCat.lowercased() == "always_on" ? "Event" : (rawCat.prefix(1).uppercased() + rawCat.dropFirst())
                if !displayCat.isEmpty {
                    Text(displayCat)
                        .font(.system(size: 9, weight: .heavy))
                        .foregroundColor(AppColor.ink)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                        .padding(8)
                }

                // Free badge
                if event.isFree {
                    HStack {
                        Spacer()
                        Text("Free")
                            .font(.system(size: 9, weight: .heavy))
                            .foregroundColor(Color(hex: 0x2D9F73))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color(hex: 0xE8F5E9))
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                            .padding(8)
                    }
                }
            }

            // Info — fixed height so all cards match
            VStack(alignment: .leading, spacing: 3) {
                Text(event.title)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(AppColor.ink)
                    .lineLimit(2)

                if let dateDisplay = event.dateDisplay, !dateDisplay.isEmpty {
                    Text(dateDisplay)
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundColor(AppColor.ink3)
                }
            }
            .padding(10)
            .frame(height: 70, alignment: .topLeading)
        }
        .background(.white)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(AppColor.pebble, lineWidth: 1))
        .onTapGesture {
            // Open event detail via bridge
            EventDetailBridge.shared.showEventDetail?(
                event.id, event.title, nil, nil,
                event.imageUrl, event.startAt,
                nil, nil, "", 0.0, 0.0,
                KotlinBoolean(value: event.isFree), event.priceDisplay ?? "",
                nil, nil,
                event.category ?? "", event.subcategory,
                nil,
                0, 0, 0, 0, event.imageUrl
            )
        }
    }

    private func eventPlaceholder(_ event: VenueUpcomingEvent) -> some View {
        ZStack {
            LinearGradient(
                colors: [Color(hex: 0xFFE8E0), Color(hex: 0xFFF0E8)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Image(systemName: "calendar")
                .font(.system(size: 16, weight: .medium))
                .foregroundColor(AppColor.ink3.opacity(0.4))
        }
        .frame(width: 56, height: 56)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    // MARK: - CTA Bar

    private var ctaBar: some View {
        VStack(spacing: 10) {
            // Reserve button — prominent, full-width
            if let resUrl = venue.reservationUrl, !resUrl.isEmpty {
                Button(action: {
                    // Build deep link with today's date and 2 seats
                    let today = ISO8601DateFormatter().string(from: Date()).prefix(10)
                    var deepLink = resUrl
                    if resUrl.contains("resy.com") {
                        deepLink = "\(resUrl)?date=\(today)&seats=2"
                    } else if resUrl.contains("opentable.com") {
                        deepLink = "\(resUrl)?dateTime=\(today)T19:00&covers=2&partySize=2"
                    }
                    if let url = URL(string: deepLink) {
                        UIApplication.shared.open(url)
                    }
                }) {
                    HStack(spacing: 6) {
                        Image(systemName: "calendar.badge.clock")
                            .font(.system(size: 14, weight: .semibold))
                        Text("Reserve a Table")
                            .font(.system(size: 14, weight: .heavy))
                    }
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(
                        LinearGradient(
                            colors: [AppColor.coral, AppColor.coralDk],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
            }

            // Secondary row
            HStack(spacing: 8) {
                if venue.hasLocation {
                    secondaryButton(
                        systemName: "mappin.and.ellipse",
                        label: mapsExpanded ? "Close" : "Directions",
                        isActive: mapsExpanded
                    ) {
                        withAnimation(.easeInOut(duration: 0.2)) {
                            mapsExpanded.toggle()
                        }
                    }
                }

                if let website = venue.website, !website.isEmpty {
                    secondaryButton(
                        systemName: "globe",
                        label: "Website",
                        isActive: false
                    ) {
                        if let url = URL(string: website) {
                            UIApplication.shared.open(url)
                        }
                    }
                }
            }

            // Expandable maps
            if mapsExpanded {
                HStack(spacing: 8) {
                    expandedOption(label: "Apple Maps") {
                        let urlStr = "https://maps.apple.com/?q=\(venue.name)&ll=\(venue.lat),\(venue.lng)"
                        if let url = URL(string: urlStr.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? urlStr) {
                            UIApplication.shared.open(url)
                        }
                    }
                    expandedOption(label: "Google Maps") {
                        if let url = URL(string: "https://maps.google.com/?q=\(venue.lat),\(venue.lng)") {
                            UIApplication.shared.open(url)
                        }
                    }
                }
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(AppColor.white)
        .overlay(alignment: .top) {
            Rectangle().fill(AppColor.mist).frame(height: 1)
        }
    }

    private func secondaryButton(systemName: String, label: String, isActive: Bool, action: @escaping () -> Void) -> some View {
        let bgColor = isActive ? AppColor.coralLt : AppColor.snow
        let borderColor = isActive ? AppColor.coral : AppColor.pebble
        let contentColor = isActive ? AppColor.coral : AppColor.ink2

        return Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: systemName)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(contentColor)
                Text(label)
                    .font(.system(size: 10, weight: .heavy))
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
                .font(.system(size: 13, weight: .heavy))
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
