import Foundation

/// Swift mirror of the Kotlin `Plan` data model. Hydrated from the JSON payload
/// the bridge passes via `PlanDetailBridge.showPlanDetail`. Stays in sync with
/// state pushes via `PlanDetailBridge.pushPlanState`.
struct PlanDetailPlan: Decodable, Equatable {
    let id: String
    let title: String
    let city: String
    let dateDisplay: String
    let isPast: Bool
    let creatorId: String?
    let creatorName: String?
    let shareToken: String?
    let groupSize: Int?
    let confirmedCount: Int
    let currentUserId: String?
    let attendees: [PlanDetailAttendee]
    let days: [PlanDetailDay]

    /// Total stops across all days.
    var totalStops: Int { days.reduce(0) { $0 + $1.slots.count } }

    /// Multi-day if more than one day actually has stops (matches the Compose
    /// auto-demote rule from the spec).
    var isEffectivelyMultiDay: Bool {
        days.count > 1 && days.filter { !$0.slots.isEmpty }.count > 1
    }

    /// Default selected day per spec §7.4 — V1 simplified: first day with stops, else day 0.
    var defaultActiveDayIndex: Int {
        let i = days.firstIndex { !$0.slots.isEmpty } ?? 0
        return i
    }
}

struct PlanDetailDay: Decodable, Equatable {
    /// Server-supplied day index when pushed; absent on first hydrate.
    let dayIndex: Int?
    let label: String
    let shortName: String
    let slots: [PlanDetailSlot]
    let travel: [PlanDetailTravel]?
}

/// Reservation state machine — mirrors Kotlin `ReservationState`.
enum ReservationState: String, Decodable {
    case none, made, unavailable
}

/// Ticket state machine — mirrors Kotlin `TicketState`.
enum TicketState: String, Decodable {
    case none, bought, unavailable
}

struct PlanDetailSlot: Decodable, Equatable {
    let id: String
    let time: String
    let emoji: String
    let name: String
    let detail: String
    let price: String
    let isFree: Bool
    let isReserved: Bool
    let imageUrl: String?
    let ticketUrl: String?
    let venueRating: Double?
    let venueAddress: String?
    /// Venue city — set on the Kotlin side from `event.venueCity` (events)
    /// or `venue.city` (venue stops). Subtitle fallback when venueAddress
    /// is null/unparseable.
    let venueCity: String?
    let venueName: String?
    let venueLat: Double?
    let venueLng: Double?
    let venueCategory: String?
    let venueWebsite: String?
    let sourceUrl: String?
    let reservationUrl: String?
    let eventId: String?

    /// Phase 2: true when Bo created this slot from a web_search finding.
    let isBoWeb: Bool

    /// Phase 3.5: current state of the bo_web_events row this slot points at.
    /// One of: "unverified" | "verifying" | "verified" | "rejected" — drives
    /// the badge (coral verifying / no badge when verified / red warning when
    /// rejected with override option).
    let boWebState: String?

    // V1 reservation/ticket state
    let venueReservable: Bool?
    let reservationState: ReservationState
    let reservationPartySize: Int?
    let reservationTime: String?
    let ticketState: TicketState
    let ticketCount: Int?

    // Defaults so older JSON payloads (before Phase 2) decode cleanly.
    private enum CodingKeys: String, CodingKey {
        case id, time, emoji, name, detail, price, isFree, isReserved
        case imageUrl, ticketUrl, venueRating, venueAddress, venueCity, venueLat, venueLng
        case venueCategory, venueWebsite, sourceUrl, reservationUrl, eventId, venueName
        case isBoWeb = "isBoWeb"
        case boWebState = "boWebState"
        case venueReservable, reservationState, reservationPartySize, reservationTime
        case ticketState, ticketCount
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        time = try c.decode(String.self, forKey: .time)
        emoji = (try? c.decode(String.self, forKey: .emoji)) ?? ""
        name = try c.decode(String.self, forKey: .name)
        detail = (try? c.decode(String.self, forKey: .detail)) ?? ""
        price = (try? c.decode(String.self, forKey: .price)) ?? ""
        isFree = (try? c.decode(Bool.self, forKey: .isFree)) ?? false
        isReserved = (try? c.decode(Bool.self, forKey: .isReserved)) ?? false
        imageUrl = try? c.decode(String?.self, forKey: .imageUrl) ?? nil
        ticketUrl = try? c.decode(String?.self, forKey: .ticketUrl) ?? nil
        venueRating = try? c.decode(Double?.self, forKey: .venueRating) ?? nil
        venueAddress = try? c.decode(String?.self, forKey: .venueAddress) ?? nil
        venueCity = try? c.decode(String?.self, forKey: .venueCity) ?? nil
        venueName = try? c.decode(String?.self, forKey: .venueName) ?? nil
        venueLat = try? c.decode(Double?.self, forKey: .venueLat) ?? nil
        venueLng = try? c.decode(Double?.self, forKey: .venueLng) ?? nil
        venueCategory = try? c.decode(String?.self, forKey: .venueCategory) ?? nil
        venueWebsite = try? c.decode(String?.self, forKey: .venueWebsite) ?? nil
        sourceUrl = try? c.decode(String?.self, forKey: .sourceUrl) ?? nil
        reservationUrl = try? c.decode(String?.self, forKey: .reservationUrl) ?? nil
        eventId = try? c.decode(String?.self, forKey: .eventId) ?? nil
        isBoWeb = (try? c.decode(Bool.self, forKey: .isBoWeb)) ?? false
        boWebState = try? c.decode(String?.self, forKey: .boWebState) ?? nil
        venueReservable = try? c.decode(Bool?.self, forKey: .venueReservable) ?? nil
        reservationState = (try? c.decode(ReservationState.self, forKey: .reservationState)) ?? .none
        reservationPartySize = try? c.decode(Int?.self, forKey: .reservationPartySize) ?? nil
        reservationTime = try? c.decode(String?.self, forKey: .reservationTime) ?? nil
        ticketState = (try? c.decode(TicketState.self, forKey: .ticketState)) ?? .none
        ticketCount = try? c.decode(Int?.self, forKey: .ticketCount) ?? nil
    }

    /// Stop-type derivation per V1 spec — mirrors EventDetailView's CTA logic
    /// at iosApp/iosApp/EventDetailView.swift:47-49.
    /// - .restaurant: eventId is null AND venue category looks like food/drink
    /// - .ticketed:   eventId is set AND ticketUrl is set
    /// - .freeRsvp:   eventId is set AND ticketUrl is null (free / RSVP / source-URL only)
    var stopType: StopType {
        if eventId != nil {
            return ticketUrl != nil ? .ticketed : .freeRsvp
        }
        // Venue-type nouns (match against name OR category).
        let restaurantWords = [
            "restaurant", "bar", "brewery", "winery", "cafe", "café", "bistro",
            "pub", "tavern", "grill", "steakhouse", "pizzeria", "pizza", "bakery",
            "diner", "eatery", "kitchen", "food", "gastropub", "trattoria",
            "osteria", "cantina", "taqueria", "ramen", "noodle", "bbq", "barbecue",
            "coffee", "lounge", "cocktail",
        ]
        // Cuisine adjectives — these only appear in a `cuisine_or_type` category
        // (e.g. Bo's "contemporary American", "Italian") or a venue name, never
        // in a personal-location activity label ("Dinner", "Brunch", "Drinks"),
        // so they're safe to treat as a dining signal. `recommend_and_save_venue`
        // is dining-only by definition, so its venue refs land here.
        let cuisineWords = [
            "american", "italian", "mexican", "french", "thai", "chinese",
            "japanese", "sushi", "seafood", "mediterranean", "indian", "korean",
            "vietnamese", "spanish", "greek", "contemporary", "wine",
        ]
        let cat = (venueCategory ?? "").lowercased()
        let n = name.lowercased()
        let isDining = restaurantWords.contains { cat.contains($0) || n.contains($0) } ||
            cuisineWords.contains { cat.contains($0) }
        if isDining {
            return .restaurant
        }
        // Falls back to .freeRsvp for non-event venue stops with a sourceUrl.
        return .freeRsvp
    }

    /// Backwards-compat alias used by existing callers.
    var isRestaurant: Bool { stopType == .restaurant }

    /// Subtitle line shown under the slot title. Per latest spec:
    ///  • Restaurants keep cuisine/category text — useful context
    ///    ("Mediterranean seafood", "Italian", "Cocktail bar")
    ///  • All other stop types drop the raw category (e.g. "Historic_site"
    ///    reads as a leaked DB enum) and instead show the venue address;
    ///    if no address is available, the venue/place name is the fallback
    var subtitle: String {
        if stopType == .restaurant {
            var parts: [String] = []
            if let cat = venueCategory, !cat.isEmpty {
                let pretty = cat.replacingOccurrences(of: "_", with: " ")
                parts.append(pretty.prefix(1).uppercased() + pretty.dropFirst())
            }
            if let addr = venueAddress, !addr.isEmpty {
                let neighborhood = addr.split(separator: ",").first.map(String.init)?.trimmingCharacters(in: .whitespaces) ?? ""
                if !neighborhood.isEmpty { parts.append(neighborhood) }
            }
            return parts.joined(separator: " · ")
        }
        // Non-restaurant (events): show "{Venue Name} · {City}". Prefer
        // the dedicated `venueCity` field (set from `event.venueCity` on
        // the Kotlin side) over parsing it out of `venueAddress`, since
        // many events have null addresses but always carry a city.
        var parts: [String] = []
        if let v = venueName, !v.isEmpty,
           v.compare(name, options: .caseInsensitive) != .orderedSame {
            parts.append(v)
        }
        let city: String? = {
            if let c = venueCity, !c.isEmpty { return c }
            // Fallback to address parse when venueCity wasn't provided
            // (legacy slots / Bo-Web findings without explicit city).
            //
            // US addresses come in several shapes — the previous code blindly
            // grabbed segs[1], which surfaces "MA 02109" instead of "Boston"
            // for short addresses like "Boston, MA 02109" (Faneuil Hall et
            // al). Heuristic: walk from the right, skip the trailing
            // STATE-ZIP token if present, and return whatever segment is now
            // last — that's the city in every common US format:
            //   ["1 Main St", "Boston", "MA 02109"] → "Boston"
            //   ["Boston", "MA 02109"]              → "Boston"
            //   ["MA 02109"]                        → nil
            //   ["Boston"]                          → "Boston"
            guard let addr = venueAddress, !addr.isEmpty else { return nil }
            var segs = addr.split(separator: ",")
                .map { $0.trimmingCharacters(in: .whitespaces) }
                .filter { !$0.isEmpty }
            if segs.isEmpty { return nil }
            if let last = segs.last, looksLikeStateZip(last) {
                segs.removeLast()
            }
            // Also drop a trailing "USA" / "United States" if present.
            if let last = segs.last?.uppercased(), last == "USA" || last == "UNITED STATES" {
                segs.removeLast()
            }
            return segs.last
        }()
        if let c = city, !c.isEmpty { parts.append(c) }
        // Fallback: if neither venueName-distinct nor city is available,
        // surface the venue name as a single line even if it duplicates the
        // title — better one redundant line than nothing useful.
        if parts.isEmpty, let v = venueName, !v.isEmpty {
            parts.append(v)
        }
        return parts.joined(separator: " · ")
    }

    /// True when [s] matches a US state abbreviation followed by an optional
    /// 5-digit (or ZIP+4) zip code — e.g. "MA", "MA 02109", "CA 94110-1234".
    /// Used by the subtitle parser to drop the state/zip token and surface
    /// the city instead.
    private func looksLikeStateZip(_ s: String) -> Bool {
        let pattern = #"^[A-Z]{2}( \d{5}(-\d{4})?)?$"#
        return s.range(of: pattern, options: .regularExpression) != nil
    }

    /// V1 OpenTable URL — search-based, no affiliate.
    var openTableUrl: URL? {
        if let r = reservationUrl, !r.isEmpty, let u = URL(string: r) { return u }
        let term = name.replacingOccurrences(of: " ", with: "+")
        return URL(string: "https://www.opentable.com/s?term=\(term)")
    }
}

/// Swap alternative pushed from Kotlin's `PlanSwapService` via the bridge.
struct SwapAlternative: Decodable, Identifiable, Equatable {
    let kind: Kind
    let sourceId: String
    let name: String
    let category: String?
    let neighborhood: String?
    let rating: Double?
    let imageUrl: String?
    let lat: Double?
    let lng: Double?
    let ticketUrl: String?
    let sourceUrl: String?
    let reservationUrl: String?
    let venueWebsite: String?
    let venueAddress: String?
    let priceMin: Double?
    let priceMax: Double?
    let isFree: Bool?
    let eventStartAt: String?
    let eventTime: String?

    var id: String { "\(kind.rawValue)-\(sourceId)" }

    enum Kind: String, Decodable, Equatable {
        case restaurant
        case event
    }
}

/// V1 stop categorization. Drives which primary action chip + sheets the card uses.
enum StopType {
    case restaurant   // category = food/drink venue
    case ticketed     // event with a ticket_url
    case freeRsvp     // free event, RSVP event, or source-URL only
}

struct PlanDetailTravel: Decodable, Equatable {
    let mode: String
    let mins: Int
}

struct PlanDetailAttendee: Decodable, Equatable, Identifiable {
    let id: String
    let name: String
    let initials: String
    let confirmed: Bool
    let avatarColor: Int64
}

extension PlanDetailPlan {
    static func decode(from json: String) -> PlanDetailPlan? {
        guard let data = json.data(using: .utf8) else { return nil }
        let decoder = JSONDecoder()
        return try? decoder.decode(PlanDetailPlan.self, from: data)
    }
}
