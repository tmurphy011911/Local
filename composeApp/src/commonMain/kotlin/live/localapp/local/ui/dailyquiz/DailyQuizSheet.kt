package live.localapp.local.ui.dailyquiz

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import live.localapp.local.data.remote.dto.QuizBreakdownDto
import live.localapp.local.data.remote.dto.QuizQuestionDto
import live.localapp.local.ui.character.Bo
import live.localapp.local.ui.common.pressable
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

private const val QUESTION_MS = 10_000L
private const val REVEAL_HOLD_MS = 1_100L
private const val READ_IN_MS = 2_000L

private fun stateName(code: String?): String = when (code) {
    "MA" -> "Massachusetts"; "PA" -> "Pennsylvania"; "RI" -> "Rhode Island"
    "NY" -> "New York"; "NJ" -> "New Jersey"; "CA" -> "California"
    else -> code ?: "Local"
}

private fun difficultyColor(d: String): Color = when (d) {
    "medium" -> AppColors.Sun
    "hard" -> AppColors.Coral
    else -> AppColors.Sage
}

/** "10.0s" / "5.5s" — tenth-of-a-second precision from milliseconds. */
private fun tenthsLabel(remainingMs: Long): String {
    val t = (remainingMs / 100L).toInt()
    return "${t / 10}.${t % 10}s"
}

private fun timerColor(remainingMs: Long): Color = when {
    remainingMs <= 3_000L -> AppColors.Coral
    remainingMs <= 5_000L -> AppColors.Sun
    else -> AppColors.Sage
}

@Composable
fun DailyQuizSheetContent(
    vm: DailyQuizViewModel,
    onShare: (String) -> Unit,
) {
    val s by vm.uiState.collectAsState()
    Box(
        Modifier.fillMaxWidth().background(AppColors.Snow).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        when (s.phase) {
            DailyQuizViewModel.Phase.RESULTS ->
                ResultsStage(s, onShare = onShare, onRetry = { vm.retrySubmit() })
            DailyQuizViewModel.Phase.SUBMITTING ->
                Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppColors.Sun)
                }
            else -> {
                val q = s.currentQuestion
                if (q != null) {
                    QuestionStage(
                        question = q,
                        index = s.questionIndex,
                        total = s.questionCount,
                        revealing = s.revealing,
                        revealSelectedIndex = s.revealSelectedIndex,
                        onAnswer = { idx, ms -> vm.recordAnswer(idx, ms) },
                        onTimeout = { vm.timeout() },
                        onAdvance = { vm.advance() },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionStage(
    question: QuizQuestionDto,
    index: Int,
    total: Int,
    revealing: Boolean,
    revealSelectedIndex: Int?,
    onAnswer: (Int, Int) -> Unit,
    onTimeout: () -> Unit,
    onAdvance: () -> Unit,
) {
    var remainingMs by remember(question.id) { mutableStateOf(QUESTION_MS) }
    var live by remember(question.id) { mutableStateOf(false) }
    // Question intro choreography (mirrors iOS DailyQuizView): Bo lands big +
    // centred → shrinks → floats to the top-left → the question types in beside
    // it → a read-in countdown → the options float up and the clock goes live.
    var intro by remember(question.id) { mutableStateOf(Intro.BO_BIG) }
    var placed by remember(question.id) { mutableStateOf(false) }   // Bo floated to top-left
    var streamed by remember(question.id) { mutableStateOf(0) }     // chars of the question revealed
    var readInSecs by remember(question.id) { mutableStateOf(2) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(question.id, revealing) {
        if (revealing) return@LaunchedEffect
        intro = Intro.BO_BIG; placed = false; streamed = 0; readInSecs = 2
        live = false; remainingMs = QUESTION_MS
        delay(700)
        intro = Intro.STREAMING                     // Bo shrinks in place
        delay(450)
        placed = true                               // Bo floats to the top-left
        delay(600)                                  // wait out the float, then type
        val full = question.question
        while (streamed < full.length) { streamed++; delay(20) }
        intro = Intro.READ_IN
        readInSecs = 2
        while (readInSecs > 1) { delay(1000); readInSecs-- }
        delay(1000)
        intro = Intro.LIVE                          // options float in, clock goes live
        live = true
        var last = withFrameMillis { it }
        while (remainingMs > 0L && isActive) {
            val now = withFrameMillis { it }
            remainingMs = (remainingMs - (now - last)).coerceAtLeast(0L)
            last = now
        }
        if (remainingMs <= 0L) onTimeout()
    }
    LaunchedEffect(revealing) { if (revealing) { delay(REVEAL_HOLD_MS); onAdvance() } }

    val correctIndex = question.answers.indexOfFirst { it.correct }

    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        // Draining bar
        val frac = (remainingMs.toFloat() / QUESTION_MS).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(AppColors.CoralLt)) {
            Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(4.dp)).background(timerColor(remainingMs)))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Question ${index + 1} of $total",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Ink3))
            Spacer(Modifier.width(8.dp))
            DifficultyBadge(question.difficulty)
            Spacer(Modifier.weight(1f))
            Text("${tenthsLabel(remainingMs)} left",
                style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 14.sp, color = timerColor(remainingMs)))
        }

        Spacer(Modifier.height(16.dp))

        // Bo glides from big + centred to small + top-left; the question types in
        // beside it. The FULL text is laid out with a transparent tail so revealing
        // more characters never re-wraps (no line jumps).
        val boSize by animateDpAsState(
            if (intro == Intro.BO_BIG) 96.dp else 30.dp,
            spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow), label = "boSize",
        )
        val boBias by animateFloatAsState(
            if (placed) -1f else 0f,
            spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessLow), label = "boBias",
        )
        // Fixed floor so the Bo/question block is the same height whether Bo is
        // big-centred or the question has typed in — the sheet never jumps.
        Box(Modifier.fillMaxWidth().heightIn(min = 150.dp)) {
            Box(Modifier.align(BiasAlignment(boBias, -1f))) {
                Bo(size = boSize, showShadow = false, showSparkles = false)
            }
            if (placed) {
                Text(
                    buildAnnotatedString {
                        append(question.question)
                        if (streamed < question.question.length) {
                            addStyle(SpanStyle(color = Color.Transparent), streamed, question.question.length)
                        }
                    },
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 42.dp),
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = AppColors.Ink, lineHeight = 28.sp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Reserve the options' vertical space up-front so the sheet is already at
        // full height before they appear (matches iOS's fixed detent instead of
        // growing when they float in). The read-in chip and the options share this
        // slot — only one is visible at a time.
        Box(Modifier.fillMaxWidth().height(270.dp)) {
          Column(Modifier.fillMaxWidth()) {
            AnimatedVisibility(
                visible = intro == Intro.READ_IN,
                enter = fadeIn(), exit = fadeOut(),
            ) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AppColors.Ink).padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Timer starts in ${readInSecs}s", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = AppColors.White))
                }
            }
            AnimatedVisibility(
                visible = intro == Intro.LIVE,
                enter = slideInVertically(spring(dampingRatio = 0.8f)) { it / 3 } + fadeIn(),
            ) {
                Column {
                    question.answers.forEachIndexed { i, ans ->
                        AnswerPill(
                            text = ans.text,
                            enabled = live && !revealing,
                            state = when {
                                !revealing -> PillState.IDLE
                                i == correctIndex -> PillState.CORRECT
                                i == revealSelectedIndex -> PillState.WRONG
                                else -> PillState.DIM
                            },
                            onClick = {
                                // One crisp hit on tap; a second, slightly-delayed hit only
                                // when the tapped answer is the correct one (correctness is
                                // known client-side from the DTO, so it lands immediately).
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (i == correctIndex) {
                                    scope.launch { delay(90); haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                                }
                                onAnswer(i, remainingMs.toInt())
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
          }
        }
    }
}

private enum class Intro { BO_BIG, STREAMING, READ_IN, LIVE }

private enum class PillState { IDLE, CORRECT, WRONG, DIM }

@Composable
private fun AnswerPill(text: String, enabled: Boolean, state: PillState, onClick: () -> Unit) {
    val bg by animateColorAsState(
        when (state) {
            PillState.CORRECT -> AppColors.Sage
            PillState.WRONG -> AppColors.Coral
            else -> AppColors.White
        },
        tween(220),
    )
    val fg = when (state) {
        PillState.CORRECT, PillState.WRONG -> AppColors.White
        PillState.DIM -> AppColors.Ink3
        PillState.IDLE -> AppColors.Ink
    }
    Box(
        // pressable (scale press, NO ripple) instead of clickable — the default
        // clickable indication paints an unbounded ripple that reads as the whole
        // sheet shimmering on CMP iOS. Haptic is driven at the call site, so the
        // press's own haptic is disabled here.
        Modifier.fillMaxWidth()
            .pressable(onClick = onClick, scale = 0.97f, haptic = false, enabled = enabled)
            .height(56.dp).clip(RoundedCornerShape(14.dp)).background(bg)
            .border(1.5.dp, if (state == PillState.IDLE || state == PillState.DIM) AppColors.Ink3 else Color.Transparent, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = fg))
    }
}

@Composable
private fun ResultsStage(
    s: DailyQuizViewModel.UiState,
    onShare: (String) -> Unit,
    onRetry: () -> Unit,
) {
    if (s.submitError && s.result == null) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Text("Couldn't save your score", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AppColors.Ink))
            Spacer(Modifier.height(6.dp))
            Text("Check your connection and try again.", style = TextStyle(fontFamily = AppFonts.Body, fontSize = 14.sp, color = AppColors.Ink3))
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Retry", onRetry)
        }
        return
    }

    val r = s.result ?: return
    // Fresh submit → play the reveal; re-opening a played quiz → show it resolved.
    val animate = s.animateResults
    // Bo + Share stay hidden while the rows reveal. Once the postcard finishes,
    // Bo floats up and types; then Share floats up. Stored view starts done.
    var rowsDone by remember(r) { mutableStateOf(!animate) }
    var shareVisible by remember(r) { mutableStateOf(!animate) }
    val floatUp = slideInVertically(spring(dampingRatio = 0.8f)) { it / 2 } + fadeIn()
    // No Bo line → reveal Share as soon as the rows finish.
    LaunchedEffect(rowsDone) { if (rowsDone && r.boMessage.isBlank()) shareVisible = true }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(4.dp))
        SharePostcard(
            breakdown = r.breakdown,
            bestAllTime = r.bestAllTime,
            isFirst = r.isFirst,
            animate = animate,
            onRevealComplete = { rowsDone = true },
        )

        AnimatedVisibility(visible = rowsDone && r.boMessage.isNotBlank(), enter = floatUp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                BoReaction(r.boMessage, animate = animate, onTypingDone = { shareVisible = true })
            }
        }

        AnimatedVisibility(visible = shareVisible, enter = floatUp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Share") { onShare(dailyQuizShareText(r)) }
            }
        }
    }
}

/** Bo types out a reaction line, then signals completion so Share can follow.
 *  Mounts only after the rows have revealed, so it types immediately. */
@Composable
private fun BoReaction(message: String, animate: Boolean = true, onTypingDone: () -> Unit) {
    // Matches the iOS recap: Bo starts centred, floats to the top-left (the full
    // line laid out invisibly so it doesn't reflow), then types once it has landed.
    var placed by remember(message) { mutableStateOf(!animate) }
    var shown by remember(message) { mutableStateOf(if (animate) 0 else message.length) }
    LaunchedEffect(message) {
        if (!animate) { onTypingDone(); return@LaunchedEffect }
        delay(350)                 // Bo holds centre a beat
        placed = true              // float to the left
        delay(600)                 // wait out the float before typing
        for (i in 1..message.length) { shown = i; delay(22) }
        delay(300)
        onTypingDone()
    }
    // Bo glides from centre (0f) to start (-1f); the line only reveals after.
    val boBias by animateFloatAsState(
        if (placed) -1f else 0f,
        spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        label = "boBias",
    )
    Box(Modifier.fillMaxWidth()) {
        if (placed) {
            Text(
                // Lay out the FULL message (fixed line breaks + height) and hide the
                // untyped tail with a transparent span, so revealing more characters
                // never re-wraps — no line jumps as a word crosses the boundary.
                buildAnnotatedString {
                    append(message)
                    if (shown < message.length) {
                        addStyle(SpanStyle(color = Color.Transparent), shown, message.length)
                    }
                },
                // 26.dp Bo + 10.dp gap → the line sits in the column to Bo's right.
                modifier = Modifier.align(Alignment.TopStart).padding(start = 36.dp, top = 3.dp),
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Ink2),
            )
        }
        Bo(
            size = 26.dp, showShadow = false, showSparkles = false,
            modifier = Modifier.align(BiasAlignment(boBias, -1f)),
        )
    }
}

/**
 * The "run" postcard: total counts up as each question's result reveals in turn —
 * the correct ones drain a speed bar (10s -> time-left) then pop their points.
 */
@Composable
private fun SharePostcard(
    breakdown: List<QuizBreakdownDto>,
    bestAllTime: Int,
    isFirst: Boolean,
    animate: Boolean = true,
    onRevealComplete: () -> Unit = {},
) {
    // Stored view: start fully resolved (final total, every row revealed) so the
    // recap shows at once instead of replaying the tally.
    val finalTotal = breakdown.filter { it.correct }.sumOf { it.points }
    var rowsShown by remember { mutableStateOf(if (animate) 0 else breakdown.size) }
    var total by remember { mutableStateOf(if (animate) 0 else finalTotal) }
    val rowProg = remember { mutableStateListOf(if (animate) 0f else 1f, if (animate) 0f else 1f, if (animate) 0f else 1f) }
    val rowRevealed = remember { mutableStateListOf(!animate, !animate, !animate) }
    val rowBlinkOp = remember { mutableStateListOf(1f, 1f, 1f) }
    // The theoretical max ("/600" for the standard easy/medium/hard set), shown
    // beside the score once the rows finish tallying (just before Bo types).
    val maxScore = breakdown.sumOf { 100 * diffMult(it.difficulty) }
    var revealComplete by remember { mutableStateOf(!animate) }
    val maxAlpha by animateFloatAsState(if (revealComplete) 1f else 0f, tween(300), label = "maxAlpha")
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        if (!animate) { onRevealComplete(); return@LaunchedEffect }
        for (i in breakdown.indices) {
            delay(400)
            rowsShown = i + 1
            val b = breakdown[i]
            // Count phase (seconds + points count down together). Dense steps
            // (~45fps) for smoothness; haptic only every 4th step.
            repeat(40) { s -> delay(22); rowProg[i] = (s + 1) / 40f; if (s % 4 == 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            delay(120)
            rowRevealed[i] = true
            if (b.correct) {
                val startT = total
                repeat(28) { s -> delay(16); total = startT + (b.points * (s + 1)) / 28; if (s % 3 == 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                total = startT + b.points
                delay(500)
            } else {
                for (op in listOf(0f, 1f, 0f, 1f)) { rowBlinkOp[i] = op; delay(120) }   // blink to 0
                delay(450)
            }
        }
        revealComplete = true
        onRevealComplete()
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(AppColors.White)
            .border(1.dp, AppColors.SunLt, RoundedCornerShape(20.dp)),
    ) {
        Row(Modifier.fillMaxWidth().height(6.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth().background(AppColors.Sage))
            Box(Modifier.weight(1f).fillMaxWidth().background(AppColors.Sun))
            Box(Modifier.weight(1f).fillMaxWidth().background(AppColors.Coral))
        }
        Column(Modifier.padding(20.dp)) {
            Text("DAILY LOCAL", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 13.sp, color = AppColors.SunDk, letterSpacing = 1.sp))
            Spacer(Modifier.height(10.dp))
            Row {
                Text("$total", modifier = Modifier.alignByBaseline(), style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 52.sp, color = AppColors.Ink))
                Spacer(Modifier.width(6.dp))
                Text("/$maxScore", modifier = Modifier.alignByBaseline().alpha(maxAlpha), style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 22.sp, color = AppColors.Ink3))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val subtitleStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = AppColors.Ink3, letterSpacing = 0.5.sp)
                Text("FOMO EARNED TODAY", style = subtitleStyle)
                if (!isFirst) {
                    // Best-all-time fades in with the score's "/maxScore" once the
                    // tally lands — hidden until the total is actually revealed.
                    Text(" · BEST ALL TIME $bestAllTime", modifier = Modifier.alpha(maxAlpha), style = subtitleStyle)
                }
            }
            Spacer(Modifier.height(18.dp))
            breakdown.forEachIndexed { i, b ->
                if (i < rowsShown) {
                    ResultRow(b, rowProg.getOrElse(i) { 0f }, rowRevealed.getOrElse(i) { false }, rowBlinkOp.getOrElse(i) { 1f })
                    if (i < breakdown.lastIndex) Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

/**
 * One question's result row. While counting: yellow ring + yellow bar drain, and
 * the seconds + points count down together. On reveal it turns green (✓) or red
 * (✗, with the points blinking to 0).
 */
@Composable
private fun ResultRow(b: QuizBreakdownDto, prog: Float, revealed: Boolean, blinkOp: Float) {
    val timeLeft = b.msRemaining / 1000f
    val frac = (b.msRemaining / 10000f).coerceIn(0f, 1f)
    val mult = diffMult(b.difficulty)
    val shownTime = 10f - (10f - timeLeft) * prog
    val baseNow = (shownTime * 10).roundToInt()
    val totalNow = baseNow * mult
    val barFrac = 1f - (1f - frac) * prog
    val tt = (shownTime * 10).roundToInt()
    val accent = if (!revealed) AppColors.Sun else if (b.correct) AppColors.Sage else AppColors.Coral
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (revealed) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                Text(if (b.correct) "✓" else "✗", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 13.sp, color = AppColors.White))
            }
        } else {
            Box(Modifier.size(26.dp).clip(CircleShape).border(3.dp, AppColors.Sun, CircleShape))
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(AppColors.SunLt)) {
            Box(Modifier.fillMaxWidth(barFrac).height(8.dp).clip(RoundedCornerShape(4.dp)).background(accent))
        }
        Spacer(Modifier.width(10.dp))
        Text("${tt / 10}.${tt % 10}s", modifier = Modifier.width(40.dp), textAlign = TextAlign.End, style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AppColors.Ink3))
        Spacer(Modifier.width(8.dp))
        Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            when {
                !revealed -> Text("$totalNow", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 14.sp, color = AppColors.Ink3))
                b.correct -> {
                    Text("${b.base} × $mult = ", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3))
                    Text("${b.points}", style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 14.sp, color = AppColors.Ink))
                }
                else -> Text("0", modifier = Modifier.alpha(blinkOp), style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 14.sp, color = AppColors.Coral))
            }
        }
    }
}

private fun diffMult(d: String): Int = when (d) { "medium" -> 2; "hard" -> 3; else -> 1 }

@Composable
private fun DifficultyBadge(d: String) {
    val c = when (d) { "hard" -> AppColors.Coral; "medium" -> AppColors.Sun; else -> AppColors.Sage }
    Box(Modifier.clip(CircleShape).background(c.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(d.uppercase(), style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = c))
    }
}

/** Event-page 3D hard-shadow CTA style, in Daily Local amber. */
@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        // Darker base peeking 4.dp below = the hard "3D" shadow.
        Box(Modifier.fillMaxWidth().height(52.dp).align(Alignment.BottomCenter).clip(RoundedCornerShape(14.dp)).background(AppColors.SunDk))
        Box(
            // pressable (scale press, no ripple) so the face compresses on tap like
            // the app's other CTAs — the default clickable ripple flashed the whole
            // full-width button.
            Modifier.fillMaxWidth().height(52.dp).align(Alignment.TopCenter)
                .pressable(onClick = onClick, scale = 0.97f)
                .clip(RoundedCornerShape(14.dp))
                .background(AppColors.Sun)
                .border(2.dp, AppColors.SunDk, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 16.sp, color = AppColors.Ink))
        }
    }
}
