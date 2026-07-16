package live.localapp.local

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.domain.model.BoStructuredResponse
import live.localapp.local.domain.model.Event
import live.localapp.local.ui.chat.ChatMessage
import live.localapp.local.ui.chat.ChatUiState
import live.localapp.local.ui.chat.MessageContent
import live.localapp.local.ui.chat.MessageRole
import live.localapp.local.ui.chat.QuickReply
import live.localapp.local.ui.chat.ItineraryDay
import live.localapp.local.ui.chat.ItinerarySlot

/**
 * Bridge between Kotlin ChatViewModel and SwiftUI ChatView.
 *
 * Kotlin pushes state updates → Swift observes.
 * Swift sends actions → Kotlin executes.
 */
/** Bo's web_search tool emits inline citation markup like
 *  `<cite index="2-2">Open Wed-Sun, 11 AM-5 PM</cite>` that the chat bubble
 *  renders as literal text. Strip the tags but keep the cited content. */
private val CITE_OPEN_REGEX = Regex("""<cite\b[^>]*>""", RegexOption.IGNORE_CASE)
private val CITE_CLOSE_REGEX = Regex("""</cite>""", RegexOption.IGNORE_CASE)

/**
 * Drop glyphs that SwiftUI's Nunito font can't render (emoji, misc symbols).
 * Keeps ASCII, Latin, Cyrillic, CJK, arrows, and standard punctuation; strips
 * anything in the Miscellaneous Symbols / Emoji / Dingbat ranges. Also strips
 * Bo's web_search `<cite index="X-Y">…</cite>` markup.
 */
private fun stripUnsupportedGlyphs(text: String): String {
    val deCited = text
        .replace(CITE_OPEN_REGEX, "")
        .replace(CITE_CLOSE_REGEX, "")
    val filtered = deCited.filter { c ->
        val cp = c.code
        cp < 0x2600 || (cp in 0x2800..0xFDFF) || (cp in 0xFE10..0xFE1F) || (cp in 0xFF00..0xFFEF)
    }
    // Collapse whitespace that was left by stripped glyphs.
    return filtered.replace(Regex("[ \\t]{2,}"), " ")
}

object ChatBridge {
    // ── State pushed from Kotlin to Swift ──
    // Keep the latest snapshot so we can replay it when SwiftUI attaches its
    // observer after the VM has already fired (e.g. welcome greeting emitted
    // before ChatView.onAppear wires up onStateUpdate).
    private var lastSnapshot: ChatStateSnapshot? = null
    private var _onStateUpdate: ((ChatStateSnapshot) -> Unit)? = null
    var onStateUpdate: ((ChatStateSnapshot) -> Unit)?
        get() = _onStateUpdate
        set(value) {
            _onStateUpdate = value
            if (value != null) {
                lastSnapshot?.let(value)
            }
        }

    // ── Actions from Swift to Kotlin ──
    var sendMessage: ((String) -> Unit)? = null
    var resetChat: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    /** Intent sheet on open — Swift sends the picked goal's wire token
     *  ("find_events" / "create_plan" / "browsing"). The VM records it and
     *  steers Bo's framing + planning behavior for the rest of the session. */
    var setBoIntent: ((String) -> Unit)? = null
    /** + on a result card — saves [eventId] to Saved Events (favorites) if it
     *  isn't already saved. Idempotent; persists to community_interactions. */
    var saveFavorite: ((String) -> Unit)? = null
    // Quick reply: pass both label (display text) and action (command token)
    // so the VM can route specials like "reset" / "save_plan" correctly.
    var onQuickReply: ((String, String) -> Unit)? = null
    // Direct plan approve — triggered by the "Save to My Plans" button. Bypasses
    // the chat-message path so a user bubble isn't added; VM runs the gate
    // check then the save flow.
    var approvePlan: (() -> Unit)? = null
    // Called by Swift after a successful RevenueCat purchase / restore so the
    // Kotlin PremiumGate re-queries subscription status immediately (otherwise
    // hasPaidSubscription stays false until the next app launch, and the save
    // retry would re-trigger the paywall).
    var refreshSubscription: (() -> Unit)? = null
    // Presents the native SwiftUI PaywallView modally without requiring a chat
    // session. Used by the Profile "Upgrade" CTA. The callback reports whether
    // the user successfully subscribed / restored.
    var presentPaywall: ((onPurchased: (Boolean) -> Unit) -> Unit)? = null
    // Fires when PremiumGate blocks a plan save — Swift presents the paywall.
    var onPlanBlocked: (() -> Unit)? = null
    // Clear the plan-saved celebration and reset chat.
    var clearSuccessState: (() -> Unit)? = null
    // Share the plan that triggered the current success celebration — fires
    // the system share sheet. Set by iosMain; Swift calls it from the
    // PlanCelebrationOverlay "Share Plan" button.
    var sharePlan: (() -> Unit)? = null

    // Open the just-saved plan in the native plan-detail sheet. Set by
    // iosMain; Swift calls it from the celebration's "View Plan" link
    // after switching to the Plans tab. Param is the saved plan id.
    var viewSavedPlan: ((String) -> Unit)? = null

    // ── Event/venue taps from Swift ──
    var onEventTap: ((String, String?) -> Unit)? = null  // eventId, imageUrl
    var onVenueTap: ((String) -> Unit)? = null            // venueName

    // ── Suggestion-tray actions from Swift ──
    /** "Find more for this group" — fired by the trailing tile in a tray
     *  carousel. Runs an inline event search (no chat round-trip with Bo)
     *  and appends the new events to the existing structured message. */
    var loadMoreSuggestions: ((messageId: String, dayKey: String?, categoryKey: String?) -> Unit)? = null
    /** Fires after [loadMoreSuggestions] completes — `addedCount` is 0 when
     *  the well is dry. Swift uses this to clear the per-group spinner and
     *  optionally hide the Find-more tile when no more results exist. */
    var onLoadMoreFinished: ((messageId: String, groupKey: String, addedCount: Int) -> Unit)? = null
    /** "Create plan" CTA — replaces the verbose canonical message with a
     *  clean user bubble while the technical event_id list goes to Bo via
     *  conversation history only. */
    var buildPlanFromSelections: ((eventIds: List<String>) -> Unit)? = null

    /** "Save Events" CTA — persists the staged picks to Saved Events and has
     *  Bo acknowledge in-chat (what was saved + where to find it, then a short
     *  "anything else?" follow-up). */
    var saveEventsFromSelections: ((eventIds: List<String>) -> Unit)? = null

    /** Editable location chip — Swift calls this when the user picks a new
     *  scope in the location editor sheet. ChatViewModel geocodes the name
     *  and updates currentLat/Lng/currentLocationName; the next push of the
     *  snapshot will reflect the new value. */
    var setLocationByName: ((String) -> Unit)? = null

    /** Full scope edit — name + radius miles. Set from the new banner
     *  editor sheet that includes a radius slider + map preview. */
    var setSearchScope: ((String, Double) -> Unit)? = null

    /** Same as [setSearchScope] but the caller has already geocoded the
     *  location and provides explicit coordinates. Preferred path: iOS uses
     *  Apple's CLGeocoder which handles arbitrary place names ("Concord, MA",
     *  "South End", "94110"), where the Kotlin-side geocodeCity only knows
     *  a hardcoded list of metros. Pass null lat/lng to fall back to
     *  Kotlin geocoding. */
    var setSearchScopeWithCoords: ((String, Double, Double, Double) -> Unit)? = null

    /** Push a state snapshot to Swift */
    fun pushState(state: ChatUiState) {
        val messages = state.messages.map { msg ->
            val content = msg.content
            val rawText = when (content) {
                is MessageContent.Text -> content.text
                is MessageContent.DayCardRef -> "[plan_card]"
                is MessageContent.MultiDayPlanRef -> "[multi_day_plan]"
                // Suggestions render as a structured tray on iOS via the
                // `structured` field below; the bubble text is left blank.
                // The other four structured shapes are short prose answers
                // that we surface as a plain text bubble so SwiftUI can
                // render them with no extra UI work.
                is MessageContent.Structured -> when (val r = content.response) {
                    is BoStructuredResponse.Suggestions -> ""
                    is BoStructuredResponse.EventBrief -> r.answerLine
                    is BoStructuredResponse.VenueBrief -> r.answerLine ?: "Upcoming at ${r.venueName}"
                    is BoStructuredResponse.QuickFact -> r.text
                    is BoStructuredResponse.ScopeRedirect -> r.explanation
                }
            }
            val structuredSnap = (content as? MessageContent.Structured)?.let { sc ->
                (sc.response as? BoStructuredResponse.Suggestions)?.let { s ->
                    s.toSnapshot(sc.hydratedEvents)
                }
            }
            ChatMessageSnapshot(
                id = msg.id,
                isUser = msg.role == MessageRole.USER,
                // SwiftUI's chat renderer uses Nunito, which doesn't ship emoji
                // glyphs — anything outside the common Unicode planes shows as
                // tofu. Strip before crossing the bridge. User text is passed
                // through untouched.
                text = if (msg.role == MessageRole.USER) rawText else stripUnsupportedGlyphs(rawText),
                isPlanCard = content is MessageContent.DayCardRef || content is MessageContent.MultiDayPlanRef,
                planSnapshot = when (content) {
                    is MessageContent.DayCardRef -> content.snapshot?.map { it.toSnapshot() }
                    is MessageContent.MultiDayPlanRef -> content.snapshot?.map { it.toSnapshot() }
                    else -> null
                },
                dayIndex = if (content is MessageContent.DayCardRef) content.dayIndex else 0,
                isSuperseded = msg.id in supersededIds,
                structured = structuredSnap,
                preRevealed = live.localapp.local.ui.chat.TypewriterCompletionStore.isCompleted(msg.id),
            )
        }

        val snapshot = ChatStateSnapshot(
            messages = messages,
            quickReplies = state.quickReplies.map { QuickReplySnapshot(it.label, it.isGreen, it.action) },
            streamingText = stripUnsupportedGlyphs(state.streamingText),
            isTyping = state.isTyping,
            isSearching = state.isSearching,
            searchingNarration = state.searchingNarration.map { stripUnsupportedGlyphs(it) },
            localIdioms = state.localIdioms.map { stripUnsupportedGlyphs(it) },
            isBusy = state.isBusy,
            inputEnabled = state.inputEnabled,
            showPlanSuccess = state.showPlanSuccess,
            days = state.days.map { it.toSnapshot() },
            weather = state.weather.mapValues { (_, w) -> WeatherSnapshot(w.tempHigh, w.description) },
            planAttendeeCount = state.planAttendeeCount,
            lastSavedPlanId = state.lastSavedPlanId.orEmpty(),
            currentLocationName = state.currentLocationName,
            currentLat = state.currentLat ?: 0.0,
            currentLng = state.currentLng ?: 0.0,
            hasLocationCoords = state.currentLat != null && state.currentLng != null,
            searchRadiusMiles = state.searchRadiusMiles,
            scopeEditorPlaceholder = live.localapp.local.ui.home.scopeEditorPlaceholder(state.currentLat, state.currentLng),
            boIntent = state.boIntent?.wireName ?: "",
            favoriteEventIds = state.favoriteEventIds.toList(),
        )

        lastSnapshot = snapshot
        _onStateUpdate?.invoke(snapshot)
    }

    var supersededIds: Set<String> = emptySet()
}

// ── Snapshot data classes (simple, no Kotlin-specific types) ──

data class ChatStateSnapshot(
    val messages: List<ChatMessageSnapshot>,
    val quickReplies: List<QuickReplySnapshot>,
    val streamingText: String,
    val isTyping: Boolean,
    val isSearching: Boolean,
    /** Ordered, human-readable steps for the searching indicator, derived
     *  from Bo's tool args (e.g. "Searching within 20 miles of Waltham").
     *  Empty = fall back to the rotating gerunds. */
    val searchingNarration: List<String> = emptyList(),
    /** Hyperlocal "searching" idioms for the user's set location, generated by
     *  Claude (e.g. "Taking the Broad Street Line..."). The searching indicator
     *  interleaves one every ~3rd rotation. Empty = generic gerunds only. */
    val localIdioms: List<String> = emptyList(),
    val isBusy: Boolean,
    val inputEnabled: Boolean,
    val showPlanSuccess: Boolean,
    val days: List<DaySnapshot>,
    val weather: Map<Int, WeatherSnapshot> = emptyMap(),
    /** Total people on the plan (creator + invitees). Drives the
     *  Share Plan CTA copy on the post-save celebration overlay —
     *  solo plans say "Share Plan", group plans say "Share with N others". */
    val planAttendeeCount: Int = 1,
    /** ID of the plan that just got saved — used by the celebration's
     *  "View Plan" CTA to deep-link straight to its detail page after
     *  switching tabs. Empty string when nothing has been saved yet. */
    val lastSavedPlanId: String = "",
    /** Canonical search scope — drives the editable location banner in the
     *  chat header. Empty string when the user hasn't set one yet. */
    val currentLocationName: String = "",
    /** Geocoded coordinates for [currentLocationName], used by the scope
     *  editor map to center on the named place. When [hasLocationCoords] is
     *  false these are placeholder zeros — Swift should fall back to a
     *  client-side geocode or a default region. */
    val currentLat: Double = 0.0,
    val currentLng: Double = 0.0,
    val hasLocationCoords: Boolean = false,
    /** Search radius in miles — drives the radius slider + the bbox query
     *  in Bo's search tools. */
    val searchRadiusMiles: Double = 20.0,
    /** Placeholder text for the scope-editor's city field. Region-aware:
     *  MA users see MA towns, PA users see PA towns, etc. Computed in
     *  Kotlin so the city list lives in one place. */
    val scopeEditorPlaceholder: String = "e.g. a city or neighborhood",
    /** The goal the user picked from the intent sheet ("find_events" /
     *  "create_plan" / "browsing"), or "" when not yet chosen. Empty = Swift
     *  presents the intent sheet on open. */
    val boIntent: String = "",
    /** Event ids the user has saved (favorited). Drives the filled ❤️ on result
     *  cards and the "build a plan" CTA selection. */
    val favoriteEventIds: List<String> = emptyList(),
)

data class WeatherSnapshot(
    val tempHigh: Int,
    val description: String,
)

data class ChatMessageSnapshot(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val isPlanCard: Boolean,
    val planSnapshot: List<DaySnapshot>?,
    val dayIndex: Int,
    val isSuperseded: Boolean,
    /** Non-null for `present_suggestions` results — Swift renders this as a
     *  structured event tray instead of a text bubble. The four other
     *  structured tools (event/venue brief, quick fact, scope redirect)
     *  collapse to plain text in [text] so no extra UI is needed. */
    val structured: SuggestionsSnapshot? = null,
    /** True when this bubble was already revealed live via streamingText, so
     *  Swift should render the full text statically instead of replaying the
     *  typewriter (which reads as: full message flashes, vanishes, re-streams).
     *  Sourced from the shared TypewriterCompletionStore. */
    val preRevealed: Boolean = false,
)

// ── Structured response snapshots (Phase 6 / Bo present_suggestions) ──

data class SuggestionsSnapshot(
    val headline: String,
    val groups: List<SuggestionGroupSnapshot>,
    val footer: SuggestionFooterSnapshot?,
)

data class SuggestionGroupSnapshot(
    val label: String,
    val events: List<SuggestionEventSnapshot>,
    /** ISO date string (YYYY-MM-DD) when this group represents a day; used as
     *  the bucket key for "Find more" inline loads. Null for category/none groupings. */
    val dayKey: String? = null,
    /** Free-text key when this group represents a category (whatever Bo set
     *  as the group label). Null for day groupings. */
    val categoryKey: String? = null,
)

data class SuggestionEventSnapshot(
    val eventId: String,
    val title: String,
    val venueName: String,
    val timeLabel: String,
    val dateDisplay: String,
    /** Epoch millis of the event's start, for chronological sorting of the
     *  draft picks. Undated/unparseable events get Long.MAX_VALUE so they
     *  sort to the end. */
    val sortEpochMillis: Long = Long.MAX_VALUE,
    /** Day-only label ("Friday, Jul 3") with NO time — for grouping draft
     *  picks into day tabs (dateDisplay includes the time, so same-day events
     *  would otherwise split into multiple tabs). */
    val dayLabel: String = "",
    val priceLabel: String,
    val imageUrl: String?,
    val rationale: String?,
    /** Distance from the user's current location in miles. Null when the
     *  user's coords aren't available or the event has no venue coords. */
    val distanceMiles: Double = -1.0,
    /** Venue coordinates for the results map pin. [hasCoords] is false when the
     *  event has no usable venue lat/lng (lat/lng are placeholder 0.0 then), in
     *  which case the card still shows in the carousel but drops no pin. */
    val venueLat: Double = 0.0,
    val venueLng: Double = 0.0,
    val hasCoords: Boolean = false,
)

data class SuggestionFooterSnapshot(
    val text: String,
    val actions: List<SuggestionActionSnapshot>,
)

/** Footer chip — `intent` is the canonical message Swift sends back to
 *  Bo via [ChatBridge.sendMessage] when the chip is tapped. Keeps the
 *  action-to-intent mapping in one place (Kotlin) and avoids duplicating
 *  it on the SwiftUI side. */
data class SuggestionActionSnapshot(
    val label: String,
    val intent: String,
)

data class QuickReplySnapshot(
    val label: String,
    val isGreen: Boolean,
    val action: String,
)

data class DaySnapshot(
    val label: String,
    val shortName: String,
    val slots: List<SlotSnapshot>,
    val isApproved: Boolean,
)

data class SlotSnapshot(
    val id: String,
    val time: String,
    val name: String,
    val detail: String,
    val price: String,
    val isFree: Boolean,
    val imageUrl: String?,
    val eventId: String?,
    val venueRating: Double?,
)

private fun ItineraryDay.toSnapshot() = DaySnapshot(
    label = label,
    shortName = shortName,
    slots = slots.map { it.toSnapshot() },
    isApproved = isApproved,
)

private fun BoStructuredResponse.Suggestions.toSnapshot(
    hydrated: Map<String, Event>,
): SuggestionsSnapshot {
    // For day-grouped suggestions, IGNORE Bo's group labels — Bo has been
    // seen to bucket Saturday events under a "Sunday, May 10" label when
    // the user asked about Sunday and the search ±1-day-broadened. Re-key
    // by each event's actual local start_at, then sort days chronologically.
    // For category / none grouping, Bo's labels are the source of truth.
    val groupSnaps = when (groupBy) {
        BoStructuredResponse.Suggestions.GroupBy.DAY -> regroupByDay(this, hydrated)
        BoStructuredResponse.Suggestions.GroupBy.CATEGORY -> regroupByCategory(this, hydrated)
        BoStructuredResponse.Suggestions.GroupBy.NONE -> groups.map { g ->
            val eventSnaps = g.events.mapNotNull { ref -> ref.toEventSnapshot(hydrated) }
            SuggestionGroupSnapshot(label = g.label, events = eventSnaps)
        }
    }
    return SuggestionsSnapshot(
        headline = headline,
        groups = groupSnaps,
        footer = footer?.let { f ->
            SuggestionFooterSnapshot(
                text = f.text.orEmpty(),
                actions = f.actions.map { a ->
                    SuggestionActionSnapshot(label = a.label(), intent = a.intent())
                },
            )
        },
    )
}

private fun BoStructuredResponse.Suggestions.EventRef.toEventSnapshot(
    hydrated: Map<String, Event>,
): SuggestionEventSnapshot? {
    val ev = hydrated[eventId] ?: return null
    return SuggestionEventSnapshot(
        eventId = eventId,
        // Defensive cleanup at the bridge boundary — DTO-level decode should
        // have already handled this, but events created before the decode
        // path was added, or saved from web_search via a different route,
        // can still arrive with raw entities/tags in their title. Idempotent
        // so re-cleaning a clean title is a no-op.
        title = ev.title.decodeHtmlEntities(),
        venueName = ev.venueName.orEmpty().decodeHtmlEntities(),
        timeLabel = eventTimeLabel(ev),
        dateDisplay = ev.dateDisplay,
        sortEpochMillis = ev.startAt?.let {
            try { Instant.parse(it).toEpochMilliseconds() } catch (_: Throwable) { null }
        } ?: Long.MAX_VALUE,
        dayLabel = computeDayKey(ev, TimeZone.currentSystemDefault(), "__tbd").third,
        priceLabel = if (ev.isFree) "Free" else ev.priceDisplay.orEmpty(),
        imageUrl = ev.imageUrl,
        rationale = rationale,
        distanceMiles = ev.distanceMiles ?: -1.0,
        // Venue coords for the results map pin (fall back to the event's own
        // coords). hasCoords gates whether this event drops a pin.
        venueLat = ev.venueLat ?: ev.eventLat ?: 0.0,
        venueLng = ev.venueLng ?: ev.eventLng ?: 0.0,
        hasCoords = (ev.venueLat ?: ev.eventLat) != null && (ev.venueLng ?: ev.eventLng) != null,
    )
}

/** Just the time portion ("8pm", "10:30am", or "TBD") in the user's local tz.
 *  The card row used to render dateDisplay's "$dayLabel, $time" combined,
 *  but the card now shows only "time · venue" — the day is already conveyed
 *  by the surrounding group header. */
private fun eventTimeLabel(ev: Event): String {
    val s = ev.startAt ?: return ""
    return try {
        val local = Instant.parse(s).toLocalDateTime(TimeZone.currentSystemDefault())
        val hour = local.hour
        val minute = local.minute
        // Date-only events return empty so the card's metaLine filters
        // them out (no "TBD" filler). The midnight-local guard catches
        // legacy rows ingested with a 00:00 sentinel before time_known
        // was set correctly upstream.
        if (!ev.timeKnown || (hour == 0 && minute == 0)) ""
        else {
            val ampm = if (hour < 12) "am" else "pm"
            val displayHour = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            if (minute == 0) "${displayHour}${ampm}"
            else "${displayHour}:${minute.toString().padStart(2, '0')}${ampm}"
        }
    } catch (_: Throwable) { "" }
}

/**
 * Bucket every event Bo returned by the local-time date of its `start_at`
 * (epoch-day key for chronological sort, "Saturday, May 9" for display).
 * Events with no resolvable date fall into a single trailing "Date TBD" group.
 */
private fun regroupByDay(
    suggestions: BoStructuredResponse.Suggestions,
    hydrated: Map<String, Event>,
): List<SuggestionGroupSnapshot> {
    data class Bucket(val sortKey: Long, val label: String, val events: MutableList<SuggestionEventSnapshot>)
    val tz = TimeZone.currentSystemDefault()
    val buckets = LinkedHashMap<String, Bucket>()
    val tbdKey = "__tbd"

    for (group in suggestions.groups) {
        for (ref in group.events) {
            val snap = ref.toEventSnapshot(hydrated) ?: continue
            val ev = hydrated[ref.eventId] ?: continue
            val (key, sortKey, label) = computeDayKey(ev, tz, tbdKey)
            val bucket = buckets.getOrPut(key) { Bucket(sortKey, label, mutableListOf()) }
            // Avoid duplicating the same event across multiple Bo groups.
            if (bucket.events.none { it.eventId == ref.eventId }) {
                bucket.events += snap
            }
        }
    }
    return buckets.values
        .sortedBy { it.sortKey }
        .map { b ->
            val k = buckets.entries.firstOrNull { it.value === b }?.key
            SuggestionGroupSnapshot(
                label = b.label,
                events = b.events,
                dayKey = if (k == tbdKey) null else k,
            )
        }
}

/**
 * For category-grouped suggestions, IGNORE Bo's group labels — Bo has been
 * seen to bucket Celtics games and stand-up comedy under an "Outdoor events
 * this weekend" label. Re-key by each event's actual `category` /
 * `subcategory` field, label with a friendly name, and sort by a stable
 * priority. Drops events whose category we don't have (rare).
 */
private fun regroupByCategory(
    suggestions: BoStructuredResponse.Suggestions,
    hydrated: Map<String, Event>,
): List<SuggestionGroupSnapshot> {
    data class Bucket(val sortKey: Int, val label: String, val events: MutableList<SuggestionEventSnapshot>)
    val buckets = LinkedHashMap<String, Bucket>()
    val tbdKey = "__other"

    for (group in suggestions.groups) {
        for (ref in group.events) {
            val snap = ref.toEventSnapshot(hydrated) ?: continue
            val ev = hydrated[ref.eventId] ?: continue
            val (key, sortKey, label) = computeCategoryKey(ev, tbdKey)
            val bucket = buckets.getOrPut(key) { Bucket(sortKey, label, mutableListOf()) }
            if (bucket.events.none { it.eventId == ref.eventId }) {
                bucket.events += snap
            }
        }
    }
    return buckets.values
        .sortedBy { it.sortKey }
        .map { b ->
            val k = buckets.entries.firstOrNull { it.value === b }?.key
            SuggestionGroupSnapshot(
                label = b.label,
                events = b.events,
                categoryKey = k?.takeIf { it != tbdKey },
            )
        }
}

private fun computeCategoryKey(
    ev: Event,
    tbdKey: String,
): Triple<String, Int, String> {
    // Single source of truth: the shared bucketer in commonMain
    // (SuggestionBucketing.kt), which mirrors Explore's subcategory→parent
    // taxonomy so iOS tabs match the Explore page AND the Android tray. It
    // returns "__other" for the catch-all; remap to this caller's tbdKey.
    val (key, sort, label) = live.localapp.local.ui.chat.computeEventCategoryKey(ev)
    return if (key == "__other") Triple(tbdKey, sort, label) else Triple(key, sort, label)
}

private fun computeDayKey(
    ev: Event,
    tz: TimeZone,
    tbdKey: String,
): Triple<String, Long, String> {
    val s = ev.startAt ?: return Triple(tbdKey, Long.MAX_VALUE, "Date TBD")
    return try {
        val date = Instant.parse(s).toLocalDateTime(tz).date
        val key = date.toString() // YYYY-MM-DD, stable bucket key
        val sort = date.toEpochDays().toLong()
        val dayNames = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val label = "${dayNames[date.dayOfWeek.ordinal]}, ${monthNames[date.monthNumber - 1]} ${date.dayOfMonth}"
        Triple(key, sort, label)
    } catch (_: Throwable) {
        Triple(tbdKey, Long.MAX_VALUE, "Date TBD")
    }
}

private fun BoStructuredResponse.Suggestions.Action.label(): String = when (this) {
    BoStructuredResponse.Suggestions.Action.SEARCH_WIDER -> "Search wider"
    BoStructuredResponse.Suggestions.Action.DIFFERENT_CATEGORY -> "Different category"
    BoStructuredResponse.Suggestions.Action.LATE_NIGHT -> "Late night"
    BoStructuredResponse.Suggestions.Action.DIFFERENT_DAY -> "Different day"
    BoStructuredResponse.Suggestions.Action.MORE_RESULTS -> "More results"
    BoStructuredResponse.Suggestions.Action.BUILD_FROM_WEB -> "Build a plan"
}

/** Mirrors `footerActionToIntent` in ChatScreen.kt. Kept in sync manually —
 *  if you change one, change both. */
private fun BoStructuredResponse.Suggestions.Action.intent(): String = when (this) {
    BoStructuredResponse.Suggestions.Action.SEARCH_WIDER ->
        "Cast a wider net, Bo"
    BoStructuredResponse.Suggestions.Action.DIFFERENT_CATEGORY ->
        "Try something different"
    BoStructuredResponse.Suggestions.Action.LATE_NIGHT ->
        "What's open late night?"
    BoStructuredResponse.Suggestions.Action.DIFFERENT_DAY ->
        "How about another day?"
    BoStructuredResponse.Suggestions.Action.MORE_RESULTS ->
        "Show me more results, Bo!"
    BoStructuredResponse.Suggestions.Action.BUILD_FROM_WEB ->
        live.localapp.local.ui.chat.BUILD_FROM_WEB_INTENT
}

private fun ItinerarySlot.toSnapshot() = SlotSnapshot(
    id = id,
    time = time,
    // Defensive HTML cleanup — same belt-and-suspenders as the suggestion
    // snapshot. Plan-slot names are usually copied from a search result's
    // title, so anything that arrived with raw entities/tags would propagate
    // into Plan Detail cards otherwise.
    name = name.decodeHtmlEntities(),
    detail = detail.decodeHtmlEntities(),
    price = price,
    isFree = isFree,
    imageUrl = imageUrl,
    eventId = eventId,
    venueRating = venueRating,
)
