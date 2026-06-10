package live.localapp.local.ui.home

import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import live.localapp.local.ui.common.pressable
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import live.localapp.local.ui.common.CircleShape
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.LocalDining
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Nightlife
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import coil3.compose.AsyncImage
import local.composeapp.generated.resources.Res
import local.composeapp.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import live.localapp.local.ui.common.ProximityFilterChip
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.Config
import org.koin.mp.KoinPlatform
import live.localapp.local.domain.model.CarouselSection
import live.localapp.local.domain.repository.CategoryInfo
import live.localapp.local.domain.model.Event
import live.localapp.local.ui.common.AppHeader
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppShapes
import live.localapp.local.ui.theme.AppType

// ── Title case helper ──

private val minorWords = setOf("vs", "vs.", "and", "the", "of", "in", "at", "to", "for", "a", "an", "on", "or")

private fun String.toTitleCase(): String {
    if (isBlank()) return this
    return split(" ").mapIndexed { i, word ->
        // Strip punctuation so "U.S.A." and "AM-PM" still register as acronyms.
        val letters = word.filter { it.isLetter() }
        when {
            // Short all-caps token = acronym → preserve as-is. Catches MIT, NHL,
            // USA, NBA, MLB, WNBA, AAPI, etc. Limit to length 5 so we don't
            // accidentally preserve all-caps screaming titles like "FREE PIZZA".
            letters.length in 2..5 && letters.all { it.isUpperCase() } -> word
            // Already mixed case (has both upper and lower) → preserve. Catches
            // "iPhone", "MacBook", "TikTok", brand names.
            word.any { it.isUpperCase() } && word.any { it.isLowerCase() } -> word
            // Minor word in a non-leading position → lowercase.
            i > 0 && word.lowercase() in minorWords -> word.lowercase()
            // Default: capitalize first letter, lowercase the rest.
            else -> word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }.joinToString(" ")
}

/**
 * Whole days remaining until [trialEndsAt] (ISO-8601), rounded up so any partial
 * final day still reads as "1 day left". Returns null if the date is missing,
 * unparseable, or already in the past (trial over → no countdown).
 */
private fun trialDaysRemaining(trialEndsAt: String?): Int? {
    if (trialEndsAt.isNullOrBlank()) return null
    val endMs = try {
        kotlinx.datetime.Instant.parse(trialEndsAt).toEpochMilliseconds()
    } catch (_: Exception) {
        return null
    }
    val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
    if (endMs <= nowMs) return null
    val secondsLeft = (endMs - nowMs) / 1000.0
    return kotlin.math.ceil(secondsLeft / 86_400.0).toInt()
}

/** Today's local calendar date as an ISO string (yyyy-MM-dd). */
private fun todayLocalDateIso(): String {
    val nowMs = kotlin.time.Clock.System.now().toEpochMilliseconds()
    return kotlinx.datetime.Instant.fromEpochMilliseconds(nowMs)
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        .date
        .toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onEventClick: (Event, CardTransitionInfo?) -> Unit = { _, _ -> }, onConciergeClick: () -> Unit = {}, onConciergeSend: (String) -> Unit = { onConciergeClick() }, onProfileClick: () -> Unit = {}, onLocationClick: () -> Unit = {}, onFavorite: ((Event) -> Unit)? = null, showUpdateBanner: Boolean = false, onDismissUpdateBanner: () -> Unit = {}, onUpgrade: () -> Unit = {}, onSeeAllVenue: (venueName: String, venueCity: String?) -> Unit = { _, _ -> }, onSeeAllEvents: (category: String?, dateFilter: live.localapp.local.ui.places.EventDateFilter?) -> Unit = { _, _ -> }) {
    val state by viewModel.uiState.collectAsState()
    var previouslyLoading by remember { mutableStateOf(true) }
    val haptic = LocalHapticFeedback.current

    // Haptic when data arrives
    LaunchedEffect(state.isLoading, state.carousels) {
        if (previouslyLoading && !state.isLoading && state.carousels.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            live.localapp.local.observability.Analytics.track(
                live.localapp.local.observability.AnalyticsEvent.feedViewed(
                    feed = "for_you",
                    resultCount = state.carousels.size,
                )
            )
        }
        previouslyLoading = state.isLoading
    }

    // Radius-filtered carousel list — memoized so it's not rebuilt every recomposition.
    // Also strips the "Top X This Week" carousel on the Experiences tab — there's no
    // sensible "Top Experiences" cohort, the rolled-up title reads awkwardly there.
    val displayCarousels = remember(state.carousels, state.radiusFilter, state.selectedCategory) {
        val radius = state.radiusFilter
        val radiusFiltered = if (radius == null) {
            state.carousels
        } else {
            state.carousels
                .map { section -> section.copy(events = section.events.filter { (it.distanceMiles ?: 999.0) <= radius }) }
                .filter { it.events.isNotEmpty() }
        }
        if (state.selectedCategory.equals("experiences", ignoreCase = true)) {
            radiusFiltered.filterNot { section ->
                section.filterType.equals("top_weekly", ignoreCase = true)
            }
        } else {
            radiusFiltered
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Snow),
    ) {
        AppHeader(
                title = "local",
                locationName = state.locationName,
                radiusFilter = state.radiusFilter,
                streakDays = state.streakDays,
                userInitial = state.userName.firstOrNull()?.uppercase() ?: "",
                onProfileClick = onProfileClick,
                onLocationClick = onLocationClick,
                onLogoClick = {},
                isUnsupportedState = state.isUnsupportedState,
            )

        if (showUpdateBanner) {
            live.localapp.local.ui.common.UpdateAvailableBanner(
                onUpdate = { live.localapp.local.platform.openAppStoreListing() },
                onDismiss = onDismissUpdateBanner,
            )
        }

        // Local Pro trial countdown. Shows with < 14 days left, only
        // for non-paid users still in trial. Dismissible at 7–14 days, but
        // dismissal only silences it for that calendar day — it returns each
        // new day. Under 7 days it's non-dismissible and always shows. Tapping
        // it opens the paywall.
        run {
            val trialGate = remember { live.localapp.local.ui.chat.PremiumGate() }
            // HomeViewModel is a Koin singleton that loads trialEndsAt once, and
            // the days-left value is keyed below on that (unchanging) end-date
            // string — so without a resume nudge the countdown stays frozen on
            // whatever it read at process start. Bump a tick on every ON_RESUME so
            // trialDaysRemaining() re-evaluates against the current date.
            var trialResumeTick by remember { mutableStateOf(0) }
            val trialLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            androidx.compose.runtime.DisposableEffect(trialLifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) trialResumeTick++
                }
                trialLifecycleOwner.lifecycle.addObserver(observer)
                onDispose { trialLifecycleOwner.lifecycle.removeObserver(observer) }
            }
            val trialDaysLeft = remember(state.userPreferences?.trialEndsAt, trialResumeTick) {
                trialDaysRemaining(state.userPreferences?.trialEndsAt)
            }
            // Re-evaluated on each ON_RESUME so the "dismissed today" check rolls
            // over at midnight without needing a process restart.
            val today = remember(trialResumeTick) { todayLocalDateIso() }
            var trialBannerDismissedOn by remember { mutableStateOf(trialGate.trialBannerDismissedOn) }
            // Guard on the *paid* signal, not isPremium — isPremium is true
            // during an active trial, which would suppress the very banner meant
            // to count that trial down. Show for non-paid users in their window.
            if (!trialGate.hasPaidSubscription && trialDaysLeft != null && trialDaysLeft in 1..14) {
                val dismissible = trialDaysLeft >= 7
                val showBanner = if (dismissible) trialBannerDismissedOn != today else true
                androidx.compose.animation.AnimatedVisibility(
                    visible = showBanner,
                    exit = androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(220),
                    ) + androidx.compose.animation.shrinkVertically(
                        animationSpec = androidx.compose.animation.core.tween(220),
                    ),
                ) {
                    live.localapp.local.ui.common.TrialCountdownBanner(
                        daysLeft = trialDaysLeft,
                        dismissible = dismissible,
                        onUpgrade = onUpgrade,
                        onDismiss = {
                            trialGate.trialBannerDismissedOn = today
                            trialBannerDismissedOn = today
                        },
                    )
                }
            }
        }

        // Concierge typewriter prompt — chosen here, above the LazyColumn, on
        // purpose. The card lives in a LazyColumn item and LazyColumn disposes
        // off-screen items; keeping the choice + animation state up here means
        // scrolling the card away and back can't re-roll or replay it.
        //
        // For "foryou" we prefer the hyperlocal (server) prompts, but those are
        // gated on location + a network fetch that can be slow or fail. So we
        // hold a blinking cursor (committed == null) for up to the grace window
        // waiting for them; if they land we type the local prompt first (no
        // overwrite), if the window expires we commit the generic fallback and
        // never overwrite it afterward. Non-"foryou" tabs have static local
        // lists, so they commit immediately. The choice is sticky per category
        // and only re-rolls on a genuine category switch.
        val conciergeCategory = state.selectedCategory
        var committedPrompt by remember { mutableStateOf<String?>(null) }
        var animatedPrompt by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(conciergeCategory) {
            committedPrompt = null
            // Reset the "already animated" marker on a genuine category switch so
            // the new prompt always types out. Scrolling does NOT re-run this
            // effect (it's hoisted above the LazyColumn), so a scrolled-away card
            // keeps its marker and renders static on return — no replay.
            animatedPrompt = null
            val staticList = categoryPromptsFor(conciergeCategory)
            val source = staticList ?: run {
                // foryou: hold the cursor briefly waiting for the hyperlocal
                // pool, then fall back. The fetch is kicked off in parallel at
                // app launch (HomeViewModel.init), so on a normal launch the
                // prompts are already in hand and this resolves immediately. The
                // window only matters on a cold/slow network — and there we keep
                // it short so the bar doesn't sit on a blank cursor: better to
                // type a generic prompt promptly than to stare at a blinking
                // cursor. The choice stays sticky, so no visible overwrite once
                // it commits.
                val graceMs = 2500L
                kotlinx.coroutines.withTimeoutOrNull(graceMs) {
                    var prompts = viewModel.uiState.value.yocalPrompts
                    while (prompts.isEmpty()) {
                        kotlinx.coroutines.delay(50)
                        prompts = viewModel.uiState.value.yocalPrompts
                    }
                    prompts
                } ?: FALLBACK_YOCAL_PROMPTS
            }
            committedPrompt = source.shuffled().firstOrNull() ?: FALLBACK_YOCAL_PROMPTS.random()
        }
        val conciergePrompt = committedPrompt
        val conciergePending = conciergePrompt == null
        val animateConciergePrompt = conciergePrompt != null && conciergePrompt != animatedPrompt

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            @OptIn(ExperimentalFoundationApi::class)
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
            ) {
                item { GreetingSection(state) }
                item {
                    AiConciergeCard(
                        displayPrompt = conciergePrompt.orEmpty(),
                        pending = conciergePending,
                        animatePrompt = animateConciergePrompt,
                        // No per-character haptics on For You — keep them on every
                        // other category tab.
                        promptHaptics = conciergeCategory != "foryou",
                        onPromptAnimated = { animated -> animatedPrompt = animated },
                        onSend = onConciergeSend,
                    )
                }

                // "Your Events This Week" album carousel
                if (state.savedThisWeek.isNotEmpty()) {
                    item(key = "saved_events_album") {
                        SavedEventsAlbumCarousel(
                            events = state.savedThisWeek,
                            onEventClick = { event, transitionInfo -> onEventClick(event, transitionInfo) },
                            onUnfavorite = { event -> viewModel.toggleEventFavorite(event.id, event.subcategory ?: event.category) },
                        )
                    }
                }

                // Category row — sticky header
                stickyHeader {
                    CategoryRow(state, viewModel)
                }

                if (state.isLoading && !state.isRefreshing) {
                    item { SkeletonCarousels() }
                } else if (state.carousels.isEmpty() && !state.isLoading) {
                    // Bank 1 — Bo empty state. Covers both the error path
                    // and the genuine zero-carousels case for For You + every
                    // subcategory page. Same Refresh CTA either way; the bank
                    // copy never references the gesture (the button carries it).
                    item {
                        live.localapp.local.ui.emptystate.BoEmptyState(
                            bank = live.localapp.local.ui.emptystate.EmptyStateCopy.NO_EVENTS,
                            ctaLabel = "Refresh",
                            onCta = { viewModel.refresh() },
                            rotationKey = state.selectedCategory,
                            modifier = Modifier.fillMaxWidth().height(440.dp),
                        )
                    }
                } else {
                    val isForYou = state.selectedCategory == "foryou"
                    displayCarousels.forEach { section ->
                        item(key = section.slug) {
                            val dest = seeAllDestFor(section, state.selectedCategory)
                            CarouselSectionView(
                                section, onEventClick,
                                showTags = isForYou,
                                onFavorite = onFavorite,
                                favoriteIds = state.favoriteEventIds,
                                onSeeAll = dest?.let { d ->
                                    {
                                        when (d) {
                                            is SeeAllDest.Venue -> onSeeAllVenue(d.name, d.city)
                                            is SeeAllDest.Events -> onSeeAllEvents(d.category, d.dateFilter)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SkeletonCarousels() {
    val shimmerAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
    )
    val skeletonColor = AppColors.Pebble.copy(alpha = 0.3f + shimmerAlpha * 0.2f)

    repeat(3) {
        // Section title skeleton
        Box(
            modifier = Modifier
                .padding(start = 20.dp, top = 18.dp, bottom = 10.dp)
                .width(140.dp)
                .height(16.dp)
                .background(skeletonColor, RoundedCornerShape(8.dp)),
        )
        // Card row skeleton
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(4) {
                Column {
                    Box(
                        modifier = Modifier
                            .width(156.dp)
                            .height(92.dp)
                            .background(skeletonColor, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
                    )
                    Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
                        Box(
                            modifier = Modifier
                                .width(120.dp)
                                .height(12.dp)
                                .background(skeletonColor, RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .width(80.dp)
                                .height(10.dp)
                                .background(skeletonColor, RoundedCornerShape(5.dp)),
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .width(60.dp)
                                .height(10.dp)
                                .background(skeletonColor, RoundedCornerShape(5.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 60.dp, horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                color = AppColors.Ink,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                color = AppColors.Ink3,
            ),
        )
    }
}

// ── "See All" routing ──

/** Where a carousel's "See All" should take the user. null = hide "See All". */
private sealed interface SeeAllDest {
    /** Open the venue's detail page (fav-venue "At {Venue}" rails). */
    data class Venue(val name: String, val city: String?) : SeeAllDest
    /** Open the Explore tab's events page with an optional category + date filter. */
    data class Events(val category: String?, val dateFilter: live.localapp.local.ui.places.EventDateFilter?) : SeeAllDest
}

/**
 * Maps a home-feed carousel to its "See All" destination, or null when the rail
 * has no sensible deep-link (in which case "See All" is hidden). Discriminates
 * purely on the stable [CarouselSection.slug] + filterType, so it needs no
 * extra payload from the edge function. [selectedCategory] is the current home
 * page ("foryou" or a subcategory id).
 */
private fun seeAllDestFor(
    section: CarouselSection,
    selectedCategory: String,
): SeeAllDest? {
    val ft = section.filterType.lowercase()
    val slug = section.slug.lowercase()

    // 1. Venue rails ("At {Venue}") → that venue's page.
    //    Two sources: the For-You fav-venue rail (filter_type "favorite_venue")
    //    and the per-subcategory venue rails (filter_type "dynamic_sub",
    //    slug "{sub}-venue-{name}"). Both carry the venue name in the title.
    if (ft == "favorite_venue" || (ft == "dynamic_sub" && slug.contains("-venue-"))) {
        val name = section.title.removePrefix("At ").trim()
        if (name.isBlank()) return null
        return SeeAllDest.Venue(name, section.events.firstOrNull()?.venueCity)
    }

    // 2. "Top Events Happening Soon" (top_weekly) — only meaningful on a
    //    subcategory page, where it inherits that subcategory's context.
    if (ft == "top_weekly") {
        val cat = selectedCategory.takeIf { it.isNotBlank() && !it.equals("foryou", ignoreCase = true) }
            ?: return null
        return SeeAllDest.Events(cat, live.localapp.local.ui.places.EventDateFilter.NEXT_30_DAYS)
    }

    // 3. Date-window rails → events page with the matching date filter.
    //    "Your Weekend Lineup" (weekend) + DB "This Weekend" → This Weekend
    //    "Coming Up" (next_2_weeks) → This Week
    //    "This Month" (this_month) → Next 30 Days
    if (ft == "date_range" || ft == "weekend") {
        val dateFilter = when {
            ft == "weekend" || slug == "this_weekend" -> live.localapp.local.ui.places.EventDateFilter.THIS_WEEKEND
            slug == "next_2_weeks" -> live.localapp.local.ui.places.EventDateFilter.THIS_WEEK
            slug == "this_month" -> live.localapp.local.ui.places.EventDateFilter.NEXT_30_DAYS
            else -> return null
        }
        return SeeAllDest.Events(null, dateFilter)
    }

    // 4. Category-style rails that map onto an Explore filter. The explore
    //    events endpoint filters on `category.eq.<x> OR subcategory.eq.<x>`,
    //    so both parent-category rails ("Arts & Culture" → arts) and the
    //    affinity / fav-category rails keyed on a SUBCATEGORY ("For Library
    //    Lovers" → libraries, "Museum Picks For You" → museums) resolve
    //    correctly. Pass the rail's category through directly — gating on the
    //    parent-category list was silently dropping every subcategory-keyed
    //    affinity rail, which is the bulk of the For-You feed.
    val catId = when (ft) {
        "category" -> slug                              // DB category rail: slug == category id
        "affinity" -> slug.removePrefix("affinity-")
        "favorite_category" -> slug.removePrefix("fav-cat-")
        else -> null
    }
    if (catId != null && catId.isNotBlank()) {
        // Generic buckets have no meaningful Explore filter → land unfiltered.
        if (catId in GENERIC_CATEGORY_IDS) return SeeAllDest.Events(null, null)
        return SeeAllDest.Events(catId, live.localapp.local.ui.places.EventDateFilter.NEXT_30_DAYS)
    }

    return null
}

private val GENERIC_CATEGORY_IDS = setOf("other", "general", "unknown")

// ── Dynamic Carousel Section ──

@Composable
private fun CarouselSectionView(section: CarouselSection, onEventClick: (Event, CardTransitionInfo?) -> Unit, showTags: Boolean = true, onFavorite: ((Event) -> Unit)? = null, favoriteIds: Set<String> = emptySet(), onSeeAll: (() -> Unit)? = null) {
    SectionHeader(
        title = section.title,
        // "See All" shows whenever the rail maps to a navigable destination
        // (the caller passes null otherwise, via seeAllDestFor). No event-count
        // gate: the destination is a broader page (full category / Next-30-days
        // / the venue's whole slate) than the rail's capped preview, so it's
        // meaningful even when the rail itself only shows a couple of cards.
        onSeeAll = onSeeAll,
    )
    EventCardRow(section.slug, section.events, onEventClick, showTags = showTags, showSaves = section.showSaves, onFavorite = onFavorite, favoriteIds = favoriteIds)
}

// ── Category Row ──

private const val ICON_BASE = "${Config.SUPABASE_URL}/storage/v1/object/public/category-icons"

private val forYouTile = CategoryInfo(
    id = "foryou",
    label = "For You",
    iconUrl = "$ICON_BASE/foryou.png?v=${live.localapp.local.domain.repository.CATEGORY_ICONS_VERSION}",
    eventCount = 0,
)

@Composable
private fun CategoryRow(state: HomeUiState, viewModel: HomeViewModel) {
    // Show skeleton placeholders while categories load
    if (state.availableCategories.isEmpty() && state.isLoading) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Snow)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(7) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .background(AppColors.Mist, RoundedCornerShape(14.dp)),
                    )
                    Spacer(Modifier.height(5.dp))
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(10.dp)
                            .background(AppColors.Mist, RoundedCornerShape(5.dp)),
                    )
                }
            }
        }
        return
    }

    val categories = remember(state.availableCategories) {
        listOf(forYouTile) + state.availableCategories
    }
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val selectedIndex = categories.indexOfFirst { it.id == state.selectedCategory }.coerceAtLeast(0)

    // Item width in px: 64dp per item
    val itemWidthPx = with(density) { 64.dp.toPx() }

    // Auto-scroll to center the selected category
    LaunchedEffect(selectedIndex) {
        val targetCenter = (selectedIndex * itemWidthPx + itemWidthPx / 2).toInt()
        val viewportCenter = scrollState.viewportSize / 2
        val scrollTo = (targetCenter - viewportCenter).coerceIn(0, scrollState.maxValue)
        scrollState.animateScrollTo(scrollTo)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Snow),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 10.dp)
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            categories.forEach { cat ->
                key(cat.id) {
                    val isActive = cat.id == state.selectedCategory
                    CategoryTileItem(
                        tile = cat,
                        isActive = isActive,
                        onClick = { viewModel.selectCategory(cat.id) },
                    )
                }
            }
        }

        // Bottom border line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.5.dp)
                .background(Color(0xFFEBEBEB)),
        )
    }
}

@Composable
private fun CategoryTileItem(
    tile: CategoryInfo,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (isActive) 1.08f else 1f,
        animationSpec = tween(150),
    )
    var trigger by remember { mutableStateOf(0) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .pressable(onClick = { trigger++; onClick() }),
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clipToBounds()
                .graphicsLayer { scaleX = scale; scaleY = scale },
            contentAlignment = Alignment.Center,
        ) {
            val rendered = renderLocalCategoryIcon(tile.id, trigger)
            if (!rendered) {
                val localIcon = categoryDrawable(tile.id)
                if (localIcon != null) {
                    Image(
                        painter = painterResource(localIcon),
                        contentDescription = tile.label,
                        modifier = Modifier.size(50.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        categoryIcon(tile.id),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = AppColors.Ink3.copy(alpha = 0.3f),
                    )
                }
            }
        }

        Spacer(Modifier.height(5.dp))

        // 11sp + tightened letter spacing fits "Experiences" (the longest label) inside
        // the 64dp tile. wrapContentWidth(unbounded) + Visible overflow prevents the
        // trailing letter from being clipped if a future label sneaks just over.
        Text(
            tile.label,
            style = TextStyle(
                fontFamily = AppFonts.Body,
                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = (-0.2).sp,
                color = if (isActive) AppColors.Ink else AppColors.Ink3,
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
            modifier = Modifier.wrapContentWidth(unbounded = true),
        )

        Spacer(Modifier.height(10.dp))

        // Bottom indicator bar
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(2.5.dp)
                .background(
                    if (isActive) AppColors.Ink else Color.Transparent,
                    RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                ),
        )
    }
}

// ── Section Header ──

// ═══════════════════════════════════════
// ── See All Sheet ──
// ═══════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeeAllSheet(
    section: CarouselSection,
    onDismiss: () -> Unit,
    onEventClick: (Event) -> Unit,
    onFavorite: ((Event) -> Unit)? = null,
    favoriteIds: Set<String> = emptySet(),
) {
    var searchQuery by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val filteredEvents = remember(searchQuery, section.events) {
        if (searchQuery.isBlank()) section.events
        else {
            val q = searchQuery.lowercase()
            section.events.filter { event ->
                event.title.lowercase().contains(q) ||
                    (event.venueName ?: "").lowercase().contains(q) ||
                    event.venueCity.lowercase().contains(q) ||
                    (event.subcategory ?: "").lowercase().contains(q)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppColors.Snow,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AppColors.Pebble),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f),
        ) {
            // Title
            Text(
                section.title,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = AppColors.Ink,
                    letterSpacing = (-0.5).sp,
                ),
            )

            // Search bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.Mist)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (searchQuery.isEmpty()) {
                    Text(
                        "Search events...",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = AppColors.Ink3,
                        ),
                    )
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    textStyle = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = AppColors.Ink,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Event count
            Text(
                "${filteredEvents.size} event${if (filteredEvents.size != 1) "s" else ""}",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = AppColors.Ink3,
                ),
            )

            // Event list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredEvents.size) { index ->
                    val event = filteredEvents[index]
                    SeeAllEventRow(
                        event = event,
                        onClick = {
                            // Hide the sheet first so the event detail doesn't open
                            // behind it, then notify the caller to open the event.
                            scope.launch {
                                sheetState.hide()
                                onDismiss()
                                onEventClick(event)
                            }
                        },
                        onFavorite = onFavorite?.let { fav -> { fav(event) } },
                        isFavorited = event.id in favoriteIds,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeeAllEventRow(
    event: Event,
    onClick: () -> Unit,
    onFavorite: (() -> Unit)? = null,
    isFavorited: Boolean = false,
) {
    val (month, day) = parseMonthDayHome(event.startAt)
    val weekday = parseWeekdayHome(event.startAt)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, AppColors.Pebble, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Date block
        Column(
            modifier = Modifier.width(44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(month, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = AppColors.Coral))
            Text(day, style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 20.sp, color = AppColors.Ink, lineHeight = 22.sp))
            Text(weekday, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = AppColors.Ink3))
        }

        // Event info
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                event.title,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    event.dateDisplay,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
                )
                val rawCat = event.subcategory ?: event.category
                val displayCat = if (rawCat.lowercase() == "always_on") "Event" else rawCat.replaceFirstChar { it.uppercaseChar() }
                Text(
                    displayCat,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, color = AppColors.Ink2),
                    modifier = Modifier.background(AppColors.Mist, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (event.venueName != null) {
                Text(
                    event.venueName,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
                    maxLines = 1,
                )
            }
        }

        // Price / Free badge + heart
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (event.isFree) {
                Text(
                    "Free",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = Color(0xFF2D9F73)),
                    modifier = Modifier.background(Color(0xFFE8F5E9), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else if (event.priceDisplay.isNotEmpty()) {
                Text(event.priceDisplay, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AppColors.Ink3))
            }
            if (onFavorite != null) {
                Icon(
                    if (isFavorited) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    tint = if (isFavorited) AppColors.Coral else AppColors.Ink3,
                    modifier = Modifier.size(18.dp).clickable(onClick = onFavorite),
                )
            }
        }
    }
}

private fun parseMonthDayHome(startAt: String?): Pair<String, String> {
    if (startAt == null) return "" to ""
    return try {
        val instant = kotlinx.datetime.Instant.parse(startAt)
        val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        monthNames[local.monthNumber - 1] to local.dayOfMonth.toString()
    } catch (_: Exception) { "" to "" }
}

private fun parseWeekdayHome(startAt: String?): String {
    if (startAt == null) return ""
    return try {
        val instant = kotlinx.datetime.Instant.parse(startAt)
        val local = instant.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
        val dayNames = arrayOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        dayNames[local.dayOfWeek.ordinal]
    } catch (_: Exception) { "" }
}

private fun categoryGradient(category: String): List<Color> {
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
private fun SectionHeader(title: String, onSeeAll: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AppType.SectionTitle, modifier = Modifier.weight(1f))
        if (onSeeAll != null) {
            Text(
                "See all",
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSeeAll,
                    )
                    .padding(start = 8.dp),
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = AppColors.Coral,
                ),
            )
        }
    }
}

// ── Shared-element transition info ──

data class CardTransitionInfo(
    val bounds: Rect,       // card position in screen coordinates (pixels)
    val imageUrl: String?,
)

// ── Event Card ──

data class EventCardData(
    val title: String,
    val venueName: String,
    val date: String,
    val city: String,
    val distance: String,
    val tag: String,
    val showTag: Boolean = true,
    val isFree: Boolean = false,
    val price: String = "",
    val imageUrl: String?,
    val bgStart: Color,
    val bgEnd: Color,
    val icon: ImageVector,
)

@Composable
fun EventCard(data: EventCardData, modifier: Modifier = Modifier.width(156.dp), onClick: (Rect) -> Unit = {}, onFavorite: (() -> Unit)? = null, isFavorited: Boolean = false) {
    val scaleAnim = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    val isPressed = scaleAnim.value < 0.99f

    Box(
        modifier = modifier
            .height(212.dp)
            .onGloballyPositioned { coordinates ->
                cardBounds = coordinates.boundsInRoot()
            }
            .graphicsLayer {
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        scope.launch { scaleAnim.animateTo(0.94f, tween(80)) }
                        val released = tryAwaitRelease()
                        scope.launch { scaleAnim.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 500f)) }
                        if (released) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClick(cardBounds)
                        }
                    },
                )
            }
            .testTag("home_event_card"),
    ) {
        // Shadow
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = if (!isPressed) 4.dp else 1.dp)
                .background(AppColors.Pebble, AppShapes.CardMedium),
        )

        // Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(212.dp)
                .background(AppColors.White, AppShapes.CardMedium)
                .border(2.dp, AppColors.Pebble, AppShapes.CardMedium)
                .clip(AppShapes.CardMedium),
        ) {
            // Image area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(92.dp)
                    .background(
                        Brush.linearGradient(listOf(data.bgStart, data.bgEnd)),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (data.imageUrl != null) {
                    AsyncImage(
                        model = data.imageUrl,
                        contentDescription = data.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(data.icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = AppColors.Ink3.copy(alpha = 0.3f))
                }

                // Tag pill — only shown on "For You" tab
                if (data.showTag) {
                    Text(
                        data.tag,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(7.dp)
                            .background(AppColors.White, AppShapes.Pill)
                            .border(1.5.dp, AppColors.Pebble, AppShapes.Pill)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        style = AppType.LabelMicro.copy(color = AppColors.Ink2),
                    )
                }

                // Heart / favorite button
                if (onFavorite != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(28.dp)
                            .background(Color.White.copy(alpha = 0.75f), CircleShape)
                            .clip(CircleShape)
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    // Double haptic for save
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scope.launch {
                                        kotlinx.coroutines.delay(80)
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                    onFavorite()
                                }
                            }
                            .testTag("home_event_favorite"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (isFavorited) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Favorite",
                            modifier = Modifier.size(16.dp),
                            tint = if (isFavorited) AppColors.Coral else AppColors.Ink2,
                        )
                    }
                }

            }

            // Body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    data.title,
                    style = AppType.EventCardTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Column {
                    // Venue name
                    if (data.venueName.isNotEmpty()) {
                        Text(
                            data.venueName,
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                                color = AppColors.Ink3,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Date
                    if (data.date.isNotEmpty()) {
                        Text(
                            data.date,
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp,
                                color = AppColors.Ink2,
                            ),
                            maxLines = 1,
                        )
                    }

                    // City + distance
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (data.city.isNotEmpty()) {
                            Text(
                                data.city,
                                style = TextStyle(
                                    fontFamily = AppFonts.Body,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,
                                    color = AppColors.Ink3,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (data.distance.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 4.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Place,
                                    contentDescription = null,
                                    modifier = Modifier.size(11.dp),
                                    tint = AppColors.Ink3,
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    data.distance,
                                    style = TextStyle(
                                        fontFamily = AppFonts.Body,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp,
                                        color = AppColors.Ink3,
                                    ),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCardRow(slug: String, events: List<Event>, onEventClick: (Event, CardTransitionInfo?) -> Unit, showTags: Boolean = true, showSaves: Boolean = false, onFavorite: ((Event) -> Unit)? = null, favoriteIds: Set<String> = emptySet()) {
    val communityRepo = if (showSaves) {
        remember { KoinPlatform.getKoin().get<live.localapp.local.domain.repository.CommunityInterestRepository>() }
    } else null

    // Preserve horizontal scroll per (category, carousel). The parent LazyColumn
    // keys its section item by `section.slug`, so the SaveableStateHolder retains
    // this ScrollState across the carousel leaving composition when the user
    // taps to another category and returns. Passing `slug` as an input keeps
    // the saver bucket explicit and stable.
    val scrollState = rememberSaveable(slug, saver = ScrollState.Saver) { ScrollState(0) }

    // After a pull-to-refresh swaps in fewer events, the saved offset may exceed
    // the new content width. Wait one frame for layout, then clamp so the
    // carousel doesn't render pinned past its content end.
    LaunchedEffect(slug, events.size) {
        withFrameNanos { }
        if (scrollState.value > scrollState.maxValue) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Row(
        modifier = Modifier
            .horizontalScroll(scrollState)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        events.forEach { event ->
            val saveCount = communityRepo?.totalSaves(event.id) ?: 0
            val tagOverride = if (showSaves && saveCount > 10) "$saveCount Saves" else null
            val cardData = event.toCardData(showTag = showTags || tagOverride != null, tagOverride = tagOverride)
            EventCard(
                data = cardData,
                onClick = { bounds ->
                    onEventClick(event, CardTransitionInfo(bounds = bounds, imageUrl = cardData.imageUrl))
                },
                onFavorite = onFavorite?.let { { it(event) } },
                isFavorited = event.id in favoriteIds,
            )
        }
    }
}

// ── Category styling helpers ──

private fun categoryColors(category: String): Pair<Color, Color> = when (category.lowercase()) {
    "music" -> AppColors.BgPlumStart to AppColors.BgPlumEnd
    "sports" -> AppColors.BgSkyStart to AppColors.BgSkyEnd
    "arts" -> AppColors.BgSageStart to AppColors.BgSageEnd
    "comedy" -> AppColors.BgSunStart to AppColors.BgSunEnd
    "food" -> AppColors.BgSunStart to AppColors.BgSunEnd
    "outdoors" -> AppColors.BgSageStart to AppColors.BgSageEnd
    "nightlife" -> AppColors.BgPlumStart to AppColors.BgPlumEnd
    else -> AppColors.BgCoralStart to AppColors.BgCoralEnd
}

@Composable
private fun renderLocalCategoryIcon(category: String, trigger: Int): Boolean {
    val size = 50.dp
    val mod = Modifier.size(size)
    when (category.lowercase()) {
        "foryou" -> live.localapp.local.ui.icons.LocalIcon.ForYou(size = size, trigger = trigger, modifier = mod)
        "music" -> live.localapp.local.ui.icons.LocalIcon.Music(size = size, trigger = trigger, modifier = mod)
        "sports" -> live.localapp.local.ui.icons.LocalIcon.Sports(size = size, trigger = trigger, modifier = mod)
        "arts" -> live.localapp.local.ui.icons.LocalIcon.Art(size = size, trigger = trigger, modifier = mod)
        "theatre" -> live.localapp.local.ui.icons.LocalIcon.Theatre(size = size, trigger = trigger, modifier = mod)
        "comedy" -> live.localapp.local.ui.icons.LocalIcon.Comedy(size = size, trigger = trigger, modifier = mod)
        "food" -> live.localapp.local.ui.icons.LocalIcon.Food(size = size, trigger = trigger, modifier = mod)
        "outdoors" -> live.localapp.local.ui.icons.LocalIcon.Outdoors(size = size, trigger = trigger, modifier = mod)
        "tours" -> live.localapp.local.ui.icons.LocalIcon.Tours(size = size, trigger = trigger, modifier = mod)
        "libraries" -> live.localapp.local.ui.icons.LocalIcon.Libraries(size = size, trigger = trigger, modifier = mod)
        "for-kids" -> live.localapp.local.ui.icons.LocalIcon.Kids(size = size, trigger = trigger, modifier = mod)
        "experiences" -> live.localapp.local.ui.icons.LocalIcon.Experiences(size = size, trigger = trigger, modifier = mod)
        "nightlife" -> live.localapp.local.ui.icons.LocalIcon.Nightlife(size = size, trigger = trigger, modifier = mod)
        "museums" -> live.localapp.local.ui.icons.LocalIcon.Museums(size = size, trigger = trigger, modifier = mod)
        "other" -> live.localapp.local.ui.icons.LocalIcon.Other(size = size, trigger = trigger, modifier = mod)
        else -> return false
    }
    return true
}

private fun categoryDrawable(category: String): DrawableResource? = when (category.lowercase()) {
    "foryou" -> Res.drawable.cat_foryou
    "music" -> Res.drawable.cat_music
    "sports" -> Res.drawable.cat_sports
    "arts" -> Res.drawable.cat_arts
    "theatre" -> Res.drawable.cat_theatre
    "comedy" -> Res.drawable.cat_comedy
    "food" -> Res.drawable.cat_food
    "outdoors" -> Res.drawable.cat_outdoors
    "museums" -> Res.drawable.cat_museums
    "tours" -> Res.drawable.cat_tours
    "libraries" -> Res.drawable.cat_libraries
    "for-kids" -> Res.drawable.cat_forkids
    "experiences" -> Res.drawable.cat_foryou
    "other" -> Res.drawable.cat_other
    else -> null
}

private fun categoryIcon(category: String): ImageVector = when (category.lowercase()) {
    "music" -> Icons.Outlined.MusicNote
    "sports" -> Icons.Outlined.SportsScore
    "arts" -> Icons.Outlined.Palette
    "comedy" -> Icons.Outlined.TheaterComedy
    "food" -> Icons.Outlined.LocalDining
    "outdoors" -> Icons.Outlined.Park
    "nightlife" -> Icons.Outlined.Nightlife
    "experiences" -> Icons.Outlined.Explore
    else -> Icons.Outlined.AutoAwesome
}

fun Event.toCardData(showTag: Boolean = true, tagOverride: String? = null, fallbackImageUrl: String? = null): EventCardData {
    // Prefer subSubcategory for a more specific tag (e.g. "Hip Hop" vs "Music")
    val tagKey = subSubcategory ?: subcategory ?: category
    val displayTag = tagOverride ?: formatSubcategoryDisplay(tagKey)
    val colorKey = subcategory ?: category
    val (bgS, bgE) = categoryColors(colorKey)
    return EventCardData(
        title = title.toTitleCase(),
        venueName = venueName ?: "",
        date = dateDisplay,
        city = buildString {
            // Prefer the physical event location over the host-venue HQ.
            // displayCity returns eventCity when set, otherwise venueCity.
            append(displayCity.replaceFirstChar { it.uppercase() })
            val state = (eventState ?: venueState)?.uppercase()
            if (state != null && state != "MA") {
                append(", $state")
            }
        },
        distance = distanceDisplay,
        tag = displayTag,
        showTag = showTag,
        isFree = isFree,
        price = priceDisplay,
        imageUrl = imageUrl ?: venueImageUrl ?: fallbackImageUrl,
        bgStart = bgS,
        bgEnd = bgE,
        icon = categoryIcon(colorKey),
    )
}

private fun formatSubcategoryDisplay(key: String): String = when (key.lowercase()) {
    "for-kids" -> "For Kids"
    "r&b" -> "R&B"
    else -> key.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}

// ── Plans Section ──

@Composable
private fun PlansSection() {
    SectionHeader("Your Plans")
    PlanRow(
        icon = Icons.Outlined.Map,
        name = "Sarah's Visit",
        meta = "5 events · Nov 8-10",
    )
    Spacer(Modifier.height(9.dp))
    PlanRow(
        icon = Icons.Outlined.FavoriteBorder,
        name = "Date Night Ideas",
        meta = "3 events · This weekend",
    )
}

@Composable
private fun PlanRow(icon: ImageVector, name: String, meta: String) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val offsetY by animateFloatAsState(
        targetValue = if (isPressed) 2f else 0f,
        animationSpec = tween(80),
    )
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = tween(80),
    )
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .graphicsLayer {
                translationY = offsetY * density
                scaleX = scale
                scaleY = scale
            },
    ) {
        // Shadow
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = if (!isPressed) 3.dp else 1.dp)
                .background(AppColors.Pebble, RoundedCornerShape(16.dp)),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.White, RoundedCornerShape(16.dp))
                .border(2.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(AppColors.Mist, AppShapes.IconBox)
                    .border(2.dp, AppColors.Pebble, AppShapes.IconBox),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = AppColors.Ink2)
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = AppType.PlanName)
                Spacer(Modifier.height(2.dp))
                Text(meta, style = AppType.PlanMeta)
            }

            Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = AppColors.Pebble)
        }
    }
}

// ── Greeting ──

@Composable
private fun GreetingSection(state: HomeUiState) {
    @Suppress("DEPRECATION")
    val timeGreeting = remember {
        val hour = kotlinx.datetime.Instant.fromEpochMilliseconds(
            kotlin.time.Clock.System.now().toEpochMilliseconds()
        ).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).hour
        when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
    }
    val name = state.userName

    if (name.isNotEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$timeGreeting, $name",
                style = AppType.GreetingSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            state.weather?.let { w ->
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        weatherIcon(w.weatherCode),
                        contentDescription = w.description,
                        modifier = Modifier.size(16.dp),
                        tint = AppColors.Pebble,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${w.tempHigh}°",
                        style = AppType.GreetingSmall,
                        color = AppColors.Pebble,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun weatherIcon(code: Int) = when (code) {
    0, 1 -> Icons.Outlined.WbSunny
    in 71..77, in 85..86 -> Icons.Outlined.AcUnit
    in 51..67, in 80..82 -> Icons.Outlined.Grain
    in 95..99 -> Icons.Outlined.Bolt
    else -> Icons.Outlined.Cloud
}

// ── AI Concierge Card ──

@Composable
private fun AiConciergeCard(
    displayPrompt: String,
    pending: Boolean = false,
    animatePrompt: Boolean = true,
    promptHaptics: Boolean = true,
    onPromptAnimated: (String) -> Unit = {},
    onSend: (String) -> Unit = {},
) {
    val haptic = LocalHapticFeedback.current

    var input by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    val performSend: () -> Unit = {
        val effective = if (input.text.isBlank()) displayPrompt else input.text
        if (effective.isNotBlank()) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onSend(effective)
        }
    }

    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp, bottom = 10.dp)
            .fillMaxWidth(),
    ) {
        // Shadow layer — contained within the box (no overflow)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .matchParentSize()
                .offset(y = 5.dp)
                .background(AppColors.CoralDk, RoundedCornerShape(20.dp)),
        )

        // Card content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(AppColors.Coral, RoundedCornerShape(20.dp))
                .border(2.dp, AppColors.CoralDk, RoundedCornerShape(20.dp))
                .drawBehind {
                    // Decorative shine — drawn as paint, not a layout child, so it
                    // doesn't force the card's minimum height to the circle's size.
                    val r = 65.dp.toPx()
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.13f),
                        radius = r,
                        center = androidx.compose.ui.geometry.Offset(
                            x = size.width - 5.dp.toPx(),
                            y = 17.dp.toPx(),
                        ),
                    )
                }
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
        ) {
            Column {
                // Title row: "Ask Bo" on the left, Bo character on the right.
                // Bo lives inside this row (not absolutely positioned) so the
                // input bar always flows below it and they never overlap.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { focusRequester.requestFocus() },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Ask ", style = AppType.AiTitle)
                    Text(
                        "Bo",
                        style = AppType.AiTitle.copy(fontStyle = FontStyle.Italic),
                    )
                    Spacer(Modifier.weight(1f))
                    // Bo keeps its 72dp drawn size but lives in a shorter box so
                    // it doesn't inflate the row height. Its transparent headroom
                    // overflows the box rather than adding vertical space.
                    Box(
                        modifier = Modifier.size(width = 72.dp, height = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        live.localapp.local.ui.character.Bo(
                            size = 72.dp,
                            level = live.localapp.local.ui.character.cachedBoLevel(),
                            showSparkles = false,
                            showShadow = false,
                            modifier = Modifier.requiredSize(72.dp),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Input bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            AppColors.White.copy(alpha = 0.2f),
                            AppShapes.AiInput,
                        )
                        .border(
                            2.dp,
                            AppColors.White.copy(alpha = 0.35f),
                            AppShapes.AiInput,
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            textStyle = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = AppColors.White,
                            ),
                            cursorBrush = SolidColor(AppColors.White),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { performSend() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onFocusChanged { state ->
                                    val gainedFocus = state.isFocused && !focused
                                    focused = state.isFocused
                                    if (gainedFocus && input.text.isEmpty() && displayPrompt.isNotBlank()) {
                                        // Pre-fill with the prompt that was on screen, with the
                                        // entire range selected so a single delete press wipes
                                        // it (matches placeholder convention) and typing replaces it.
                                        input = androidx.compose.ui.text.input.TextFieldValue(
                                            text = displayPrompt,
                                            selection = androidx.compose.ui.text.TextRange(0, displayPrompt.length),
                                        )
                                    }
                                }
                                .testTag("home_concierge_input"),
                        )
                        // Typewriter overlay shown only while empty + unfocused.
                        // The typewriter component types once and holds (no auto-cycle).
                        if (input.text.isEmpty() && !focused) {
                            TypewriterPlaceholder(
                                fullText = displayPrompt,
                                style = TextStyle(
                                    fontFamily = AppFonts.Body,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = AppColors.White.copy(alpha = 0.85f),
                                ),
                                cursorColor = AppColors.White,
                                pending = pending,
                                animate = animatePrompt,
                                haptics = promptHaptics,
                                onAnimated = onPromptAnimated,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(AppColors.White, CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { performSend() },
                            )
                            .testTag("home_concierge_send"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Send, contentDescription = "Send", modifier = Modifier.size(14.dp), tint = AppColors.Coral)
                    }
                }
            }
        }
    }
}

// ── Saved Events Album Carousel ──

@Composable
private fun SavedEventsAlbumCarousel(
    events: List<Event>,
    onEventClick: (Event, CardTransitionInfo?) -> Unit,
    onUnfavorite: (Event) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }

    // Preserve horizontal scroll across category switches. Item key on the
    // LazyColumn parent ("saved_events_album") plus the rememberSaveable bucket
    // here means the offset survives when the user navigates away and back.
    val scrollState = rememberSaveable("saved_events_album_scroll", saver = ScrollState.Saver) { ScrollState(0) }
    LaunchedEffect(events.size) {
        withFrameNanos { }
        if (scrollState.value > scrollState.maxValue) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
    ) {
        // Header — tappable to collapse/expand
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressable(onClick = { expanded = !expanded }, scale = 0.98f)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Saved Events This Week (${events.size})",
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = AppColors.Coral,
                    letterSpacing = (-0.3).sp,
                ),
            )
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp),
                tint = AppColors.Coral,
            )
        }

        // Cards — collapsible
        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState)
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    events.forEach { event ->
                        AlbumEventCard(event = event, onClick = { bounds ->
                            onEventClick(event, CardTransitionInfo(bounds, event.imageUrl))
                        }, onUnfavorite = { onUnfavorite(event) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumEventCard(event: Event, onClick: (Rect) -> Unit, onUnfavorite: () -> Unit = {}) {
    val cardShape = RoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, tween(80))
    val haptic = LocalHapticFeedback.current
    var cardBounds by remember { mutableStateOf(Rect.Zero) }

    Box(
        modifier = Modifier
            .width(156.dp)
            .height(190.dp)
            .onGloballyPositioned { coordinates ->
                cardBounds = coordinates.boundsInRoot()
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = 8f
                shape = cardShape
                clip = true
            }
            .clip(cardShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick(cardBounds)
                },
            ),
    ) {
        // Full-bleed image (or category gradient fallback)
        val imageUrl = event.imageUrl
        val colorKey = event.subcategory ?: event.category
        val (bgStart, bgEnd) = categoryColors(colorKey)
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalPlatformContext.current)
                    .data(imageUrl)
                    .build(),
                contentDescription = event.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(bgStart, bgEnd))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    categoryIcon(colorKey),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = AppColors.Ink3.copy(alpha = 0.3f),
                )
            }
        }

        // Date pill in top-left corner
        val dateText = event.dateDisplay
        if (dateText.isNotBlank()) {
            Text(
                dateText,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(
                        Color.Black.copy(alpha = 0.6f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp,
                    color = Color.White,
                ),
                maxLines = 1,
            )
        }

        // Heart — top right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(28.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onUnfavorite()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = "Remove",
                modifier = Modifier.size(14.dp),
                tint = AppColors.Coral,
            )
        }

        // Title overlaid at bottom with dark gradient
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            Text(
                event.title,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                    color = Color.White,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// HomeHeader removed — now uses shared AppHeader

