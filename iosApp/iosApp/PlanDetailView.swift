import SwiftUI
import MapKit
import ComposeApp

// MARK: - Brand colors (mirror V5 palette in AppTheme.kt)
private let pdInk        = Color(red: 0.10, green: 0.09, blue: 0.08)
private let pdInk2       = Color(red: 0.24, green: 0.22, blue: 0.20)
private let pdInk3       = Color(red: 0.42, green: 0.40, blue: 0.38)
private let pdInk4       = Color(red: 0.64, green: 0.62, blue: 0.60)
private let pdLine       = Color(red: 0.91, green: 0.89, blue: 0.86)
private let pdLineSoft   = Color(red: 0.95, green: 0.93, blue: 0.90)
private let pdPaper      = Color(red: 0.984, green: 0.973, blue: 0.957)
private let pdShade      = Color(red: 0.961, green: 0.937, blue: 0.910)
private let pdCoral      = Color(red: 0.961, green: 0.42, blue: 0.388)
private let pdCoralDk    = Color(red: 0.914, green: 0.31, blue: 0.278)
private let pdCoralSoft  = Color(red: 0.984, green: 0.894, blue: 0.882)
private let pdGreen      = Color(red: 0.29, green: 0.635, blue: 0.478)
private let pdSky        = Color(red: 0.357, green: 0.612, blue: 0.965)
private let pdSkyLt      = Color(red: 0.922, green: 0.949, blue: 1.0)

// MARK: - Fonts (mirror the app's display/body font system)
// Fraunces = display (headlines). Nunito = body (UI text).
private enum PDF {
    static func display(_ size: CGFloat, _ weight: Font.Weight = .black) -> Font {
        Font.custom("Fraunces", size: size).weight(weight)
    }
    static func body(_ size: CGFloat, _ weight: Font.Weight = .medium) -> Font {
        Font.custom("Nunito", size: size).weight(weight)
    }
}

// MARK: - Caps (mirror Plan Detail spec V1)
private let kMaxDays = 7
private let kMaxStopsPerDay = 8
private let kMaxAttendees = 500

// MARK: - Plan Detail View

struct PlanDetailView: View {
    /// Initial plan from the bridge. Bound to a @State so push updates work.
    let initialPlan: PlanDetailPlan
    let isCreator: Bool
    let currentUserId: String?
    let onBack: () -> Void

    @State private var plan: PlanDetailPlan
    @State private var selectedDayIndex: Int
    @State private var showRenameSheet = false
    @State private var showDeleteConfirm = false
    @State private var showAttendeeSheet = false
    @State private var showGroupSizeSheet = false
    @State private var menuOpen = false
    @State private var activeStopSheet: StopSheet?
    @State private var safariUrl: IdentifiableURL?
    /// Slot IDs whose overlap warning the user has dismissed this session.
    @State private var acknowledgedOverlaps: Set<String> = []
    /// Slot IDs flagged with an overlap warning after the most recent reservation
    /// time-update cascade. Detection runs on plan state change (see [detectOverlaps]).
    @State private var overlapSlotIds: Set<String> = []
    /// Most recent swap alternatives delivered by the bridge.
    @State private var swapAlternatives: [SwapAlternative] = []
    @State private var swapLoading: Bool = false
    // Bubbled up from MapHeroView once MKDirections finishes — used by the
    // floating title card to render "X stops · X mi · ~X min".
    @State private var routeTotals: RouteTotals? = nil
    /// Phase 3.5: when the user taps a "BO COULDN'T VERIFY" badge, store the
    /// slot here so the override modal can render. Tapping "I've verified
    /// this" calls the override edge function and locally flips the badge.
    @State private var boWebOverrideTarget: PlanDetailSlot? = nil
    @State private var boWebLocallyVerified: Set<String> = []

    init(
        initialPlan: PlanDetailPlan,
        isCreator: Bool,
        currentUserId: String?,
        onBack: @escaping () -> Void
    ) {
        self.initialPlan = initialPlan
        self.isCreator = isCreator
        self.currentUserId = currentUserId
        self.onBack = onBack
        _plan = State(initialValue: initialPlan)
        _selectedDayIndex = State(initialValue: initialPlan.defaultActiveDayIndex)
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            pdPaper.ignoresSafeArea()
            VStack(spacing: 0) {
                hero
                // The "sheet" below the hero is just a ScrollView with a
                // rounded top mask. Previously the rounded paper layer was
                // a child .background offset upward — at the corners of
                // the rounded mask we could see the ScrollView's default
                // (white) background poking through. Clip the ScrollView
                // itself instead so there's nothing behind the rounded
                // edges to leak.
                ScrollView {
                    VStack(spacing: 16) {
                        if plan.isPast { pastBanner }
                        // Old AttendeeCard ("X of Y going" + Invite pill)
                        // removed — replaced by the persistent Share Plan
                        // CTA at the bottom + the menu's View Attendees /
                        // Edit # of Attendees actions.
                        dayHeader
                        stopList
                        // Bottom inset so the persistent Share CTA bar
                        // doesn't cover the last stop / delete footer.
                        Spacer().frame(height: 110)
                    }
                    // Extra top padding so the day-strip card sits with
                    // breathing room below the rounded sheet edge instead
                    // of jammed against the hero.
                    .padding(.top, 20)
                }
                .background(pdPaper)
                .clipShape(RoundedCorner(radius: 22, corners: [.topLeft, .topRight]))
                .offset(y: -12)
            }

            // Persistent Share Plan CTA pinned to the bottom of the screen.
            shareCtaBar
        }
        .onChange(of: selectedDayIndex) { _, _ in
            // Clear stale route metrics on day change. The new day's
            // MapHeroView will repopulate via onTotalsChanged once
            // MKDirections returns; otherwise totals stay nil and the
            // title card's metrics line collapses gracefully.
            routeTotals = nil
        }
        .sheet(isPresented: $showRenameSheet) {
            RenamePlanSheet(currentName: plan.title) { newName in
                showRenameSheet = false
                PlanDetailBridge.shared.onChangeName?(newName)
                plan = PlanDetailPlan(
                    id: plan.id, title: newName, city: plan.city,
                    dateDisplay: plan.dateDisplay, isPast: plan.isPast,
                    creatorId: plan.creatorId, creatorName: plan.creatorName,
                    shareToken: plan.shareToken, groupSize: plan.groupSize,
                    confirmedCount: plan.confirmedCount,
                    currentUserId: plan.currentUserId,
                    attendees: plan.attendees, days: plan.days
                )
            } onCancel: {
                showRenameSheet = false
            }
        }
        .sheet(isPresented: $showGroupSizeSheet) {
            GroupSizeSheet(
                currentSize: max(plan.groupSize ?? plan.attendees.count, 1),
                onSave: { newSize in
                    showGroupSizeSheet = false
                    PlanDetailBridge.shared.onSetGroupSize?(KotlinInt(int: Int32(newSize)))
                    plan = PlanDetailPlan(
                        id: plan.id, title: plan.title, city: plan.city,
                        dateDisplay: plan.dateDisplay, isPast: plan.isPast,
                        creatorId: plan.creatorId, creatorName: plan.creatorName,
                        shareToken: plan.shareToken, groupSize: newSize,
                        confirmedCount: plan.confirmedCount,
                        currentUserId: plan.currentUserId,
                        attendees: plan.attendees, days: plan.days
                    )
                },
                onCancel: { showGroupSizeSheet = false }
            )
        }
        .sheet(isPresented: $showAttendeeSheet) {
            AttendeeSheet(
                plan: plan,
                currentUserId: currentUserId,
                isCreator: isCreator,
                onChangeRsvp: { status in
                    PlanDetailBridge.shared.onChangeRsvp?(status)
                },
                onRemoveAttendee: { id in
                    PlanDetailBridge.shared.onRemoveAttendee?(id)
                },
                onInvite: {
                    PlanDetailBridge.shared.onInvite?()
                },
                onDismiss: { showAttendeeSheet = false }
            )
        }
        .confirmationDialog(
            "Delete this plan?",
            isPresented: $showDeleteConfirm,
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                PlanDetailBridge.shared.onDeletePlan?()
                onBack()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This will permanently remove '\(plan.title)' for everyone going. This action cannot be undone.")
        }
        .sheet(item: $safariUrl) { wrapped in
            SafariView(url: wrapped.url).ignoresSafeArea()
        }
        .sheet(item: $activeStopSheet) { target in
            stopSheetView(for: target)
        }
        .alert("Bo couldn't verify this", isPresented: Binding(
            get: { boWebOverrideTarget != nil },
            set: { if !$0 { boWebOverrideTarget = nil } }
        )) {
            Button("Cancel", role: .cancel) { boWebOverrideTarget = nil }
            Button("I've verified it") {
                if let target = boWebOverrideTarget, let id = target.eventId {
                    overrideBoWebVerification(id: id, slotId: target.id)
                }
                boWebOverrideTarget = nil
            }
        } message: {
            Text("Bo found this on the web but couldn't independently confirm it's real. If you've checked the source yourself and it's legit, tap \"I've verified it\" to clear the warning. The event still won't be added to other users' feeds.")
        }
        .onAppear {
            // Listen for state pushes from Kotlin while presented. Each push
            // also triggers an overlap re-detection so stale warnings don't linger.
            PlanDetailBridge.shared.pushPlanState = { json in
                if let updated = PlanDetailPlan.decode(from: json as String) {
                    DispatchQueue.main.async {
                        plan = updated
                        if selectedDayIndex >= plan.days.count {
                            selectedDayIndex = max(0, plan.days.count - 1)
                        }
                        overlapSlotIds = detectOverlaps(in: updated)
                    }
                }
            }
            // Phase 5: receive swap alternatives from Kotlin's PlanSwapService.
            PlanDetailBridge.shared.onSwapAlternativesReady = { json in
                DispatchQueue.main.async {
                    swapLoading = false
                    swapAlternatives = decodeSwapAlternatives(from: json as String)
                }
            }
            overlapSlotIds = detectOverlaps(in: plan)
            maybeAutoOpenGroupSizeSheet()
        }
        .onDisappear {
            // NOTE: do NOT nil out pushPlanState / onSwapAlternativesReady here.
            // onDisappear fires when a sheet is presented over this view (e.g.
            // the per-plan Bo chat), and clearing the bridge would drop the
            // push that arrives from Bo's update_plan confirmation, leaving the
            // detail view stale when the user returns. The next onAppear of any
            // PlanDetailView re-binds these handlers anyway.
            PlanDetailBridge.shared.onDismissed?()
        }
    }

    // MARK: - First-open group-size auto-prompt

    /// One-time per plan, on the creator's first time opening the detail
    /// screen, surface the GroupSizeSheet so they can set the count without
    /// Bo asking in chat. Skip if:
    /// - User isn't the creator (can't edit count anyway)
    /// - Plan already has an explicit group size
    /// - Plan is past (count is irrelevant)
    /// - We've already prompted for this plan id (UserDefaults flag, set as
    ///   soon as we open the sheet so cancelling once doesn't re-prompt)
    private func maybeAutoOpenGroupSizeSheet() {
        guard isCreator, !plan.isPast, plan.groupSize == nil else { return }
        let key = "attendees_prompted_v1_\(plan.id)"
        if UserDefaults.standard.bool(forKey: key) { return }
        UserDefaults.standard.set(true, forKey: key)
        // Tiny delay so the entrance transition finishes before the sheet
        // animates up — otherwise the two animations stack and feel jittery.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
            showGroupSizeSheet = true
        }
    }

    // MARK: - Stop sheet routing

    @ViewBuilder
    private func stopSheetView(for target: StopSheet) -> some View {
        switch target {
        case .reservation(let slot, let dayIdx):
            ReservationSheet(
                slot: slot, dayIdx: dayIdx,
                onMakeReservation: { url in
                    activeStopSheet = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        safariUrl = IdentifiableURL(url: url)
                    }
                },
                onMarkUnavailable: {
                    // Same chooser as the ticket flow — Replace event opens
                    // Bo with a swap prompt, Remove event drops the slot.
                    activeStopSheet = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        activeStopSheet = .unavailableReservation(slot: slot, dayIdx: dayIdx)
                    }
                },
                onChooseDetails: {
                    activeStopSheet = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        activeStopSheet = .reservationDetails(slot: slot, dayIdx: dayIdx, isEditing: false)
                    }
                },
                onDismiss: { activeStopSheet = nil }
            )
        case .reservationDetails(let slot, let dayIdx, let isEditing):
            let goingCount = plan.attendees.filter { $0.confirmed }.count
            ReservationDetailsSheet(
                slot: slot, dayIdx: dayIdx,
                goingCount: max(goingCount, 1),
                isEditing: isEditing,
                onSave: { partySize, time in
                    PlanDetailBridge.shared.onSaveReservationDetails?(
                        KotlinInt(int: Int32(dayIdx)), slot.id,
                        KotlinInt(int: Int32(partySize)), time
                    )
                    activeStopSheet = nil
                },
                onClear: {
                    PlanDetailBridge.shared.onClearReservation?(KotlinInt(int: Int32(dayIdx)), slot.id)
                    activeStopSheet = nil
                },
                onDismiss: { activeStopSheet = nil }
            )
        case .tickets(let slot, let dayIdx):
            TicketsSheet(
                slot: slot, dayIdx: dayIdx,
                onGetTickets: { url in
                    activeStopSheet = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        safariUrl = IdentifiableURL(url: url)
                    }
                },
                onMarkUnavailable: {
                    // Bounce through a tiny chooser sheet — "Replace event"
                    // routes to the existing onReplaceSlot (opens Bo with a
                    // replacement prompt), "Remove event" calls onRemoveSlot.
                    // Two-step animation: dismiss tickets, brief delay, then
                    // present the chooser so sheets don't fight presentation.
                    activeStopSheet = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        activeStopSheet = .unavailableTicket(slot: slot, dayIdx: dayIdx)
                    }
                },
                onChooseDetails: {
                    activeStopSheet = nil
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        activeStopSheet = .ticketDetails(slot: slot, dayIdx: dayIdx, isEditing: false)
                    }
                },
                onDismiss: { activeStopSheet = nil }
            )
        case .unavailableTicket(let slot, let dayIdx):
            UnavailableActionSheet(
                title: "No tickets for \(slot.name)",
                subtitle: "Swap it for something else or take it off the plan.",
                onReplace: {
                    activeStopSheet = nil
                    PlanDetailBridge.shared.onReplaceSlot?(
                        KotlinInt(int: Int32(dayIdx)), slot.id, slot.name
                    )
                },
                onRemove: {
                    activeStopSheet = nil
                    PlanDetailBridge.shared.onRemoveSlot?(
                        KotlinInt(int: Int32(dayIdx)), slot.id
                    )
                },
                onDismiss: { activeStopSheet = nil }
            )
        case .unavailableReservation(let slot, let dayIdx):
            UnavailableActionSheet(
                title: "No reservations for \(slot.name)",
                subtitle: "Swap it for something else or take it off the plan.",
                onReplace: {
                    activeStopSheet = nil
                    PlanDetailBridge.shared.onReplaceSlot?(
                        KotlinInt(int: Int32(dayIdx)), slot.id, slot.name
                    )
                },
                onRemove: {
                    activeStopSheet = nil
                    PlanDetailBridge.shared.onRemoveSlot?(
                        KotlinInt(int: Int32(dayIdx)), slot.id
                    )
                },
                onDismiss: { activeStopSheet = nil }
            )
        case .ticketDetails(let slot, let dayIdx, let isEditing):
            let goingCount = plan.attendees.filter { $0.confirmed }.count
            TicketDetailsSheet(
                slot: slot, dayIdx: dayIdx,
                goingCount: max(goingCount, 1),
                isEditing: isEditing,
                onSave: { count in
                    PlanDetailBridge.shared.onSaveTicketDetails?(
                        KotlinInt(int: Int32(dayIdx)), slot.id, KotlinInt(int: Int32(count))
                    )
                    activeStopSheet = nil
                },
                onClear: {
                    PlanDetailBridge.shared.onClearTickets?(KotlinInt(int: Int32(dayIdx)), slot.id)
                    activeStopSheet = nil
                },
                onDismiss: { activeStopSheet = nil }
            )
        case .swap(let slot, let dayIdx):
            SwapSheet(
                slot: slot, dayIdx: dayIdx,
                alternatives: swapAlternatives,
                isLoading: swapLoading,
                onSwapIn: { alt in
                    let json = encodeSwapAlternative(alt)
                    PlanDetailBridge.shared.onSwapInAlternative?(KotlinInt(int: Int32(dayIdx)), slot.id, json)
                    activeStopSheet = nil
                },
                onKeepCurrent: { activeStopSheet = nil },
                onSeeMoreOptions: {
                    PlanDetailBridge.shared.onSeeMoreSwapOptions?(KotlinInt(int: Int32(dayIdx)), slot.id, slot.name)
                    activeStopSheet = nil
                }
            )
            .onAppear {
                // Kick off the alternatives fetch when the swap sheet appears.
                swapAlternatives = []
                swapLoading = true
                PlanDetailBridge.shared.onRequestSwapAlternatives?(KotlinInt(int: Int32(dayIdx)), slot.id)
            }
        }
    }

    /// Decode the JSON list returned by Kotlin's PlanSwapService.
    /// Phase 3.5: user override for a rejected Bo-Web slot. Calls the
    /// override edge function (which bumps user_verified_count) and on
    /// success adds the slot id to boWebLocallyVerified so the badge
    /// disappears immediately. Failure is silent — the warning stays.
    private func overrideBoWebVerification(id: String, slotId: String) {
        guard let userId = currentUserId,
              let supabaseURL = Bundle.main.object(forInfoDictionaryKey: "SUPABASE_URL") as? String
                  ?? URL(string: "https://blifubxslaucshmlphdc.supabase.co")?.absoluteString,
              let url = URL(string: "\(supabaseURL)/functions/v1/override-bo-web-finding")
        else { return }

        let supabaseAnonKey = Bundle.main.object(forInfoDictionaryKey: "SUPABASE_ANON_KEY") as? String ?? ""

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(supabaseAnonKey)", forHTTPHeaderField: "Authorization")
        let body: [String: Any] = ["user_id": userId, "id": id, "kind": "event"]
        req.httpBody = try? JSONSerialization.data(withJSONObject: body)

        URLSession.shared.dataTask(with: req) { _, response, _ in
            DispatchQueue.main.async {
                if (response as? HTTPURLResponse)?.statusCode == 200 {
                    boWebLocallyVerified.insert(slotId)
                }
            }
        }.resume()
    }

    private func decodeSwapAlternatives(from json: String) -> [SwapAlternative] {
        guard let data = json.data(using: .utf8) else { return [] }
        let decoder = JSONDecoder()
        return (try? decoder.decode([SwapAlternative].self, from: data)) ?? []
    }

    /// Encode a single alternative back to JSON for the swap-in bridge call.
    private func encodeSwapAlternative(_ alt: SwapAlternative) -> String {
        // We only need a subset of fields for the Kotlin side to construct the
        // new PlanSlot — see AppNavigation.kt's onSwapInAlternative handler.
        var dict: [String: Any] = [
            "kind": alt.kind.rawValue,
            "sourceId": alt.sourceId,
            "name": alt.name,
        ]
        if let v = alt.category { dict["category"] = v }
        if let v = alt.neighborhood { dict["neighborhood"] = v }
        if let v = alt.rating { dict["rating"] = v }
        if let v = alt.imageUrl { dict["imageUrl"] = v }
        if let v = alt.lat { dict["lat"] = v }
        if let v = alt.lng { dict["lng"] = v }
        if let v = alt.ticketUrl { dict["ticketUrl"] = v }
        if let v = alt.sourceUrl { dict["sourceUrl"] = v }
        if let v = alt.reservationUrl { dict["reservationUrl"] = v }
        if let v = alt.venueWebsite { dict["venueWebsite"] = v }
        if let v = alt.venueAddress { dict["venueAddress"] = v }
        if let v = alt.priceMin { dict["priceMin"] = v }
        if let v = alt.priceMax { dict["priceMax"] = v }
        if let v = alt.isFree { dict["isFree"] = v }
        if let v = alt.eventStartAt { dict["eventStartAt"] = v }
        if let v = alt.eventTime { dict["eventTime"] = v }
        guard let data = try? JSONSerialization.data(withJSONObject: dict),
              let str = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return str
    }

    // MARK: - Overlap detection
    //
    // Phase 3 detection is intentionally simple: within each day, walk slots
    // in time order; if two adjacent slots have less than a 60-minute gap
    // between their start times, the LATER slot earns an overlap warning.
    // Only fires for slots in `.made` state — we don't surface conflicts
    // for unbooked stops (they're flexible by definition).

    private func detectOverlaps(in plan: PlanDetailPlan) -> Set<String> {
        var ids = Set<String>()
        for day in plan.days {
            let withTimes = day.slots.compactMap { s -> (PlanDetailSlot, Date)? in
                guard let d = parseTime(s.time) else { return nil }
                return (s, d)
            }.sorted { $0.1 < $1.1 }
            for i in 1..<max(withTimes.count, 1) where i < withTimes.count {
                let prev = withTimes[i - 1]
                let curr = withTimes[i]
                let gap = curr.1.timeIntervalSince(prev.1)
                let conflictsWithReservation =
                    prev.0.reservationState == .made || curr.0.reservationState == .made
                if gap < 60 * 60 && conflictsWithReservation {
                    ids.insert(curr.0.id)
                }
            }
        }
        return ids
    }

    // MARK: - Hero

    private var hero: some View {
        // Show a route map for the currently-selected day when at least 2 of
        // its slots have geocoded coordinates. Otherwise fall back to a
        // coral gradient hero so the plan never looks like a broken image.
        let dayIdx = max(0, min(selectedDayIndex, plan.days.count - 1))
        let dayGeocoded: [PlanDetailSlot] = plan.days.indices.contains(dayIdx)
            ? plan.days[dayIdx].slots.filter { $0.venueLat != nil && $0.venueLng != nil }
            : []

        let day = plan.days.indices.contains(dayIdx) ? plan.days[dayIdx] : nil
        let dayStops = day?.slots.count ?? 0
        return ZStack(alignment: .bottom) {
            ZStack {
                if !dayGeocoded.isEmpty {
                    MapHeroView(slots: dayGeocoded) { totals in
                        // Coalesce updates so a stale day's metrics don't
                        // flicker in after the user has switched away.
                        routeTotals = totals
                    }
                } else {
                    heroFallback
                }
            }
            .frame(height: 250)
            .frame(maxWidth: .infinity)
            .clipped()
            .overlay(
                // Soft top-only gradient — keeps the back / share / menu
                // buttons readable on the map without darkening the route.
                LinearGradient(
                    stops: [
                        .init(color: Color.black.opacity(0.30), location: 0),
                        .init(color: Color.black.opacity(0.08), location: 0.30),
                        .init(color: Color.black.opacity(0.0), location: 0.55),
                    ],
                    startPoint: .top, endPoint: .bottom
                )
            )

            // Top toolbar
            VStack {
                HStack {
                    glassButton {
                        onBack()
                    } content: {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.white)
                    }
                    Spacer()
                    glassButton {
                        PlanDetailBridge.shared.onShare?()
                    } content: {
                        Image(systemName: "square.and.arrow.up")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.white)
                    }
                    if isCreator {
                        Menu {
                            Button {
                                showAttendeeSheet = true
                            } label: {
                                Label("View Attendees", systemImage: "person.2")
                            }
                            Button {
                                showGroupSizeSheet = true
                            } label: {
                                Label("Edit # of Attendees", systemImage: "person.crop.circle.badge.plus")
                            }
                            Divider()
                            Button {
                                PlanDetailBridge.shared.onEditPlan?()
                            } label: {
                                Label("Edit Plan", systemImage: "sparkles")
                            }
                            Button {
                                showRenameSheet = true
                            } label: {
                                Label("Rename Plan", systemImage: "pencil")
                            }
                            // Add Day moved here from the day-strip "+ ADD"
                            // cell + single-day "+ Add day" button. Keeps
                            // the strip + stop list visually quiet and
                            // makes Add Day discoverable alongside other
                            // plan-level edits.
                            if !plan.isPast && plan.days.count < kMaxDays {
                                Button {
                                    PlanDetailBridge.shared.onAddDay?()
                                } label: {
                                    Label("Add Day", systemImage: "calendar.badge.plus")
                                }
                            }
                            Divider()
                            Button(role: .destructive) {
                                showDeleteConfirm = true
                            } label: {
                                Label("Delete Plan", systemImage: "trash")
                            }
                        } label: {
                            Circle()
                                .fill(Color.white.opacity(0.18))
                                .frame(width: 32, height: 32)
                                .overlay(
                                    Image(systemName: "ellipsis")
                                        .font(.system(size: 14, weight: .bold))
                                        .foregroundColor(.white)
                                )
                                .overlay(Circle().stroke(Color.white.opacity(0.25), lineWidth: 0.5))
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 22)
                Spacer()
            }

            // Floating peek-paging title-card carousel overlapping the map's
            // bottom. Each day has its own card; the active card snaps to
            // center and adjacent days peek from the edges (left peek for
            // the previous day, right peek for the next). Replaces the
            // prior single-card + DragGesture pattern so the user can see
            // they're in a multi-day plan at a glance.
            titleCardCarousel
                .padding(.bottom, 14)
        }
        .frame(height: 250)
    }

    // MARK: - Title card carousel (peek-paging)

    @ViewBuilder
    private var titleCardCarousel: some View {
        if plan.days.count <= 1 {
            // Single-day plan: keep the original single floating card —
            // there's nothing to peek to.
            let day = plan.days.first
            let dayStops = day?.slots.count ?? 0
            titleCard(day: day, dayStops: dayStops, isActive: true)
                .padding(.horizontal, 14)
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 10) {
                    ForEach(Array(plan.days.enumerated()), id: \.offset) { idx, d in
                        let isActive = idx == selectedDayIndex
                        titleCard(day: d, dayStops: d.slots.count, isActive: isActive)
                            .containerRelativeFrame(.horizontal, count: 1, span: 1, spacing: 0)
                            .id(idx)
                    }
                }
                .scrollTargetLayout()
            }
            // 28pt margin on each side leaves 28pt of the next/prev card
            // visible at the edges. Tune higher for more peek, lower for
            // less. Less than ~16 reads as a layout glitch; more than ~40
            // makes the active card feel cramped.
            .contentMargins(.horizontal, 28, for: .scrollContent)
            .scrollTargetBehavior(.viewAligned)
            .scrollPosition(id: scrollDayIdBinding)
            // Hug the card's intrinsic height. Without this, the ScrollView
            // fills the ZStack vertically and the cards align to its top —
            // pushing the carousel away from the hero's bottom edge.
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity)
        }
    }

    /// Bridge between `@State selectedDayIndex: Int` and SwiftUI's
    /// `.scrollPosition(id: Binding<Int?>)`. Keeps the day index in sync
    /// with whichever card is snapped to center, so the map camera and
    /// the day strip below stay aligned with the visible title card.
    private var scrollDayIdBinding: Binding<Int?> {
        Binding<Int?>(
            get: { selectedDayIndex },
            set: { newValue in
                guard let v = newValue, v != selectedDayIndex else { return }
                withAnimation(.easeInOut(duration: 0.25)) { selectedDayIndex = v }
            }
        )
    }

    // MARK: - Title card (floats on the map's bottom edge)

    private func titleCard(day: PlanDetailDay?, dayStops: Int, isActive: Bool) -> some View {
        let dateLabel = headerDateLabel(day: day)
        // Only the active card wears the route metrics — siblings show
        // just the stop count, since `routeTotals` only reflects the day
        // currently rendered by MapHeroView.
        let metrics = headerMetricsLabel(dayStops: dayStops, includeRoute: isActive)
        let titleHeader = headerTitleLabel()
        return HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                // Line 1 — coral all-caps plan title (small label).
                if !titleHeader.isEmpty {
                    Text(titleHeader.uppercased())
                        .font(.system(size: 10, weight: .heavy))
                        .tracking(0.8)
                        .foregroundColor(pdCoral)
                }
                // Line 2 — current day's date in the display font.
                if !dateLabel.isEmpty {
                    Text(dateLabel)
                        .font(PDF.display(22, .black))
                        .foregroundColor(pdInk)
                        .lineLimit(2)
                }
                // Line 3 — per-day stops + miles + travel time.
                if !metrics.isEmpty {
                    Text(metrics)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(pdInk3)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(.white)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(pdLine, lineWidth: 0.5)
        )
        .shadow(color: .black.opacity(0.10), radius: 8, x: 0, y: 3)
        // Day switching is now driven by the carousel's snap-paging in
        // `titleCardCarousel`; no per-card DragGesture needed. Inactive
        // sibling cards dim slightly so the active one reads as primary.
        .opacity(isActive ? 1.0 : 0.85)
    }

    private func headerTitleLabel() -> String {
        // Strip any "Family Trip: May 13-16" → "Family Trip" so the all-caps
        // header line doesn't duplicate the date range that's already shown
        // in the per-day date line below.
        let raw = plan.title
        if let colonRange = raw.range(of: ":") {
            return String(raw[..<colonRange.lowerBound]).trimmingCharacters(in: .whitespaces)
        }
        return raw
    }

    private func headerDateLabel(day: PlanDetailDay?) -> String {
        // Prefer the per-day label ("Saturday, May 16") shortened to
        // "SAT, MAY 16". Falls back to the plan-level date display
        // ("WED – SAT") when a single day isn't available.
        if let raw = day?.label, !raw.isEmpty {
            return shortDayLabel(raw)
        }
        return plan.dateDisplay
    }

    private func shortDayLabel(_ raw: String) -> String {
        // raw examples: "Saturday, May 16" | "Sat, May 16" | "May 16"
        // Output: "Sat, May 16" — keep month name as-is.
        let parts = raw.split(separator: ",", maxSplits: 1, omittingEmptySubsequences: true)
        if parts.count == 2 {
            let dayName = parts[0].trimmingCharacters(in: .whitespaces)
            let rest = parts[1].trimmingCharacters(in: .whitespaces)
            let short = String(dayName.prefix(3))
            return "\(short), \(rest)"
        }
        return raw
    }

    private func headerMetricsLabel(dayStops: Int, includeRoute: Bool = true) -> String {
        // Always render the stops count, even when 0, so the title card
        // keeps a 3-line layout (header / date / metrics) and the card's
        // height stays constant across days.
        // `includeRoute` is false for sibling cards in the peek-paging
        // carousel — `routeTotals` only reflects the active day so we'd
        // otherwise label every visible card with the active day's miles.
        var parts: [String] = ["\(dayStops) \(dayStops == 1 ? "stop" : "stops")"]
        if includeRoute, let r = routeTotals {
            if r.miles > 0 {
                parts.append(String(format: "%.1f mi", r.miles))
            }
            if r.minutes > 0 {
                parts.append("~\(formatTravelMinutes(r.minutes)) \(r.dominantMode.lowerLabel)")
            }
        }
        return parts.joined(separator: " · ")
    }

    private func formatTravelMinutes(_ mins: Int) -> String {
        if mins >= 60 {
            let hr = mins / 60
            let rem = mins % 60
            return rem == 0 ? "\(hr) hr" : "\(hr) hr \(rem) min"
        }
        return "\(mins) min"
    }

    private var heroFallback: some View {
        let initial = String(plan.title.first.map(String.init) ?? "L").uppercased()
        return ZStack {
            LinearGradient(
                colors: [
                    Color(red: 1.00, green: 0.42, blue: 0.42),
                    Color(red: 0.91, green: 0.26, blue: 0.24),
                    Color(red: 0.55, green: 0.18, blue: 0.20),
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            // Soft circles to give the gradient some depth
            Circle()
                .fill(.white.opacity(0.10))
                .frame(width: 220, height: 220)
                .offset(x: 110, y: -70)
            Circle()
                .fill(.black.opacity(0.10))
                .frame(width: 180, height: 180)
                .offset(x: -90, y: 80)
            Text(initial)
                .font(.system(size: 86, weight: .black))
                .foregroundColor(.white.opacity(0.18))
                .offset(x: 80, y: -10)
        }
    }

    private func glassButton<Content: View>(action: @escaping () -> Void, @ViewBuilder content: @escaping () -> Content) -> some View {
        Button(action: action) {
            Circle()
                .fill(Color.white.opacity(0.18))
                .frame(width: 32, height: 32)
                .overlay(content())
                .overlay(Circle().stroke(Color.white.opacity(0.25), lineWidth: 0.5))
        }
        .buttonStyle(.plain)
    }

    // MARK: - Attendee card
    //
    // Spec: stacked avatars (max 3 visible, +N overflow chip) + "X of Y going"
    // text + a *prominent* Invite pill, all on one row in a white card.

    private var attendeeCard: some View {
        let goingCount = max(plan.attendees.filter { $0.confirmed }.count, 1)
        let totalCount = max(plan.attendees.count, goingCount)
        let visibleAvatars = Array(plan.attendees.prefix(3))
        let overflow = max(0, totalCount - visibleAvatars.count)
        return HStack(spacing: 12) {
            // Avatar stack — overlapping, max 3 + overflow chip
            Button {
                showAttendeeSheet = true
            } label: {
                HStack(spacing: -10) {
                    ForEach(Array(visibleAvatars.enumerated()), id: \.element.id) { idx, a in
                        attendeeAvatar(a, size: 30)
                            .zIndex(Double(visibleAvatars.count - idx))
                    }
                    if overflow > 0 {
                        Text("+\(overflow)")
                            .font(.system(size: 11, weight: .black))
                            .foregroundColor(pdInk2)
                            .frame(width: 30, height: 30)
                            .background(pdShade)
                            .clipShape(Circle())
                            .overlay(Circle().stroke(Color.white, lineWidth: 1.5))
                            .zIndex(0)
                    }
                }
            }
            .buttonStyle(.plain)

            // "X of Y going" — tappable, opens the attendee sheet.
            Button {
                showAttendeeSheet = true
            } label: {
                HStack(spacing: 4) {
                    Text("\(goingCount) of \(totalCount)")
                        .font(.system(size: 15, weight: .black))
                        .foregroundColor(pdInk)
                    Text("going")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(pdInk3)
                }
            }
            .buttonStyle(.plain)

            Spacer()

            // Maximally clickable Invite pill.
            InvitePill {
                PlanDetailBridge.shared.onInvite?()
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(pdLine, lineWidth: 0.5)
        )
        .shadow(color: .black.opacity(0.06), radius: 6, x: 0, y: 2)
        .padding(.horizontal, 14)
    }

    // MARK: - Invite pill
    //
    // The whole thing is the tap target. Coral fill, white plus + label, slight
    // press-spring, subtle inner highlight. Designed to feel inviting (heh) and
    // be the most-clickable thing on the screen.
    private struct InvitePill: View {
        let onTap: () -> Void
        @State private var pressed = false
        var body: some View {
            Button(action: onTap) {
                HStack(spacing: 5) {
                    Image(systemName: "plus")
                        .font(.system(size: 13, weight: .black))
                        .foregroundColor(.white)
                    Text("Invite")
                        .font(.system(size: 14, weight: .black))
                        .foregroundColor(.white)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(
                    LinearGradient(
                        colors: [
                            Color(red: 1.0, green: 0.48, blue: 0.45),
                            Color(red: 0.91, green: 0.31, blue: 0.28),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .clipShape(Capsule())
                .overlay(
                    // Inner highlight — fakes a glossy top edge.
                    Capsule()
                        .strokeBorder(Color.white.opacity(0.35), lineWidth: 1)
                        .blendMode(.overlay)
                )
                .shadow(color: Color(red: 0.91, green: 0.31, blue: 0.28).opacity(0.35), radius: 8, x: 0, y: 4)
                .scaleEffect(pressed ? 0.94 : 1.0)
                .animation(.spring(response: 0.25, dampingFraction: 0.6), value: pressed)
            }
            .buttonStyle(.plain)
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in pressed = true }
                    .onEnded { _ in pressed = false }
            )
        }
    }

    // MARK: - Share Plan CTA (persistent bottom bar)

    private var shareCtaBar: some View {
        let total = max(plan.groupSize ?? plan.attendees.count, 1)
        let confirmed = max(plan.attendees.filter { $0.confirmed }.count, 0)
        let subtext: String? = {
            if total <= 1 { return nil }                                // solo
            let unconfirmed = max(0, total - confirmed)
            if unconfirmed == 0 { return "\(total) confirmed" }         // all in
            let noun = unconfirmed == 1 ? "person" : "people"
            return "\(unconfirmed) \(noun) unconfirmed"
        }()
        return Button {
            PlanDetailBridge.shared.onShare?()
        } label: {
            VStack(spacing: 2) {
                HStack(spacing: 6) {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: 15, weight: .heavy))
                    Text("Share Plan")
                        .font(.system(size: 16, weight: .heavy))
                }
                .foregroundColor(.white)
                if let sub = subtext {
                    Text(sub)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.white.opacity(0.9))
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            // Match the event-detail primaryButton styling: solid coral fill,
            // 2pt coralDk border overlay, flat coralDk drop-shadow at y=4
            // (radius=0). Creates a stacked "block" depth instead of a soft
            // gradient — consistent across primary CTAs in the app.
            .background(pdCoral)
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(pdCoralDk, lineWidth: 2))
            .shadow(color: pdCoralDk.opacity(0.4), radius: 0, x: 0, y: 4)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 14)
        .padding(.bottom, 16)
    }

    private func attendeeAvatar(_ a: PlanDetailAttendee, size: CGFloat) -> some View {
        let color = uiColor(forKotlinARGB: a.avatarColor)
        return Text(a.confirmed ? a.initials.prefix(1).uppercased() : "+")
            .font(.system(size: size * 0.42, weight: .black))
            .foregroundColor(.white)
            .frame(width: size, height: size)
            .background(a.confirmed ? color : pdInk4)
            .clipShape(Circle())
            .overlay(Circle().stroke(Color.white, lineWidth: 1.5))
    }

    // MARK: - Day header (single-day "+ Add day" / multi-day strip)

    @ViewBuilder
    private var dayHeader: some View {
        if plan.isEffectivelyMultiDay {
            multiDayStrip
        } else {
            // Single-day plans: no header strip. The "X stops" pill +
            // standalone "+ Add day" button were redundant with the
            // title-card metrics line + the overflow menu's Add Day
            // entry, and added vertical chrome below the hero.
            EmptyView()
        }
    }

    // Multi-day: single white card containing equally-spaced day cells.
    // Active day fills with coral; inactive cells are flat ink on transparent.
    // Cells fit within the screen when there are <=4 days; >4 enables scroll.
    private var multiDayStrip: some View {
        // Trim trailing orphan days. Add-Day moved to the overflow menu
        // (header `⋯`) so the strip itself is just date cells now —
        // cleaner read and the cell widths divide evenly.
        let visibleDays = trimTrailingPhantomDays(plan.days)
        let cellCount = visibleDays.count
        let fitsWithoutScroll = cellCount <= 4

        let row = HStack(spacing: 0) {
            ForEach(Array(visibleDays.enumerated()), id: \.offset) { idx, day in
                dayCell(idx: idx, day: day)
                    .frame(maxWidth: .infinity)
                if idx < visibleDays.count - 1 {
                    Rectangle()
                        .fill(pdLine)
                        .frame(width: 0.5)
                        .padding(.vertical, 10)
                }
            }
        }

        return Group {
            if fitsWithoutScroll {
                row
            } else {
                ScrollView(.horizontal, showsIndicators: false) { row.frame(minWidth: 360) }
            }
        }
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(pdLine, lineWidth: 0.5)
        )
        .shadow(color: .black.opacity(0.06), radius: 6, x: 0, y: 2)
        .padding(.horizontal, 14)
    }

    private func dayCell(idx: Int, day: PlanDetailDay) -> some View {
        let isActive = idx == selectedDayIndex
        let dayName = shortDayOfWeek(day) // "THU" or "DAY 1" fallback
        let numeral = extractDayNumeral(day) ?? day.shortName
        let stopsLabel = "\(day.slots.count) \(day.slots.count == 1 ? "stop" : "stops")"
        return Button {
            withAnimation(.easeInOut(duration: 0.18)) { selectedDayIndex = idx }
        } label: {
            VStack(spacing: 4) {
                Text(dayName)
                    .font(.system(size: 9, weight: .heavy))
                    .tracking(0.8)
                    .foregroundColor(isActive ? .white.opacity(0.85) : pdInk3)
                Text(numeral)
                    .font(PDF.display(22, .black))
                    .foregroundColor(isActive ? .white : pdInk)
                Text(stopsLabel)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(isActive ? .white.opacity(0.85) : pdInk3)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(
                Group {
                    if isActive {
                        LinearGradient(
                            colors: [pdCoral, pdCoralDk],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    } else {
                        Color.clear
                    }
                }
            )
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .padding(.horizontal, 4)
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
    }

    private func addDayCell() -> some View {
        Button {
            PlanDetailBridge.shared.onAddDay?()
        } label: {
            VStack(spacing: 4) {
                Image(systemName: "plus")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(pdCoral)
                Text("ADD")
                    .font(.system(size: 9, weight: .heavy))
                    .tracking(0.8)
                    .foregroundColor(pdCoral)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .padding(.horizontal, 4)
        }
        .buttonStyle(.plain)
    }

    /// Drop trailing "phantom" days — days with 0 stops AND no proper
    /// day-of-week label. These appear when upstream code adds orphan
    /// placeholder days; rendering them confuses the user (they look like
    /// extra future days). A trailing day WITH a real date label is kept
    /// (the creator may have added it intentionally to fill in later).
    private func trimTrailingPhantomDays(_ days: [PlanDetailDay]) -> [PlanDetailDay] {
        var result = days
        while let last = result.last,
              last.slots.isEmpty,
              shortDayOfWeek(last).isEmpty {
            result.removeLast()
        }
        return result
    }

    private func shortDayOfWeek(_ day: PlanDetailDay) -> String {
        // Try to derive from day.label ("Saturday, May 16") → "SAT".
        // Falls back to "DAY N"-style ordinal.
        let raw = day.label
        if let comma = raw.firstIndex(of: ",") {
            let firstWord = raw[..<comma].trimmingCharacters(in: .whitespaces)
            if !firstWord.isEmpty {
                return String(firstWord.prefix(3)).uppercased()
            }
        }
        // Try shortName ("Sat") if it's a 3-letter weekday.
        let trimmed = day.shortName.trimmingCharacters(in: .whitespaces)
        if !trimmed.isEmpty && trimmed.count <= 4 {
            return trimmed.prefix(3).uppercased()
        }
        return ""
    }

    // Single-day: white pill containing only "X stops", then "+ Add day"
    // sits OUTSIDE the pill as a flat coral link — secondary, doesn't
    // compete visually with the stops count.
    private var singleDayStrip: some View {
        HStack(spacing: 12) {
            Text("\(plan.totalStops) \(plan.totalStops == 1 ? "stop" : "stops")")
                .font(.system(size: 16, weight: .black))
                .foregroundColor(pdInk)
                .padding(.horizontal, 14)
                .padding(.vertical, 14)
                .background(Color.white)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .overlay(
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(pdLine, lineWidth: 0.5)
                )
                .shadow(color: .black.opacity(0.06), radius: 6, x: 0, y: 2)

            Spacer()

            if isCreator && !plan.isPast && plan.days.count < kMaxDays {
                Button {
                    PlanDetailBridge.shared.onAddDay?()
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "plus").font(.system(size: 12, weight: .bold))
                        Text("Add day").font(.system(size: 13, weight: .bold))
                    }
                    .foregroundColor(pdCoral)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 14)
    }

    // MARK: - Stop list

    @ViewBuilder
    private var stopList: some View {
        let dayIdx: Int = {
            if plan.isEffectivelyMultiDay {
                return min(selectedDayIndex, plan.days.count - 1)
            } else {
                return plan.days.firstIndex(where: { !$0.slots.isEmpty }) ?? 0
            }
        }()
        if dayIdx >= 0 && dayIdx < plan.days.count {
            let day = plan.days[dayIdx]
            // VStack with 0 spacing so the per-slot timeline columns butt
            // up against each other and form a single continuous line
            // running through all the cards. Inter-card visual gap is
            // produced by the card row's bottom padding instead.
            VStack(spacing: 0) {
                if day.slots.isEmpty {
                    emptyDayCard(dayIdx: dayIdx)
                } else {
                    ForEach(Array(day.slots.enumerated()), id: \.element.id) { i, slot in
                        let isLast = i == day.slots.count - 1
                        HStack(alignment: .top, spacing: 10) {
                            // Timeline column — TIME label + dot + line
                            // continuing to the bottom of the row so the
                            // line meets the next slot's TIME label,
                            // making the rail visually unbroken across
                            // the whole day.
                            VStack(spacing: 0) {
                                Text(slot.time.uppercased())
                                    .font(.system(size: 10, weight: .heavy))
                                    .foregroundColor(pdCoral)
                                    .tracking(0.4)
                                    .lineLimit(1)
                                    .fixedSize()
                                    .padding(.bottom, 5)
                                Circle()
                                    .fill(pdCoral)
                                    .frame(width: 9, height: 9)
                                if !isLast {
                                    Rectangle()
                                        .fill(pdCoral.opacity(0.22))
                                        .frame(width: 1.5)
                                        .frame(maxHeight: .infinity)
                                        .padding(.top, 4)
                                } else {
                                    Spacer(minLength: 0)
                                }
                            }
                            .frame(width: 56)

                            StopCard(
                                slot: slot,
                                readOnly: !isCreator || plan.isPast,
                                dayIdx: dayIdx,
                                hasOverlapWarning: overlapSlotIds.contains(slot.id),
                                isOverlapAcknowledged: acknowledgedOverlaps.contains(slot.id),
                                onAcknowledgeOverlap: {
                                    acknowledgedOverlaps.insert(slot.id)
                                },
                                onOpenSheet: { target in activeStopSheet = target },
                                onReplace: {
                                    PlanDetailBridge.shared.onReplaceSlot?(KotlinInt(int: Int32(dayIdx)), slot.id, slot.name)
                                },
                                onRemove: {
                                    PlanDetailBridge.shared.onRemoveSlot?(KotlinInt(int: Int32(dayIdx)), slot.id)
                                },
                                onOpenSafari: { url in safariUrl = IdentifiableURL(url: url) },
                                onTapBoWebOverride: { boWebOverrideTarget = slot },
                                isLocallyVerified: boWebLocallyVerified.contains(slot.id)
                            )
                            .padding(.bottom, isLast ? 0 : 10)
                        }
                        if !isLast, let travel = day.travel?[safe: i] {
                            HStack { Spacer().frame(width: 56); TransitRow(mode: travel.mode, mins: Int(travel.mins)); Spacer() }
                        }
                    }
                    if isCreator && !plan.isPast {
                        if day.slots.count < kMaxStopsPerDay {
                            Button {
                                PlanDetailBridge.shared.onAddEvent?(KotlinInt(int: Int32(dayIdx)))
                            } label: {
                                HStack(spacing: 6) {
                                    Image(systemName: "plus").font(.system(size: 13, weight: .bold))
                                    Text("Add event").font(.system(size: 14, weight: .bold))
                                }
                                .foregroundColor(pdCoral)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 14)
                                        .stroke(pdCoral, lineWidth: 1.5)
                                        .opacity(0.8)
                                )
                                .background(
                                    RoundedRectangle(cornerRadius: 14)
                                        .fill(pdCoralSoft.opacity(0.4))
                                )
                            }
                            .buttonStyle(.plain)
                            // Breathing room between the last stop card and
                            // the Add Event CTA. The card list now uses
                            // VStack(spacing: 0) so this button needs its
                            // own explicit top inset.
                            .padding(.top, 16)
                        } else {
                            Text("Day full — max 8 stops.")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundColor(pdInk4)
                                .frame(maxWidth: .infinity)
                                .padding(.top, 16)
                                .padding(.vertical, 8)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private func emptyDayCard(dayIdx: Int) -> some View {
        VStack(spacing: 12) {
            // Bo as the empty-state icon — replaces the prior sparkle glyph.
            // Bo sits on the soft-coral backdrop so the empty state still
            // reads as "this is a Bo invitation" rather than a stock icon.
            ZStack {
                Circle().fill(pdCoralSoft).frame(width: 64, height: 64)
                Image("BoDefaultClean")
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 56, height: 56)
            }
            Text("Your plan is empty")
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(pdInk)
            Text(isCreator ? "Build with Bo, or add a stop yourself." : "The planner hasn't added anything yet.")
                .font(.system(size: 12))
                .foregroundColor(pdInk3)
                .multilineTextAlignment(.center)
            if isCreator && !plan.isPast {
                Button {
                    PlanDetailBridge.shared.onAddEvent?(KotlinInt(int: Int32(dayIdx)))
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "plus").font(.system(size: 13, weight: .bold))
                        Text("Add an event").font(.system(size: 13, weight: .bold))
                    }
                    .foregroundColor(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(pdCoral)
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 24)
        .frame(maxWidth: .infinity)
        .background(Color.white)
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(pdLine, lineWidth: 1.5))
        .clipShape(RoundedRectangle(cornerRadius: 18))
    }

    // MARK: - Past banner

    private var pastBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: "clock")
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(pdInk3)
            Text("This plan is in the past — \(plan.dateDisplay)")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(pdInk2)
            Spacer()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(pdShade)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(pdLine, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .padding(.horizontal, 16)
    }

    // MARK: - Delete footer

    private var deleteFooter: some View {
        Button {
            showDeleteConfirm = true
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "trash")
                    .font(.system(size: 12, weight: .bold))
                Text("Delete plan")
                    .font(.system(size: 13, weight: .bold))
            }
            .foregroundColor(pdInk3)
        }
        .buttonStyle(.plain)
        .padding(.top, 12)
    }
}

// MARK: - Stop card

/// Press-shrink for slot cards. Lives in the ButtonStyle (not a separate
/// gesture) so isPressed flips instantly on touch-down and the tap action
/// fires reliably on touch-up. Inner chip Buttons and the ellipsis Menu
/// keep working — SwiftUI's nested-button hit-testing absorbs their taps
/// before the outer Button sees them.
private struct PressableCardStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1.0)
            .animation(.spring(response: 0.28, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

private struct StopCard: View {
    let slot: PlanDetailSlot
    let readOnly: Bool
    let dayIdx: Int
    let hasOverlapWarning: Bool
    let isOverlapAcknowledged: Bool
    let onAcknowledgeOverlap: () -> Void
    let onOpenSheet: (StopSheet) -> Void
    let onReplace: () -> Void
    let onRemove: () -> Void
    let onOpenSafari: (URL) -> Void
    let onTapBoWebOverride: () -> Void
    let isLocallyVerified: Bool

    @Environment(\.openURL) private var openURL
    @State private var showDirections = false
    @State private var showRemoveConfirm = false

    var body: some View {
        // Body — three single-line rows. The timeline rail (TIME + dot
        // + line) now lives in the parent stop list so it spans the
        // full vertical span across cards instead of breaking inside
        // each card.
        //
        // Wrapped in a Button so the tap-to-detail action and the
        // press-shrink animation share a single, reliable gesture path.
        // Earlier we used .simultaneousGesture(DragGesture) + .onTapGesture
        // — the drag silently locked out the tap on a quick press, and
        // the .onLongPressGesture fallback felt mushy due to gesture-
        // coordinator disambiguation lag. ButtonStyle drives isPressed
        // instantly on touch-down and Button.action fires reliably on
        // touch-up, with nested chip/Menu buttons absorbing their own
        // taps as SwiftUI does by default.
        Button(action: {
            PlanDetailBridge.shared.onSlotTap?(
                KotlinInt(int: Int32(dayIdx)),
                slot.id,
                slot.eventId,
                slot.venueName ?? slot.name
            )
        }) {
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 5) {
                // Row 1: name + inline status badges + overflow menu.
                // The badge logic is unchanged from the prior layout — same
                // Bo-Web verification states, restaurant BOOK AHEAD signal,
                // and acknowledgeable time-conflict warning, just rendered
                // alongside the title instead of next to the time.
                HStack(spacing: 6) {
                    Text(slot.name)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(pdInk)
                        .lineLimit(1)
                        .truncationMode(.tail)

                    if slot.isBoWeb {
                        let effectiveState = isLocallyVerified ? "verified" : slot.boWebState
                        switch effectiveState {
                        case "verified":
                            EmptyView()
                        case "rejected":
                            Button(action: { onTapBoWebOverride() }) {
                                HStack(spacing: 3) {
                                    Image(systemName: "exclamationmark.triangle.fill")
                                        .font(.system(size: 9, weight: .bold))
                                    Text("BO COULDN'T VERIFY")
                                        .font(.system(size: 8, weight: .black))
                                        .tracking(0.5)
                                }
                                .foregroundColor(Color(red: 0.85, green: 0.40, blue: 0.20))
                            }
                            .buttonStyle(.plain)
                            .layoutPriority(1)
                        default:
                            HStack(spacing: 3) {
                                Image(systemName: "sparkles")
                                    .font(.system(size: 9, weight: .bold))
                                Text("VERIFYING")
                                    .font(.system(size: 8, weight: .black))
                                    .tracking(0.5)
                            }
                            .foregroundColor(pdCoral)
                            .layoutPriority(1)
                        }
                    }
                    // BOOK AHEAD is rendered as a corner badge below
                    // (overlay), not inline — the inline version was
                    // truncating the venue name on narrower cards.
                    if hasOverlapWarning && !isOverlapAcknowledged {
                        Button(action: onAcknowledgeOverlap) {
                            HStack(spacing: 3) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .font(.system(size: 9, weight: .bold))
                                Text("TIME CONFLICT")
                                    .font(.system(size: 8, weight: .black))
                                    .tracking(0.5)
                            }
                            .foregroundColor(Color(red: 0.85, green: 0.20, blue: 0.20))
                        }
                        .buttonStyle(.plain)
                        .layoutPriority(1)
                    }
                    Spacer(minLength: 0)
                    if !readOnly {
                        Menu {
                            Button { onReplace() } label: {
                                Label("Replace this stop", systemImage: "sparkles")
                            }
                            Divider()
                            Button(role: .destructive) { showRemoveConfirm = true } label: {
                                Label("Remove from plan", systemImage: "trash")
                            }
                        } label: {
                            Image(systemName: "ellipsis")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(pdInk4)
                                .frame(width: 22, height: 22)
                        }
                        .layoutPriority(2)
                    }
                }

                // Row 2: subtitle line. For events: "Venue · City". For
                // venues: "Type · Neighborhood · ★ Rating". Always single
                // line — `subtitleLine` is composed by the existing helper.
                if !subtitleLine.isEmpty {
                    Text(subtitleLine)
                        .font(.system(size: 12))
                        .foregroundColor(pdInk3)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }

                // Row 3: CTA chips — Reservation/Tickets/Info + Directions.
                FlowChips(spacing: 6) {
                    if hasPrimaryAction {
                        primaryChip
                    } else if let info = infoUrl {
                        chip(label: "Info", systemIcon: "globe") {
                            onOpenSafari(info)
                        }
                    }
                    if slot.venueLat != nil && slot.venueLng != nil {
                        chip(label: "Directions", systemIcon: "figure.walk") {
                            showDirections = true
                        }
                    }
                }
                .padding(.top, 2)
            }
        }
        .padding(EdgeInsets(top: 12, leading: 12, bottom: 12, trailing: 12))
        .background(Color.white)
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(pdLine, lineWidth: 1.5))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        // Stacked-block "bezel" shadow — matches the Compose
        // `localCardSurface` look used on the Home + Explore feeds: a flat
        // pebble-coloured rectangle offset 4pt below the card, no Material
        // elevation glow. Placed in a trailing .background so it lands
        // BEHIND the white surface.
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(pdLine)
                .offset(y: 4)
        )
        // BOOK AHEAD as a corner badge — flush with the top-right corner
        // of the card so the title row never gets truncated by an inline
        // chip. Same condition as before (restaurant + no reservation +
        // venue reservable).
        .overlay(alignment: .topTrailing) {
            if slot.stopType == .restaurant
                && slot.reservationState == .none
                && (slot.venueReservable ?? true) {
                Text("BOOK AHEAD")
                    .font(.system(size: 8, weight: .black))
                    .tracking(0.5)
                    .foregroundColor(.white)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 4)
                    .background(Color(red: 0.95, green: 0.62, blue: 0.13))
                    .clipShape(
                        UnevenRoundedRectangle(
                            cornerRadii: .init(topLeading: 0, bottomLeading: 6, bottomTrailing: 0, topTrailing: 16)
                        )
                    )
            }
        }
        }
        .buttonStyle(PressableCardStyle())
        .confirmationDialog(
            "Get directions to \(slot.name)",
            isPresented: $showDirections,
            titleVisibility: .visible
        ) {
            Button("Apple Maps") { openMap(.apple) }
            Button("Google Maps") { openMap(.google) }
            Button("Uber") { openRideshare(.uber) }
            Button("Lyft") { openRideshare(.lyft) }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog(
            "Remove \(slot.name) from your plan?",
            isPresented: $showRemoveConfirm,
            titleVisibility: .visible
        ) {
            Button("Remove", role: .destructive) { onRemove() }
            Button("Cancel", role: .cancel) {}
        }
    }

    // MARK: - Subtitle composition

    private var subtitleLine: String {
        var parts: [String] = []
        let s = slot.subtitle
        if !s.isEmpty { parts.append(s) }
        if let rating = slot.venueRating {
            parts.append(String(format: "★ %.1f", rating))
        }
        return parts.joined(separator: " · ")
    }

    // True when the stop type produces an actionable primary chip
    // (Reservation, Tickets, or "More info"). When false, the slot has no
    // dedicated state-machine action and we fall back to a generic "Info"
    // chip if a website/source URL is available.
    private var hasPrimaryAction: Bool {
        switch slot.stopType {
        case .restaurant:
            // Renders something useful for both reservable and non-reservable.
            return true
        case .ticketed:
            return true
        case .freeRsvp:
            // Only "More info" — present iff there's a URL to open.
            return (slot.sourceUrl ?? slot.ticketUrl)?.isEmpty == false
        }
    }

    /// "Info" chip target — venueWebsite for non-restaurants (where it isn't
    /// already covered by the primary chip), falling back to sourceUrl.
    private var infoUrl: URL? {
        let candidates = [slot.venueWebsite, slot.sourceUrl]
        for raw in candidates {
            if let r = raw, !r.isEmpty, let u = URL(string: r) { return u }
        }
        return nil
    }

    // MARK: - Primary chip / state badge (type + state driven)

    @ViewBuilder
    private var primaryChip: some View {
        switch slot.stopType {
        case .restaurant:
            switch slot.reservationState {
            case .made:
                stateBadge(
                    label: "Booked · \(slot.reservationPartySize ?? 1) \(pluralize(slot.reservationPartySize ?? 1, "person", "people"))",
                    systemIcon: "checkmark.circle.fill",
                    color: pdGreen,
                    enabled: !readOnly
                ) {
                    onOpenSheet(.reservationDetails(slot: slot, dayIdx: dayIdx, isEditing: true))
                }
            case .unavailable, .none:
                // The "unavailable" state is no longer surfaced as a badge — tapping
                // "No reservations available" jumps straight to Bo. Existing rows
                // marked .unavailable from older saves render as if .none so the
                // user can re-engage them.
                if (slot.venueReservable ?? true) {
                    chip(label: "Reservation", systemIcon: "fork.knife") {
                        onOpenSheet(.reservation(slot: slot, dayIdx: dayIdx))
                    }
                } else {
                    // Spec §3.1.1 — venue marked non-reservable.
                    Text("Does not need a reservation")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(pdInk3)
                }
            }
        case .ticketed:
            switch slot.ticketState {
            case .bought:
                // Short label so the badge fits next to "Directions" on one
                // line. Was "Got Tickets · N tickets" — wrapped on narrow
                // cards and pushed Directions off-screen on iPhones. The
                // green check + "N tickets" still communicates the bought state.
                stateBadge(
                    label: "\(slot.ticketCount ?? 1) \(pluralize(slot.ticketCount ?? 1, "ticket", "tickets"))",
                    systemIcon: "checkmark.circle.fill",
                    color: pdGreen,
                    enabled: !readOnly
                ) {
                    onOpenSheet(.ticketDetails(slot: slot, dayIdx: dayIdx, isEditing: true))
                }
            case .unavailable, .none:
                // Same treatment as restaurants: no .unavailable badge — the
                // tap path goes through Bo. Legacy rows fall through to the chip.
                chip(label: "Tickets", systemIcon: "ticket") {
                    onOpenSheet(.tickets(slot: slot, dayIdx: dayIdx))
                }
            }
        case .freeRsvp:
            // No state machine. Opens the source URL in the in-app browser.
            if let urlStr = slot.sourceUrl ?? slot.ticketUrl, let url = URL(string: urlStr) {
                chip(label: "More info", systemIcon: "info.circle") {
                    onOpenSafari(url)
                }
            }
        }
    }

    /// Filled status badge — green for State B (made/bought), amber/orange for State C.
    /// Tappable when not read-only; reopens the corresponding sheet.
    private func stateBadge(
        label: String,
        systemIcon: String,
        color: Color,
        enabled: Bool,
        onTap: @escaping () -> Void
    ) -> some View {
        Button(action: { if enabled { onTap() } }) {
            HStack(spacing: 5) {
                Image(systemName: systemIcon).font(.system(size: 11, weight: .bold))
                Text(label).font(.system(size: 12, weight: .bold))
            }
            .foregroundColor(color)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(color.opacity(0.10))
            .overlay(RoundedRectangle(cornerRadius: 999).stroke(color.opacity(0.45), lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 999))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    private func pluralize(_ n: Int, _ singular: String, _ plural: String) -> String {
        n == 1 ? singular : plural
    }

    private func chip(label: String, systemIcon: String, onTap: @escaping () -> Void) -> some View {
        Button(action: onTap) {
            HStack(spacing: 5) {
                Image(systemName: systemIcon).font(.system(size: 11, weight: .bold))
                Text(label).font(.system(size: 12, weight: .bold))
            }
            .foregroundColor(pdInk2)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .overlay(RoundedRectangle(cornerRadius: 999).stroke(pdLine, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }

    // MARK: - Directions deep-links

    private enum MapApp { case apple, google }
    private enum RideApp { case uber, lyft }

    private func openMap(_ app: MapApp) {
        guard let lat = slot.venueLat, let lng = slot.venueLng else { return }
        let q = slot.name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let url: URL?
        switch app {
        case .apple:
            url = URL(string: "http://maps.apple.com/?daddr=\(lat),\(lng)&q=\(q)")
        case .google:
            // Try the Google Maps app first; the URL falls through to the web app
            // automatically if the user doesn't have it installed.
            url = URL(string: "comgooglemaps://?daddr=\(lat),\(lng)&q=\(q)")
                ?? URL(string: "https://www.google.com/maps/dir/?api=1&destination=\(lat),\(lng)&destination_place_id=\(q)")
        }
        if let u = url { openURL(u) }
    }

    private func openRideshare(_ app: RideApp) {
        guard let lat = slot.venueLat, let lng = slot.venueLng else { return }
        let nick = slot.name.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let url: URL?
        switch app {
        case .uber:
            // Tries Uber app first, falls back to web booking.
            url = URL(string: "uber://?action=setPickup&pickup=my_location&dropoff[latitude]=\(lat)&dropoff[longitude]=\(lng)&dropoff[nickname]=\(nick)")
                ?? URL(string: "https://m.uber.com/ul/?action=setPickup&pickup=my_location&dropoff[latitude]=\(lat)&dropoff[longitude]=\(lng)")
        case .lyft:
            url = URL(string: "lyft://ridetype?id=lyft&destination[latitude]=\(lat)&destination[longitude]=\(lng)")
                ?? URL(string: "https://ride.lyft.com/ridetype?id=lyft&destination[latitude]=\(lat)&destination[longitude]=\(lng)")
        }
        if let u = url { openURL(u) }
    }
}

// MARK: - In-app browser

import SafariServices

/// Wraps SFSafariViewController so we can present URLs in-app as a sheet rather
/// than punting to external Safari (avoids the system tab-switch hop).
struct SafariView: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> SFSafariViewController {
        let cfg = SFSafariViewController.Configuration()
        cfg.entersReaderIfAvailable = false
        let vc = SFSafariViewController(url: url, configuration: cfg)
        vc.preferredControlTintColor = UIColor(red: 0.961, green: 0.42, blue: 0.388, alpha: 1.0)
        return vc
    }
    func updateUIViewController(_ vc: SFSafariViewController, context: Context) {}
}

/// SwiftUI's `.sheet(item:)` wants Identifiable; URL isn't, so wrap.
struct IdentifiableURL: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

// MARK: - Flow layout for chips
//
// SwiftUI's HStack doesn't wrap, and `Layout` is iOS 16+ but available here.
// Falls back gracefully on overflow — chips break to the next line.

@available(iOS 16.0, *)
private struct FlowChips: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var lineWidth: CGFloat = 0
        var totalHeight: CGFloat = 0
        var lineHeight: CGFloat = 0
        for sv in subviews {
            let size = sv.sizeThatFits(.unspecified)
            if lineWidth + size.width > maxWidth, lineWidth > 0 {
                totalHeight += lineHeight + spacing
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
        totalHeight += lineHeight
        return CGSize(width: maxWidth.isFinite ? maxWidth : lineWidth, height: totalHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var lineHeight: CGFloat = 0
        for sv in subviews {
            let size = sv.sizeThatFits(.unspecified)
            if x + size.width > bounds.maxX, x > bounds.minX {
                x = bounds.minX
                y += lineHeight + spacing
                lineHeight = 0
            }
            sv.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}

// MARK: - Transit row

private struct TransitRow: View {
    let mode: String
    let mins: Int
    var body: some View {
        HStack(spacing: 6) {
            Rectangle().fill(pdLine).frame(height: 1)
            Image(systemName: mode == "walk" ? "figure.walk" : "car.fill")
                .font(.system(size: 10))
                .foregroundColor(pdInk3)
            Text("\(mins) MIN \(mode.uppercased())")
                .font(.system(size: 10, weight: .bold))
                .foregroundColor(pdInk3)
                .tracking(0.8)
            Rectangle().fill(pdLine).frame(height: 1)
        }
        .padding(.vertical, 6)
    }
}

// MARK: - Rename sheet

private struct GroupSizeSheet: View {
    let currentSize: Int
    let onSave: (Int) -> Void
    let onCancel: () -> Void

    @State private var size: Int

    init(currentSize: Int, onSave: @escaping (Int) -> Void, onCancel: @escaping () -> Void) {
        self.currentSize = currentSize
        self.onSave = onSave
        self.onCancel = onCancel
        _size = State(initialValue: max(1, currentSize))
    }

    // Enable Save whenever the value is in range. The old `size != currentSize`
    // guard blocked the common first-open case where the default (1) is also
    // the desired value — user couldn't confirm "just me." Saving an unchanged
    // value is harmless (idempotent write), and Cancel is right there for users
    // who genuinely want to back out.
    private var canSave: Bool { size >= 1 && size <= 500 }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("How many people?")
                .font(.system(size: 20, weight: .black))
                .foregroundColor(pdInk)
            Text("Includes you. Update if your group changes.")
                .font(.system(size: 13, weight: .semibold))
                .foregroundColor(pdInk3)

            HStack(spacing: 16) {
                Button {
                    if size > 1 { size -= 1 }
                } label: {
                    Image(systemName: "minus")
                        .font(.system(size: 18, weight: .heavy))
                        .foregroundColor(size > 1 ? pdInk : pdInk4)
                        .frame(width: 48, height: 48)
                        .background(pdShade)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .disabled(size <= 1)

                Text("\(size)")
                    .font(.system(size: 44, weight: .black))
                    .foregroundColor(pdInk)
                    .frame(minWidth: 90)

                Button {
                    if size < 500 { size += 1 }
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 18, weight: .heavy))
                        .foregroundColor(size < 500 ? pdInk : pdInk4)
                        .frame(width: 48, height: 48)
                        .background(pdShade)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .disabled(size >= 500)
            }
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.vertical, 8)

            HStack(spacing: 10) {
                Button {
                    onCancel()
                } label: {
                    Text("Cancel")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(pdInk2)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(pdShade)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
                Button {
                    onSave(size)
                } label: {
                    Text("Save")
                        .font(.system(size: 14, weight: .black))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(canSave ? pdCoral : pdCoral.opacity(0.4))
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
                .disabled(!canSave)
            }
        }
        .padding(20)
        .presentationDetents([.height(320)])
    }
}

private struct RenamePlanSheet: View {
    let currentName: String
    let onSave: (String) -> Void
    let onCancel: () -> Void

    @State private var name: String

    init(currentName: String, onSave: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
        self.currentName = currentName
        self.onSave = onSave
        self.onCancel = onCancel
        _name = State(initialValue: currentName)
    }

    var trimmed: String { name.trimmingCharacters(in: .whitespaces) }
    var canSave: Bool { !trimmed.isEmpty && trimmed != currentName.trimmingCharacters(in: .whitespaces) && trimmed.count <= 60 }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Change plan name")
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(pdInk)
            TextField("Plan name", text: $name)
                .padding(14)
                .background(pdShade)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .onChange(of: name) { newValue in
                    if newValue.count > 60 { name = String(newValue.prefix(60)) }
                }
            HStack {
                Spacer()
                Text("\(name.count)/60")
                    .font(.system(size: 11))
                    .foregroundColor(pdInk4)
            }
            HStack(spacing: 10) {
                Button {
                    onCancel()
                } label: {
                    Text("Cancel")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(pdInk2)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(pdShade)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
                Button {
                    onSave(trimmed)
                } label: {
                    Text("Save")
                        .font(.system(size: 14, weight: .black))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(canSave ? pdCoral : pdCoral.opacity(0.4))
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
                .disabled(!canSave)
            }
        }
        .padding(20)
        .presentationDetents([.fraction(0.42)])
    }
}

// MARK: - Attendee sheet

private struct AttendeeSheet: View {
    let plan: PlanDetailPlan
    let currentUserId: String?
    let isCreator: Bool
    let onChangeRsvp: (String) -> Void
    let onRemoveAttendee: (String) -> Void
    let onInvite: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Who's coming?")
                .font(.system(size: 20, weight: .black))
                .foregroundColor(pdInk)
            ScrollView {
                LazyVStack(spacing: 10) {
                    ForEach(plan.attendees) { a in
                        attendeeRow(a)
                    }
                }
            }
            if isCreator {
                Button {
                    onDismiss()
                    onInvite()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "plus").font(.system(size: 13, weight: .bold))
                        Text("Invite more").font(.system(size: 14, weight: .bold))
                    }
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(pdCoral)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(20)
        .presentationDetents([.medium, .large])
    }

    private func attendeeRow(_ a: PlanDetailAttendee) -> some View {
        HStack(spacing: 12) {
            let color = uiColor(forKotlinARGB: a.avatarColor)
            Text(a.confirmed ? a.initials.prefix(1).uppercased() : "+")
                .font(.system(size: 14, weight: .black))
                .foregroundColor(.white)
                .frame(width: 36, height: 36)
                .background(a.confirmed ? color : pdInk4)
                .clipShape(Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(a.name)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(pdInk)
                Text(a.confirmed ? "Going" : "Pending")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(a.confirmed ? pdGreen : pdInk3)
            }
            Spacer()
            if a.id == currentUserId {
                Menu {
                    Button("Going") { onChangeRsvp("going") }
                    Button("Maybe") { onChangeRsvp("maybe") }
                    Button("Not going") { onChangeRsvp("not_going") }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(pdInk3)
                        .frame(width: 24, height: 24)
                }
            } else if isCreator {
                Button {
                    onRemoveAttendee(a.id)
                } label: {
                    Image(systemName: "minus.circle.fill")
                        .font(.system(size: 16))
                        .foregroundColor(pdInk4)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 6)
    }
}

// MARK: - Stop sheets (Phase 3)
//
// Four bottom sheets driven by per-stop actions:
//   1. ReservationSheet         — restaurant primary action picker
//   2. ReservationDetailsSheet  — party size + time picker (and clear)
//   3. TicketsSheet             — ticketed-event primary action picker
//   4. TicketDetailsSheet       — ticket count (and clear)
//
// All sheets read from a single SafariURL state at the parent view level for
// any in-app browser launches, and all state mutations are dispatched through
// PlanDetailBridge.shared callbacks back to Kotlin.

/// Routes a reservation/ticket sheet open. Identifiable so `.sheet(item:)` works.
enum StopSheet: Identifiable {
    case reservation(slot: PlanDetailSlot, dayIdx: Int)
    case reservationDetails(slot: PlanDetailSlot, dayIdx: Int, isEditing: Bool)
    case tickets(slot: PlanDetailSlot, dayIdx: Int)
    case ticketDetails(slot: PlanDetailSlot, dayIdx: Int, isEditing: Bool)
    case swap(slot: PlanDetailSlot, dayIdx: Int)
    // Shown after the user taps "No tickets available" / "No reservations
    // available" — two CTAs (Replace event / Remove event) so the user
    // decides what to do with the dead slot. Previously these jumped
    // straight to Bo via onSwapForUnavailable; the explicit choice reads
    // better and avoids a chat round-trip when the user just wants to
    // drop the stop.
    case unavailableTicket(slot: PlanDetailSlot, dayIdx: Int)
    case unavailableReservation(slot: PlanDetailSlot, dayIdx: Int)
    var id: String {
        switch self {
        case .reservation(let s, _): return "res-\(s.id)"
        case .reservationDetails(let s, _, _): return "resd-\(s.id)"
        case .tickets(let s, _): return "tix-\(s.id)"
        case .ticketDetails(let s, _, _): return "tixd-\(s.id)"
        case .swap(let s, _): return "swap-\(s.id)"
        case .unavailableTicket(let s, _): return "unavail-tix-\(s.id)"
        case .unavailableReservation(let s, _): return "unavail-res-\(s.id)"
        }
    }
}

private struct ReservationSheet: View {
    let slot: PlanDetailSlot
    let dayIdx: Int
    let onMakeReservation: (URL) -> Void
    let onMarkUnavailable: () -> Void
    let onChooseDetails: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Reservation at \(slot.name)")
                .font(.system(size: 20, weight: .black))
                .foregroundColor(pdInk)
            Text("Make it official, mark it unavailable, or save what you've already booked.")
                .font(.system(size: 13))
                .foregroundColor(pdInk3)
            Spacer().frame(height: 8)

            // Primary CTA
            primaryButton("Make reservation", systemIcon: "fork.knife") {
                let url = slot.openTableUrl
                    ?? slot.venueWebsite.flatMap { URL(string: $0) }
                if let url { onMakeReservation(url) } else { onDismiss() }
            }

            // Secondary CTAs
            secondaryButton("No reservations available", systemIcon: "exclamationmark.triangle") {
                onMarkUnavailable()
            }
            secondaryButton("Reservation made", systemIcon: "checkmark.circle") {
                onChooseDetails()
            }

            Spacer().frame(height: 4)
        }
        .padding(20)
        .presentationDetents([.fraction(0.46)])
        .presentationDragIndicator(.visible)
    }

    private func primaryButton(_ label: String, systemIcon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: systemIcon).font(.system(size: 14, weight: .bold))
                Text(label).font(.system(size: 15, weight: .black))
            }
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(pdCoral)
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }

    private func secondaryButton(_ label: String, systemIcon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: systemIcon).font(.system(size: 13, weight: .bold))
                Text(label).font(.system(size: 14, weight: .bold))
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 11, weight: .bold)).foregroundColor(pdInk4)
            }
            .foregroundColor(pdInk)
            .padding(.horizontal, 14)
            .padding(.vertical, 14)
            .background(pdShade)
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }
}

private struct ReservationDetailsSheet: View {
    let slot: PlanDetailSlot
    let dayIdx: Int
    let goingCount: Int
    let isEditing: Bool   // true when re-opening from State B (shows Clear button)
    let onSave: (_ partySize: Int, _ time: String) -> Void
    let onClear: () -> Void
    let onDismiss: () -> Void

    @State private var partySize: Int
    @State private var pickerDate: Date
    @State private var didInit = false

    init(
        slot: PlanDetailSlot,
        dayIdx: Int,
        goingCount: Int,
        isEditing: Bool,
        onSave: @escaping (_ partySize: Int, _ time: String) -> Void,
        onClear: @escaping () -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.slot = slot
        self.dayIdx = dayIdx
        self.goingCount = goingCount
        self.isEditing = isEditing
        self.onSave = onSave
        self.onClear = onClear
        self.onDismiss = onDismiss
        // Default party size = current saved count, else number of going attendees, min 1.
        let defaultPartySize = slot.reservationPartySize ?? max(goingCount, 1)
        _partySize = State(initialValue: min(max(defaultPartySize, 1), 20))
        // Default time = saved reservation time, else slot's scheduled time.
        let defaultTimeStr = slot.reservationTime?.isEmpty == false ? slot.reservationTime! : slot.time
        _pickerDate = State(initialValue: parseTime(defaultTimeStr) ?? Date())
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Reservation details")
                .font(.system(size: 20, weight: .black))
                .foregroundColor(pdInk)
                .padding(.bottom, 4)

            // Party size stepper
            VStack(alignment: .leading, spacing: 6) {
                Text("Number of people")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(pdInk2)
                HStack(spacing: 14) {
                    stepperButton(systemIcon: "minus", enabled: partySize > 1) {
                        if partySize > 1 { partySize -= 1 }
                    }
                    Text("\(partySize)")
                        .font(.system(size: 22, weight: .black))
                        .foregroundColor(pdInk)
                        .frame(minWidth: 44)
                    stepperButton(systemIcon: "plus", enabled: partySize < 20) {
                        if partySize < 20 { partySize += 1 }
                    }
                }
            }

            // Time picker
            VStack(alignment: .leading, spacing: 6) {
                Text("Reservation time")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(pdInk2)
                DatePicker("", selection: $pickerDate, displayedComponents: .hourAndMinute)
                    .datePickerStyle(.wheel)
                    .labelsHidden()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .frame(height: 100)
                    .clipped()
            }

            HStack(spacing: 10) {
                Button {
                    onSave(partySize, formatTime(pickerDate))
                } label: {
                    Text("Save")
                        .font(.system(size: 15, weight: .black))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(pdCoral)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
            }

            if isEditing {
                Button(role: .destructive) {
                    onClear()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "trash").font(.system(size: 12, weight: .bold))
                        Text("Clear reservation").font(.system(size: 13, weight: .bold))
                    }
                    .foregroundColor(pdCoral)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(20)
        .padding(.bottom, 8)
        .presentationDetents([.fraction(isEditing ? 0.62 : 0.55)])
        .presentationDragIndicator(.visible)
    }

    private func stepperButton(systemIcon: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemIcon)
                .font(.system(size: 14, weight: .bold))
                .foregroundColor(enabled ? pdInk : pdInk4)
                .frame(width: 36, height: 36)
                .background(pdShade)
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

private struct TicketsSheet: View {
    let slot: PlanDetailSlot
    let dayIdx: Int
    let onGetTickets: (URL) -> Void
    let onMarkUnavailable: () -> Void
    let onChooseDetails: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Tickets for \(slot.name)")
                .font(.system(size: 20, weight: .black))
                .foregroundColor(pdInk)
            Text("Get them now, mark sold out, or save the ones you've already bought.")
                .font(.system(size: 13))
                .foregroundColor(pdInk3)
            Spacer().frame(height: 8)

            primaryButton("Get tickets", systemIcon: "ticket") {
                if let urlStr = slot.ticketUrl, let url = URL(string: urlStr) { onGetTickets(url) }
                else { onDismiss() }
            }
            secondaryButton("No tickets available", systemIcon: "exclamationmark.triangle") {
                onMarkUnavailable()
            }
            secondaryButton("Tickets already bought", systemIcon: "checkmark.circle") {
                onChooseDetails()
            }
            Spacer().frame(height: 4)
        }
        .padding(20)
        .presentationDetents([.fraction(0.46)])
        .presentationDragIndicator(.visible)
    }

    private func primaryButton(_ label: String, systemIcon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: systemIcon).font(.system(size: 14, weight: .bold))
                Text(label).font(.system(size: 15, weight: .black))
            }
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(pdCoral)
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }
    private func secondaryButton(_ label: String, systemIcon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: systemIcon).font(.system(size: 13, weight: .bold))
                Text(label).font(.system(size: 14, weight: .bold))
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 11, weight: .bold)).foregroundColor(pdInk4)
            }
            .foregroundColor(pdInk)
            .padding(.horizontal, 14)
            .padding(.vertical, 14)
            .background(pdShade)
            .clipShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
    }
}

/// Two-CTA chooser shown after the user marks a slot as having no
/// tickets / reservations available. Pure router — Replace fires the
/// existing `onReplaceSlot` Bo flow, Remove fires `onRemoveSlot`. No new
/// bridge methods needed. Title/subtitle are passed in so the same view
/// serves both the ticket and reservation flows.
private struct UnavailableActionSheet: View {
    let title: String
    let subtitle: String
    let onReplace: () -> Void
    let onRemove: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.system(size: 20, weight: .black))
                .foregroundColor(pdInk)
            Text(subtitle)
                .font(.system(size: 13))
                .foregroundColor(pdInk3)
            Spacer().frame(height: 8)

            Button(action: onReplace) {
                HStack(spacing: 8) {
                    Image(systemName: "sparkles").font(.system(size: 14, weight: .bold))
                    Text("Replace event").font(.system(size: 15, weight: .black))
                }
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(pdCoral)
                .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .buttonStyle(.plain)

            Button(action: onRemove) {
                HStack(spacing: 8) {
                    Image(systemName: "trash").font(.system(size: 13, weight: .bold))
                    Text("Remove event").font(.system(size: 14, weight: .bold))
                    Spacer()
                    Image(systemName: "chevron.right").font(.system(size: 11, weight: .bold)).foregroundColor(pdInk4)
                }
                .foregroundColor(pdInk)
                .padding(.horizontal, 14)
                .padding(.vertical, 14)
                .background(pdShade)
                .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .buttonStyle(.plain)

            Spacer().frame(height: 4)
        }
        .padding(20)
        .presentationDetents([.fraction(0.36)])
        .presentationDragIndicator(.visible)
    }
}

private struct TicketDetailsSheet: View {
    let slot: PlanDetailSlot
    let dayIdx: Int
    let goingCount: Int
    let isEditing: Bool
    let onSave: (_ count: Int) -> Void
    let onClear: () -> Void
    let onDismiss: () -> Void

    @State private var ticketCount: Int

    init(
        slot: PlanDetailSlot,
        dayIdx: Int,
        goingCount: Int,
        isEditing: Bool,
        onSave: @escaping (_ count: Int) -> Void,
        onClear: @escaping () -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.slot = slot
        self.dayIdx = dayIdx
        self.goingCount = goingCount
        self.isEditing = isEditing
        self.onSave = onSave
        self.onClear = onClear
        self.onDismiss = onDismiss
        let defaultCount = slot.ticketCount ?? max(goingCount, 1)
        _ticketCount = State(initialValue: min(max(defaultCount, 1), 20))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Ticket details")
                .font(.system(size: 20, weight: .black))
                .foregroundColor(pdInk)

            VStack(alignment: .leading, spacing: 6) {
                Text("Number of tickets")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundColor(pdInk2)
                HStack(spacing: 14) {
                    stepperButton(systemIcon: "minus", enabled: ticketCount > 1) {
                        if ticketCount > 1 { ticketCount -= 1 }
                    }
                    Text("\(ticketCount)")
                        .font(.system(size: 22, weight: .black))
                        .foregroundColor(pdInk)
                        .frame(minWidth: 44)
                    stepperButton(systemIcon: "plus", enabled: ticketCount < 20) {
                        if ticketCount < 20 { ticketCount += 1 }
                    }
                }
            }

            Button {
                onSave(ticketCount)
            } label: {
                Text("Save")
                    .font(.system(size: 15, weight: .black))
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(pdCoral)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .buttonStyle(.plain)

            if isEditing {
                Button(role: .destructive) {
                    onClear()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "trash").font(.system(size: 12, weight: .bold))
                        Text("Clear tickets").font(.system(size: 13, weight: .bold))
                    }
                    .foregroundColor(pdCoral)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(20)
        .padding(.bottom, 8)
        .presentationDetents([.fraction(isEditing ? 0.40 : 0.34)])
        .presentationDragIndicator(.visible)
    }

    private func stepperButton(systemIcon: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemIcon)
                .font(.system(size: 14, weight: .bold))
                .foregroundColor(enabled ? pdInk : pdInk4)
                .frame(width: 36, height: 36)
                .background(pdShade)
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

// MARK: - Swap Sheet (Phase 5)

private struct SwapSheet: View {
    let slot: PlanDetailSlot
    let dayIdx: Int
    let alternatives: [SwapAlternative]
    let isLoading: Bool
    let onSwapIn: (SwapAlternative) -> Void
    let onKeepCurrent: () -> Void
    let onSeeMoreOptions: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Try one of these instead")
                .font(.system(size: 20, weight: .black))
                .foregroundColor(pdInk)
            Text("Bo found these near \(slot.name).")
                .font(.system(size: 12))
                .foregroundColor(pdInk3)
            Spacer().frame(height: 4)

            if isLoading {
                ProgressView()
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 30)
            } else if alternatives.isEmpty {
                Text("Nothing nearby fit. Try \"See more options\" to ask Bo directly.")
                    .font(.system(size: 13))
                    .foregroundColor(pdInk3)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 30)
            } else {
                ForEach(alternatives) { alt in
                    altCard(alt)
                }
            }

            Spacer().frame(height: 4)

            Button(action: onKeepCurrent) {
                Text("Keep current")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(pdInk2)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(pdShade)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .buttonStyle(.plain)

            Button(action: onSeeMoreOptions) {
                HStack(spacing: 6) {
                    Image(systemName: "sparkles").font(.system(size: 13, weight: .bold))
                    Text("See more options").font(.system(size: 14, weight: .bold))
                }
                .foregroundColor(pdCoral)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
            }
            .buttonStyle(.plain)
        }
        .padding(20)
        .padding(.bottom, 8)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }

    private func altCard(_ alt: SwapAlternative) -> some View {
        HStack(alignment: .top, spacing: 12) {
            // Thumbnail
            ZStack {
                RoundedRectangle(cornerRadius: 12).fill(pdShade)
                if let urlStr = alt.imageUrl, let url = URL(string: urlStr) {
                    AsyncImage(url: url) { img in
                        img.resizable().scaledToFill()
                    } placeholder: {
                        Image(systemName: "mappin.and.ellipse")
                            .font(.system(size: 16))
                            .foregroundColor(pdInk4)
                    }
                    .frame(width: 64, height: 64)
                    .clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                } else {
                    Image(systemName: alt.kind == .event ? "ticket" : "fork.knife")
                        .font(.system(size: 16))
                        .foregroundColor(pdInk4)
                }
            }
            .frame(width: 64, height: 64)

            // Body
            VStack(alignment: .leading, spacing: 3) {
                Text(alt.name)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundColor(pdInk)
                    .lineLimit(2)
                let sub = subtitleFor(alt)
                if !sub.isEmpty {
                    Text(sub)
                        .font(.system(size: 12))
                        .foregroundColor(pdInk3)
                        .lineLimit(1)
                }
                if let time = alt.eventTime, !time.isEmpty {
                    Text(time)
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(pdCoral)
                        .padding(.top, 1)
                }
                Spacer().frame(height: 4)
                Button {
                    onSwapIn(alt)
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: "arrow.triangle.2.circlepath")
                            .font(.system(size: 11, weight: .bold))
                        Text("Swap in")
                            .font(.system(size: 12, weight: .black))
                    }
                    .foregroundColor(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(pdCoral)
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(12)
        .background(Color.white)
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(pdLine, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private func subtitleFor(_ alt: SwapAlternative) -> String {
        var parts: [String] = []
        if let cat = alt.category, !cat.isEmpty {
            parts.append(cat.prefix(1).uppercased() + cat.dropFirst())
        }
        if let neighborhood = alt.neighborhood, !neighborhood.isEmpty {
            parts.append(neighborhood)
        }
        if let rating = alt.rating {
            parts.append(String(format: "★ %.1f", rating))
        }
        return parts.joined(separator: " · ")
    }
}

// MARK: - Time helpers

/// Parse a "7:00 PM" string into a Date (today, time-only).
private func parseTime(_ s: String) -> Date? {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "h:mm a"
    return f.date(from: s) ?? {
        f.dateFormat = "h:mma"
        return f.date(from: s.replacingOccurrences(of: " ", with: ""))
    }()
}

/// Format a Date back to "7:00 PM".
private func formatTime(_ d: Date) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "h:mm a"
    return f.string(from: d)
}

// MARK: - Helpers

/// Pull a date numeral out of a day label (e.g. "Saturday, May 3" → "3").
/// Falls back to nil if the label has no numeric component.
private func extractDayNumeral(_ day: PlanDetailDay) -> String? {
    let label = day.label
    if let match = label.range(of: #"\b\d{1,2}\b"#, options: .regularExpression) {
        return String(label[match])
    }
    return nil
}

private extension Int {
    /// Lower-bound clamp.
    func max(_ floor: Int) -> Int { Swift.max(self, floor) }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}

private func uiColor(forKotlinARGB argb: Int64) -> Color {
    let value = UInt64(bitPattern: Int64(argb))
    let a = Double((value >> 24) & 0xFF) / 255.0
    let r = Double((value >> 16) & 0xFF) / 255.0
    let g = Double((value >> 8) & 0xFF) / 255.0
    let b = Double(value & 0xFF) / 255.0
    return Color(.sRGB, red: r, green: g, blue: b, opacity: a == 0 ? 1.0 : a)
}

// Rounded corners on selected corners only.
private struct RoundedCorner: Shape {
    var radius: CGFloat = .infinity
    var corners: UIRectCorner = .allCorners

    func path(in rect: CGRect) -> Path {
        let path = UIBezierPath(roundedRect: rect, byRoundingCorners: corners, cornerRadii: CGSize(width: radius, height: radius))
        return Path(path.cgPath)
    }
}

// MARK: - Map Hero (route preview for the currently-selected plan day)

private struct MapLeg {
    let midpoint: CLLocationCoordinate2D
    let minutes: Int
    let mode: LegMode
    let meters: Double
}

struct RouteTotals: Equatable {
    let miles: Double
    let minutes: Int
    let dominantMode: LegMode
}

enum LegMode {
    case walking
    case driving
    var label: String {
        switch self {
        case .walking: return "WALK"
        case .driving: return "DRIVE"
        }
    }
    var lowerLabel: String {
        switch self {
        case .walking: return "walk"
        case .driving: return "drive"
        }
    }
}

// Switch from walking to driving once the straight-line distance between
// two stops exceeds this threshold. ~1 mile of walking is roughly the upper
// edge of "I can walk that"; beyond it driving is the realistic mode and
// quoting a 600-minute walk reads as a bug.
private let kWalkDriveThresholdMeters: Double = 1_609.0  // 1.0 mi

private struct MapHeroView: View {
    let slots: [PlanDetailSlot]
    var onTotalsChanged: (RouteTotals?) -> Void = { _ in }

    @State private var legs: [MapLeg] = []
    @State private var cameraPosition: MapCameraPosition = .automatic

    private var coords: [CLLocationCoordinate2D] {
        slots.compactMap { s in
            guard let lat = s.venueLat, let lng = s.venueLng else { return nil }
            return CLLocationCoordinate2D(latitude: lat, longitude: lng)
        }
    }

    private var region: MKCoordinateRegion {
        let lats = coords.map(\.latitude)
        let lngs = coords.map(\.longitude)
        guard let minLat = lats.min(), let maxLat = lats.max(),
              let minLng = lngs.min(), let maxLng = lngs.max() else {
            return MKCoordinateRegion(
                center: CLLocationCoordinate2D(latitude: 42.36, longitude: -71.06),
                span: MKCoordinateSpan(latitudeDelta: 0.05, longitudeDelta: 0.05)
            )
        }
        // ~2.4× lat padding (taller span) so the route still has breathing
        // room when the floating title card covers the bottom ~40% of the
        // hero. Longitude only needs the standard 1.7× padding.
        let span = MKCoordinateSpan(
            latitudeDelta: max((maxLat - minLat) * 2.4, 0.020),
            longitudeDelta: max((maxLng - minLng) * 1.7, 0.018)
        )
        // Bias the center southward so the bounding box falls into the
        // upper portion of the visible map — keeps pins clear of the
        // title card overlapping at the bottom.
        let center = CLLocationCoordinate2D(
            latitude: (minLat + maxLat) / 2 - span.latitudeDelta * 0.18,
            longitude: (minLng + maxLng) / 2
        )
        return MKCoordinateRegion(center: center, span: span)
    }

    // Stable key for the .task — re-fetch when the underlying coords change.
    private var coordsKey: String {
        coords.map { String(format: "%.5f,%.5f", $0.latitude, $0.longitude) }.joined(separator: "|")
    }

    var body: some View {
        Map(position: $cameraPosition, interactionModes: []) {
            // Numbered coral pins — one per stop in the day.
            ForEach(Array(coords.enumerated()), id: \.offset) { idx, coord in
                Annotation("", coordinate: coord, anchor: .center) {
                    NumberedPin(idx: idx + 1)
                }
            }
            // Solid thin route between consecutive stops. The previous
            // dashed thicker stroke read as "approximate / placeholder";
            // a clean 2-pt solid line reads as a real path.
            if coords.count >= 2 {
                MapPolyline(coordinates: coords)
                    .stroke(
                        pdCoral,
                        style: StrokeStyle(lineWidth: 2, lineCap: .round)
                    )
            }
            // Travel-time pill at each leg's midpoint — SF Symbol + minutes.
            // Hide pills for legs shorter than ~80 m: at typical zoom that
            // puts the midpoint right on top of the adjacent numbered pins,
            // which is the collision the user reported. The route line
            // alone is enough signal for very short legs.
            ForEach(Array(legs.enumerated()), id: \.offset) { _, leg in
                if leg.meters >= 80 {
                    Annotation("", coordinate: leg.midpoint, anchor: .center) {
                        HStack(spacing: 4) {
                            Image(systemName: leg.mode == .walking ? "figure.walk" : "car.fill")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundColor(pdInk2)
                            Text("\(leg.minutes) MIN")
                                .font(.system(size: 9, weight: .heavy))
                                .tracking(0.4)
                                .foregroundColor(pdInk2)
                        }
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(.white)
                        .clipShape(Capsule())
                        .overlay(Capsule().stroke(pdLine, lineWidth: 0.5))
                        .shadow(color: .black.opacity(0.18), radius: 2, x: 0, y: 1)
                        // Shift the pill upward in screen space so it sits
                        // above the route line and clear of numbered pins
                        // when the map is zoomed out and stops are close.
                        .offset(y: -16)
                    }
                }
            }
        }
        .mapStyle(.standard(pointsOfInterest: .excludingAll))
        .task(id: coordsKey) {
            await fetchLegs()
        }
        .onAppear {
            cameraPosition = .region(region)
        }
        .onChange(of: coordsKey) { _, _ in
            // Day strip change — re-fit the camera to the new day's stops.
            withAnimation(.easeInOut(duration: 0.4)) {
                cameraPosition = .region(region)
            }
        }
    }

    private func fetchLegs() async {
        guard coords.count >= 2 else {
            legs = []
            onTotalsChanged(nil)
            return
        }
        var newLegs: [MapLeg] = []
        for i in 0..<(coords.count - 1) {
            let a = coords[i]
            let b = coords[i + 1]
            let mid = CLLocationCoordinate2D(
                latitude: (a.latitude + b.latitude) / 2,
                longitude: (a.longitude + b.longitude) / 2
            )
            // Straight-line distance gates the transport choice. Anything
            // beyond ~1 mi gets quoted as a drive; closer than that, walking.
            let straightLineMeters = CLLocation(latitude: a.latitude, longitude: a.longitude)
                .distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude))
            let mode: LegMode = straightLineMeters > kWalkDriveThresholdMeters ? .driving : .walking

            let req = MKDirections.Request()
            req.source = MKMapItem(placemark: MKPlacemark(coordinate: a))
            req.destination = MKMapItem(placemark: MKPlacemark(coordinate: b))
            req.transportType = mode == .driving ? .automobile : .walking
            do {
                let resp = try await MKDirections(request: req).calculate()
                if let route = resp.routes.first {
                    let mins = max(1, Int((route.expectedTravelTime / 60).rounded()))
                    newLegs.append(MapLeg(midpoint: mid, minutes: mins, mode: mode, meters: route.distance))
                    continue
                }
            } catch {
                // MKDirections rate-limited or offline — fall through to
                // a coarse straight-line estimate.
            }
            // Fallback pace: ~80 m/min walking, ~600 m/min driving (~22 mph
            // average for short urban hops including stops/turns).
            let pacePerMin = mode == .driving ? 600.0 : 80.0
            let mins = max(1, Int((straightLineMeters / pacePerMin).rounded()))
            newLegs.append(MapLeg(midpoint: mid, minutes: mins, mode: mode, meters: straightLineMeters))
        }
        let totalMeters = newLegs.reduce(0) { $0 + $1.meters }
        let totalMinutes = newLegs.reduce(0) { $0 + $1.minutes }
        // Dominant mode = the mode accounting for the most travel time. Single-
        // mode plans (all walk or all drive) just use that. Mixed plans pick
        // whichever consumes more minutes — that's the experience that
        // dominates the user's mental model of the day.
        let walkMinutes = newLegs.filter { $0.mode == .walking }.reduce(0) { $0 + $1.minutes }
        let driveMinutes = newLegs.filter { $0.mode == .driving }.reduce(0) { $0 + $1.minutes }
        let dominantMode: LegMode = driveMinutes > walkMinutes ? .driving : .walking
        let totals = RouteTotals(miles: totalMeters / 1609.34, minutes: totalMinutes, dominantMode: dominantMode)
        await MainActor.run {
            legs = newLegs
            onTotalsChanged(totals)
        }
    }
}

private struct NumberedPin: View {
    let idx: Int
    var body: some View {
        ZStack {
            Circle()
                .fill(.white)
                .frame(width: 28, height: 28)
                .shadow(color: .black.opacity(0.22), radius: 2, x: 0, y: 1)
            Circle()
                .stroke(pdCoral, lineWidth: 2)
                .frame(width: 28, height: 28)
            Text("\(idx)")
                .font(.system(size: 13, weight: .heavy))
                .foregroundColor(pdCoral)
        }
    }
}
