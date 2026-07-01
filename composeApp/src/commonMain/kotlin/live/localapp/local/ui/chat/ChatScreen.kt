package live.localapp.local.ui.chat

import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import live.localapp.local.ui.common.CircleShape
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.domain.model.Event
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import live.localapp.local.ui.common.pressable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import local.composeapp.generated.resources.Res
import local.composeapp.generated.resources.bo_tiny
import org.jetbrains.compose.resources.painterResource
import live.localapp.local.platform.MapHeroStop
import live.localapp.local.platform.PlaceSuggestion
import live.localapp.local.platform.PlanMapHero
import live.localapp.local.platform.rememberInAppBrowser
import live.localapp.local.platform.rememberPlacesAutocomplete
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppShapes

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onBack: (() -> Unit)? = null,
    onNavigateToPlans: (() -> Unit)? = null,
    onNavigateHome: (() -> Unit)? = null,
    onLoginRequired: (() -> Unit)? = null,
    onNativeEventClick: ((String, String?) -> Unit)? = null,  // eventId, fallbackImageUrl
    onNativeVenueClick: ((String) -> Unit)? = null,           // venueName
    showUpgradeBanner: Boolean = false,
    onUpgrade: () -> Unit = {},
    // Premium gate: chat itself is free, but opening an event/place detail from
    // Bo's results is gated (paywall gates 1/3). Runs the action only if the user
    // has access, otherwise surfaces the paywall. Defaults to no gating.
    gate: ((() -> Unit) -> Unit) = { it() },
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var selectedEventId by remember { mutableStateOf<String?>(null) }
    var selectedEvent by remember { mutableStateOf<live.localapp.local.domain.model.Event?>(null) }
    var selectedVenueName by remember { mutableStateOf<String?>(null) }
    var selectedVenue by remember { mutableStateOf<live.localapp.local.domain.model.Venue?>(null) }
    // Events the user dismissed (✕) from results — view-only filter across all
    // suggestion trays this session. Reset when the chat resets (state.messages
    // empties on Start over).
    var dismissedEventIds by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(state.messages.isEmpty()) {
        if (state.messages.isEmpty()) dismissedEventIds = emptySet()
    }

    // Inline "Find more" state for SuggestionTray carousels. Keyed by
    // "$messageId|$groupKey". A group is loading from the moment the user taps
    // Find more until the ViewModel fires onSuggestionLoadMoreFinished; it's
    // marked exhausted when that callback reports 0 new events.
    val loadingGroups = remember { mutableStateMapOf<String, Boolean>() }
    val exhaustedGroups = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(Unit) {
        viewModel.onSuggestionLoadMoreFinished = { msgId, groupKey, added ->
            val key = "$msgId|$groupKey"
            loadingGroups[key] = false
            if (added == 0) exhaustedGroups[key] = true
        }
    }

    // ChatGPT-style scroll anchor: every time the user sends, snap the new
    // bubble to the top of the viewport so older history scrolls out of view.
    //
    // History of attempts before the version below:
    //   v1 — `animateScrollToItem(idx)` + trailing `Spacer(fillParentMaxHeight)`.
    //        The spacer was measured lazily and the scroll often ran before
    //        the LazyColumn had enough scroll range to hoist the new bubble
    //        to the top. Bubble landed in the middle, history stayed visible.
    //   v2 — `scrollToItem` + `snapshotFlow { totalItemsCount }`. Compose-iOS
    //        sometimes silently no-ops `scrollToItem` when the target row's
    //        layout offset hasn't been measured yet. snapshotFlow caught the
    //        item-count change but not the layout-info update.
    //
    // v3 (this): use raw `scrollBy` driven off the item's measured offset.
    // We wait until `visibleItemsInfo` actually contains the target row
    // (i.e. it has been measured and positioned), then dispatch the exact
    // scroll delta needed to put its top edge at the viewport's top edge.
    // `scrollBy` is the primitive `scrollToItem` is built on; it can't
    // silently no-op the way the higher-level call does. The LazyColumn
    // wrapper below gives a guaranteed `viewport - 60.dp` of bottom
    // contentPadding so there's always enough scrollable room for the
    // new bubble to reach the top.
    //
    // Re-anchor effect catches the case where Bo's response materializes
    // below the anchor (typing → streaming → final message → plan card)
    // and pushes the user bubble down a few px per frame. We skip the
    // re-anchor if the user is dragging — they may be reading history.
    // Imperative scroll function — called both reactively (LaunchedEffect on
    // new message) AND directly from the send action below. Three earlier
    // versions only used LaunchedEffect and never worked on iOS, so we're
    // belt-and-suspenders: every scroll API is tried, and the call site is
    // wired in two places so neither timing path can drop it.
    suspend fun anchorTo(tag: String, yieldToOverflowBelow: Boolean = false, predicate: (ChatMessage) -> Boolean) {
        val msgs = viewModel.uiState.value.messages
        val targetIdx = msgs.indexOfLast(predicate)
        println("[ChatScroll/$tag] start targetIdx=$targetIdx msgCount=${msgs.size}")
        if (targetIdx < 0) return
        repeat(30) { attempt ->
            delay(if (attempt == 0) 32 else 50)
            if (listState.isScrollInProgress) return@repeat
            val info = listState.layoutInfo
            // If a reply BELOW the anchor target is already overflowing the
            // viewport, stop trying to pin the target to the top. For a tall
            // reply the user wants to follow its bottom (handled by the
            // follow-the-bottom effect), not have their question yanked back
            // up while Bo's answer runs off the screen. Without this, a fast /
            // fully-rendered reply that lands inside this 30-attempt window
            // stays stuck with the question pinned at top and the answer
            // overflowing unread.
            if (yieldToOverflowBelow) {
                val lastVisible = info.visibleItemsInfo.lastOrNull()
                if (lastVisible != null && lastVisible.index > targetIdx &&
                    (lastVisible.offset + lastVisible.size) - info.viewportEndOffset > 0
                ) {
                    println("[ChatScroll/$tag] reply overflow below anchor — yielding to follow")
                    return
                }
            }
            val visible = info.visibleItemsInfo.map { it.index }
            val target = info.visibleItemsInfo.firstOrNull { it.index == targetIdx }
            println("[ChatScroll/$tag] att=$attempt visible=$visible first=${listState.firstVisibleItemIndex}@${listState.firstVisibleItemScrollOffset} target=${target?.offset}")
            // Anchored: target row is the first visible AND at offset 0.
            if (listState.firstVisibleItemIndex == targetIdx && listState.firstVisibleItemScrollOffset == 0) {
                println("[ChatScroll/$tag] anchored, done")
                return
            }
            // Try every API. Whichever one actually moves the list wins; the
            // others become no-ops on the next iteration once the goal is met.
            try {
                if (target != null) {
                    val delta = (target.offset - info.viewportStartOffset).toFloat()
                    if (delta != 0f) {
                        val consumed = listState.scrollBy(delta)
                        println("[ChatScroll/$tag] scrollBy($delta) consumed=$consumed")
                    }
                }
            } catch (e: Throwable) { println("[ChatScroll/$tag] scrollBy err: $e") }
            try {
                listState.scrollToItem(targetIdx, 0)
                println("[ChatScroll/$tag] scrollToItem($targetIdx,0) → first=${listState.firstVisibleItemIndex}@${listState.firstVisibleItemScrollOffset}")
            } catch (e: Throwable) { println("[ChatScroll/$tag] scrollToItem err: $e") }
            if (attempt == 5 || attempt == 15) {
                try {
                    listState.animateScrollToItem(targetIdx, 0)
                    println("[ChatScroll/$tag] animateScrollToItem fired")
                } catch (e: Throwable) { println("[ChatScroll/$tag] animate err: $e") }
            }
        }
        println("[ChatScroll/$tag] exhausted 30 attempts, first=${listState.firstVisibleItemIndex}@${listState.firstVisibleItemScrollOffset}")
    }

    suspend fun anchorLatestUserMessage(tag: String) =
        anchorTo(tag, yieldToOverflowBelow = true) { it.role == MessageRole.USER }

    val lastUserMessageId = state.messages.lastOrNull { it.role == MessageRole.USER }?.id
    LaunchedEffect(lastUserMessageId) {
        if (lastUserMessageId != null) anchorLatestUserMessage("reactive")
    }

    // Presentation cards (plan cards AND structured event-presentation cards
    // like "This week in and around Perkasie") aren't user messages, so the
    // user-message anchor above never moves them. A freshly built (or updated)
    // card must snap to the TOP of the viewport — otherwise a tall card lands
    // wherever the prior scroll left it: scrolled off the top with dead space
    // beneath, OR (for a second card sent after history has built up) peeking
    // in at the very bottom edge, unreadable. Anchor on the newest card's id
    // so this fires again whenever a new card is appended.
    val isPresentationCard: (ChatMessage) -> Boolean = {
        it.content is MessageContent.MultiDayPlanRef ||
            it.content is MessageContent.DayCardRef ||
            it.content is MessageContent.Structured
    }
    val lastCardId = state.messages.lastOrNull(isPresentationCard)?.id
    LaunchedEffect(lastCardId) {
        if (lastCardId != null) anchorTo("card", predicate = isPresentationCard)
    }

    // Auto-follow a growing/revealing message: as Bo's reply types out (or a
    // tall block streams in), its bottom edge bleeds past the viewport. While
    // the last item is the one visible at the bottom, nudge the scroll so the
    // newest text stays on screen. The `lastIndex == totalItemsCount - 1`
    // guard means we ONLY follow when the user is already pinned to the
    // bottom — the instant they scroll up to read history the last item
    // leaves the bottom of the viewport and following stops on its own, so we
    // never yank them back down. Following resumes when they return to the
    // bottom. scrollBy is non-animated, so it can't fight the anchor effects
    // (those run while the reply is still empty/short, when overflow <= 0).
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            if (last == null) {
                null
            } else {
                val overflow = (last.offset + last.size) - info.viewportEndOffset
                Triple(last.index, info.totalItemsCount, overflow)
            }
        }.collect { snapshot ->
            if (snapshot == null) return@collect
            val (lastIndex, totalItems, overflow) = snapshot
            if (lastIndex == totalItems - 1 && overflow > 0 && !listState.isScrollInProgress) {
                listState.scrollBy(overflow.toFloat())
            }
        }
    }

    // Catch-up follow: the snapshotFlow follow above only acts on distinct
    // emissions, and skips any that land while a scroll is in progress (e.g.
    // the user-message anchor settling). When Bo's reply arrives fast or
    // fully-rendered, its emissions get skipped during that window and there
    // are no further emissions once the layout settles — so the reply ends up
    // overflowing with nothing pulling the newest text on screen. This effect
    // re-keys on the reply CONTENT itself (streamed text + the newest text
    // message) and drives a retry loop that pins the bottom of the reply to
    // the viewport bottom. Scoped to streaming + plain AI text replies so it
    // never overrides the top-anchoring of plan / structured event cards.
    //
    // The loop runs a ~3s budget — LONGER than the user-message anchor's
    // ~1.5s loop — because the two run concurrently: when a text reply is
    // sent, the anchor pins the question to the top (correct while the reply
    // is empty/short) and only yields once the reply overflows. An 8-attempt
    // (~0.3s) follow exited while the anchor was still scrolling, so every
    // follow tick hit `isScrollInProgress` and was skipped, leaving the
    // question pinned at top and the answer overflowing unread (the reported
    // iOS bug). We outlast the anchor and keep retrying until the bottom is
    // pinned and a few frames have settled.
    val lastTextMessageId = state.messages.lastOrNull {
        it.role == MessageRole.AI && it.content is MessageContent.Text
    }?.id
    LaunchedEffect(state.streamingText, state.isTyping, lastTextMessageId) {
        val lastMsg = state.messages.lastOrNull()
        val followable = state.streamingText.isNotEmpty() ||
            (lastMsg?.role == MessageRole.AI && lastMsg.content is MessageContent.Text)
        if (!followable) return@LaunchedEffect
        var settledFrames = 0
        repeat(60) { attempt ->
            delay(if (attempt == 0) 16L else 50L)
            // Skip (don't give up) while a scroll/drag is in progress — this is
            // usually the anchor still settling, or the user actively dragging.
            if (listState.isScrollInProgress) return@repeat
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@repeat
            // User scrolled up to read history → the newest item is no longer
            // the last visible one; stop following so we never yank them down.
            if (last.index < info.totalItemsCount - 1) {
                println("[ChatScroll/follow] att=$attempt user scrolled up (lastVisible=${last.index} < ${info.totalItemsCount - 1}) — stop")
                return@LaunchedEffect
            }
            val overflow = (last.offset + last.size) - info.viewportEndOffset
            println("[ChatScroll/follow] att=$attempt lastIdx=${last.index}/${info.totalItemsCount} offset=${last.offset} size=${last.size} vpEnd=${info.viewportEndOffset} overflow=$overflow first=${listState.firstVisibleItemIndex}@${listState.firstVisibleItemScrollOffset}")
            if (overflow > 1f) {
                try {
                    val consumed = listState.scrollBy(overflow.toFloat())
                    println("[ChatScroll/follow] scrollBy($overflow) consumed=$consumed")
                } catch (e: Throwable) { println("[ChatScroll/follow] scrollBy err: $e") }
                settledFrames = 0
            } else {
                // Bottom is pinned. Require a few stable frames before exiting,
                // in case more text is still streaming in.
                settledFrames++
                if (settledFrames >= 3 && state.streamingText.isEmpty()) {
                    println("[ChatScroll/follow] att=$attempt bottom pinned + settled — done")
                    return@LaunchedEffect
                }
            }
        }
        println("[ChatScroll/follow] exhausted budget, first=${listState.firstVisibleItemIndex}@${listState.firstVisibleItemScrollOffset}")
    }

    // Navigate to Plans when plan is approved
    LaunchedEffect(Unit) {
        viewModel.navigateToPlans.collect {
            onNavigateToPlans?.invoke()
        }
    }

    // Load event when slot is tapped
    LaunchedEffect(selectedEventId) {
        val id = selectedEventId ?: return@LaunchedEffect
        // Gate 1 (from Bo results): non-premium users can't open an event detail.
        var allowed = false
        gate { allowed = true }
        if (!allowed) { selectedEventId = null; return@LaunchedEffect }
        val event = viewModel.getEventById(id) ?: return@LaunchedEffect
        // If the DB event has no image, fall back to the slot's image (often a venue photo)
        selectedEvent = if (event.imageUrl == null) {
            val slotImage = state.days.flatMap { it.slots }.firstOrNull { it.eventId == id }?.imageUrl
            if (slotImage != null) event.copy(imageUrl = slotImage) else event
        } else event
    }

    // Load venue when venue slot is tapped
    LaunchedEffect(selectedVenueName) {
        val name = selectedVenueName ?: return@LaunchedEffect
        // Gate 3 (from Bo results): non-premium users can't open a place detail.
        var allowed = false
        gate { allowed = true }
        if (!allowed) { selectedVenueName = null; return@LaunchedEffect }
        selectedVenue = viewModel.getVenueByName(name)
    }

    // Event detail — always Compose inside chat (native would open behind the sheet)
    if (selectedEvent != null) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { selectedEvent = null; selectedEventId = null },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = AppColors.Snow,
        ) {
            live.localapp.local.ui.detail.EventDetailScreen(
                event = selectedEvent!!,
                onBack = { selectedEvent = null; selectedEventId = null },
            )
        }
    }

    // Venue detail sheet
    if (selectedVenue != null) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { selectedVenue = null; selectedVenueName = null },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = AppColors.Snow,
        ) {
            live.localapp.local.ui.places.VenueDetailPage(
                venueWithMeta = live.localapp.local.domain.repository.VenueWithMeta(venue = selectedVenue!!),
                events = emptyList(),
                isLoading = false,
                onBack = { selectedVenue = null; selectedVenueName = null },
            )
        }
    }

    // Intent picker on open — "What are you looking to do?" Shown until the
    // user picks a goal (boIntent set), which steers Bo's framing + planning.
    // confirmValueChange blocks swipe-to-dismiss so a goal must be chosen
    // (mirrors the iOS interactiveDismissDisabled sheet).
    if (state.boIntent == null) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != androidx.compose.material3.SheetValue.Hidden },
            ),
            containerColor = Color.White,
        ) {
            BoIntentPicker(onPick = { viewModel.setBoIntent(it) })
        }
    }

    // Airbnb-style scale-in: on iOS the background view scales to 0.92 when
    // the chat sheet opens. We can't reach the host view from here, so we
    // approximate the depth cue by scaling the sheet's own content from
    // 0.92 → 1.0 on first composition (and rounding its corners 0 → 16dp).
    // This is a simplification — the real effect is on the *background*.
    var sheetShown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { sheetShown = true }
    val sheetScale by animateFloatAsState(
        targetValue = if (sheetShown) 1f else 0.92f,
        animationSpec = tween(durationMillis = 320, easing = EaseOutCubic),
        label = "sheetScale",
    )
    val sheetCornerRadius by animateFloatAsState(
        targetValue = if (sheetShown) 16f else 0f,
        animationSpec = tween(durationMillis = 320, easing = EaseOutCubic),
        label = "sheetCorner",
    )

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = sheetScale
                scaleY = sheetScale
                clip = true
                shape = RoundedCornerShape(sheetCornerRadius.dp)
            }
            // iOS doesn't auto-shift content when the keyboard appears — without
            // this the BasicTextField's caret renders at the wrong y-coordinate
            // during the keyboard slide-in (looks like the cursor "flies up
            // into the chat area" while Bo is streaming).
            .imePadding()
            .background(AppColors.Snow),
    ) {
        // ── Header ──
        var locationEditorOpen by remember { mutableStateOf(false) }
        ChatHeader(
            onBack = onBack,
            onReset = { viewModel.resetChat() },
            location = state.currentLocationName,
            radiusMiles = state.searchRadiusMiles,
            onEditLocation = { locationEditorOpen = true },
        )
        if (locationEditorOpen) {
            LocationEditorSheet(
                current = state.currentLocationName,
                currentLat = state.currentLat,
                currentLng = state.currentLng,
                currentRadiusMiles = state.searchRadiusMiles,
                onDismiss = { locationEditorOpen = false },
                onConfirm = { name, lat, lng, radius ->
                    // Picked a Places suggestion → real coords; free-typed → NaN
                    // so the VM falls back to its in-memory geocoder.
                    viewModel.setChatSearchScopeWithCoords(name, lat ?: Double.NaN, lng ?: Double.NaN, radius)
                    locationEditorOpen = false
                },
            )
        }

        // ── Upgrade Banner (for users who declined paywall) ──
        // Temporarily disabled — hidden for now.
        @Suppress("ConstantConditionIf")
        if (false && showUpgradeBanner) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(AppColors.Coral, Color(0xFFE85D5D))))
                    .clickable(onClick = onUpgrade)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Build personalized and shareable plans with Bo",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White),
                    )
                    Text(
                        "Plans start at just \$0.99/month",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f)),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Upgrade now",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 11.sp, color = AppColors.Coral),
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // ── Messages ──
        // BoxWithConstraints supplies the viewport height as a stable value
        // we can use for bottom contentPadding. The earlier approach (a
        // trailing `Spacer(fillParentMaxHeight)` item) failed on iOS because
        // LazyColumn measures items lazily — when scroll fired right after
        // a new user message landed, the spacer wasn't measured yet, so
        // there wasn't enough scroll range for the new bubble to reach the
        // top of the viewport. Using bottom contentPadding instead is a
        // constraint applied at LazyColumn construction time, not lazily.
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val viewportHeight = maxHeight
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().nestedScroll(remember {
                    object : NestedScrollConnection {
                        override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                            // Consume upward scroll when at top to prevent sheet drag
                            if (available.y > 0 && !listState.canScrollBackward) {
                                return available
                            }
                            return androidx.compose.ui.geometry.Offset.Zero
                        }
                    }
                }),
                contentPadding = PaddingValues(
                    start = 14.dp, end = 14.dp, top = 4.dp,
                    // A full viewport of scrollable bottom padding guarantees
                    // we can always scroll the last user bubble to the top
                    // (`scrollBy` would otherwise be clamped to whatever
                    // scroll range happens to exist when it fires).
                    bottom = viewportHeight,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            items(state.messages, key = { it.id }) { message ->
                val isSuperseded = viewModel.isPlanSuperseded(message.id)
                when (message.content) {
                    is MessageContent.Text -> MessageRow(message.role, (message.content as MessageContent.Text).text, messageId = message.id)
                    is MessageContent.DayCardRef -> {
                        val ref = message.content as MessageContent.DayCardRef
                        val dayIdx = ref.dayIndex
                        val cardDays = ref.snapshot ?: state.days
                        val day = cardDays.getOrNull(dayIdx) ?: return@items

                        Box {
                            DayCard(
                                day = day,
                                dayIndex = dayIdx,
                                totalDays = cardDays.size,
                                onEventClick = { id ->
                                    if (!isSuperseded) {
                                        // Drop the Bo input focus so the keyboard collapses
                                        // before the event/venue detail opens over the chat.
                                        focusManager.clearFocus()
                                        viewModel.recordEventTap(id.removePrefix("venue:"))
                                        if (id.startsWith("venue:")) {
                                            if (onNativeVenueClick != null) onNativeVenueClick(id.removePrefix("venue:"))
                                            else selectedVenueName = id.removePrefix("venue:")
                                        } else {
                                            if (onNativeEventClick != null) {
                                                val slotImage = cardDays.flatMap { it.slots }.firstOrNull { it.eventId == id }?.imageUrl
                                                onNativeEventClick(id, slotImage)
                                            } else {
                                                selectedEventId = id
                                            }
                                        }
                                    }
                                },
                                weather = state.weather.getOrElse(dayIdx) { null },
                                onSave = { viewModel.sendMessage("Looks good, add to My Plans") },
                                showSaveButton = !isSuperseded && !day.isApproved,
                            )
                            if (isSuperseded) {
                                SupersededOverlay()
                            }
                        }
                    }
                    is MessageContent.MultiDayPlanRef -> {
                        val ref = message.content as MessageContent.MultiDayPlanRef
                        val cardDays = ref.snapshot ?: state.days

                        Box {
                            MultiDayPlanCard(
                                days = cardDays,
                                onEventClick = { id ->
                                    if (!isSuperseded) {
                                        // Drop the Bo input focus so the keyboard collapses
                                        // before the event/venue detail opens over the chat.
                                        focusManager.clearFocus()
                                        viewModel.recordEventTap(id.removePrefix("venue:"))
                                        if (id.startsWith("venue:")) {
                                            if (onNativeVenueClick != null) onNativeVenueClick(id.removePrefix("venue:"))
                                            else selectedVenueName = id.removePrefix("venue:")
                                        } else {
                                            if (onNativeEventClick != null) {
                                                val slotImage = cardDays.flatMap { it.slots }.firstOrNull { it.eventId == id }?.imageUrl
                                                onNativeEventClick(id, slotImage)
                                            } else {
                                                selectedEventId = id
                                            }
                                        }
                                    }
                                },
                                weather = state.weather,
                                onSave = { viewModel.sendMessage("Looks good, add to My Plans") },
                                showSaveButton = !isSuperseded && cardDays.none { it.isApproved },
                            )
                            if (isSuperseded) {
                                SupersededOverlay()
                            }
                        }
                    }
                    is MessageContent.Structured -> {
                        val ref = message.content as MessageContent.Structured
                        val structMsgId = message.id
                        StructuredResponseRenderer(
                            messageId = structMsgId,
                            response = ref.response,
                            hydratedEvents = ref.hydratedEvents,
                            tray = state.draftTray,
                            onAddToTray = { ev -> viewModel.addToDraftTray(ev) },
                            onOpenEvent = { ev ->
                                if (onNativeEventClick != null) onNativeEventClick(ev.id, ev.imageUrl)
                                else selectedEventId = ev.id
                            },
                            onFooterAction = { action ->
                                viewModel.sendDraftTrayChip(footerActionToIntent(action))
                            },
                            onScopeChip = { intent -> viewModel.sendDraftTrayChip(intent) },
                            isDismissed = { id -> id in dismissedEventIds },
                            onDismiss = { id ->
                                dismissedEventIds = dismissedEventIds + id
                                viewModel.removeFromDraftTray(id)
                            },
                            isGroupLoading = { groupKey -> loadingGroups["$structMsgId|$groupKey"] == true },
                            isGroupExhausted = { groupKey -> exhaustedGroups["$structMsgId|$groupKey"] == true },
                            onFindMore = { dayKey, categoryKey ->
                                val groupKey = dayKey ?: categoryKey ?: ""
                                if (groupKey.isNotEmpty()) {
                                    loadingGroups["$structMsgId|$groupKey"] = true
                                    viewModel.loadMoreSuggestionsForGroup(structMsgId, dayKey, categoryKey)
                                }
                            },
                        )
                    }
                }
            }

            // Streaming text / searching / typing indicator
            if (state.isSearching) {
                item { SearchingIndicator(opener = state.searchingOpener, narration = state.searchingNarration, localIdioms = state.localIdioms) }
            } else if (state.streamingText.isNotEmpty()) {
                item { MessageRow(role = MessageRole.AI, text = state.streamingText) }
            } else if (state.isTyping) {
                item { TypingIndicator() }
            }
            // The trailing-spacer item that used to live here was replaced
            // by the BoxWithConstraints bottom contentPadding above.
            }
        }

        // ── Phase 6 Draft Tray ──
        // Persistent above the input bar whenever the user has tapped [+]
        // on at least one event from a SuggestionTray / EventBriefCard /
        // VenueBriefCard. Hidden when the tray is empty.
        if (state.draftTray.isNotEmpty()) {
            DraftPlanTray(
                tray = state.draftTray,
                chips = state.draftTrayChips,
                onRemove = { id -> viewModel.removeFromDraftTray(id) },
                onChipTap = { chip -> viewModel.sendDraftTrayChip(chip.intent) },
                onBuild = { viewModel.buildFromDraftTray() },
                boIntent = state.boIntent,
            )
        }

        // ── Quick Replies ──
        if (state.quickReplies.isNotEmpty()) {
            QuickReplyBar(
                replies = state.quickReplies,
                onReply = { viewModel.onQuickReply(it) },
            )
        }

        // ── Input Bar ──
        ChatInputBar(
            enabled = state.inputEnabled,
            placeholder = state.inputPlaceholder,
            onSend = { text ->
                viewModel.sendMessage(text)
                // Imperative scroll. The reactive LaunchedEffect above will
                // also fire on the same state change — whichever lands the
                // anchor first wins, the other becomes a no-op.
                scrollScope.launch { anchorLatestUserMessage("imperative") }
            },
        )
    }

    // Auth prompt is now handled at navigation level

    // ── Plan Success Overlay ──
    if (state.showPlanSuccess) {
        PlanSuccessOverlay(
            planTitle = state.planTitle.ifBlank { "Your Plan" },
            days = state.days,
            onViewPlans = {
                viewModel.dismissPlanSuccess(navigateToPlans = false)
                onNavigateToPlans?.invoke()
            },
            onInviteFriends = {
                // TODO: share sheet
            },
        )
    }
    } // end Box
}

// ── Header ──

@Composable
private fun ChatHeader(
    onBack: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    location: String = "",
    radiusMiles: Double = 20.0,
    onEditLocation: (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().background(AppColors.Snow)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Icon(
                    Icons.Outlined.ChevronLeft, "Back",
                    modifier = Modifier.size(22.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null, onClick = onBack,
                    ),
                    tint = AppColors.Ink3,
                )
                Spacer(Modifier.width(8.dp))
            }

            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Ask Bo",
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.Ink),
                )
                Spacer(Modifier.width(8.dp))
                live.localapp.local.ui.character.Bo(
                    size = 40.dp,
                    level = live.localapp.local.ui.character.cachedBoLevel(),
                    showSparkles = false,
                    showShadow = false,
                    modifier = Modifier.size(40.dp),
                )
            }

            if (onReset != null) {
                Text(
                    "Start over",
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null, onClick = onReset,
                    ),
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AppColors.Ink3),
                )
            }
        }

        // Search-scope banner (Bug #19) — full-width sky-blue banner matching
        // the home tab's location pill + iOS ChatView. Single line:
        // "Searching {City} · Within {N} miles" with a mappin and a trailing
        // down-chevron. Replaces the old pink coral "Change" chip. The city is
        // stripped of its state/country suffix ("Ipswich, MA, US" → "Ipswich").
        if (onEditLocation != null) {
            val city = location.substringBefore(",").trim()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.SkyLt)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onEditLocation,
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Place, "Location",
                    tint = AppColors.Sky,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                if (city.isBlank()) {
                    Text(
                        "Set search location",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.Sky),
                    )
                } else {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Searching $city ") }
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("· Within ${radiusMiles.toInt()} miles") }
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFamily = AppFonts.Body, fontSize = 13.sp, color = AppColors.Sky),
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Outlined.KeyboardArrowDown, contentDescription = null,
                    tint = AppColors.Sky,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.Pebble.copy(alpha = 0.5f)))
}

/**
 * Bottom-sheet editor for the chat's search scope — matches iOS
 * SearchScopeEditorSheet (ChatView.swift). A city/neighborhood text field, a
 * live map preview of the current scope center, and a radius slider (2–50 mi).
 * On "Set scope" the viewmodel geocodes the name and pins all of Bo's searches
 * to that scope + radius until the user changes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationEditorSheet(
    current: String,
    currentLat: Double?,
    currentLng: Double?,
    currentRadiusMiles: Double,
    onDismiss: () -> Unit,
    onConfirm: (name: String, lat: Double?, lng: Double?, radius: Double) -> Unit,
) {
    val places = rememberPlacesAutocomplete()
    val scope = rememberCoroutineScope()
    var text by remember(current) { mutableStateOf(current) }
    var radius by remember(currentRadiusMiles) { mutableStateOf(currentRadiusMiles.toFloat().coerceIn(1f, 50f)) }
    // Coords for the chosen scope: seeded from the current scope, replaced when the
    // user picks a Places suggestion, cleared to null when they free-type (so the VM
    // falls back to its geocoder). This is the fix for Android, where there was no
    // autocomplete and the in-memory geocoder couldn't resolve most towns.
    var pickedLat by remember(current) { mutableStateOf(currentLat) }
    var pickedLng by remember(current) { mutableStateOf(currentLng) }
    var suggestions by remember { mutableStateOf<List<PlaceSuggestion>>(emptyList()) }
    var justPicked by remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        if (justPicked) { justPicked = false; suggestions = emptyList(); return@LaunchedEffect }
        // Don't surface suggestions for the unchanged prefilled value.
        if (text == current || text.trim().length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        delay(250)
        suggestions = runCatching { places.query(text.trim(), currentLat, currentLng) }.getOrDefault(emptyList())
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AppColors.Snow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 32.dp)
                .imePadding(),
        ) {
            Text(
                "Where should Bo search?",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 22.sp, color = AppColors.Ink),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Bo pins every search to this scope until you change it.",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = AppColors.Ink3),
            )
            Spacer(Modifier.height(20.dp))
            TextField(
                value = text,
                onValueChange = { text = it; pickedLat = null; pickedLng = null },
                placeholder = { Text("e.g. Brookline, Cambridge, Hyannis") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AppColors.White,
                    unfocusedContainerColor = AppColors.White,
                    focusedIndicatorColor = AppColors.Coral,
                    unfocusedIndicatorColor = AppColors.Pebble,
                ),
                textStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = AppColors.Ink),
            )

            // Google Places autocomplete suggestions.
            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppColors.White),
                ) {
                    items(suggestions, key = { it.id }) { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val d = runCatching { places.resolve(s) }.getOrNull()
                                        justPicked = true
                                        text = s.primaryText
                                        pickedLat = d?.lat
                                        pickedLng = d?.lng
                                        suggestions = emptyList()
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                        ) {
                            Column {
                                Text(s.primaryText, style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink))
                                if (s.secondaryText.isNotBlank()) {
                                    Text(s.secondaryText, style = TextStyle(fontFamily = AppFonts.Body, fontSize = 12.sp, color = AppColors.Ink3))
                                }
                            }
                        }
                    }
                }
            }

            // Live map preview of the chosen scope center.
            val previewLat = pickedLat ?: currentLat
            val previewLng = pickedLng ?: currentLng
            if (previewLat != null && previewLng != null) {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(16.dp)),
                ) {
                    PlanMapHero(
                        stops = listOf(MapHeroStop(previewLat, previewLng, text.ifBlank { current })),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Radius",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Ink),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${radius.toInt()} mi",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Coral),
                )
            }
            Slider(
                value = radius,
                onValueChange = { radius = it },
                valueRange = 1f..50f,
                steps = 48,
                colors = SliderDefaults.colors(
                    thumbColor = AppColors.Coral,
                    activeTrackColor = AppColors.Coral,
                    inactiveTrackColor = AppColors.Pebble.copy(alpha = 0.4f),
                ),
            )

            Spacer(Modifier.height(16.dp))
            val canSet = text.trim().isNotBlank()
            Box(
                modifier = Modifier.fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(50))
                    .background(AppColors.Coral)
                    .graphicsLayer { alpha = if (canSet) 1f else 0.3f }
                    .then(if (canSet) Modifier.clickable { onConfirm(text.trim(), pickedLat, pickedLng, radius.toDouble()) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Set scope",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White),
                )
            }
        }
    }
}

// ── Message Bubbles ──

@Composable
private fun SupersededOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Snow.copy(alpha = 0.7f))
            .clickable(enabled = false, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Updated below",
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = AppColors.Ink3,
            ),
            modifier = Modifier
                .background(AppColors.White, RoundedCornerShape(8.dp))
                .border(1.dp, AppColors.Pebble, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun MessageRow(role: MessageRole, text: String, messageId: String? = null) {
    val isUser = role == MessageRole.USER

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) Spacer(Modifier.width(48.dp))

        if (isUser) {
            // User messages: bubble. iOS uses uniform 18pt corners.
            val bubbleShape = RoundedCornerShape(18.dp)
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .background(Color(0xFFF0EDF6), bubbleShape)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Text(
                    cleanMarkdown(text),
                    style = TextStyle(
                        fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        color = AppColors.Ink, lineHeight = (15 * 1.55).sp,
                    ),
                )
            }
        } else {
            // AI messages (iOS parity — ChatView.swift aiMessageBubble):
            // a leading-aligned Column whose FIRST row is the "Bo" label with
            // the avatar inline to its RIGHT, then the body text below at full
            // width. The avatar is paired with the name, NOT stacked in a
            // separate left gutter beside the body text.
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(vertical = 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Bo",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = AppColors.Coral,
                        ),
                    )
                    Image(
                        painter = painterResource(Res.drawable.bo_tiny),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (messageId != null) {
                    // Persisted AI reply with a stable id — use the
                    // markdown-aware typewriter reveal (matches iOS
                    // ChatView.swift). Bold/bullets render live during the
                    // reveal; boldAnnotatedString strips emoji + normalizes
                    // markdown internally, so pass the raw text.
                    TypewritingAiText(
                        messageId = messageId,
                        fullText = text,
                        color = AppColors.Ink,
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 21.sp,
                        ),
                        fontSize = 15.sp,
                    )
                } else {
                    RichChatText(text = text, textColor = AppColors.Ink)
                }
            }
        }

        if (!isUser) Spacer(Modifier.width(48.dp))
    }
}

// ── Typing Indicator ──

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition()
    val bubbleShape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    Row(
        modifier = Modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Row(
            Modifier.background(AppColors.White, bubbleShape)
                .border(1.dp, AppColors.Pebble.copy(alpha = 0.6f), bubbleShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { index ->
                val offset by infiniteTransition.animateFloat(
                    initialValue = 0f, targetValue = -5f,
                    animationSpec = infiniteRepeatable(tween(600, delayMillis = index * 180), RepeatMode.Reverse),
                )
                Box(Modifier.size(6.dp).graphicsLayer { translationY = offset }.background(AppColors.Ink3.copy(alpha = 0.4f), CircleShape))
            }
        }
    }
}

// ── Searching Indicator (rotating gerund phrases) ──

private val searchingPhrases = listOf(
    "Flambéing...",
    "Parallel parking...",
    "Consulting the locals...",
    "Polishing the itinerary...",
    "Taste-testing...",
    "Checking the vibe...",
    "Dodging tourists...",
    "Asking the bartender...",
    "Finding the secret menu...",
    "Warming up the car...",
    "Scouting rooftops...",
    "Bribing the maître d'...",
    "Googling parking...",
    "Judging the Yelp reviews...",
    "Texting the group chat...",
    "Stretching before the walk...",
    "Charging the phone...",
    "Picking the playlist...",
    "Ironing the outfit...",
    "Checking the weather...",
    "Memorizing the wine list...",
    "Practicing small talk...",
    "Feeding the meter...",
    "Adjusting the GPS...",
    "Calling an audible...",
    "Haggling...",
    "Eavesdropping on regulars...",
    "Reading the room...",
    "Shaking the cocktail...",
    "Folding the napkin...",
    "Buttering the bread...",
    "Sampling the charcuterie...",
    "Tipping generously...",
    "Sneaking into VIP...",
    "Befriending the bouncer...",
    "Splitting the check...",
    "Flagging the waiter...",
    "Double-checking the address...",
    "Refreshing the page...",
    "Stalking their Instagram...",
    "Manifesting good weather...",
    "Summoning the Uber...",
    "Hydrating...",
    "Pregaming...",
    "Limbering up...",
    "Coordinating outfits...",
    "Sniffing the cork...",
    "Judging the font on the menu...",
    "Pretending to read the specials...",
    "Complimenting the décor...",
    "Warming the engine...",
    "Negotiating the dress code...",
    "Pocketing the mints...",
    "Claiming the booth...",
    "Decoding the cocktail menu...",
    "Rating the ambiance...",
    "Swiping for inspiration...",
    "Checking the tide chart...",
    "Waxing the surfboard...",
    "Alphabetizing the beer list...",
    "Arguing with the GPS...",
    "Avoiding eye contact with mimes...",
    "Borrowing a lighter...",
    "Calculating the tip...",
    "Canceling other plans...",
    "Checking coat pockets...",
    "Choosing the right sneakers...",
    "Clearing the camera roll...",
    "Comparing happy hours...",
    "Confirming the dress code...",
    "Convincing the group...",
    "Counting the steps...",
    "Crossing fingers...",
    "Cueing the DJ...",
    "Debugging the playlist...",
    "Defrosting the windshield...",
    "Deleting bad selfies...",
    "Doing recon...",
    "Double-knotting the laces...",
    "Downloading the map offline...",
    "Drafting the toast...",
    "Dry-cleaning the jacket...",
    "Dusting off the dance moves...",
    "Eating a snack first...",
    "Estimating walk time...",
    "Eyeing the dessert menu...",
    "Fact-checking the specials...",
    "Faking a French accent...",
    "Finding an outlet...",
    "Fixing the hair...",
    "Flipping through records...",
    "Forging a shortcut...",
    "Gargling mouthwash...",
    "Getting the inside scoop...",
    "Grabbing the umbrella...",
    "Guarding the parking spot...",
    "Hailing a cab...",
    "Hiding the shopping bags...",
    "Holding the elevator...",
    "Hunting for street parking...",
    "Ignoring the calorie count...",
    "Improvising...",
    "Inspecting the glassware...",
    "Jaywalking responsibly...",
    "Keeping it casual...",
    "Layering appropriately...",
    "Learning the specials...",
    "Licking the spoon...",
    "Making a fashionably late entrance...",
    "Marinating the chicken...",
    "Microwaving nothing...",
    "Mispronouncing the entrée...",
    "Nabbing the window seat...",
    "Nodding at the sommelier...",
    "Opening all the tabs...",
    "Ordering water first...",
    "Overthinking the appetizer...",
    "Pacing the block...",
    "Peeling the orange...",
    "Photographing the food...",
    "Picking the right emoji...",
    "Pivoting plans...",
    "Plating the garnish...",
    "Popping the cork...",
    "Powering through the menu...",
    "Pre-buttering the rolls...",
    "Procrastinating productively...",
    "Queuing strategically...",
    "Rearranging the chairs...",
    "Recalibrating expectations...",
    "Rehearsing the order...",
    "Reloading the gift card...",
    "Renaming the group chat...",
    "Repressing FOMO...",
    "Reviewing the fire exits...",
    "Rolling up the sleeves...",
    "Rotating the cheese board...",
    "Saving the corner seat...",
    "Scanning for celebrities...",
    "Seasoning to taste...",
    "Sharpening the steak knife...",
    "Shushing the table...",
    "Silencing the phone...",
    "Skimming the cocktail bible...",
    "Slicing the sourdough...",
    "Smelling the candles...",
    "Sneaking in snacks...",
    "Sorting by price...",
    "Squeezing the lemon...",
    "Straightening the tie...",
    "Surviving the wait list...",
    "Tasting the olive oil...",
    "Testing the acoustics...",
    "Toasting the bread...",
    "Turning on flashlight to view the menu...",
    "Uncorking the Malbec...",
    "Updating the story...",
    "Vibing...",
    "Whispering to the chef...",
    "Almost there...",
)

@Composable
private fun SearchingIndicator(
    opener: String? = null,
    narration: List<String> = emptyList(),
    localIdioms: List<String> = emptyList(),
) {
    // Rotating playful gerund — the always-present "thinking" line that sits
    // at the bottom while Bo works. Rotates every 2.5s.
    var phraseIndex by remember { mutableStateOf((0 until searchingPhrases.size).random()) }
    // Every ~3rd rotation, surface a hyperlocal idiom for the user's location
    // (when Claude has generated some) to forge a place-specific connection;
    // otherwise fall back to the generic whimsical gerunds.
    val currentGerund = remember(phraseIndex, localIdioms) {
        if (localIdioms.isNotEmpty() && phraseIndex % 3 == 2) {
            localIdioms[(phraseIndex / 3) % localIdioms.size]
        } else {
            searchingPhrases[phraseIndex % searchingPhrases.size]
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2500L)
            phraseIndex++
        }
    }
    // Fade the gerund on each rotation.
    val gerundAlpha = remember { Animatable(1f) }
    LaunchedEffect(phraseIndex) {
        gerundAlpha.snapTo(0f)
        gerundAlpha.animateTo(1f, animationSpec = tween(400))
    }

    // Stagger the reveal: even though a batch of narration steps arrives all at
    // once (after the ~10s round finishes), reveal them one at a time so the
    // wait feels like progress. visibleCount persists across appends so already
    // shown steps never re-hide.
    var visibleCount by remember { mutableStateOf(0) }
    LaunchedEffect(narration.size) {
        while (visibleCount < narration.size) {
            delay(700L)
            visibleCount++
        }
    }
    val visibleNarration = narration.take(visibleCount)

    // iOS parity (ChatView.swift SearchingIndicatorView): NO bordered bubble.
    // Two stacked rows, each sharing a leftmost Bo-avatar column:
    //  • the narration block (Bo avatar + checked steps) — only once steps exist
    //  • the live idiom line (Bo avatar + gerund) — always present while searching
    Column(
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // One-time browse opener — a human beat that echoes the request + scope
        // ("Let me find Live music this week for you. Searching within 20 miles
        // of Waltham.") above the ✓ steps. Reads as Bo speaking, so it carries
        // the Bo avatar like the rows below it.
        if (!opener.isNullOrBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Image(
                    painter = painterResource(Res.drawable.bo_tiny),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).padding(top = 1.dp),
                )
                Text(
                    opener,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        color = AppColors.Ink,
                    ),
                )
            }
        }
        // Completed narration steps stack up and persist, each with a check.
        // Derived from Bo's tool args (e.g. "Searching within 20 miles of
        // Waltham"). Only render the row (with its avatar) once steps exist.
        if (visibleNarration.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Image(
                    painter = painterResource(Res.drawable.bo_tiny),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).padding(top = 1.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    visibleNarration.forEach { step ->
                        val rowAlpha = remember(step) { Animatable(0f) }
                        LaunchedEffect(step) { rowAlpha.animateTo(1f, animationSpec = tween(300)) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.graphicsLayer { this.alpha = rowAlpha.value },
                        ) {
                            Text(
                                "✓",
                                style = TextStyle(
                                    fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp, color = AppColors.Ink3.copy(alpha = 0.5f),
                                ),
                            )
                            Text(
                                step,
                                style = TextStyle(
                                    fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp, color = AppColors.Ink3.copy(alpha = 0.7f),
                                ),
                            )
                        }
                    }
                }
            }
        }
        // Live "thinking" line — always present while searching. Its Bo avatar
        // shares the leftmost column with the narration avatar above, so once
        // steps appear it sits directly beneath that avatar. The avatar stays
        // put; only the gerund text fades and re-appears as phrases rotate.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(Res.drawable.bo_tiny),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                currentGerund,
                modifier = Modifier.graphicsLayer { this.alpha = gerundAlpha.value },
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = AppColors.Ink3,
                ),
            )
        }
    }
}

// ── Plan Cards (read-only, magazine style) ──

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MultiDayPlanCard(
    days: List<ItineraryDay>,
    onEventClick: (String) -> Unit = {},
    weather: Map<Int, live.localapp.local.data.remote.WeatherForecast> = emptyMap(),
    onSave: () -> Unit = {},
    showSaveButton: Boolean = true,
) {
    var currentDayIndex by remember { mutableStateOf(0) }
    val cardShape = RoundedCornerShape(18.dp)
    val currentDay = days.getOrNull(currentDayIndex) ?: return
    val color = Color(dayColor(currentDayIndex))

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        ) {
            // Shadow
            Box(
                Modifier.fillMaxWidth().matchParentSize().offset(y = 5.dp)
                    .background(AppColors.Pebble, cardShape),
            )

            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(AppColors.White, cardShape)
                    .border(2.dp, AppColors.Pebble, cardShape)
                    .clip(cardShape),
            ) {
                // Day header with arrows
                Column(
                    modifier = Modifier.fillMaxWidth().background(color)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "DAY ${currentDayIndex + 1} OF ${days.size}",
                            style = TextStyle(
                                fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 10.sp,
                                color = AppColors.White.copy(alpha = 0.7f), letterSpacing = 0.8.sp,
                            ),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (currentDayIndex > 0) {
                                Text(
                                    "\u2190",
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { currentDayIndex-- },
                                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White),
                                )
                            }
                            if (currentDayIndex < days.size - 1) {
                                Text(
                                    "\u2192",
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { currentDayIndex++ },
                                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        currentDay.label.replace("Today ", "").replace("Tomorrow ", "").ifBlank { "Your Plan" },
                        style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 16.sp, color = AppColors.White),
                    )
                    Text(
                        "${currentDay.slots.size} ${if (currentDay.slots.size == 1) "stop" else "stops"}",
                        style = TextStyle(
                            fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
                            color = AppColors.White.copy(alpha = 0.7f),
                        ),
                    )
                }

                // Weather bar
                weather[currentDayIndex]?.let { w ->
                    Row(
                        modifier = Modifier.fillMaxWidth().background(AppColors.SkyLt)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${w.tempHigh}\u00B0F  ${w.description}",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AppColors.SkyDk),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Horizontal timeline row
                val slotPagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { currentDay.slots.size })

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    currentDay.slots.forEachIndexed { index, slot ->
                        val isActive = slotPagerState.currentPage == index
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Dot
                            Box(
                                modifier = Modifier
                                    .size(if (isActive) 8.dp else 6.dp)
                                    .background(if (isActive) color else AppColors.Pebble, CircleShape),
                            )
                            Spacer(Modifier.width(4.dp))
                            // Time
                            Text(
                                slot.time,
                                style = TextStyle(
                                    fontFamily = AppFonts.Body,
                                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    fontSize = if (isActive) 12.sp else 10.sp,
                                    color = if (isActive) AppColors.Ink else AppColors.Ink3,
                                ),
                            )
                        }
                        // Connecting line between dots
                        if (index < currentDay.slots.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(1.dp)
                                    .padding(horizontal = 4.dp)
                                    .background(AppColors.Pebble.copy(alpha = 0.4f)),
                            )
                        }
                    }
                }

                // Slot carousel
                androidx.compose.foundation.pager.HorizontalPager(
                    state = slotPagerState,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = if (currentDay.slots.size > 1) PaddingValues(start = 12.dp, end = 36.dp) else PaddingValues(horizontal = 12.dp),
                    pageSpacing = 8.dp,
                ) { slotIndex ->
                    val slot = currentDay.slots[slotIndex]
                    SlotCard(
                        slot = slot,
                        dateLabel = currentDay.label,
                        onEventClick = {
                            if (slot.eventId != null) onEventClick(slot.eventId)
                            else if (!slot.isCustom && slot.name.length > 2) onEventClick("venue:${slot.name}")
                        },
                    )
                }

                // Slot indicator dots
                if (currentDay.slots.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        currentDay.slots.forEachIndexed { index, _ ->
                            val isSelected = slotPagerState.currentPage == index
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(if (isSelected) 8.dp else 6.dp)
                                    .background(
                                        if (isSelected) color else AppColors.Pebble,
                                        CircleShape,
                                    ),
                            )
                        }
                    }
                }

                // Cost estimate
                val pricedSlots = currentDay.slots.filter { !it.isFree && (it.priceMin != null || it.price.isNotBlank()) }
                if (pricedSlots.isNotEmpty()) {
                    val totalMin = pricedSlots.sumOf { it.priceMin ?: extractPriceValue(it.price) ?: 0.0 }
                    val totalMax = pricedSlots.sumOf { it.priceMax ?: it.priceMin ?: extractPriceValue(it.price) ?: 0.0 }
                    if (totalMin > 0 || totalMax > 0) {
                        val attendees = 2
                        val minEst = (totalMin * attendees).toInt()
                        val maxEst = (totalMax * attendees).toInt()
                        val estimate = if (minEst == maxEst) "~$$minEst" else "$$minEst-$maxEst"
                        Text(
                            "$estimate estimated for $attendees",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AppColors.Ink3),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
            }
        }

        if (showSaveButton) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(AppColors.Sage, AppColors.SageDk)))
                    .clickable(onClick = onSave)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Save to My Plans",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 14.sp, color = AppColors.White),
                )
            }
        }
    }
}

@Composable
private fun DayCard(
    day: ItineraryDay,
    dayIndex: Int,
    totalDays: Int,
    onEventClick: (String) -> Unit = {},
    weather: live.localapp.local.data.remote.WeatherForecast? = null,
    onSave: () -> Unit = {},
    showSaveButton: Boolean = true,
    onPrevDay: (() -> Unit)? = null,
    onNextDay: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val color = Color(dayColor(dayIndex))
    val cardShape = RoundedCornerShape(18.dp)

    Box(modifier = modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().matchParentSize().offset(y = 5.dp).background(AppColors.Pebble, cardShape))

        Column(
            modifier = Modifier.fillMaxWidth()
                .background(AppColors.White, cardShape)
                .border(2.dp, AppColors.Pebble, cardShape)
                .clip(cardShape),
        ) {
            // Header
            Column(modifier = Modifier.fillMaxWidth().background(color).padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (totalDays > 1) {
                    Text(
                        "DAY ${dayIndex + 1} OF $totalDays",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 10.sp,
                            color = AppColors.White.copy(alpha = 0.7f), letterSpacing = 0.8.sp),
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    day.label.replace("Today ", "").replace("Tomorrow ", "").ifBlank { "Your Plan" },
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 16.sp, color = AppColors.White),
                )
                Text(
                    "${day.slots.size} ${if (day.slots.size == 1) "stop" else "stops"}",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
                        color = AppColors.White.copy(alpha = 0.7f)),
                )
            }

            // Weather bar
            if (weather != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(AppColors.SkyLt).padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${weather.tempHigh}°F  ${weather.description}",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AppColors.SkyDk),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Horizontal timeline row
            val slotPagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { day.slots.size })

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                day.slots.forEachIndexed { index, slot ->
                    val isActive = slotPagerState.currentPage == index
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(if (isActive) 8.dp else 6.dp)
                                .background(if (isActive) color else AppColors.Pebble, CircleShape),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            slot.time,
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
                                fontSize = if (isActive) 12.sp else 10.sp,
                                color = if (isActive) AppColors.Ink else AppColors.Ink3,
                            ),
                        )
                    }
                    if (index < day.slots.lastIndex) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(1.dp)
                                .padding(horizontal = 4.dp)
                                .background(AppColors.Pebble.copy(alpha = 0.4f)),
                        )
                    }
                }
            }

            // Slots — horizontal swipe carousel
            androidx.compose.foundation.pager.HorizontalPager(
                state = slotPagerState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = if (day.slots.size > 1) PaddingValues(start = 12.dp, end = 36.dp) else PaddingValues(horizontal = 12.dp),
                pageSpacing = 8.dp,
            ) { slotIndex ->
                val slot = day.slots[slotIndex]
                SlotCard(
                    slot = slot,
                    dateLabel = day.label,
                    onEventClick = {
                        if (slot.eventId != null) onEventClick(slot.eventId)
                        else if (!slot.isCustom && slot.name.length > 2) onEventClick("venue:${slot.name}")
                    },
                )
            }

            // Slot indicator dots
            if (day.slots.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    day.slots.forEachIndexed { index, _ ->
                        val isSelected = slotPagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (isSelected) 8.dp else 6.dp)
                                .background(if (isSelected) color else AppColors.Pebble, CircleShape),
                        )
                    }
                }
            }

            // Cost estimate
            val pricedSlots = day.slots.filter { !it.isFree && (it.priceMin != null || it.price.isNotBlank()) }
            if (pricedSlots.isNotEmpty()) {
                val totalMin = pricedSlots.sumOf { it.priceMin ?: extractPriceValue(it.price) ?: 0.0 }
                val totalMax = pricedSlots.sumOf { it.priceMax ?: it.priceMin ?: extractPriceValue(it.price) ?: 0.0 }
                if (totalMin > 0 || totalMax > 0) {
                    val attendees = 2
                    val minEst = (totalMin * attendees).toInt()
                    val maxEst = (totalMax * attendees).toInt()
                    val estimate = if (minEst == maxEst) "~$$minEst" else "$$minEst-$maxEst"
                    Text(
                        "$estimate estimated for $attendees",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AppColors.Ink3),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }

            // Save button
            if (showSaveButton) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(AppColors.Sage, AppColors.SageDk)))
                        .clickable(onClick = onSave)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Save to My Plans",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 14.sp, color = AppColors.White),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Individual slot rendered as a standalone photo card. */
@Composable
private fun SlotCard(
    slot: ItinerarySlot,
    dateLabel: String = "",
    onEventClick: () -> Unit = {},
) {
    val inAppBrowser = rememberInAppBrowser()
    val hasImage = slot.imageUrl != null
    val cardShape = RoundedCornerShape(13.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (hasImage) Modifier.height(120.dp) else Modifier)
            .clip(cardShape)
            .background(AppColors.Snow)
            .border(if (hasImage) 0.dp else 1.5.dp, if (hasImage) Color.Transparent else AppColors.Pebble, cardShape)
            .pressable(
                onClick = onEventClick,
                enabled = slot.eventId != null || (!slot.isCustom && slot.name.length > 2),
            ),
    ) {
        if (hasImage) {
            AsyncImage(
                model = slot.imageUrl,
                contentDescription = slot.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                    startY = 20f,
                )
            ))
        }

        val textColor = if (hasImage) AppColors.White else AppColors.Ink
        val subColor = if (hasImage) AppColors.White.copy(alpha = 0.85f) else AppColors.Ink3

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .then(if (hasImage) Modifier else Modifier),
            verticalArrangement = if (hasImage) Arrangement.Bottom else Arrangement.Center,
        ) {
            // Name + badges
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stripEmoji(slot.name),
                    modifier = Modifier.weight(1f, fill = false),
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = textColor),
                    maxLines = 1,
                )
                if (slot.isFree && !slot.isCustom) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Free",
                        modifier = Modifier
                            .background(Color(0xFFE8F5E9).copy(alpha = if (hasImage) 0.9f else 1f), AppShapes.Pill)
                            .border(1.dp, Color(0xFFA5D6A7), AppShapes.Pill)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 8.sp, color = Color(0xFF2E7D32)),
                    )
                }
                val isRestaurantPrice = slot.price.isNotBlank() && slot.price.all { it == '$' || it == ' ' }
                if (isRestaurantPrice) {
                    Spacer(Modifier.width(4.dp))
                    Text(slot.price.trim(), style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = textColor))
                } else if (slot.price.isNotBlank() && !slot.isFree) {
                    Spacer(Modifier.width(6.dp))
                    Text(slot.price, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 11.sp, color = subColor))
                }
            }

            // Detail
            val displayDetail = if (slot.price.isNotBlank() && slot.price.all { it == '$' || it == ' ' }) {
                stripEmoji(slot.detail).replace(Regex("\\$+\\s*[·\\-]?\\s*"), "").replace(Regex("^\\s*[·\\-]\\s*"), "").trim()
            } else { stripEmoji(slot.detail) }
            Text(displayDetail, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = subColor), maxLines = 1)

            // Inline actions — rating + reserve, or date/time when there's no
            // rating. This row is always rendered so cards without a star
            // rating keep the same height as cards that have one.
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (slot.venueRating != null) {
                    Text("★ ${slot.venueRating}", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = AppColors.Sun))
                } else {
                    val dateTime = listOf(dateLabel.trim(), slot.time.trim()).filter { it.isNotBlank() }.joinToString(" · ")
                    Text(dateTime, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = subColor))
                }
                if (slot.reservationUrl != null) {
                    Text(
                        "Reserve",
                        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            inAppBrowser.open(slot.venueWebsite?.takeIf { it.isNotBlank() } ?: slot.reservationUrl)
                        },
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = if (hasImage) AppColors.White else AppColors.Coral),
                    )
                }
            }
        }
    }
}

// ── Progress Indicator ──

@Composable
private fun ProgressIndicator(totalDays: Int, approvedDays: Int, currentDay: Int, dayNames: List<String> = emptyList()) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until totalDays) {
            val isDone = i < approvedDays
            val isActive = i == currentDay
            val dotColor = when {
                isDone -> AppColors.Sage
                isActive -> AppColors.Coral
                else -> AppColors.Pebble
            }
            val textColor = when {
                isDone -> AppColors.SageDk
                isActive -> AppColors.Coral
                else -> AppColors.Ink3
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(
                    dayNames.getOrElse(i) { "Day ${i + 1}" },
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = textColor),
                )
            }

            if (i < totalDays - 1) {
                Box(
                    Modifier.weight(1f).height(2.dp).padding(horizontal = 6.dp)
                        .background(if (isDone) AppColors.Sage else AppColors.Mist),
                )
            }
        }
    }
}

// ── Summary Card ──

@Composable
private fun SummaryCard(state: ChatUiState) {
    val cardShape = RoundedCornerShape(18.dp)
    val totalEvents = state.days.sumOf { it.slots.size }
    val freeEvents = state.days.sumOf { day -> day.slots.count { it.isFree } }

    Box(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().matchParentSize().offset(y = 5.dp).background(AppColors.Pebble, cardShape))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.White, cardShape)
                .border(2.dp, AppColors.Pebble, cardShape)
                .clip(cardShape),
        ) {
            // Dark header
            Box(
                modifier = Modifier.fillMaxWidth().background(AppColors.Ink).padding(16.dp),
            ) {
                // Glow circle
                Box(
                    Modifier.size(120.dp).offset(x = 60.dp, y = (-30).dp)
                        .align(Alignment.TopEnd)
                        .background(AppColors.Coral.copy(alpha = 0.2f), CircleShape),
                )
                Column {
                    Text(
                        state.planTitle,
                        style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.White),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val dates = "${state.days.size} day${if (state.days.size != 1) "s" else ""}"
                        val group = ""
                        SummaryChip(dates)
                        if (group.isNotEmpty()) SummaryChip(group)
                        SummaryChip("Boston")
                    }
                }
            }

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth().border(width = 1.5.dp, color = AppColors.Mist),
            ) {
                StatCell("${state.days.size}", "Days", Modifier.weight(1f))
                Box(Modifier.width(1.5.dp).height(50.dp).background(AppColors.Mist))
                StatCell("$totalEvents", "Events", Modifier.weight(1f))
                Box(Modifier.width(1.5.dp).height(50.dp).background(AppColors.Mist))
                StatCell("$freeEvents", "Free", Modifier.weight(1f))
            }

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    label = "Share",
                    isPrimary = false,
                    modifier = Modifier.weight(1f),
                    onClick = {},
                )
                ActionButton(
                    label = "Save to Plans",
                    isPrimary = true,
                    modifier = Modifier.weight(1f),
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun SummaryChip(text: String) {
    Text(
        text,
        modifier = Modifier
            .background(AppColors.White.copy(alpha = 0.12f), AppShapes.Pill)
            .border(1.dp, AppColors.White.copy(alpha = 0.2f), AppShapes.Pill)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = AppColors.White.copy(alpha = 0.85f)),
    )
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 20.sp, color = AppColors.Ink))
        Spacer(Modifier.height(2.dp))
        Text(label, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = AppColors.Ink3))
    }
}

@Composable
private fun ActionButton(label: String, isPrimary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(modifier = modifier) {
        Box(
            Modifier.fillMaxWidth().matchParentSize()
                .offset(y = if (isPressed) 0.dp else 3.dp)
                .background(
                    if (isPrimary) AppColors.CoralDk else AppColors.Pebble,
                    RoundedCornerShape(13.dp),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(
                    if (isPrimary) AppColors.Coral else AppColors.White,
                    RoundedCornerShape(13.dp),
                )
                .then(
                    if (!isPrimary) Modifier.border(2.dp, AppColors.Pebble, RoundedCornerShape(13.dp))
                    else Modifier
                )
                .clip(RoundedCornerShape(13.dp))
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = TextStyle(
                    fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
                    color = if (isPrimary) AppColors.White else AppColors.Ink2,
                ),
            )
        }
    }
}




// ── Quick Reply Chips ──

@Composable
private fun QuickReplyBar(replies: List<QuickReply>, onReply: (QuickReply) -> Unit) {
    // Tall plan cards used to butt right up against the chip strip below
    // them — the eye read it as "chips covering the bottom of the card".
    // A 1px divider plus the bar's own Snow background gives the strip a
    // clear top edge that visually separates it from the LazyColumn's
    // last item, even when that item is being clipped by a tight viewport.
    Column(modifier = Modifier.fillMaxWidth().background(AppColors.Snow)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.Pebble.copy(alpha = 0.4f)))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.padding(vertical = 10.dp),
        ) {
            items(replies) { reply ->
                QuickReplyChip(reply = reply, onClick = { onReply(reply) })
            }
        }
    }
}

@Composable
private fun QuickReplyChip(reply: QuickReply, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val bgColor = if (reply.isGreen) AppColors.Sage else AppColors.Snow
    val borderColor = if (reply.isGreen) AppColors.Sage else AppColors.Pebble
    val textColor = if (reply.isGreen) AppColors.White else AppColors.Ink

    Box(
        Modifier
            .background(bgColor, RoundedCornerShape(20.dp))
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            reply.label, maxLines = 1,
            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = textColor),
        )
    }
}

// ── Input Bar ──

@Composable
private fun ChatInputBar(enabled: Boolean, placeholder: String, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    // Drop focus on send so the keyboard collapses and the user can read Bo's
    // reply without manually dismissing.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    fun submit() {
        if (text.isBlank()) return
        onSend(text)
        text = ""
        focusManager.clearFocus()
    }

    // Top divider
    Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.Pebble.copy(alpha = 0.4f)))

    Row(
        modifier = Modifier.fillMaxWidth().background(AppColors.Snow)
            .padding(horizontal = 14.dp, vertical = 10.dp).padding(bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier.weight(1f).heightIn(min = 42.dp)
                .background(AppColors.White, RoundedCornerShape(22.dp))
                .border(1.dp, AppColors.Pebble.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(placeholder, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = AppColors.Ink3))
            }
            if (enabled) {
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = AppColors.Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    // Hide the caret when the field is empty — on iOS, heavy
                    // recompositions during Bo's streaming can momentarily
                    // mis-place the caret (it "jumps" outside the field).
                    // Showing the caret only once the user has typed avoids
                    // the visual artifact entirely without breaking input.
                    cursorBrush = if (text.isEmpty()) SolidColor(Color.Transparent) else SolidColor(AppColors.Coral),
                    singleLine = false, maxLines = 3,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        val canSend = enabled && text.isNotBlank()
        Box(
            modifier = Modifier.size(42.dp).graphicsLayer { alpha = if (canSend) 1f else 0.3f }
                .background(AppColors.Ink, RoundedCornerShape(21.dp))
                .clip(RoundedCornerShape(21.dp))
                .then(if (canSend) Modifier.clickable { submit() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Outlined.Send, "Send", Modifier.size(20.dp), tint = AppColors.White)
        }
    }
}

/** Detect if an event is recurring based on keywords in the name or detail */
private fun detectRecurrenceTag(name: String, detail: String): String? {
    val text = "$name $detail".lowercase()
    if (text.contains("daily") || text.contains("every day")) return "Daily"
    if (text.contains("weekly") || text.contains("every week") ||
        text.contains("every monday") || text.contains("every tuesday") ||
        text.contains("every wednesday") || text.contains("every thursday") ||
        text.contains("every friday") || text.contains("every saturday") ||
        text.contains("every sunday")) return "Weekly"
    if (text.contains("monthly") || text.contains("every month") ||
        text.contains("first friday") || text.contains("first saturday")) return "Monthly"
    return null
}

/** Strip markdown formatting (bold, italic, bullets) that renders as raw text */
// ═══════════════════════════════════════
// ── Also Happening Row ──
// ═══════════════════════════════════════

@Composable
private fun AlsoHappeningRow(
    events: List<Event>,
    onEventTap: (Event) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "ALSO HAPPENING",
            modifier = Modifier.padding(start = 14.dp, bottom = 8.dp),
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                color = AppColors.Ink3,
                letterSpacing = 1.sp,
            ),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(events, key = { _, e -> e.id }) { index, event ->
                AlsoHappeningCard(event = event, onClick = {
                    live.localapp.local.observability.Analytics.track(
                        live.localapp.local.observability.AnalyticsEvent.boResultEventTapped(
                            eventId = event.id,
                            positionInResponse = index,
                        )
                    )
                    onEventTap(event)
                })
            }
        }
    }
}

@Composable
private fun AlsoHappeningCard(
    event: Event,
    onClick: () -> Unit,
) {
    val timeDisplay = event.startAt?.let { iso ->
        try {
            val instant = kotlinx.datetime.Instant.parse(iso)
            val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
            val hour = local.hour
            val min = local.minute
            val amPm = if (hour < 12) "am" else "pm"
            val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
            if (min == 0) "${displayHour}${amPm}" else "${displayHour}:${min.toString().padStart(2, '0')}${amPm}"
        } catch (_: Exception) { "" }
    } ?: ""

    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.White)
            .border(1.5.dp, AppColors.Mist, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        // Image or gradient placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.linearGradient(
                        colors = categoryGradientColors(event.subcategory ?: event.category),
                    )
                ),
        ) {
            // Price badge
            if (event.priceDisplay.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (event.isFree) AppColors.Sage else AppColors.Ink.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        event.priceDisplay,
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 9.sp,
                            color = AppColors.White,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            event.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = AppColors.Ink,
                lineHeight = 15.sp,
            ),
        )

        Spacer(Modifier.height(3.dp))

        Text(
            "${event.venueName ?: ""} · $timeDisplay",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                color = AppColors.Ink3,
            ),
        )
    }
}

private fun categoryGradientColors(category: String): List<Color> {
    return when (category.lowercase()) {
        "music", "live-music", "concerts" -> listOf(AppColors.BgPlumStart, AppColors.BgPlumEnd)
        "comedy" -> listOf(AppColors.BgSunStart, AppColors.BgSunEnd)
        "sports" -> listOf(AppColors.BgSageStart, AppColors.BgSageEnd)
        "arts", "performing-arts", "theater" -> listOf(AppColors.BgSkyStart, AppColors.BgSkyEnd)
        "food", "food-drink" -> listOf(AppColors.BgCoralStart, AppColors.BgCoralEnd)
        else -> listOf(AppColors.BgSkyStart, AppColors.BgSkyEnd)
    }
}

@Composable
private fun AuthRequiredOverlay(
    onLogin: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AppColors.White)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}, // consume clicks
                )
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Sign in to save plans",
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = AppColors.Ink,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Create an account or sign in to save your plans and access them anywhere.",
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = AppColors.Ink3,
                    lineHeight = 20.sp,
                ),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.Coral)
                    .clickable(onClick = onLogin),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Sign in",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = AppColors.White,
                    ),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Maybe later",
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = AppColors.Ink3,
                ),
            )
        }
    }
}

/** Renders chat text with bold, formatted bullets, and visual hierarchy.
 *  Designed to be friendly to STREAMING text: an open `**` with no closing
 *  delimiter yet renders the rest as bold immediately (so the user sees
 *  bold formatting live, instead of literal asterisks until Bo finishes).
 *  When the closing `**` arrives, the regular pairing logic takes over. */
@Composable
private fun RichChatText(text: String, textColor: Color) {
    val annotated = remember(text, textColor) { boldAnnotatedString(text, textColor) }

    Text(
        text = annotated,
        style = TextStyle(
            fontFamily = AppFonts.Body,
            lineHeight = 21.sp,
        ),
    )
}

/**
 * Parses Bo's chat text (`**bold**`, markdown headers, mixed bullets) into a
 * styled [AnnotatedString]. Shared by [RichChatText] and the markdown-aware
 * typewriter reveal so that bold formatting shows LIVE during the reveal
 * (revealing a prefix via [AnnotatedString.subSequence] preserves styling)
 * instead of leaking literal `**` until the message completes.
 *
 * Streaming-friendly: an open `**` with no closing delimiter yet renders the
 * rest as bold immediately; when the closing `**` arrives, the pairing logic
 * tightens the bold span to the actual range.
 */
internal fun boldAnnotatedString(text: String, textColor: Color): AnnotatedString {
    // Pre-normalize: strip markdown headers and unify bullet prefixes
    // (`- foo`, `* foo`, `• foo` → `• foo`). Also strip surrogate-pair emoji
    // that iOS Compose can't render — Bo is told via prompt not to use them
    // but Claude slips occasionally, and a broken glyph reads worse than a
    // silent strip.
    val cleaned = stripEmoji(text)
        .replace(Regex("^#+\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s*[-*•]\\s+", RegexOption.MULTILINE), "• ")

    return buildAnnotatedString {
        var remaining = cleaned
        while (remaining.isNotEmpty()) {
            val boldStart = remaining.indexOf("**")
            if (boldStart < 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, color = textColor)) {
                    append(remaining)
                }
                break
            }
            if (boldStart > 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, color = textColor)) {
                    append(remaining.substring(0, boldStart))
                }
            }
            remaining = remaining.substring(boldStart + 2)
            val boldEnd = remaining.indexOf("**")
            if (boldEnd < 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Black, fontSize = 15.sp, color = textColor)) {
                    append(remaining)
                }
                break
            }
            withStyle(SpanStyle(fontWeight = FontWeight.Black, fontSize = 15.sp, color = textColor)) {
                append(remaining.substring(0, boldEnd))
            }
            remaining = remaining.substring(boldEnd + 2)
        }
    }
}

private fun cleanMarkdown(text: String): String {
    return text
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")  // **bold** → bold
        .replace(Regex("\\*(.+?)\\*"), "$1")          // *italic* → italic
        .replace(Regex("__(.+?)__"), "$1")             // __bold__ → bold
        .replace(Regex("_(.+?)_"), "$1")               // _italic_ → italic
        .replace(Regex("^#+\\s+", RegexOption.MULTILINE), "")  // headers
        // Keep bullet points but normalize to •
        .replace(Regex("^\\s*[-*]\\s+", RegexOption.MULTILINE), "• ")
        .trim()
}

/**
 * Strip Unicode characters that iOS Compose can't render — modern emoji
 * (code points above U+FFFF, encoded as surrogate pairs) fall back to
 * the system's missing-glyph box. The previous version of this function
 * filtered by single-Char code point and missed surrogates entirely,
 * which is why Bo's "Hey! 🙂‍↕️" leaked through. This version walks the
 * string by Char, drops any high-surrogate + low-surrogate pair, and
 * also drops the trailing combiners (ZWJ U+200D, variation selectors
 * U+FE00..FE0F) that decorate a stripped emoji — otherwise they leave
 * dangling glyph-boxes of their own.
 */
private fun stripEmoji(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    var skippedRecent = false
    while (i < text.length) {
        val ch = text[i]
        if (ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            i += 2
            skippedRecent = true
            continue
        }
        val cp = ch.code
        val isCombiner = cp == 0x200D || cp in 0xFE00..0xFE0F
        if (isCombiner && skippedRecent) {
            i++
            continue
        }
        sb.append(ch)
        skippedRecent = false
        i++
    }
    return sb.toString().replace(Regex("\\s{2,}"), " ").trim()
}

/** Extract a numeric price from strings like "$25", "From $15", "$$" */
private fun extractPriceValue(price: String): Double? {
    if (price.isBlank()) return null
    if (price.trim().all { it == '$' || it == ' ' }) {
        return when (price.trim().count { it == '$' }) {
            1 -> 10.0; 2 -> 25.0; 3 -> 45.0; 4 -> 80.0; 5 -> 120.0
            else -> null
        }
    }
    val match = Regex("\\$?(\\d+\\.?\\d*)").find(price)
    return match?.groupValues?.get(1)?.toDoubleOrNull()
}

// ── Plan Success Overlay ──

@Composable
private fun PlanSuccessOverlay(
    planTitle: String,
    days: List<ItineraryDay>,
    onViewPlans: () -> Unit,
    onInviteFriends: () -> Unit,
) {
    // Matches iOS PlanCelebrationOverlay.dateSubtitle: full day label(s) joined
    // with an en dash, plus the total stop count.
    val subtitle = remember(days) {
        if (days.isEmpty()) ""
        else {
            val totalStops = days.sumOf { it.slots.size }
            if (days.size == 1) {
                val stopWord = if (totalStops == 1) "stop" else "stops"
                "${days.first().label} · $totalStops $stopWord"
            } else {
                "${days.first().label} – ${days.last().label} · $totalStops stops"
            }
        }
    }

    // Hero animations
    val checkScale = remember { Animatable(0f) }
    val titleScale = remember { Animatable(0f) }
    val subtitleAlpha = remember { Animatable(0f) }

    // Ring animations
    val ringAScale = remember { Animatable(0.2f) }
    val ringAAlpha = remember { Animatable(0f) }
    val ringBScale = remember { Animatable(0.2f) }
    val ringBAlpha = remember { Animatable(0f) }

    // Card animation
    val cardOffsetY = remember { Animatable(80f) }
    val cardAlpha = remember { Animatable(0f) }

    // CTA animations — staggered from bottom
    val shareOffsetY = remember { Animatable(60f) }
    val shareAlpha = remember { Animatable(0f) }
    val plansOffsetY = remember { Animatable(60f) }
    val plansAlpha = remember { Animatable(0f) }

    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        // Rings
        launch {
            delay(300)
            ringAAlpha.animateTo(0.6f, tween(100))
            launch { ringAScale.animateTo(3.2f, tween(800)) }
            ringAAlpha.animateTo(0f, tween(700))
        }
        launch {
            delay(450)
            ringBAlpha.animateTo(0.5f, tween(100))
            launch { ringBScale.animateTo(2.6f, tween(800)) }
            ringBAlpha.animateTo(0f, tween(700))
        }
        // Check — haptic 1
        launch {
            delay(400)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            checkScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
        // Title — haptic 2
        launch {
            delay(850)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            titleScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
        // Subtitle — haptic 3
        launch {
            delay(1100)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            subtitleAlpha.animateTo(1f, tween(400))
        }
        // Plan card — haptic 4
        launch {
            delay(1200)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            launch { cardOffsetY.animateTo(0f, tween(500, easing = EaseOutCubic)) }
            cardAlpha.animateTo(1f, tween(500))
        }
        // CTAs — staggered: Share, View Plans, Home. Haptic 5 on the group.
        launch {
            delay(1600)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            launch { shareOffsetY.animateTo(0f, tween(400, easing = EaseOutCubic)) }
            shareAlpha.animateTo(1f, tween(400))
        }
        launch {
            delay(1750)
            launch { plansOffsetY.animateTo(0f, tween(400, easing = EaseOutCubic)) }
            plansAlpha.animateTo(1f, tween(400))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFFF6B6B),
                        Color(0xFFFF4F4F),
                        Color(0xFFE8433E),
                    ),
                )
            ),
    ) {
        // Background decorative circles
        Box(
            modifier = Modifier
                .size(200.dp)
                .offset(x = 100.dp, y = (-60).dp)
                .background(Color.White.copy(alpha = 0.08f), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(240.dp)
                .offset(x = (-80).dp, y = 400.dp)
                .background(Color.Black.copy(alpha = 0.06f), CircleShape),
        )

        // Hero + scrollable card — leaves room for CTAs at bottom
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 180.dp)
                .clip(RoundedCornerShape(0.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))

            // Check circle with expanding rings
            Box(
                modifier = Modifier.size(110.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .graphicsLayer {
                            scaleX = ringAScale.value
                            scaleY = ringAScale.value
                            alpha = ringAAlpha.value
                        }
                        .border(3.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .graphicsLayer {
                            scaleX = ringBScale.value
                            scaleY = ringBScale.value
                            alpha = ringBAlpha.value
                        }
                        .border(3.dp, Color(0xFFFFB830).copy(alpha = 0.7f), CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .graphicsLayer {
                            scaleX = checkScale.value
                            scaleY = checkScale.value
                        }
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = "Done",
                        modifier = Modifier.size(40.dp),
                        tint = AppColors.Coral,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "Plan On!",
                style = TextStyle(
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    letterSpacing = (-0.5).sp,
                    fontFamily = AppFonts.Heading,
                ),
                modifier = Modifier.graphicsLayer {
                    scaleX = titleScale.value
                    scaleY = titleScale.value
                },
            )

            Spacer(Modifier.height(6.dp))

            Text(
                subtitle.uppercase(),
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.75f),
                    letterSpacing = 0.5.sp,
                    fontFamily = AppFonts.Heading,
                ),
                modifier = Modifier.graphicsLayer { alpha = subtitleAlpha.value },
            )

            Spacer(Modifier.height(16.dp))

            // Plan card(s) — scrollable with max height
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
                    .graphicsLayer {
                        translationY = cardOffsetY.value
                        alpha = cardAlpha.value
                    },
            ) {
                if (days.size > 1) {
                    MultiDayPlanCard(
                        days = days,
                        showSaveButton = false,
                    )
                } else {
                    DayCard(
                        day = days.firstOrNull() ?: ItineraryDay(label = "", shortName = ""),
                        dayIndex = 0,
                        totalDays = 1,
                        showSaveButton = false,
                    )
                }
            }
        }

        // CTAs — fixed at bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
                // Share / Invite friends
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationY = shareOffsetY.value
                            alpha = shareAlpha.value
                        }
                        .background(
                            Brush.linearGradient(listOf(Color.White, Color(0xFFF5F5F5))),
                            RoundedCornerShape(14.dp),
                        )
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onInviteFriends() }
                        .padding(vertical = 15.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Icon(
                            Icons.Outlined.IosShare,
                            contentDescription = "Share",
                            modifier = Modifier.size(17.dp),
                            tint = AppColors.Coral,
                        )
                        Text(
                            "Share Plan",
                            style = TextStyle(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = AppColors.Coral,
                                fontFamily = AppFonts.Heading,
                            ),
                        )
                    }
                }

                // View Plan — secondary text link beneath Share (iOS parity:
                // no Home CTA; View Plan is a demoted text button).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationY = plansOffsetY.value
                            alpha = plansAlpha.value
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onViewPlans() }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "View Plan",
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White.copy(alpha = 0.9f),
                            fontFamily = AppFonts.Heading,
                        ),
                    )
                }
            }
        }
    }


// ── Phase 6 — structured-response inline renderer ──

/**
 * Routes any [BoStructuredResponse] subtype to its matching component.
 * Inline in the message stream right after Bo's prose intro line.
 */
@Composable
private fun StructuredResponseRenderer(
    messageId: String,
    response: live.localapp.local.domain.model.BoStructuredResponse,
    hydratedEvents: Map<String, live.localapp.local.domain.model.Event>,
    tray: List<live.localapp.local.domain.model.DraftTrayEvent>,
    onAddToTray: (live.localapp.local.domain.model.Event) -> Unit,
    onOpenEvent: (live.localapp.local.domain.model.Event) -> Unit,
    onFooterAction: (live.localapp.local.domain.model.BoStructuredResponse.Suggestions.Action) -> Unit,
    onScopeChip: (String) -> Unit,
    isDismissed: (String) -> Boolean = { false },
    onDismiss: (String) -> Unit = {},
    isGroupLoading: (String) -> Boolean = { false },
    isGroupExhausted: (String) -> Boolean = { false },
    onFindMore: (dayKey: String?, categoryKey: String?) -> Unit = { _, _ -> },
) {
    val trayIds = remember(tray) { tray.map { it.eventId }.toSet() }
    when (response) {
        is live.localapp.local.domain.model.BoStructuredResponse.Suggestions ->
            SuggestionTray(
                messageId = messageId,
                suggestions = response,
                eventsById = hydratedEvents,
                onAddToTray = onAddToTray,
                onOpenEvent = onOpenEvent,
                onFooterAction = onFooterAction,
                isInTray = { id -> id in trayIds },
                isDismissed = isDismissed,
                onDismiss = onDismiss,
                isGroupLoading = isGroupLoading,
                isGroupExhausted = isGroupExhausted,
                onFindMore = onFindMore,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        is live.localapp.local.domain.model.BoStructuredResponse.EventBrief -> {
            val ev = hydratedEvents[response.eventId]
            if (ev != null) {
                EventBriefCard(
                    event = ev,
                    answerLine = response.answerLine,
                    isInTray = response.eventId in trayIds,
                    onAddToTray = { onAddToTray(ev) },
                    onOpenEvent = { onOpenEvent(ev) },
                )
            }
        }
        is live.localapp.local.domain.model.BoStructuredResponse.VenueBrief -> {
            VenueBriefCard(
                response = response,
                hydratedEvents = hydratedEvents,
                trayIds = trayIds,
                onAddToTray = onAddToTray,
                onOpenEvent = onOpenEvent,
            )
        }
        is live.localapp.local.domain.model.BoStructuredResponse.QuickFact -> {
            QuickFactPill(
                response = response,
                hydratedEvents = hydratedEvents,
                onOpenEvent = onOpenEvent,
            )
        }
        is live.localapp.local.domain.model.BoStructuredResponse.ScopeRedirect -> {
            ScopeRedirectCard(response = response, onChipTap = onScopeChip)
        }
    }
}

private fun footerActionToIntent(action: live.localapp.local.domain.model.BoStructuredResponse.Suggestions.Action): String = when (action) {
    live.localapp.local.domain.model.BoStructuredResponse.Suggestions.Action.SEARCH_WIDER ->
        "Cast a wider net, Bo"
    live.localapp.local.domain.model.BoStructuredResponse.Suggestions.Action.DIFFERENT_CATEGORY ->
        "Try something different"
    live.localapp.local.domain.model.BoStructuredResponse.Suggestions.Action.LATE_NIGHT ->
        "What's open late night?"
    live.localapp.local.domain.model.BoStructuredResponse.Suggestions.Action.DIFFERENT_DAY ->
        "How about another day?"
    live.localapp.local.domain.model.BoStructuredResponse.Suggestions.Action.MORE_RESULTS ->
        "Show me more results, Bo!"
    live.localapp.local.domain.model.BoStructuredResponse.Suggestions.Action.BUILD_FROM_WEB ->
        BUILD_FROM_WEB_INTENT
}

@Composable
private fun EventBriefCard(
    event: live.localapp.local.domain.model.Event,
    answerLine: String,
    isInTray: Boolean,
    onAddToTray: () -> Unit,
    onOpenEvent: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenEvent,
            )
            .padding(14.dp),
    ) {
        Text(event.title, style = TextStyle(
            fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black,
            fontSize = 16.sp, color = AppColors.Ink, lineHeight = 19.sp,
        ))
        Spacer(Modifier.height(4.dp))
        val meta = listOfNotNull(
            event.venueName, event.dateDisplay.takeIf { it.isNotBlank() },
            if (event.isFree) "Free" else event.priceDisplay.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(meta, style = TextStyle(
                fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp, color = AppColors.Ink3,
            ))
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.SkyLt)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(answerLine, style = TextStyle(
                fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, color = AppColors.Sky,
            ))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(50))
                    .background(if (isInTray) AppColors.Sage else AppColors.Coral)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { if (!isInTray) onAddToTray() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isInTray) "✓ In your plan" else "+ Add to plan",
                    style = TextStyle(
                        fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp, color = Color.White,
                    ),
                )
            }
        }
    }
}

@Composable
private fun VenueBriefCard(
    response: live.localapp.local.domain.model.BoStructuredResponse.VenueBrief,
    hydratedEvents: Map<String, live.localapp.local.domain.model.Event>,
    trayIds: Set<String>,
    onAddToTray: (live.localapp.local.domain.model.Event) -> Unit,
    onOpenEvent: (live.localapp.local.domain.model.Event) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text(response.venueName, style = TextStyle(
            fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black,
            fontSize = 16.sp, color = AppColors.Ink,
        ))
        response.answerLine?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = TextStyle(
                fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp, color = AppColors.Ink3,
            ))
        }
        if (response.upcomingEventIds.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("Upcoming", style = TextStyle(
                fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp, color = AppColors.Ink3, letterSpacing = 0.6.sp,
            ))
            Spacer(Modifier.height(4.dp))
            response.upcomingEventIds.forEach { id ->
                val ev = hydratedEvents[id] ?: return@forEach
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onOpenEvent(ev) },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ev.title, style = TextStyle(
                            fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold,
                            fontSize = 13.sp, color = AppColors.Ink,
                        ), maxLines = 1)
                        Text(ev.dateDisplay, style = TextStyle(
                            fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp, color = AppColors.Ink3,
                        ))
                    }
                    val inTray = id in trayIds
                    Box(
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(50))
                            .background(if (inTray) AppColors.Sage else AppColors.Coral)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { if (!inTray) onAddToTray(ev) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (inTray) "✓" else "+",
                            style = TextStyle(
                                fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black,
                                fontSize = 14.sp, color = Color.White,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickFactPill(
    response: live.localapp.local.domain.model.BoStructuredResponse.QuickFact,
    hydratedEvents: Map<String, live.localapp.local.domain.model.Event>,
    onOpenEvent: (live.localapp.local.domain.model.Event) -> Unit,
) {
    val ev = response.ctaEventId?.let { hydratedEvents[it] }
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(AppColors.SkyLt)
            .border(1.dp, AppColors.Sky, RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { ev?.let(onOpenEvent) },
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(response.text, style = TextStyle(
            fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold,
            fontSize = 13.sp, color = AppColors.Sky,
        ), modifier = Modifier.weight(1f, fill = false))
        if (ev != null) {
            Spacer(Modifier.width(6.dp))
            Text("→", style = TextStyle(
                fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black,
                fontSize = 14.sp, color = AppColors.Sky,
            ))
        }
    }
}

@Composable
private fun ScopeRedirectCard(
    response: live.localapp.local.domain.model.BoStructuredResponse.ScopeRedirect,
    onChipTap: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppColors.SkyLt)
            .border(1.5.dp, AppColors.Sky, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Text(response.explanation, style = TextStyle(
            fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp, color = AppColors.Ink, lineHeight = 18.sp,
        ))
        if (response.redirectChips.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            response.redirectChips.forEach { chip ->
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .border(1.dp, AppColors.Sky, RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                live.localapp.local.observability.Analytics.track(
                                    live.localapp.local.observability.AnalyticsEvent.boChipTapped(
                                        chipLabelHash = chip.label.hashCode().toString()
                                    )
                                )
                                onChipTap(chip.intent)
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(chip.label, style = TextStyle(
                        fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold,
                        fontSize = 12.sp, color = AppColors.Sky,
                    ))
                }
            }
        }
    }
}
