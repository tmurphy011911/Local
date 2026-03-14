package live.localapp.local.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ── Enums ──

enum class ChatMode { NONE, SINGLE, ITINERARY }
enum class MessageRole { AI, USER }

enum class ChatStage {
    INTAKE_DURATION,
    INTAKE_PEOPLE,
    INTAKE_DATE,          // single-day date selection
    INTAKE_DATE_START,    // multi-day: pick start date
    INTAKE_DATE_END,      // multi-day: pick end date (max 7 days from start)
    SINGLE_GATHERING,
    SINGLE_RESULTS,
    ITIN_GATHERING,
    ITIN_PRE_DAY,
    ITIN_DAY_VIEW,
    ITIN_DONE,
}

// ── Message models ──

sealed interface MessageContent {
    data class Text(val text: String) : MessageContent
    data class DayCardRef(val dayIndex: Int) : MessageContent
    data class SingleResultsRef(val bucket: String) : MessageContent
    data class SummaryRef(val dummy: Unit = Unit) : MessageContent
    data class ProgressRef(val dummy: Unit = Unit) : MessageContent
}

data class ChatMessage(
    val id: String = generateId(),
    val role: MessageRole,
    val content: MessageContent,
)

data class QuickReply(
    val label: String,
    val isGreen: Boolean = false,
    val action: String = label,
)

// ── Itinerary models ──

data class ItinerarySlot(
    val id: String,
    val time: String,
    val emoji: String,
    val name: String,
    val detail: String,
    val price: String,
    val isFree: Boolean,
)

data class TravelInfo(
    val mode: String, // "walk" or "drive"
    val mins: Int,
)

data class ItineraryDay(
    val label: String,
    val shortName: String,
    val slots: List<ItinerarySlot>,
    val isApproved: Boolean = false,
    val travel: List<TravelInfo> = emptyList(),
)

// ── Single mode models ──

data class SinglePick(
    val emoji: String,
    val name: String,
    val detail: String,
    val price: String,
    val tag: String,
)

data class SingleMention(
    val emoji: String,
    val name: String,
    val detail: String,
)

data class SingleResults(
    val picks: List<SinglePick>,
    val mentions: List<SingleMention>,
)

// ── UI State ──

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val quickReplies: List<QuickReply> = emptyList(),
    val mode: ChatMode = ChatMode.NONE,
    val stage: ChatStage = ChatStage.INTAKE_DURATION,
    val isTyping: Boolean = false,
    val isBusy: Boolean = false,
    val isLoading: Boolean = false,
    val loadingTitle: String = "",
    val loadingSubtitle: String = "",
    val inputEnabled: Boolean = false,
    val inputPlaceholder: String = "Answer the questions above to get started\u2026",
    // Itinerary state
    val days: List<ItineraryDay> = emptyList(),
    val approvedDayCount: Int = 0,
    val currentDayIndex: Int = 0,
    val planTitle: String = "",
    val planDates: String = "",
    val planGroup: String = "",
    // Single mode state
    val singleResults: Map<String, SingleResults> = SINGLE_RESULTS_DATA,
    // Intake state
    val gatheredDuration: String? = null, // "single" or "multi"
    val gatheredPeople: String? = null,
    val gatheredDates: String? = null,
    val gatheredStartDate: String? = null, // ISO date for multi-day start (YYYY-MM-DD)
    val gatheredVibe: String? = null,
)

private var idCounter = 0L
private fun generateId(): String = "msg_${++idCounter}"

class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        showWelcome()
    }

    private fun showWelcome() {
        addAiMessage("Hey! \uD83D\uDC4B I'm your Boston concierge. Let's get started \u2014 are you looking for something for a single day or multiple days?")
        _uiState.update {
            it.copy(
                stage = ChatStage.INTAKE_DURATION,
                inputEnabled = false,
                quickReplies = listOf(
                    QuickReply("\uD83D\uDCC5 Single day", action = "intake_single_day"),
                    QuickReply("\uD83D\uDDD3\uFE0F Multi-day", action = "intake_multi_day"),
                ),
            )
        }
    }

    // ── Public actions ──

    fun selectMode(mode: ChatMode) {
        _uiState.update { it.copy(mode = mode, quickReplies = emptyList()) }
        when (mode) {
            ChatMode.SINGLE -> startSingleMode()
            ChatMode.ITINERARY -> startItineraryMode()
            ChatMode.NONE -> {}
        }
    }

    // ── Structured Intake Flow ──

    private fun handleIntakeDuration(isSingleDay: Boolean) {
        val label = if (isSingleDay) "Single day" else "Multi-day"
        addUserMessage(label)
        _uiState.update {
            it.copy(
                gatheredDuration = if (isSingleDay) "single" else "multi",
                mode = if (isSingleDay) ChatMode.SINGLE else ChatMode.ITINERARY,
                quickReplies = emptyList(),
            )
        }
        viewModelScope.launch {
            setBusy(true)
            delay(600)
            addAiMessage("How many people is this for?")
            _uiState.update {
                it.copy(
                    stage = ChatStage.INTAKE_PEOPLE,
                    isBusy = false, isTyping = false,
                    inputEnabled = false,
                    quickReplies = listOf(
                        QuickReply("Just me", action = "intake_people_1"),
                        QuickReply("2 people", action = "intake_people_2"),
                        QuickReply("3\u20134 people", action = "intake_people_3-4"),
                        QuickReply("5+ people", action = "intake_people_5+"),
                    ),
                )
            }
        }
    }

    private fun handleIntakePeople(label: String, count: String) {
        addUserMessage(label)
        _uiState.update {
            it.copy(gatheredPeople = count, quickReplies = emptyList())
        }
        viewModelScope.launch {
            setBusy(true)
            delay(600)
            val isMulti = _uiState.value.gatheredDuration == "multi"
            if (isMulti) {
                addAiMessage("When does your trip start?")
                _uiState.update {
                    it.copy(
                        stage = ChatStage.INTAKE_DATE_START,
                        isBusy = false, isTyping = false,
                        inputEnabled = false,
                        quickReplies = emptyList(),
                    )
                }
            } else {
                addAiMessage("What day works best?")
                _uiState.update {
                    it.copy(
                        stage = ChatStage.INTAKE_DATE,
                        isBusy = false, isTyping = false,
                        inputEnabled = false,
                        quickReplies = listOf(
                            QuickReply("Today", action = "intake_date_today"),
                            QuickReply("Tomorrow", action = "intake_date_tomorrow"),
                            QuickReply("This Saturday", action = "intake_date_saturday"),
                            QuickReply("This Sunday", action = "intake_date_sunday"),
                        ),
                    )
                }
            }
        }
    }

    private fun handleIntakeDate(label: String) {
        addUserMessage(label)
        _uiState.update {
            it.copy(gatheredDates = label, quickReplies = emptyList())
        }
        // All intake done — transition to the appropriate mode
        val mode = _uiState.value.mode
        when (mode) {
            ChatMode.SINGLE -> startSingleMode()
            ChatMode.ITINERARY -> startItineraryMode()
            ChatMode.NONE -> {}
        }
    }

    /** Multi-day: user picked a start date (ISO string YYYY-MM-DD) */
    fun handleIntakeDateStart(isoDate: String, displayLabel: String) {
        if (_uiState.value.isBusy) return
        addUserMessage(displayLabel)
        _uiState.update {
            it.copy(gatheredStartDate = isoDate, quickReplies = emptyList())
        }
        viewModelScope.launch {
            setBusy(true)
            delay(500)
            addAiMessage("And when does it end? (7 days max)")
            _uiState.update {
                it.copy(
                    stage = ChatStage.INTAKE_DATE_END,
                    isBusy = false, isTyping = false,
                    inputEnabled = false,
                    quickReplies = emptyList(),
                )
            }
        }
    }

    /** Multi-day: user picked an end date (ISO string YYYY-MM-DD) */
    fun handleIntakeDateEnd(isoDate: String, displayLabel: String) {
        if (_uiState.value.isBusy) return
        val startDate = _uiState.value.gatheredStartDate ?: return
        addUserMessage(displayLabel)
        val dateRange = "$startDate to $isoDate"
        _uiState.update {
            it.copy(gatheredDates = dateRange, quickReplies = emptyList())
        }
        startItineraryMode()
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isBusy) return
        addUserMessage(text.trim())
        handleInput(text.trim())
    }

    fun onQuickReply(reply: QuickReply) {
        if (_uiState.value.isBusy) return
        when {
            // Intake flow
            reply.action == "intake_single_day" -> handleIntakeDuration(isSingleDay = true)
            reply.action == "intake_multi_day" -> handleIntakeDuration(isSingleDay = false)
            reply.action.startsWith("intake_people_") -> {
                val key = reply.action.removePrefix("intake_people_")
                handleIntakePeople(reply.label, key)
            }
            reply.action.startsWith("intake_date_") -> handleIntakeDate(reply.label)
            // Existing actions
            reply.action == "reset" -> resetChat()
            reply.action == "approve_day" -> approveCurrentDay()
            reply.action == "change_day" -> requestChange()
            reply.action == "start_building" -> {
                addUserMessage("Start building")
                startBuildingDay()
            }
            reply.action == "has_notes" -> {
                _uiState.update {
                    it.copy(
                        inputEnabled = true,
                        inputPlaceholder = "e.g. avoid seafood, dinner at 8pm\u2026",
                        quickReplies = emptyList(),
                    )
                }
            }
            reply.action == "save_plan" -> savePlan()
            reply.action == "share_plan" -> sharePlan()
            reply.action == "turn_into_itinerary" -> {
                selectMode(ChatMode.ITINERARY)
            }
            reply.action == "different_vibe" -> {
                addUserMessage("Show me something different")
                _uiState.update { it.copy(gatheredVibe = null) }
                handleSingleGathering("something different")
            }
            else -> sendMessage(reply.label)
        }
    }

    fun removeSlot(dayIndex: Int, slotId: String) {
        _uiState.update { state ->
            val days = state.days.toMutableList()
            val day = days[dayIndex]
            val newSlots = day.slots.filter { it.id != slotId }
            val newTravel = if (day.travel.size > 1) {
                day.travel.toMutableList().also {
                    val idx = day.slots.indexOfFirst { s -> s.id == slotId }
                    if (idx in it.indices) it.removeAt(idx)
                }
            } else emptyList()
            days[dayIndex] = day.copy(slots = newSlots, travel = newTravel)
            state.copy(days = days)
        }
    }

    fun moveSlot(dayIndex: Int, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        _uiState.update { state ->
            val days = state.days.toMutableList()
            val day = days[dayIndex]
            val slots = day.slots.toMutableList()
            val item = slots.removeAt(fromIndex)
            slots.add(toIndex, item)
            days[dayIndex] = day.copy(slots = slots)
            state.copy(days = days)
        }
    }

    fun resetChat() {
        _uiState.value = ChatUiState()
        showWelcome()
    }

    // ── Single Mode ──

    private fun startSingleMode() {
        val people = _uiState.value.gatheredPeople
        val dates = _uiState.value.gatheredDates
        val ps = when (people) {
            "1" -> "just you"
            "2" -> "2 people"
            "3-4" -> "your group"
            "5+" -> "the crew"
            else -> "you"
        }
        viewModelScope.launch {
            setBusy(true)
            delay(600)
            addAiMessage("Got it \u2014 $ps on ${dates ?: "your day"}. What kind of experience are you in the mood for?")
            _uiState.update {
                it.copy(
                    stage = ChatStage.SINGLE_GATHERING,
                    isBusy = false, isTyping = false,
                    inputEnabled = true,
                    inputPlaceholder = "e.g. \"live music\" or \"dinner spot\"\u2026",
                    quickReplies = listOf(
                        QuickReply("\uD83C\uDFB6 Live music"),
                        QuickReply("\uD83C\uDF7D\uFE0F Dinner spot"),
                        QuickReply("\uD83C\uDFAD Show or comedy"),
                        QuickReply("\uD83C\uDF3F Outdoors"),
                        QuickReply("\uD83C\uDFA8 Arts + culture"),
                        QuickReply("\uD83C\uDD93 Free events"),
                    ),
                )
            }
        }
    }

    private fun handleSingleGathering(text: String) {
        viewModelScope.launch {
            val vibe = _uiState.value.gatheredVibe ?: extractVibe(text)
            _uiState.update { it.copy(gatheredVibe = vibe) }

            // Show results
            setBusy(true)
            delay(1400)

            val people = _uiState.value.gatheredPeople
            val bucket = getBucket("${vibe ?: ""} $text")
            val ps = when (people) {
                "1" -> "you"
                "2" -> "2 people"
                "3-4" -> "your group"
                "5+" -> "the crew"
                else -> "you"
            }
            addAiMessage("Here's what I'd recommend for $ps in Boston:")
            _uiState.update { it.copy(isTyping = false) }
            delay(300)

            addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.SingleResultsRef(bucket)))
            delay(300)

            addAiMessage("Tap any pick to save it, or tell me more \u2014 neighbourhood, budget, tonight only, anything.")
            _uiState.update {
                it.copy(
                    stage = ChatStage.SINGLE_RESULTS,
                    isBusy = false, isTyping = false,
                    inputEnabled = true,
                    quickReplies = listOf(
                        QuickReply("\uD83D\uDDD3\uFE0F Turn into an itinerary", action = "turn_into_itinerary"),
                        QuickReply("\uD83D\uDD04 Different vibe", action = "different_vibe"),
                        QuickReply("\uD83D\uDCB0 Budget-friendly only"),
                    ),
                )
            }
        }
    }

    // ── Itinerary Mode ──

    private fun startItineraryMode() {
        val people = _uiState.value.gatheredPeople
        val dates = _uiState.value.gatheredDates
        val ps = when (people) {
            "1" -> "just you"
            "2" -> "2 people"
            "3-4" -> "your group"
            "5+" -> "the crew"
            else -> "you"
        }
        viewModelScope.launch {
            setBusy(true)
            delay(600)
            addAiMessage("Awesome \u2014 $ps, ${dates ?: "your trip"}. What kind of vibe are you going for?")
            _uiState.update {
                it.copy(
                    stage = ChatStage.ITIN_GATHERING,
                    isBusy = false, isTyping = false,
                    inputEnabled = true,
                    inputPlaceholder = "e.g. \"food + culture\" or \"mix of everything\"\u2026",
                    quickReplies = listOf(
                        QuickReply("\uD83C\uDF7D\uFE0F Food + culture"),
                        QuickReply("\uD83C\uDF1F Nightlife"),
                        QuickReply("\uD83C\uDF3F Outdoors"),
                        QuickReply("\uD83C\uDFA8 Arts + museums"),
                        QuickReply("\uD83C\uDF89 Mix of everything"),
                        QuickReply("\uD83D\uDC76 Family-friendly"),
                    ),
                )
            }
        }
    }

    private fun handleItineraryGathering(text: String) {
        viewModelScope.launch {
            val vibe = _uiState.value.gatheredVibe ?: extractVibe(text)
            _uiState.update { it.copy(gatheredVibe = vibe) }

            val people = _uiState.value.gatheredPeople
            val groupLabel = when (people) {
                "1" -> "Just you"
                "2" -> "2 people"
                "3-4" -> "3\u20134 people"
                "5+" -> "5+ people"
                else -> "Your group"
            }
            val dates = _uiState.value.gatheredDates ?: "your trip"

            // Initialize plan and go to pre-day
            _uiState.update {
                it.copy(
                    days = SAMPLE_PLAN.map { it.copy() },
                    planTitle = "Boston for First-Timers",
                    planDates = dates,
                    planGroup = groupLabel,
                    currentDayIndex = 0,
                    approvedDayCount = 0,
                )
            }

            setBusy(true)
            delay(1200)

            addAiMessage("Let's pull together an itinerary day by day. Anything you want me to keep in mind for ${DAY_NAMES[0]}, or should I start building?")
            _uiState.update {
                it.copy(
                    stage = ChatStage.ITIN_PRE_DAY,
                    isBusy = false, isTyping = false,
                    inputEnabled = true,
                    inputPlaceholder = "Any notes for ${DAY_NAMES[0]}? Or just say go\u2026",
                    quickReplies = listOf(
                        QuickReply("Start building \uD83D\uDE80", isGreen = true, action = "start_building"),
                        QuickReply("\u270F\uFE0F I have some notes", action = "has_notes"),
                    ),
                )
            }
        }
    }

    private fun startBuildingDay() {
        val dayIndex = _uiState.value.currentDayIndex
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    quickReplies = emptyList(),
                    inputEnabled = false,
                    isLoading = true,
                    loadingTitle = "Planning ${DAY_NAMES[dayIndex]}",
                    loadingSubtitle = "Finding the best spots...",
                )
            }

            // Simulate planning
            delay(1000)
            _uiState.update { it.copy(loadingSubtitle = "Mixing iconic with hidden gems...") }
            delay(1000)
            _uiState.update { it.copy(loadingSubtitle = "Building your timeline...") }
            delay(800)
            _uiState.update { it.copy(isLoading = false) }

            showDay(dayIndex)
        }
    }

    private suspend fun showDay(dayIndex: Int) {
        // Progress indicator
        addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.ProgressRef()))
        delay(300)

        // Day intro
        addAiMessage(DAY_OPENERS[dayIndex])
        delay(350)

        // Day card
        addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.DayCardRef(dayIndex)))
        delay(300)

        // Question
        addAiMessage(DAY_QUESTIONS[dayIndex])

        _uiState.update {
            it.copy(
                stage = ChatStage.ITIN_DAY_VIEW,
                isBusy = false, isTyping = false,
                inputEnabled = true,
                inputPlaceholder = "Ask anything\u2026",
                quickReplies = listOf(
                    QuickReply("Looks good \u2713", isGreen = true, action = "approve_day"),
                    QuickReply("\u270F\uFE0F Change something", action = "change_day"),
                ),
            )
        }
    }

    private fun approveCurrentDay() {
        val dayIndex = _uiState.value.currentDayIndex
        viewModelScope.launch {
            addUserMessage("Looks good!")
            _uiState.update {
                it.copy(
                    isBusy = true,
                    quickReplies = emptyList(),
                    inputEnabled = false,
                )
            }

            // Mark day approved
            _uiState.update { state ->
                val days = state.days.toMutableList()
                days[dayIndex] = days[dayIndex].copy(isApproved = true)
                state.copy(
                    days = days,
                    approvedDayCount = dayIndex + 1,
                )
            }

            delay(900)

            val totalDays = _uiState.value.days.size
            if (dayIndex + 1 < totalDays) {
                // Move to next day
                val nextDay = dayIndex + 1
                _uiState.update { it.copy(currentDayIndex = nextDay) }
                addAiMessage("${DAY_NAMES[dayIndex]} is locked in! Anything you want me to keep in mind for ${DAY_NAMES[nextDay]}, or should I start building?")
                _uiState.update {
                    it.copy(
                        stage = ChatStage.ITIN_PRE_DAY,
                        isBusy = false, isTyping = false,
                        inputEnabled = true,
                        inputPlaceholder = "Any notes for ${DAY_NAMES[nextDay]}? Or just say go\u2026",
                        quickReplies = listOf(
                            QuickReply("Start building \uD83D\uDE80", isGreen = true, action = "start_building"),
                            QuickReply("\u270F\uFE0F I have some notes", action = "has_notes"),
                        ),
                    )
                }
            } else {
                // All days done — show summary
                delay(300)
                addAiMessage("You're all set! \uD83C\uDF89 All $totalDays days approved. Here's your full Boston plan:")
                _uiState.update { it.copy(isTyping = false) }
                delay(400)
                addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.SummaryRef()))
                delay(400)
                addAiMessage("\uD83D\uDCA1 Book Craigie on Main and Improv Asylum now \u2014 both sell out weeks out.")
                _uiState.update {
                    it.copy(
                        stage = ChatStage.ITIN_DONE,
                        isBusy = false, isTyping = false,
                        inputEnabled = true,
                        quickReplies = listOf(
                            QuickReply("\uD83D\uDDD3\uFE0F Save to Plans", isGreen = true, action = "save_plan"),
                            QuickReply("\uD83D\uDCE4 Share with friends", action = "share_plan"),
                        ),
                    )
                }
            }
        }
    }

    private fun requestChange() {
        val dayIndex = _uiState.value.currentDayIndex
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, quickReplies = emptyList()) }
            delay(600)
            addAiMessage("Of course \u2014 what would you like to change about ${DAY_NAMES[dayIndex]}?")
            _uiState.update {
                it.copy(
                    isBusy = false, isTyping = false,
                    inputEnabled = true,
                    quickReplies = listOf(
                        QuickReply("Swap the brewery"),
                        QuickReply("Earlier start + brunch"),
                        QuickReply("More nightlife"),
                        QuickReply("Budget-friendly"),
                    ),
                )
            }
        }
    }

    private fun handleDayChange(text: String) {
        val dayIndex = _uiState.value.currentDayIndex
        viewModelScope.launch {
            setBusy(true)
            delay(1300)

            // Apply a patch if we recognize the request
            val patch = PATCHES.firstOrNull { it.regex.containsMatchIn(text) }
            if (patch != null) {
                _uiState.update { state ->
                    val days = state.days.toMutableList()
                    days[dayIndex] = patch.apply(days[dayIndex])
                    state.copy(days = days)
                }
                addAiMessage(patch.reply + " Here's the updated day:")
            } else {
                addAiMessage("I've updated the plan. Here's the latest:")
            }

            _uiState.update { it.copy(isTyping = false) }
            delay(300)
            addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.DayCardRef(dayIndex)))
            delay(300)
            addAiMessage(DAY_QUESTIONS[dayIndex])

            _uiState.update {
                it.copy(
                    isBusy = false, isTyping = false,
                    quickReplies = listOf(
                        QuickReply("Looks good \u2713", isGreen = true, action = "approve_day"),
                        QuickReply("\u270F\uFE0F Change something else", action = "change_day"),
                    ),
                )
            }
        }
    }

    private fun savePlan() {
        viewModelScope.launch {
            setBusy(true)
            delay(600)
            addAiMessage("\u2705 Saved to your Plans tab!")
            delay(350)
            addAiMessage("You can edit this plan anytime \u2014 swap something, adjust a time, or add an event.")
            _uiState.update {
                it.copy(
                    isBusy = false, isTyping = false,
                    quickReplies = listOf(
                        QuickReply("\uD83D\uDCE4 Share with the group", action = "share_plan"),
                    ),
                )
            }
        }
    }

    private fun sharePlan() {
        viewModelScope.launch {
            setBusy(true)
            delay(600)
            addAiMessage("\uD83D\uDCE4 Share link copied!")
            delay(350)
            addAiMessage("Your friends can view the full itinerary. You stay as Organizer \u2014 hand it off anytime in Plan Settings.")
            _uiState.update {
                it.copy(
                    isBusy = false, isTyping = false,
                    quickReplies = listOf(
                        QuickReply("\uD83D\uDDD3\uFE0F Save to Plans", isGreen = true, action = "save_plan"),
                    ),
                )
            }
        }
    }

    // ── Input router ──

    private fun handleInput(text: String) {
        _uiState.update { it.copy(quickReplies = emptyList()) }
        when (_uiState.value.stage) {
            ChatStage.INTAKE_DURATION, ChatStage.INTAKE_PEOPLE, ChatStage.INTAKE_DATE,
            ChatStage.INTAKE_DATE_START, ChatStage.INTAKE_DATE_END -> {} // input disabled during intake
            ChatStage.SINGLE_GATHERING, ChatStage.SINGLE_RESULTS -> handleSingleGathering(text)
            ChatStage.ITIN_GATHERING -> handleItineraryGathering(text)
            ChatStage.ITIN_PRE_DAY -> {
                val isGo = text.lowercase().let {
                    it.contains("start building") || it.contains("go for it") || it.contains("just go") || it.contains("no notes") || it == "go"
                }
                if (isGo) {
                    startBuildingDay()
                } else {
                    // User has notes — acknowledge and build
                    viewModelScope.launch {
                        setBusy(true)
                        delay(800)
                        addAiMessage("Got it \u2014 I'll keep that in mind.")
                        _uiState.update { it.copy(isTyping = false) }
                        delay(400)
                        startBuildingDay()
                    }
                }
            }
            ChatStage.ITIN_DAY_VIEW -> handleDayChange(text)
            ChatStage.ITIN_DONE -> {
                viewModelScope.launch {
                    setBusy(true)
                    delay(900)
                    addAiMessage("The plan is saved \u2014 what would you like to change?")
                    _uiState.update {
                        it.copy(
                            isBusy = false, isTyping = false,
                            quickReplies = listOf(
                                QuickReply("\uD83D\uDDD3\uFE0F Save to Plans", isGreen = true, action = "save_plan"),
                                QuickReply("\uD83D\uDCE4 Share", action = "share_plan"),
                            ),
                        )
                    }
                }
            }
        }
    }

    // ── Helpers ──

    private fun setBusy(busy: Boolean) {
        _uiState.update { it.copy(isBusy = busy, isTyping = busy, inputEnabled = !busy) }
    }

    private fun currentDayName(): String {
        val idx = _uiState.value.currentDayIndex
        return if (idx in DAY_NAMES.indices) DAY_NAMES[idx] else "Day ${idx + 1}"
    }

    private fun addAiMessage(text: String) {
        addMessage(ChatMessage(role = MessageRole.AI, content = MessageContent.Text(text)))
    }

    private fun addUserMessage(text: String) {
        addMessage(ChatMessage(role = MessageRole.USER, content = MessageContent.Text(text)))
    }

    private fun addMessage(msg: ChatMessage) {
        _uiState.update { it.copy(messages = it.messages + msg) }
    }

    private fun extractPeople(txt: String): String? {
        val m = Regex("""(\d+)\s*(person|people|friend|guest|of us|pax)""", RegexOption.IGNORE_CASE).find(txt)
        if (m != null) return m.groupValues[1]
        if (Regex("just me|solo|myself|alone", RegexOption.IGNORE_CASE).containsMatchIn(txt)) return "1"
        if (Regex("couple|two of us|date", RegexOption.IGNORE_CASE).containsMatchIn(txt)) return "2"
        if (Regex("family|kids|children", RegexOption.IGNORE_CASE).containsMatchIn(txt)) return "4"
        if (Regex("group", RegexOption.IGNORE_CASE).containsMatchIn(txt)) return "5"
        return null
    }

    private fun extractVibe(txt: String): String? {
        if (Regex("food|eat|restaurant|dinner|lunch|brunch", RegexOption.IGNORE_CASE).containsMatchIn(txt)) return "food"
        if (Regex("music|jazz|concert|live|band", RegexOption.IGNORE_CASE).containsMatchIn(txt)) return "music"
        if (Regex("outdoor|park|hike|walk|nature", RegexOption.IGNORE_CASE).containsMatchIn(txt)) return "outdoors"
        if (Regex("art|museum|culture|gallery", RegexOption.IGNORE_CASE).containsMatchIn(txt)) return "culture"
        if (Regex("nightlife|bar|club|drinks|party", RegexOption.IGNORE_CASE).containsMatchIn(txt)) return "nightlife"
        if (Regex("comedy|show|improv|theater", RegexOption.IGNORE_CASE).containsMatchIn(txt)) return "entertainment"
        if (Regex("family|kid|child", RegexOption.IGNORE_CASE).containsMatchIn(txt)) return "family"
        if (Regex("mix|everything|anything|surprise", RegexOption.IGNORE_CASE).containsMatchIn(txt)) return "mixed"
        if (Regex("free|budget|cheap|affordable", RegexOption.IGNORE_CASE).containsMatchIn(txt)) return "budget"
        return null
    }

    private fun getBucket(txt: String): String {
        if (Regex("music|jazz|concert|live|band|gig", RegexOption.IGNORE_CASE).containsMatchIn(txt)) return "music"
        if (Regex("dinner|eat|food|restaurant|lunch|brunch", RegexOption.IGNORE_CASE).containsMatchIn(txt)) return "dinner"
        return "default"
    }
}

// ── Sample Data ──

private val DAY_NAMES = listOf("Thursday", "Friday", "Saturday")

private val DAY_OPENERS = listOf(
    "Let's start with Thursday. Here's what I'm thinking \u2014 a mix of classics and local favorites:",
    "Thursday's approved! Here's Friday \u2014 a bit more relaxed, with culture and the harbor in the afternoon:",
    "Two down, one to go. Saturday is the grand finale \u2014 Fenway, Cambridge, and the best meal of the trip:",
)

private val DAY_QUESTIONS = listOf(
    "Does Thursday work? Approve it or tell me what to change.",
    "Friday looking good? Approve it or tweak anything.",
    "How's Saturday? Approve it and I'll put the full plan together.",
)

private val DAY_COLORS = listOf(0xFFFF6B6B, 0xFF4ECBA0, 0xFFFFB830)

fun dayColor(index: Int): Long = DAY_COLORS.getOrElse(index) { DAY_COLORS[0] }

private val SAMPLE_PLAN = listOf(
    ItineraryDay(
        label = "Thursday, April 10",
        shortName = "Thursday",
        slots = listOf(
            ItinerarySlot("a1", "12PM", "\uD83E\uDD9E", "Neptune Oyster", "Best lobster rolls \u00b7 North End \u00b7 Cash only", "$25\u201340", false),
            ItinerarySlot("a2", "2PM", "\uD83D\uDDFD", "Freedom Trail", "2.5 mile self-guided walk \u00b7 Starts at Boston Common", "Free", true),
            ItinerarySlot("a3", "5PM", "\uD83C\uDF7A", "Samuel Adams Brewery", "Tour + tasting \u00b7 Jamaica Plain \u00b7 30 min from downtown", "$5", false),
            ItinerarySlot("a4", "8PM", "\uD83C\uDFB8", "The Middle East", "Best live music in Cambridge \u00b7 Central Square", "$15\u201325", false),
            ItinerarySlot("a5", "11PM", "\uD83C\uDF78", "Drink", "No-menu cocktail bar \u00b7 Fort Point \u00b7 World-class", "$16\u201320", false),
        ),
        travel = listOf(
            TravelInfo("walk", 12), TravelInfo("drive", 18), TravelInfo("drive", 22), TravelInfo("walk", 8),
        ),
    ),
    ItineraryDay(
        label = "Friday, April 11",
        shortName = "Friday",
        slots = listOf(
            ItinerarySlot("b1", "10AM", "\uD83C\uDFA8", "Museum of Fine Arts", "World-class collection \u00b7 Fenway", "$27", false),
            ItinerarySlot("b2", "1PM", "\uD83C\uDF2E", "Taqueria El Amigo", "Hidden gem \u00b7 Jamaica Plain \u00b7 Locals only", "$10\u201315", false),
            ItinerarySlot("b3", "3PM", "\uD83D\uDEA4", "Boston Harbor Cruise", "Best skyline views \u00b7 Long Wharf \u00b7 1 hr", "$32", false),
            ItinerarySlot("b4", "6PM", "\uD83C\uDF77", "Pastoral Wine Bar", "Small plates + natural wine \u00b7 South End", "$30\u201350", false),
            ItinerarySlot("b5", "8PM", "\uD83C\uDFAD", "Improv Asylum", "Legendary comedy \u00b7 North End \u00b7 Book ahead", "$25\u201335", false),
        ),
        travel = listOf(
            TravelInfo("drive", 14), TravelInfo("drive", 20), TravelInfo("walk", 6), TravelInfo("walk", 9),
        ),
    ),
    ItineraryDay(
        label = "Saturday, April 12",
        shortName = "Saturday",
        slots = listOf(
            ItinerarySlot("c1", "10AM", "\u2615", "Thinking Cup", "Best coffee in Boston \u00b7 Tremont St", "$6\u20138", false),
            ItinerarySlot("c2", "11AM", "\uD83C\uDFDF\uFE0F", "Fenway Park Tour", "Iconic stadium \u00b7 Book online \u00b7 1 hr", "$25", false),
            ItinerarySlot("c3", "1PM", "\uD83C\uDF54", "Island Creek Oyster Bar", "Fenway \u00b7 Raw bar + lobster mac", "$40\u201360", false),
            ItinerarySlot("c4", "4PM", "\uD83D\uDCDA", "Harvard Square", "Cambridge \u00b7 Street performers, Brattle Books, Tatte", "Free", true),
            ItinerarySlot("c5", "7PM", "\uD83E\uDD9E", "Craigie on Main", "\u2B50 Michelin \u00b7 Cambridge \u00b7 Book now", "$85\u2013120", false),
        ),
        travel = listOf(
            TravelInfo("walk", 5), TravelInfo("walk", 3), TravelInfo("drive", 16), TravelInfo("drive", 8),
        ),
    ),
)

private data class PatchDef(
    val regex: Regex,
    val reply: String,
    val apply: (ItineraryDay) -> ItineraryDay,
)

private val PATCHES = listOf(
    PatchDef(
        regex = Regex("comedy|improv", RegexOption.IGNORE_CASE),
        reply = "Swapped Improv Asylum for a rooftop bar \u2014 better vibe for a group on a Friday night.",
        apply = { day ->
            day.copy(slots = day.slots.map { slot ->
                if (slot.id == "b5") slot.copy(emoji = "\uD83C\uDFD9\uFE0F", name = "Lookout Rooftop Bar", detail = "Best views in the Seaport \u00b7 Open until 1AM", price = "$15\u201325")
                else slot
            })
        },
    ),
    PatchDef(
        regex = Regex("brewery|beer", RegexOption.IGNORE_CASE),
        reply = "Swapped the brewery for the Gardner Museum \u2014 a stunning Venetian-style mansion.",
        apply = { day ->
            day.copy(slots = day.slots.map { slot ->
                if (slot.id == "a3") slot.copy(emoji = "\uD83C\uDFD9\uFE0F", name = "Gardner Museum", detail = "Stunning Venetian-style mansion \u00b7 Fenway", price = "$20")
                else slot
            })
        },
    ),
    PatchDef(
        regex = Regex("budget|cheap|affordable", RegexOption.IGNORE_CASE),
        reply = "Made it more budget-friendly \u2014 swapped Craigie on Main for legendary ramen.",
        apply = { day ->
            day.copy(slots = day.slots.map { slot ->
                if (slot.id == "c5") slot.copy(emoji = "\uD83C\uDF5C", name = "Yume Wo Katare", detail = "Legendary ramen \u00b7 Porter Sq \u00b7 Cash only", price = "$18")
                else slot
            })
        },
    ),
    PatchDef(
        regex = Regex("nightlife|late|bars", RegexOption.IGNORE_CASE),
        reply = "Added a jazz club to close out the night \u2014 Wally's Caf\u00e9 is a Boston institution.",
        apply = { day ->
            day.copy(slots = day.slots + ItinerarySlot("x_jazz", "11PM", "\uD83C\uDFB7", "Wally's Caf\u00e9 Jazz Club", "Jazz since 1947 \u00b7 South End \u00b7 No cover", "Free", true))
        },
    ),
    PatchDef(
        regex = Regex("brunch|breakfast|earlier", RegexOption.IGNORE_CASE),
        reply = "Added brunch at Flour Bakery to start the day right.",
        apply = { day ->
            day.copy(slots = listOf(ItinerarySlot("x_brunch", "9AM", "\uD83E\uDD5E", "Flour Bakery + Caf\u00e9", "Best brunch in Boston \u00b7 South End \u00b7 Get the sticky bun", "$15\u201322", false)) + day.slots)
        },
    ),
)

val SINGLE_RESULTS_DATA = mapOf(
    "music" to SingleResults(
        picks = listOf(
            SinglePick("\uD83C\uDFB8", "The Middle East", "Cambridge \u00b7 Central Square \u00b7 Indie, rock, hip-hop", "$15\u201325", "Best for discovery"),
            SinglePick("\uD83C\uDFB7", "Wally's Caf\u00e9 Jazz", "South End \u00b7 Jazz since 1947 \u00b7 No cover", "Free", "Most iconic"),
            SinglePick("\uD83E\uDD41", "Brighton Music Hall", "Allston \u00b7 Intimate 450-cap \u00b7 National touring acts", "$20\u201335", "Best production"),
        ),
        mentions = listOf(
            SingleMention("\uD83C\uDFB5", "Sinclair", "Harvard Square \u00b7 Mid-size \u00b7 Great sound"),
            SingleMention("\uD83C\uDFBA", "Scullers Jazz Club", "Back Bay \u00b7 Upscale jazz \u00b7 Dinner + show"),
            SingleMention("\uD83C\uDFA4", "City Winery", "South End \u00b7 Wine + live music \u00b7 Seated"),
            SingleMention("\uD83E\uDE97", "Lizard Lounge", "Cambridge \u00b7 Folk + acoustic \u00b7 Tiny + intimate"),
            SingleMention("\uD83C\uDFBB", "Berklee Performance Center", "Fenway \u00b7 World-class student showcases"),
        ),
    ),
    "dinner" to SingleResults(
        picks = listOf(
            SinglePick("\uD83E\uDD9E", "Neptune Oyster", "North End \u00b7 Best lobster roll \u00b7 Cash only", "$30\u201350", "Most legendary"),
            SinglePick("\uD83C\uDF5C", "Pammy's", "Cambridge \u00b7 Italian-ish \u00b7 Stunning room \u00b7 Book ahead", "$50\u201380", "Best date night"),
            SinglePick("\uD83E\uDD69", "Grill 23 & Bar", "Back Bay \u00b7 Classic steakhouse \u00b7 Special occasion", "$80\u2013120", "Most celebratory"),
        ),
        mentions = listOf(
            SingleMention("\uD83C\uDF5D", "Sulmona", "South End \u00b7 Handmade pasta \u00b7 Great wine list"),
            SingleMention("\uD83D\uDC1F", "Row 34", "Fort Point \u00b7 Raw bar + fish \u00b7 Always buzzing"),
            SingleMention("\uD83C\uDF2E", "Taqueria El Amigo", "Jamaica Plain \u00b7 Hidden gem \u00b7 Locals only"),
            SingleMention("\uD83C\uDF55", "Galleria Umberto", "North End \u00b7 $4 slices \u00b7 Cash \u00b7 Lunch only"),
            SingleMention("\uD83E\uDDC6", "Oleana", "Cambridge \u00b7 Mediterranean \u00b7 James Beard winner"),
        ),
    ),
    "default" to SingleResults(
        picks = listOf(
            SinglePick("\uD83C\uDFD9\uFE0F", "Isabella Stewart Gardner Museum", "Fenway \u00b7 Stunning Venetian mansion \u00b7 World-class art", "$20", "Most unique"),
            SinglePick("\uD83D\uDEA4", "Boston Harbor Cruise", "Long Wharf \u00b7 Best skyline views \u00b7 1 hr sunset cruise", "$32", "Most memorable"),
            SinglePick("\uD83C\uDFAD", "Improv Asylum", "North End \u00b7 Legendary comedy \u00b7 Book ahead", "$25\u201335", "Most fun"),
        ),
        mentions = listOf(
            SingleMention("\uD83C\uDF3F", "Arnold Arboretum", "Jamaica Plain \u00b7 Free \u00b7 Stunning in any season"),
            SingleMention("\uD83D\uDCDA", "Harvard Square", "Cambridge \u00b7 Free \u00b7 Street performers + Brattle Books"),
            SingleMention("\uD83C\uDF7A", "Harpoon Brewery", "Seaport \u00b7 Tour + tasting \u00b7 Great outdoor space"),
            SingleMention("\uD83C\uDFA8", "SoWa Art Walk", "South End \u00b7 Sundays \u00b7 Local artists + food trucks"),
            SingleMention("\uD83C\uDFDF\uFE0F", "Fenway Park Tour", "Iconic \u00b7 Book ahead \u00b7 Great even without a game"),
        ),
    ),
)
