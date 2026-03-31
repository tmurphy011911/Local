package live.localapp.local.ui.chat

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage
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
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    var selectedEventId by remember { mutableStateOf<String?>(null) }
    var selectedEvent by remember { mutableStateOf<live.localapp.local.domain.model.Event?>(null) }
    var selectedVenueName by remember { mutableStateOf<String?>(null) }
    var selectedVenue by remember { mutableStateOf<live.localapp.local.domain.model.Venue?>(null) }

    // Auto-scroll to bottom when new messages arrive (no animation to avoid bounce loop)
    val messageCount = state.messages.size
    LaunchedEffect(messageCount) {
        if (messageCount > 0) {
            listState.scrollToItem(messageCount - 1)
        }
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
        selectedEvent = viewModel.getEventById(id)
    }

    // Load venue when venue slot is tapped
    LaunchedEffect(selectedVenueName) {
        val name = selectedVenueName ?: return@LaunchedEffect
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

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Snow)
            .imePadding(),
    ) {
        // ── Header ──
        ChatHeader(onBack = onBack, onReset = { viewModel.resetChat() })

        // ── Messages ──
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                when (message.content) {
                    is MessageContent.Text -> MessageRow(message.role, (message.content as MessageContent.Text).text)
                    is MessageContent.DayCardRef -> {
                        val dayIdx = (message.content as MessageContent.DayCardRef).dayIndex
                        val day = state.days.getOrNull(dayIdx) ?: return@items
                        DayCard(
                            day = day,
                            dayIndex = dayIdx,
                            totalDays = state.days.size,
                            onRemoveSlot = { slotId -> viewModel.removeSlot(dayIdx, slotId) },
                            onEventClick = { id ->
                                if (id.startsWith("venue:")) {
                                    selectedVenueName = id.removePrefix("venue:")
                                } else {
                                    selectedEventId = id
                                }
                            },
                        )
                    }
                    is MessageContent.DayCardSnapshot -> {
                        val snapshot = message.content as MessageContent.DayCardSnapshot
                        // Old version — dimmed, no interaction
                        Box(modifier = Modifier.alpha(0.45f)) {
                            DayCard(
                                day = snapshot.day,
                                dayIndex = snapshot.dayIndex,
                                totalDays = snapshot.totalDays,
                                onRemoveSlot = { },
                                onEventClick = { },
                            )
                        }
                    }
                    is MessageContent.MultiDayPlanRef -> {
                        MultiDayPlanCard(
                            days = state.days,
                            onRemoveSlot = { dayIdx, slotId -> viewModel.removeSlot(dayIdx, slotId) },
                            onEventClick = { id ->
                                if (id.startsWith("venue:")) {
                                    selectedVenueName = id.removePrefix("venue:")
                                } else {
                                    selectedEventId = id
                                }
                            },
                        )
                    }
                    is MessageContent.SummaryRef -> {
                        SummaryCard(state)
                    }
                    is MessageContent.ProgressRef -> {
                        ProgressIndicator(
                            totalDays = state.days.size,
                            approvedDays = state.approvedDayCount,
                            currentDay = state.currentDayIndex,
                            dayNames = state.days.map { it.shortName },
                        )
                    }
                    is MessageContent.AlsoHappeningRef -> {
                        if (state.recommendations.isNotEmpty()) {
                            AlsoHappeningRow(
                                events = state.recommendations,
                                onEventTap = { event -> viewModel.addRecommendationToPlan(event) },
                            )
                        }
                    }
                }
            }

            // Typing / searching indicator
            if (state.isSearching) {
                item { SearchingIndicator() }
            } else if (state.isTyping) {
                item { TypingIndicator() }
            }
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
            onSend = { viewModel.sendMessage(it) },
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
            onHome = {
                viewModel.dismissPlanSuccess(navigateToPlans = false)
                onNavigateHome?.invoke()
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
private fun ChatHeader(onBack: (() -> Unit)? = null, onReset: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Coral)
            .padding(horizontal = 18.dp)
            .padding(top = 6.dp, bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(AppColors.White.copy(alpha = 0.15f), RoundedCornerShape(11.dp))
                        .border(1.5.dp, AppColors.White.copy(alpha = 0.25f), RoundedCornerShape(11.dp))
                        .clip(RoundedCornerShape(11.dp))
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.ChevronLeft, "Back", Modifier.size(18.dp), tint = AppColors.White)
                }
                Spacer(Modifier.width(10.dp))
            }

            Text(
                "Yocal the Local",
                modifier = Modifier.weight(1f),
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.White),
            )

            if (onReset != null) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(AppColors.White.copy(alpha = 0.15f), RoundedCornerShape(11.dp))
                        .border(1.5.dp, AppColors.White.copy(alpha = 0.25f), RoundedCornerShape(11.dp))
                        .clip(RoundedCornerShape(11.dp))
                        .clickable(onClick = onReset),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Close, "Reset", Modifier.size(16.dp), tint = AppColors.White)
                }
            }
        }
    }
}

// ── Message Bubbles ──

@Composable
private fun MessageRow(role: MessageRole, text: String) {
    val isUser = role == MessageRole.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            AiAvatar()
            Spacer(Modifier.width(8.dp))
        }

        val bubbleShape = if (isUser) RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp) else RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)

        Box(modifier = Modifier.weight(1f, fill = false)) {
            Box(
                Modifier.matchParentSize().offset(y = 4.dp)
                    .background(if (isUser) Color(0xFFC73E3E) else Color(0xFFD8D2E8), bubbleShape),
            )
            Box(
                Modifier
                    .background(
                        if (isUser) Brush.linearGradient(listOf(AppColors.Coral, AppColors.CoralDk))
                        else Brush.linearGradient(listOf(AppColors.White, AppColors.White)),
                        bubbleShape,
                    )
                    .border(2.dp, if (isUser) AppColors.CoralDk else Color(0xFFE2DCF0), bubbleShape)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Text(
                    cleanMarkdown(text),
                    style = TextStyle(
                        fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        color = if (isUser) AppColors.White else AppColors.Ink2, lineHeight = (13 * 1.55).sp,
                    ),
                )
            }
        }

        if (isUser) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(30.dp).background(AppColors.Coral, RoundedCornerShape(10.dp))
                    .border(1.5.dp, AppColors.CoralDk, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Y", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 11.sp, color = AppColors.White))
            }
        }
    }
}

@Composable
private fun AiAvatar() {
    Box(
        Modifier.size(30.dp).background(AppColors.CoralLt, RoundedCornerShape(10.dp))
            .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(15.dp), tint = AppColors.Coral)
    }
}

// ── Typing Indicator ──

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition()
    Row(horizontalArrangement = Arrangement.Start) {
        AiAvatar()
        Spacer(Modifier.width(8.dp))
        val bubbleShape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
        Box {
            Box(Modifier.matchParentSize().offset(y = 4.dp).background(Color(0xFFD8D2E8), bubbleShape))
            Row(
                Modifier.background(AppColors.White, bubbleShape).border(2.dp, Color(0xFFE2DCF0), bubbleShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) { index ->
                    val offset by infiniteTransition.animateFloat(
                        initialValue = 0f, targetValue = -6f,
                        animationSpec = infiniteRepeatable(tween(600, delayMillis = index * 180), RepeatMode.Reverse),
                    )
                    Box(Modifier.size(7.dp).graphicsLayer { translationY = offset }.background(AppColors.Pebble, CircleShape))
                }
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
    "Loosening the belt...",
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
private fun SearchingIndicator() {
    var phraseIndex by remember { mutableStateOf((0 until searchingPhrases.size).random()) }
    val currentPhrase = searchingPhrases[phraseIndex % searchingPhrases.size]

    // Rotate every 2.5 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(2500L)
            phraseIndex++
        }
    }

    // Fade transition on phrase change
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(phraseIndex) {
        alpha.snapTo(0f)
        alpha.animateTo(1f, animationSpec = tween(400))
    }

    Row(horizontalArrangement = Arrangement.Start) {
        AiAvatar()
        Spacer(Modifier.width(8.dp))
        val bubbleShape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
        Box {
            Box(Modifier.matchParentSize().offset(y = 4.dp).background(Color(0xFFD8D2E8), bubbleShape))
            Box(
                Modifier
                    .background(AppColors.White, bubbleShape)
                    .border(2.dp, Color(0xFFE2DCF0), bubbleShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    currentPhrase,
                    modifier = Modifier.graphicsLayer { this.alpha = alpha.value },
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = AppColors.Ink3,
                    ),
                )
            }
        }
    }
}

// ── Day Card ──

@Composable
private fun MultiDayPlanCard(
    days: List<ItineraryDay>,
    onRemoveSlot: (Int, String) -> Unit,
    onEventClick: (String) -> Unit = {},
) {
    // Fixed height so all day cards are the same size in horizontal scroll
    val maxSlots = days.maxOfOrNull { it.slots.size } ?: 3
    val cardHeight = (120 + maxSlots * 60).coerceIn(250, 500)

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier.height(cardHeight.dp),
    ) {
        itemsIndexed(days) { index, day ->
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight(),
            ) {
                DayCard(
                    day = day,
                    dayIndex = index,
                    totalDays = days.size,
                    onRemoveSlot = { slotId -> onRemoveSlot(index, slotId) },
                    onEventClick = onEventClick,
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
    onRemoveSlot: (String) -> Unit,
    onEventClick: (String) -> Unit = {},
) {
    val color = Color(dayColor(dayIndex))
    val cardShape = RoundedCornerShape(18.dp)

    Box(modifier = Modifier.fillMaxWidth()) {
        // Shadow
        Box(
            Modifier.fillMaxWidth().matchParentSize().offset(y = 5.dp)
                .background(if (day.isApproved) AppColors.SageDk else AppColors.Pebble, cardShape),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.White, cardShape)
                .border(2.dp, if (day.isApproved) AppColors.Sage else AppColors.Pebble, cardShape)
                .clip(cardShape),
        ) {
            // Header
            Column(
                modifier = Modifier.fillMaxWidth().background(color).padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (totalDays > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "DAY ${dayIndex + 1} OF $totalDays",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 10.sp,
                                color = AppColors.White.copy(alpha = 0.7f), letterSpacing = 0.8.sp),
                        )
                        Text(
                            "${day.slots.size} ${if (day.slots.size == 1) "event" else "events"}",
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
                                color = AppColors.White.copy(alpha = 0.7f)),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    day.label.replace("Today ", "").replace("Tomorrow ", "").ifBlank { "Your Plan" },
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 16.sp, color = AppColors.White),
                )
                if (totalDays <= 1) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${day.slots.size} ${if (day.slots.size == 1) "event" else "events"}",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp,
                            color = AppColors.White.copy(alpha = 0.7f)),
                    )
                }
            }

            // Approved banner
            if (day.isApproved) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(AppColors.SageLt)
                        .border(width = 1.5.dp, color = AppColors.Sage)
                        .padding(vertical = 7.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = AppColors.SageDk)
                    Spacer(Modifier.width(6.dp))
                    Text("Approved", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = AppColors.SageDk))
                }
            }

            // Slots
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                day.slots.forEachIndexed { index, slot ->
                    val isLast = index == day.slots.lastIndex
                    SlotRow(
                        slot = slot,
                        color = color,
                        isLast = isLast,
                        isApproved = day.isApproved,
                        onRemove = { onRemoveSlot(slot.id) },
                        onEventClick = {
                            if (slot.eventId != null) {
                                onEventClick(slot.eventId)
                            } else if (!slot.isCustom && slot.name.length > 2) {
                                onEventClick("venue:${slot.name}")
                            }
                        },
                    )

                    if (!isLast) {
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotRow(
    slot: ItinerarySlot,
    color: Color,
    isLast: Boolean,
    isApproved: Boolean,
    onRemove: () -> Unit,
    onEventClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        // Time column
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(44.dp).fillMaxHeight(),
        ) {
            Text(
                slot.time,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 9.sp, color = AppColors.Ink3),
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.size(11.dp).background(color, CircleShape)
                    .border(3.dp, color.copy(alpha = 0.2f), CircleShape),
            )
            if (!isLast) {
                Box(Modifier.width(2.dp).weight(1f).background(AppColors.Mist))
            }
        }

        Spacer(Modifier.width(10.dp))

        // Slot content card — tappable to open event detail
        Row(
            modifier = Modifier
                .weight(1f)
                .background(AppColors.Snow, RoundedCornerShape(13.dp))
                .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(13.dp))
                .clip(RoundedCornerShape(13.dp))
                .pressable(
                    onClick = onEventClick,
                    enabled = slot.eventId != null || (!slot.isCustom && slot.name.length > 2),
                )
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stripEmoji(slot.name),
                        modifier = Modifier.weight(1f, fill = false),
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = AppColors.Ink),
                        maxLines = 1,
                    )
                    if (slot.isFree && !slot.isCustom) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Free",
                            modifier = Modifier
                                .background(Color(0xFFE8F5E9), AppShapes.Pill)
                                .border(1.dp, Color(0xFFA5D6A7), AppShapes.Pill)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 8.sp, color = Color(0xFF2E7D32)),
                        )
                    }
                    val recurrenceTag = detectRecurrenceTag(slot.name, slot.detail)
                    if (recurrenceTag != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            recurrenceTag,
                            modifier = Modifier
                                .background(Color(0xFFE3F2FD), AppShapes.Pill)
                                .border(1.dp, Color(0xFF90CAF9), AppShapes.Pill)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 8.sp, color = Color(0xFF1565C0)),
                        )
                    }
                    // Restaurant price level ($$) — show in black next to name
                    val isRestaurantPrice = slot.price.isNotBlank() && slot.price.all { it == '$' || it == ' ' }
                    if (isRestaurantPrice) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            slot.price.trim(),
                            style = TextStyle(
                                fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp,
                                color = AppColors.Ink,
                            ),
                        )
                    } else if (slot.price.isNotBlank() && !slot.isFree) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            slot.price,
                            style = TextStyle(
                                fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 11.sp,
                                color = AppColors.Ink2,
                            ),
                        )
                    }
                }
                // Detail line — for restaurants, strip duplicate $$ from detail
                val displayDetail = if (slot.price.isNotBlank() && slot.price.all { it == '$' || it == ' ' }) {
                    stripEmoji(slot.detail)
                        .replace(Regex("\\$+\\s*[·\\-]?\\s*"), "") // remove "$$  · " from detail
                        .replace(Regex("^\\s*[·\\-]\\s*"), "") // remove leading separator
                        .trim()
                } else {
                    stripEmoji(slot.detail)
                }
                Text(
                    displayDetail,
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = AppColors.Ink3),
                    maxLines = 1,
                )
            }

            // Remove button (only if not approved)
            if (!isApproved) {
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(AppColors.Mist, RoundedCornerShape(8.dp))
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Close, "Remove", Modifier.size(12.dp), tint = AppColors.Ink3)
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
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        items(replies) { reply ->
            QuickReplyChip(reply = reply, onClick = { onReply(reply) })
        }
    }
}

@Composable
private fun QuickReplyChip(reply: QuickReply, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current

    val bgColor = if (reply.isGreen) AppColors.SageLt else AppColors.White
    val borderColor = if (reply.isGreen) AppColors.Sage else Color(0xFFE2DCF0)
    val shadowColor = if (reply.isGreen) Color(0xFFB8EDD9) else Color(0xFFD8D2E8)
    val textColor = if (reply.isGreen) AppColors.SageDk else AppColors.Ink2

    val chipScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = tween(80),
    )

    Box(modifier = Modifier.graphicsLayer { scaleX = chipScale; scaleY = chipScale }) {
        Box(Modifier.matchParentSize().offset(y = if (isPressed) 0.dp else 3.dp).background(shadowColor, AppShapes.Pill))
        Box(
            Modifier.background(bgColor, AppShapes.Pill).border(2.dp, borderColor, AppShapes.Pill)
                .clip(AppShapes.Pill)
                .clickable(interactionSource = interactionSource, indication = null, onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                })
                .padding(horizontal = 13.dp, vertical = 7.dp),
        ) {
            Text(
                reply.label, maxLines = 1,
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, color = textColor),
            )
        }
    }
}

// ── Input Bar ──

@Composable
private fun ChatInputBar(enabled: Boolean, placeholder: String, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = Modifier.fillMaxWidth().background(AppColors.Snow)
            .padding(horizontal = 14.dp, vertical = 8.dp).padding(bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                .background(if (enabled) AppColors.Mist else Color(0xFFEDEAF3), RoundedCornerShape(14.dp))
                .border(2.dp, if (enabled) AppColors.Pebble else Color(0xFFD8D4E4), RoundedCornerShape(14.dp))
                .padding(horizontal = 13.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(placeholder, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Ink3))
            }
            if (enabled) {
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { onSend(text); text = "" } }),
                    singleLine = false, maxLines = 3,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        val canSend = enabled && text.isNotBlank()
        Box(
            modifier = Modifier.size(44.dp).graphicsLayer { alpha = if (canSend) 1f else 0.4f }
                .background(Brush.linearGradient(listOf(AppColors.Coral, AppColors.CoralDk)), RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .then(if (canSend) Modifier.clickable { onSend(text); text = "" } else Modifier),
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
            items(events, key = { it.id }) { event ->
                AlsoHappeningCard(event = event, onClick = { onEventTap(event) })
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

/** Strip emoji and other non-BMP Unicode characters that render as question marks */
private fun stripEmoji(text: String): String {
    // Kotlin/Native doesn't support \x{} regex syntax, so filter by code point
    return text.filter { char ->
        val cp = char.code
        // Keep ASCII + common Unicode (Latin, Cyrillic, etc.)
        // Filter out emoji ranges and other non-BMP characters
        cp < 0x2600 || (cp in 0x2800..0xFDFF) || (cp in 0xFE10..0xFE1F) || (cp in 0xFF00..0xFFEF)
    }.replace(Regex("\\s{2,}"), " ").trim()
}

// ── Plan Success Overlay ──

@Composable
private fun PlanSuccessOverlay(
    planTitle: String,
    days: List<ItineraryDay>,
    onViewPlans: () -> Unit,
    onHome: () -> Unit,
    onInviteFriends: () -> Unit,
) {
    val subtitle = remember(days) {
        if (days.isEmpty()) ""
        else if (days.size == 1) {
            // Single day: extract date part from label like "Saturday, March 28"
            val label = days.first().label
            val datePart = label.substringAfter(", ", label)
            datePart
        } else {
            // Multi-day: "March 28 to 30" or "March 28 to April 1"
            val first = days.first().label.substringAfter(", ", days.first().label)
            val last = days.last().label.substringAfter(", ", days.last().label)
            val firstMonth = first.substringBefore(" ")
            val lastMonth = last.substringBefore(" ")
            if (firstMonth == lastMonth) {
                val lastDay = last.substringAfter(" ")
                "$first to $lastDay"
            } else {
                "$first to $last"
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
    val homeOffsetY = remember { Animatable(60f) }
    val homeAlpha = remember { Animatable(0f) }

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
        // Check
        launch {
            delay(400)
            checkScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
        // Title
        launch {
            delay(850)
            titleScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
        // Subtitle
        launch {
            delay(1100)
            subtitleAlpha.animateTo(1f, tween(400))
        }
        // Plan card
        launch {
            delay(1200)
            launch { cardOffsetY.animateTo(0f, tween(500, easing = EaseOutCubic)) }
            cardAlpha.animateTo(1f, tween(500))
        }
        // CTAs — staggered: Share, View Plans, Home
        launch {
            delay(1600)
            launch { shareOffsetY.animateTo(0f, tween(400, easing = EaseOutCubic)) }
            shareAlpha.animateTo(1f, tween(400))
        }
        launch {
            delay(1750)
            launch { plansOffsetY.animateTo(0f, tween(400, easing = EaseOutCubic)) }
            plansAlpha.animateTo(1f, tween(400))
        }
        launch {
            delay(1900)
            launch { homeOffsetY.animateTo(0f, tween(400, easing = EaseOutCubic)) }
            homeAlpha.animateTo(1f, tween(400))
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
                .padding(bottom = 170.dp),
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
                        onRemoveSlot = { _, _ -> },
                        onEventClick = { },
                    )
                } else {
                    DayCard(
                        day = days.firstOrNull() ?: ItineraryDay(label = "", shortName = ""),
                        dayIndex = 0,
                        totalDays = 1,
                        onRemoveSlot = { },
                        onEventClick = { },
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

                // View Plans
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationY = plansOffsetY.value
                            alpha = plansAlpha.value
                        }
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                        .border(1.5.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onViewPlans() }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "View Plans",
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            fontFamily = AppFonts.Heading,
                        ),
                    )
                }

                // Home
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationY = homeOffsetY.value
                            alpha = homeAlpha.value
                        }
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onHome() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Home",
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.7f),
                            fontFamily = AppFonts.Heading,
                        ),
                    )
                }
            }
        }
    }

