import SwiftUI
import UIKit
import ComposeApp

// MARK: - Shared formatters (hoisted to avoid per-row allocation)

private let monthFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "MMM"
    return f
}()

private let dayFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "d"
    return f
}()

private let weekdayFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "EEE"
    return f
}()

// MARK: - Image cache

private let venueImageCache: NSCache<NSURL, UIImage> = {
    let c = NSCache<NSURL, UIImage>()
    c.countLimit = 100
    return c
}()

struct CachedVenueImage<Placeholder: View>: View {
    let url: URL
    let placeholder: Placeholder
    @State private var image: UIImage?

    init(url: URL, @ViewBuilder placeholder: () -> Placeholder) {
        self.url = url
        self.placeholder = placeholder()
    }

    var body: some View {
        Group {
            if let img = image {
                Image(uiImage: img).resizable().scaledToFill()
            } else {
                placeholder
            }
        }
        .task(id: url) {
            if let cached = venueImageCache.object(forKey: url as NSURL) {
                image = cached
                return
            }
            guard let (data, _) = try? await URLSession.shared.data(from: url),
                  let img = UIImage(data: data) else { return }
            venueImageCache.setObject(img, forKey: url as NSURL)
            image = img
        }
    }
}

// MARK: - Venue Data Model (Swift side)

struct VenueUpcomingEvent: Identifiable {
    let id: String
    let title: String
    let description: String?
    let startAt: String?
    let category: String?
    let subcategory: String?
    let imageUrl: String?
    let isFree: Bool
    let priceDisplay: String?
    let dateDisplay: String?
    let venueName: String?
    let venueCity: String?
    let ticketUrl: String?
    let sourceUrl: String?
}

struct VenueDetailData {
    let id: String
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

extension Notification.Name {
    static let venueEventsDidUpdate = Notification.Name("venueEventsDidUpdate")
    /// Posted when the event detail's venue upcoming-event count resolves async.
    static let eventVenueCountDidUpdate = Notification.Name("eventVenueCountDidUpdate")
}

/// Max upcoming events the venue events query returns (mirrors Kotlin's
/// VENUE_UPCOMING_EVENTS_CAP). Counts at this value are shown as "N+" since the
/// true total may be higher.
let venueUpcomingEventsCap = 50

/// "1 event", "12 events", or "50+ events" when capped. Returns nil for <= 0.
/// `noun` lets callers say "upcoming event" on the venue page.
func formatVenueEventCount(_ count: Int, noun: String = "event") -> String? {
    guard count > 0 else { return nil }
    let plural = count == 1 ? noun : "\(noun)s"
    if count >= venueUpcomingEventsCap { return "\(venueUpcomingEventsCap)+ \(plural)" }
    return "\(count) \(plural)"
}

struct VenueDetailView: View {
    let venue: VenueDetailData
    let onDismiss: () -> Void
    let onFavorite: ((String) -> Void)?
    /// When true, suppress Reserve a Table / Visit Website CTAs. Set when the
    /// detail is opened from a chat plan slot — the user is mid-planning, not
    /// ready to book or leave the app.
    let hidesPrimaryCTAs: Bool

    @State private var showDirections = false
    // nil = no filter (show all loaded events), mirroring Explore's default.
    @State private var selectedDateFilter: DateFilter? = nil
    @State private var customStart: Date? = nil
    @State private var customEnd: Date? = nil
    @State private var isFavorited: Bool
    // Events now load async after present(). Seed with whatever the venue came with
    // (usually empty now that the grid doesn't hydrate events), then swap in the
    // real list when the bridge's .venueEventsDidUpdate notification fires.
    @State private var liveEvents: [VenueUpcomingEvent]
    @State private var isLoadingEvents: Bool

    init(
        venue: VenueDetailData,
        onDismiss: @escaping () -> Void,
        isFavorited: Bool = false,
        onFavorite: ((String) -> Void)? = nil,
        hidesPrimaryCTAs: Bool = false
    ) {
        self.venue = venue
        self.onDismiss = onDismiss
        self.onFavorite = onFavorite
        self.hidesPrimaryCTAs = hidesPrimaryCTAs
        self._isFavorited = State(initialValue: isFavorited)
        self._liveEvents = State(initialValue: venue.upcomingEvents)
        self._isLoadingEvents = State(initialValue: venue.upcomingEvents.isEmpty)
    }

    // Match the Explore (Events) tab filter set. `selectDate` opens a native
    // date-range sheet; the rest are presets equivalent to the server slugs.
    enum DateFilter: String, CaseIterable {
        case selectDate = "Select Date"
        case today = "Today"
        case tomorrow = "Tomorrow"
        case thisWeekend = "This Weekend"
        case nextWeekend = "Next Weekend"
        case next30Days = "Next 30 Days"
    }

    // Half-open [start, end) window for the selected filter, or nil for "show
    // all". Weekend windows span Sat 00:00 → Mon 00:00 of the current/next
    // calendar week, matching THIS_WEEKEND / NEXT_WEEKEND on Explore.
    private func dateWindow(for filter: DateFilter) -> (Date, Date)? {
        let cal = Calendar.current
        let todayStart = cal.startOfDay(for: Date())
        let weekday = cal.component(.weekday, from: todayStart) // 1=Sun..7=Sat
        let isoDow = weekday == 1 ? 7 : weekday - 1 // Mon=1..Sun=7
        let monday = cal.date(byAdding: .day, value: -(isoDow - 1), to: todayStart)!
        let thisSat = cal.date(byAdding: .day, value: 5, to: monday)!
        let nextMonday = cal.date(byAdding: .day, value: 7, to: monday)!
        func add(_ days: Int, _ to: Date) -> Date { cal.date(byAdding: .day, value: days, to: to)! }
        switch filter {
        case .today: return (todayStart, add(1, todayStart))
        case .tomorrow: return (add(1, todayStart), add(2, todayStart))
        case .thisWeekend: return (thisSat, nextMonday)
        case .nextWeekend: return (add(7, thisSat), add(7, nextMonday))
        case .next30Days: return (todayStart, add(30, todayStart))
        case .selectDate:
            guard let s = customStart, let e = customEnd else { return nil }
            return (cal.startOfDay(for: s), add(1, cal.startOfDay(for: e)))
        }
    }

    private func filteredEvents() -> [VenueUpcomingEvent] {
        guard let filter = selectedDateFilter, let window = dateWindow(for: filter) else {
            return liveEvents
        }
        return liveEvents.filter { event in
            guard let startAt = event.startAt, let date = parseISO(startAt) else { return true }
            return date >= window.0 && date < window.1
        }
    }

    /// Opens the same native single-calendar range picker the Explore events
    /// page uses (via the shared NativeDateRangePickerBridge presenter), keeping
    /// iOS venue-detail in parity with Explore + the Compose VenueDetailPage.
    private func presentDateRangePicker() {
        let startMs = customStart.map { Int64($0.timeIntervalSince1970 * 1000) }
        let endMs = customEnd.map { Int64($0.timeIntervalSince1970 * 1000) }
        NativeDateRangePickerBridgeSetup.presentRangePicker(
            initialStart: startMs,
            initialEnd: endMs,
            onPicked: { s, e in
                customStart = Date(timeIntervalSince1970: TimeInterval(s) / 1000.0)
                customEnd = Date(timeIntervalSince1970: TimeInterval(e) / 1000.0)
                selectedDateFilter = .selectDate
            },
            onCancelled: {}
        )
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
        return (
            monthFormatter.string(from: date).uppercased(),
            dayFormatter.string(from: date),
            weekdayFormatter.string(from: date).uppercased()
        )
    }

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .bottom) {
                ScrollView(.vertical, showsIndicators: false) {
                    VStack(spacing: 0) {
                        heroImage(width: geo.size.width)
                        contentSection
                        // Reserve room for the floating ctaBar; collapse when
                        // it's hidden so the page doesn't end with dead space.
                        Spacer().frame(height: hidesPrimaryCTAs ? 16 : 80)
                    }
                    .frame(width: geo.size.width)
                }
                if !hidesPrimaryCTAs {
                    ctaBar
                        .frame(width: geo.size.width)
                }
            }
        }
        .background(AppColor.snow)
        .ignoresSafeArea(.all)
    }

    // MARK: - Hero Image

    private func heroImage(width: CGFloat) -> some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                // Background
                Color.black

                // Image
                if let urlStr = venue.photoUrl, let url = URL(string: urlStr) {
                    CachedVenueImage(url: url) {
                        AppColor.mist
                    }
                    .frame(width: geo.size.width, height: 200)
                    .clipped()
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
                HStack(spacing: 8) {
                    // Back button — top left
                    heroIconButton(systemName: "arrow.left") { onDismiss() }

                    Spacer()

                    // Share — LEFT of favorite, matching EventDetailView header.
                    // Uses venue ID for URL consistency with events.
                    heroIconButton(systemName: "square.and.arrow.up") {
                        let shareUrl = "https://localapp.live/v/\(venue.id)"
                        ShareBridge.shared.presentShareSheet?(venue.name, shareUrl)
                    }

                    // Favorite (heart) — RIGHTmost, matches EventDetailView
                    heroIconButton(
                        systemName: isFavorited ? "heart.fill" : "heart",
                        tint: isFavorited ? AppColor.coral : AppColor.ink
                    ) {
                        isFavorited.toggle()
                        onFavorite?(venue.id)
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

    private func heroIconButton(systemName: String, tint: Color = AppColor.ink, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(tint)
                .frame(width: 36, height: 36)
                .background(AppColor.white.opacity(0.9))
                .clipShape(Circle())
                .overlay(Circle().stroke(AppColor.pebble, lineWidth: 1.5))
        }
    }

    // MARK: - Info Chips

    /// "City · X.X mi" — distance dropped when unknown.
    private var venueLocationLine: String? {
        var parts: [String] = []
        let city = venue.city.trimmingCharacters(in: .whitespaces)
        if !city.isEmpty { parts.append(city) }
        if let dist = venue.distanceMiles {
            if dist < 0.1 { parts.append("Nearby") }
            else if dist < 10 { parts.append(String(format: "%.1f mi", dist)) }
            else { parts.append(String(format: "%.0f mi", dist)) }
        }
        return parts.isEmpty ? nil : parts.joined(separator: " \u{00B7} ")
    }

    /// Prefer the freshly-loaded live count; fall back to the seeded count.
    private var venueEventCountForChip: Int {
        liveEvents.isEmpty ? venue.upcomingEventCount : liveEvents.count
    }

    /// "4.5 · Arts Center" — category dropped when blank.
    private func venueRatingLine(_ rating: Double) -> String {
        let r = String(format: "%.1f", rating)
        let cat = venue.categoryDisplay
        return cat.isEmpty ? r : "\(r) \u{00B7} \(cat)"
    }

    private func venueInfoChip(systemName: String, iconColor: Color, text: String) -> some View {
        HStack(alignment: .center, spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 8)
                    .fill(AppColor.mist)
                RoundedRectangle(cornerRadius: 8)
                    .stroke(AppColor.pebble, lineWidth: 1.5)
                Image(systemName: systemName)
                    .font(.system(size: 13, weight: .medium, design: .rounded))
                    .foregroundColor(iconColor)
            }
            .frame(width: 32, height: 32)

            Text(text)
                .font(.system(size: 13, weight: .bold, design: .rounded))
                .foregroundColor(AppColor.ink2)
                .lineLimit(2)
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

            Spacer().frame(height: 12)

            // Info chips — icon-tile rows matching the event detail design
            // language (replaces the old run-on subtitle + star row).
            VStack(alignment: .leading, spacing: 10) {
                // Location: City · Distance
                if let locationText = venueLocationLine {
                    venueInfoChip(systemName: "mappin.and.ellipse", iconColor: AppColor.sky, text: locationText)
                }
                // Upcoming-event count
                if let countText = formatVenueEventCount(venueEventCountForChip, noun: "upcoming event") {
                    venueInfoChip(
                        systemName: "calendar",
                        iconColor: AppColor.coral,
                        text: countText
                    )
                }
                // Rating · Category
                if let rating = venue.googleRating, rating > 0 {
                    venueInfoChip(
                        systemName: "star.fill",
                        iconColor: AppColor.sun,
                        text: venueRatingLine(rating)
                    )
                }
            }

            Spacer().frame(height: 16)

            // Description
            if let desc = venue.description, !desc.isEmpty {
                Text(desc)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(AppColor.ink2)
                    .lineSpacing(8)

                Spacer().frame(height: 16)
            }

            // Directions link — hidden when this venue page was opened from
            // a Bo plan slot. The plan detail page already has its own
            // Directions chip per stop, so showing it here too is redundant
            // and visually heavy.
            if venue.hasLocation && !VenueDetailTransitionController.fromPlanContext {
                Button(action: { showDirections = true }) {
                    HStack(spacing: 6) {
                        Image(systemName: "mappin.and.ellipse")
                            .font(.system(size: 13, weight: .bold, design: .rounded))
                        Text("Directions")
                            .font(.system(size: 13, weight: .bold, design: .rounded))
                    }
                    .foregroundColor(AppColor.sky)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .frame(maxWidth: .infinity)
                    .background(AppColor.skyLt)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(AppColor.sky.opacity(0.35), lineWidth: 1.5))
                }
                .buttonStyle(.plain)
                // Same native menu as the plan detail page — Apple/Google Maps
                // plus Uber/Lyft, via a system confirmation dialog.
                .confirmationDialog(
                    "Get directions to \(venue.name)",
                    isPresented: $showDirections,
                    titleVisibility: .visible
                ) {
                    Button("Apple Maps") { openMap(.apple) }
                    Button("Google Maps") { openMap(.google) }
                    Button("Uber") { openRideshare(.uber) }
                    Button("Lyft") { openRideshare(.lyft) }
                    Button("Cancel", role: .cancel) {}
                }

                Spacer().frame(height: 16)
            }

            // Upcoming Events — hidden when accessed from a Bo plan slot.
            // The plan slot itself is already an event/venue selection;
            // showing other upcoming events here is noise in that context.
            if !VenueDetailTransitionController.fromPlanContext, isLoadingEvents {
                Spacer().frame(height: 8)

                Text("Upcoming Events")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(AppColor.ink)

                Spacer().frame(height: 12)

                eventSkeleton()
            } else if !liveEvents.isEmpty && !VenueDetailTransitionController.fromPlanContext {
                Spacer().frame(height: 8)

                Text("Upcoming Events")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(AppColor.ink)

                Spacer().frame(height: 10)

                // Date filter chips — match the Explore (Events) tab set.
                // ScrollViewReader centers the selected chip on tap, mirroring
                // the events tab's center-on-select behavior.
                ScrollViewReader { proxy in
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(DateFilter.allCases, id: \.self) { filter in
                                let isSelected = selectedDateFilter == filter
                                Text(filter.rawValue)
                                    .font(.system(size: 12, weight: .heavy))
                                    .foregroundColor(isSelected ? .white : AppColor.ink2)
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 8)
                                    .background(
                                        RoundedRectangle(cornerRadius: 20)
                                            .fill(isSelected ? AppColor.coral : AppColor.white)
                                    )
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 20)
                                            .strokeBorder(AppColor.coral, lineWidth: isSelected ? 0 : 1)
                                    )
                                    .id(filter)
                                    .onTapGesture {
                                        if filter == .selectDate {
                                            presentDateRangePicker()
                                        } else {
                                            selectedDateFilter = filter
                                        }
                                    }
                            }
                        }
                    }
                    .onChange(of: selectedDateFilter) { newValue in
                        guard let target = newValue else { return }
                        withAnimation(.easeInOut(duration: 0.3)) {
                            proxy.scrollTo(target, anchor: .center)
                        }
                    }
                }

                Spacer().frame(height: 12)

                // Event list
                let events = filteredEvents()
                if events.isEmpty {
                    // Bo typewriter empty state — matches the Explore Events tab.
                    BoTypewriterEmptyState(rotationKey: selectedDateFilter)
                        .frame(maxWidth: .infinity, minHeight: 280)
                } else {
                    LazyVStack(spacing: 0) {
                        ForEach(events) { event in
                            eventListRow(event)
                        }
                    }
                }
            } else if !VenueDetailTransitionController.fromPlanContext {
                Spacer().frame(height: 8)

                Text("Upcoming Events")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(AppColor.ink)

                Spacer().frame(height: 12)

                // Bo-voiced empty state — explains that some venues block event
                // ingestion and points the user to their own calendar. Copy is
                // the verbatim source-of-truth EmptyStateCopy.VENUE_NO_EVENTS
                // (commonMain) — keep in sync if it changes there.
                BoTypewriterEmptyState(
                    rotationKey: "venue-no-events",
                    fixedLine: "No events for this venue on Local (yet). Some venues prevent us from pulling in their events — check their website for a calendar!"
                )
                .frame(maxWidth: .infinity, minHeight: 220)
            }

            Spacer().frame(height: 32)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
        .onReceive(NotificationCenter.default.publisher(for: .venueEventsDidUpdate)) { note in
            guard let payload = note.userInfo,
                  let vid = payload["venueId"] as? String,
                  vid == venue.id,
                  let events = payload["events"] as? [VenueUpcomingEvent]
            else { return }
            liveEvents = events
            isLoadingEvents = false
        }
    }

    // Skeleton shown while events load after tap. Three shimmer rows keep the section
    // from collapsing and signal "loading" without a spinner.
    @ViewBuilder
    private func eventSkeleton() -> some View {
        VStack(spacing: 14) {
            ForEach(0..<3, id: \.self) { _ in
                HStack(spacing: 10) {
                    RoundedRectangle(cornerRadius: 10)
                        .fill(AppColor.pebble.opacity(0.4))
                        .frame(width: 70, height: 70)
                    VStack(alignment: .leading, spacing: 8) {
                        RoundedRectangle(cornerRadius: 6)
                            .fill(AppColor.pebble.opacity(0.5))
                            .frame(width: 180, height: 13)
                        RoundedRectangle(cornerRadius: 5)
                            .fill(AppColor.pebble.opacity(0.4))
                            .frame(width: 120, height: 10)
                        RoundedRectangle(cornerRadius: 5)
                            .fill(AppColor.pebble.opacity(0.3))
                            .frame(width: 80, height: 10)
                    }
                    Spacer()
                }
            }
        }
        .padding(.vertical, 4)
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
                event.id, event.title, event.description, nil,
                event.imageUrl, venue.photoUrl,
                event.startAt,
                event.venueName, nil, event.venueCity ?? venue.city, KotlinDouble(value: venue.lat), KotlinDouble(value: venue.lng),
                KotlinBoolean(value: event.isFree), event.priceDisplay ?? "",
                event.ticketUrl, event.sourceUrl,
                event.category ?? "", event.subcategory,
                nil,
                0, 0, 0, 0, event.imageUrl,
                KotlinBoolean(value: false),
                KotlinBoolean(value: true),
                KotlinDouble(value: venue.distanceMiles ?? -1)
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
                event.id, event.title, event.description, nil,
                event.imageUrl, venue.photoUrl,
                event.startAt,
                event.venueName, nil, event.venueCity ?? venue.city, KotlinDouble(value: venue.lat), KotlinDouble(value: venue.lng),
                KotlinBoolean(value: event.isFree), event.priceDisplay ?? "",
                event.ticketUrl, event.sourceUrl,
                event.category ?? "", event.subcategory,
                nil,
                0, 0, 0, 0, event.imageUrl,
                KotlinBoolean(value: false),
                KotlinBoolean(value: true),
                KotlinDouble(value: venue.distanceMiles ?? -1)
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
            // Website button — coral CTA
            if let website = venue.website, !website.isEmpty {
                Button(action: {
                    // In-app webview — stays in Local instead of bouncing to Safari.
                    InAppBrowserBridgeSetup.open(website)
                }) {
                    Text("Visit Website")
                        .font(.system(size: 16, weight: .heavy))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(AppColor.coral)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                        .shadow(color: AppColor.coralDk.opacity(0.4), radius: 0, x: 0, y: 4)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 24)
    }

    // MARK: - Directions (mirrors PlanDetailView's map / rideshare launchers)

    private enum MapApp { case apple, google }
    private enum RideApp { case uber, lyft }

    private func openMap(_ app: MapApp) {
        let q = venue.name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let url: URL?
        switch app {
        case .apple:
            url = URL(string: "http://maps.apple.com/?daddr=\(venue.lat),\(venue.lng)&q=\(q)")
        case .google:
            // Try the Google Maps app first; falls through to the web app
            // automatically if it isn't installed.
            url = URL(string: "comgooglemaps://?daddr=\(venue.lat),\(venue.lng)&q=\(q)")
                ?? URL(string: "https://www.google.com/maps/dir/?api=1&destination=\(venue.lat),\(venue.lng)")
        }
        if let u = url { UIApplication.shared.open(u) }
    }

    private func openRideshare(_ app: RideApp) {
        let nick = venue.name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let url: URL?
        switch app {
        case .uber:
            url = URL(string: "uber://?action=setPickup&pickup=my_location&dropoff[latitude]=\(venue.lat)&dropoff[longitude]=\(venue.lng)&dropoff[nickname]=\(nick)")
                ?? URL(string: "https://m.uber.com/ul/?action=setPickup&pickup=my_location&dropoff[latitude]=\(venue.lat)&dropoff[longitude]=\(venue.lng)")
        case .lyft:
            url = URL(string: "lyft://ridetype?id=lyft&destination[latitude]=\(venue.lat)&destination[longitude]=\(venue.lng)")
                ?? URL(string: "https://ride.lyft.com/ridetype?id=lyft&destination[latitude]=\(venue.lat)&destination[longitude]=\(venue.lng)")
        }
        if let u = url { UIApplication.shared.open(u) }
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

// MARK: - Bo typewriter empty state

/// Native replica of the Compose `BoEmptyState` (commonMain) used on the
/// Explore Events tab: tiny Bo slides up + fades in, then the line streams
/// character-by-character with a per-glyph haptic and a coral cursor block.
/// Re-rolls the copy line whenever `rotationKey` changes (e.g. a new filter).
private struct BoTypewriterEmptyState: View {
    let rotationKey: AnyHashable
    // When non-nil, this fixed copy is shown instead of a random bank roll
    // (e.g. the venue "no events" explainer). A fixed line never re-rolls.
    private let fixedLine: String?

    @State private var line: String
    @State private var revealed: Int = 0
    @State private var boOffset: CGFloat = 30
    @State private var boOpacity: Double = 0
    @State private var streamTask: Task<Void, Never>? = nil

    init(rotationKey: AnyHashable, fixedLine: String? = nil) {
        self.rotationKey = rotationKey
        self.fixedLine = fixedLine
        self._line = State(initialValue: fixedLine ?? (BoTypewriterEmptyState.bank.randomElement() ?? ""))
    }

    // Verbatim subset of EmptyStateCopy.NO_EVENTS (commonMain). Source of truth
    // lives in EmptyStateCopy.kt — keep this in sync if the bank's voice changes.
    static let bank: [String] = [
        "Even I need a day off sometimes.",
        "Empty-handed today.",
        "Nothing on the radar.",
        "Looks like a quiet one.",
        "Coming up blank.",
        "No events found. Yet.",
        "The city's playing coy.",
        "Drew a blank.",
        "Quiet as a Sunday morning in here.",
        "The schedule is suspiciously empty.",
        "Today's giving me nothing.",
        "Nothing booked. Nothing brewing.",
    ]

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            Image("BoTiny")
                .resizable()
                .scaledToFit()
                .frame(width: 28, height: 28)
                .offset(y: boOffset)
                .opacity(boOpacity)

            HStack(alignment: .center, spacing: 2) {
                Text(String(line.prefix(revealed)))
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(AppColor.ink3)
                    .multilineTextAlignment(.leading)
                RoundedRectangle(cornerRadius: 1)
                    .fill(AppColor.coral)
                    .frame(width: 3, height: 15)
                    .opacity(boOpacity)
            }
        }
        .padding(.horizontal, 32)
        .onAppear { start() }
        .onDisappear { streamTask?.cancel() }
        .onChange(of: rotationKey) { _ in
            line = fixedLine ?? (BoTypewriterEmptyState.bank.randomElement() ?? "")
            start()
        }
    }

    private func start() {
        streamTask?.cancel()
        revealed = 0
        boOffset = 30
        boOpacity = 0
        withAnimation(.timingCurve(0.2, 0.85, 0.3, 1.05, duration: 0.5)) { boOffset = 0 }
        withAnimation(.linear(duration: 0.35)) { boOpacity = 1 }
        streamTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 220_000_000) // breath before typing
            let chars = Array(line)
            var i = 0
            while i < chars.count {
                if Task.isCancelled { return }
                try? await Task.sleep(nanoseconds: 55_000_000)
                i += 1
                revealed = i
                if !chars[i - 1].isWhitespace {
                    UISelectionFeedbackGenerator().selectionChanged()
                }
            }
        }
    }
}
