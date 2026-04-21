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
import androidx.compose.ui.platform.LocalUriHandler
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
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale
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
    onNativeEventClick: ((String, String?) -> Unit)? = null,  // eventId, fallbackImageUrl
    onNativeVenueClick: ((String) -> Unit)? = null,           // venueName
    showUpgradeBanner: Boolean = false,
    onUpgrade: () -> Unit = {},
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
            .background(AppColors.Snow),
    ) {
        // ── Header ──
        ChatHeader(onBack = onBack, onReset = { viewModel.resetChat() })

        // ── Upgrade Banner (for users who declined paywall) ──
        if (showUpgradeBanner) {
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
                        "Build personalized and shareable plans with Yocal",
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
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).nestedScroll(remember {
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
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                val isSuperseded = viewModel.isPlanSuperseded(message.id)
                when (message.content) {
                    is MessageContent.Text -> MessageRow(message.role, (message.content as MessageContent.Text).text)
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
                }
            }

            // Streaming text / searching / typing indicator
            if (state.isSearching) {
                item { SearchingIndicator() }
            } else if (state.streamingText.isNotEmpty()) {
                item { MessageRow(role = MessageRole.AI, text = state.streamingText) }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Snow)
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

        Text(
            "ask yocal",
            modifier = Modifier.weight(1f),
            style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 18.sp, color = AppColors.Ink),
        )

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
    // Subtle divider
    Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.Pebble.copy(alpha = 0.5f)))
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
private fun MessageRow(role: MessageRole, text: String) {
    val isUser = role == MessageRole.USER

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) Spacer(Modifier.width(48.dp))

        if (isUser) {
            // User messages: bubble
            val bubbleShape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .background(Color(0xFFF0EDF6), bubbleShape)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Text(
                    cleanMarkdown(text),
                    style = TextStyle(
                        fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        color = AppColors.Ink, lineHeight = (13 * 1.55).sp,
                    ),
                )
            }
        } else {
            // AI messages: small avatar + text, no bubble
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(16.dp).padding(top = 4.dp),
                tint = AppColors.Coral,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(vertical = 4.dp),
            ) {
                RichChatText(text = text, textColor = AppColors.Ink)
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

    Row(
        modifier = Modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        val bubbleShape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
        Box {
            Box(
                Modifier
                    .background(AppColors.White, bubbleShape)
                    .border(1.dp, AppColors.Pebble.copy(alpha = 0.6f), bubbleShape)
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
    onEventClick: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
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

            // Inline actions — only rating + reserve (directions/tickets live on the detail page)
            val hasActions = slot.venueRating != null || slot.reservationUrl != null
            if (hasActions) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (slot.venueRating != null) {
                        Text("★ ${slot.venueRating}", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = AppColors.Sun))
                    }
                    if (slot.reservationUrl != null) {
                        Text(
                            "Reserve",
                            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                val deepUrl = buildReservationDeepLink(slot.reservationUrl, slot.time)
                                uriHandler.openUri(deepUrl)
                            },
                            style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp, color = if (hasImage) AppColors.White else AppColors.Coral),
                        )
                    }
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
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(placeholder, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Ink3))
            }
            if (enabled) {
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Ink),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { onSend(text); text = "" } }),
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

/** Renders chat text with bold headers, formatted bullets, and visual hierarchy. */
@Composable
private fun RichChatText(text: String, textColor: Color) {
    // Simple: just render **bold** inline, leave everything else as-is
    val annotated = remember(text) {
        val cleaned = text
            .replace(Regex("^#+\\s+", RegexOption.MULTILINE), "") // strip markdown headers
        buildAnnotatedString {
            var remaining = cleaned
            while (remaining.isNotEmpty()) {
                val boldStart = remaining.indexOf("**")
                if (boldStart < 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = textColor)) {
                        append(remaining)
                    }
                    break
                }
                if (boldStart > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = textColor)) {
                        append(remaining.substring(0, boldStart))
                    }
                }
                remaining = remaining.substring(boldStart + 2)
                val boldEnd = remaining.indexOf("**")
                if (boldEnd < 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = textColor)) {
                        append("**$remaining")
                    }
                    break
                }
                withStyle(SpanStyle(fontWeight = FontWeight.Black, fontSize = 13.sp, color = textColor)) {
                    append(remaining.substring(0, boldEnd))
                }
                remaining = remaining.substring(boldEnd + 2)
            }
        }
    }

    Text(
        text = annotated,
        style = TextStyle(
            fontFamily = AppFonts.Body,
            lineHeight = 19.sp,
        ),
    )
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

/** Extract a numeric price from strings like "$25", "From $15", "$$" */
/** Build a deep link to Resy or OpenTable with date/time/seats prefilled. */
private fun buildReservationDeepLink(baseUrl: String, slotTime: String, seats: Int = 2): String {
    val today = kotlin.time.Clock.System.now().toString().take(10) // YYYY-MM-DD

    return when {
        baseUrl.contains("resy.com") -> {
            "$baseUrl?date=$today&seats=$seats"
        }
        baseUrl.contains("opentable.com") -> {
            // Parse slot time like "7 PM" or "7:30 PM" into HH:mm format
            val timeParam = try {
                val parts = slotTime.uppercase().replace(".", "").trim().split(" ")
                val timePart = parts[0]
                val isPm = parts.getOrNull(1)?.contains("PM") == true
                val hm = timePart.split(":")
                var hour = hm[0].toInt()
                val min = hm.getOrNull(1)?.toInt() ?: 0
                if (isPm && hour < 12) hour += 12
                if (!isPm && hour == 12) hour = 0
                "${hour.toString().padStart(2, '0')}:${min.toString().padStart(2, '0')}"
            } catch (_: Exception) { "19:00" }
            "$baseUrl?dateTime=${today}T${timeParam}&covers=$seats&partySize=$seats"
        }
        else -> baseUrl
    }
}

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

