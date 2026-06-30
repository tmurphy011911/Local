package live.localapp.local.ui.plans

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import live.localapp.local.ui.common.CircleShape
import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import live.localapp.local.platform.rememberInAppBrowser
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

private val YOCAL_AVATAR_COLOR = Color(0xFFFF6B5B)
private val REACTION_EMOJIS = listOf("\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDD25", "\uD83D\uDE02", "\uD83C\uDF89", "\uD83E\uDD14", "\uD83D\uDE0D", "\uD83D\uDC4F")
private val AVATAR_PALETTE = listOf(
    Color(0xFFFF6B6B), Color(0xFF4ECBA0), Color(0xFFFFB830), Color(0xFF6B9FFF), Color(0xFFCC6BFF),
    Color(0xFFFF6BB5), Color(0xFF6BFFD4), Color(0xFF9F6BFF), Color(0xFFFF9F6B), Color(0xFF6BCCFF),
)

private fun colorForUser(userId: String): Color {
    val hash = userId.hashCode()
    val idx = ((hash % AVATAR_PALETTE.size) + AVATAR_PALETTE.size) % AVATAR_PALETTE.size
    return AVATAR_PALETTE[idx]
}

private val RICH_PATTERN = Regex("""\[([^\]]+)\]\(([^)]+)\)|\*\*([^*]+)\*\*|\*([^*]+)\*""")

@Composable
private fun RichBoText(
    text: String,
    color: Color,
    linkColor: Color = AppColors.Coral,
    modifier: Modifier = Modifier,
) {
    val inAppBrowser = rememberInAppBrowser()
    val annotated = remember(text) {
        buildAnnotatedString {
            var cursor = 0
            for (match in RICH_PATTERN.findAll(text)) {
                if (match.range.first > cursor) {
                    append(text.substring(cursor, match.range.first))
                }
                val groups = match.groupValues
                when {
                    groups[1].isNotEmpty() -> {
                        pushStringAnnotation(tag = "URL", annotation = groups[2])
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append(groups[1])
                        }
                        pop()
                    }
                    groups[3].isNotEmpty() -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(groups[3]) }
                    }
                    groups[4].isNotEmpty() -> {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(groups[4]) }
                    }
                }
                cursor = match.range.last + 1
            }
            if (cursor < text.length) {
                append(text.substring(cursor))
            }
        }
    }
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = color,
            lineHeight = 19.sp,
        ),
        onClick = { offset ->
            annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { inAppBrowser.open(it.item) }
        },
    )
}

@Composable
fun PlanChatScreen(
    planTitle: String,
    viewModel: PlanChatViewModel,
    onClose: () -> Unit,
    prePopulatedMessage: String? = null,
) {
    val state by viewModel.uiState.collectAsState()
    var input by remember { mutableStateOf(prePopulatedMessage.orEmpty()) }
    val listState = rememberLazyListState()
    var pickerForMessageId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.messages.size, state.streamingText) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    // Approximation of iOS sheet open transition: subtle scale-up + corner-radius fade
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val scale by animateFloatAsState(if (visible) 1f else 0.94f, animationSpec = spring(dampingRatio = 0.9f))
    val sheetRadius by animateDpAsState(if (visible) 0.dp else 16.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                clip = true
                shape = RoundedCornerShape(sheetRadius)
            }
            .background(AppColors.Snow)
            .imePadding(),
    ) {
        // Header — plan name as chat title
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    planTitle,
                    style = TextStyle(fontFamily = AppFonts.Heading, fontWeight = FontWeight.Black, fontSize = 17.sp, color = AppColors.Ink),
                    maxLines = 1,
                )
                Text(
                    "Tag @Bo to make changes",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = AppColors.Ink3),
                )
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(AppColors.Mist)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text("\u2715", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink2))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Mist))

        // Messages
        Box(modifier = Modifier.weight(1f)) {
            if (state.messages.isEmpty() && !state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No messages yet. Say hi or tag @Bo to edit.",
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AppColors.Ink3),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        MessageRow(
                            msg = msg,
                            myUserId = viewModel.myUserId,
                            pickerOpen = pickerForMessageId == msg.id,
                            onLongPress = {
                                pickerForMessageId = if (pickerForMessageId == msg.id) null else msg.id
                            },
                            onPickEmoji = { emoji ->
                                viewModel.toggleReaction(msg.id, emoji)
                                pickerForMessageId = null
                            },
                            onToggleReaction = viewModel::toggleReaction,
                        )
                    }
                    if (state.isYocalTyping || state.streamingText.isNotEmpty()) {
                        item { YocalTypingRow(streamingText = state.streamingText) }
                    }
                }
            }
        }

        // Confirmation bar (when Yocal has a pending change)
        state.pendingConfirmation?.let { _ ->
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Mist))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFF2EF))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.Mist)
                        .clickable { viewModel.cancelChange() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Cancel", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = AppColors.Ink))
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppColors.Coral)
                        .clickable { viewModel.confirmChange() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Confirm change", style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color.White))
                }
            }
        }

        // Input
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AppColors.Mist))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(AppColors.Snow, RoundedCornerShape(20.dp))
                    .border(1.5.dp, AppColors.Pebble, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Ink),
                    cursorBrush = SolidColor(AppColors.Coral),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = {
                        if (input.isNotBlank()) {
                            viewModel.sendMessage(input)
                            input = ""
                        }
                    }),
                    maxLines = 4,
                    decorationBox = { inner ->
                        if (input.isEmpty()) {
                            Text(
                                "Message or tag @Bo...",
                                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Pebble),
                            )
                        }
                        inner()
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (input.isNotBlank()) AppColors.Coral else AppColors.Pebble)
                    .clickable(
                        enabled = input.isNotBlank(),
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (input.isNotBlank()) {
                            viewModel.sendMessage(input)
                            input = ""
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send", modifier = Modifier.size(18.dp), tint = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    msg: PlanChatMessageUi,
    myUserId: String?,
    pickerOpen: Boolean,
    onLongPress: () -> Unit,
    onPickEmoji: (emoji: String) -> Unit,
    onToggleReaction: (messageId: String, emoji: String) -> Unit,
) {
    val alignment = if (msg.isCurrentUser) Alignment.End else Alignment.Start
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (msg.isCurrentUser) Arrangement.End else Arrangement.Start,
        ) {
            if (!msg.isCurrentUser) {
                Avatar(msg = msg)
                Spacer(Modifier.width(8.dp))
            }
            Column(
                modifier = Modifier.sizeIn(maxWidth = 280.dp),
                horizontalAlignment = if (msg.isCurrentUser) Alignment.End else Alignment.Start,
            ) {
                if (!msg.isCurrentUser) {
                    Text(
                        if (msg.isYocal) "Bo" else msg.senderName,
                        style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = if (msg.isYocal) AppColors.Coral else AppColors.Ink3),
                        modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                    )
                }
                val bubbleShape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomEnd = if (msg.isCurrentUser) 4.dp else 16.dp,
                    bottomStart = if (msg.isCurrentUser) 16.dp else 4.dp,
                )
                Box(
                    modifier = Modifier
                        .background(
                            if (msg.isCurrentUser) AppColors.Coral
                            else if (msg.isYocal) Color(0xFFFFF2EF)
                            else Color.White,
                            bubbleShape,
                        )
                        .border(
                            width = if (msg.isYocal) 1.dp else 0.dp,
                            color = if (msg.isYocal) AppColors.Coral.copy(alpha = 0.3f) else Color.Transparent,
                            shape = bubbleShape,
                        )
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                            onLongClick = { if (!msg.isLocal) onLongPress() },
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (msg.isYocal) {
                        RichBoText(text = msg.text, color = AppColors.Ink)
                    } else {
                        Text(
                            msg.text,
                            style = TextStyle(
                                fontFamily = AppFonts.Body,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = if (msg.isCurrentUser) Color.White else AppColors.Ink,
                                lineHeight = 19.sp,
                            ),
                        )
                    }
                }

                // Emoji picker (shown on long-press)
                if (pickerOpen) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(22.dp))
                            .border(1.dp, AppColors.Pebble, RoundedCornerShape(22.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        REACTION_EMOJIS.forEach { emoji ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { onPickEmoji(emoji) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(emoji, style = TextStyle(fontSize = 20.sp))
                            }
                        }
                    }
                }

                // Existing reactions row
                if (msg.reactions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        msg.reactions.forEach { (emoji, userIds) ->
                            val reactedByMe = myUserId != null && myUserId in userIds
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (reactedByMe) AppColors.Coral.copy(alpha = 0.1f) else Color.White,
                                        RoundedCornerShape(12.dp),
                                    )
                                    .border(
                                        1.dp,
                                        if (reactedByMe) AppColors.Coral else AppColors.Pebble,
                                        RoundedCornerShape(12.dp),
                                    )
                                    .clickable { onToggleReaction(msg.id, emoji) }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    "$emoji ${userIds.size}",
                                    style = TextStyle(
                                        fontFamily = AppFonts.Body,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = if (reactedByMe) AppColors.Coral else AppColors.Ink,
                                    ),
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
private fun Avatar(msg: PlanChatMessageUi) {
    if (msg.isYocal) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(YOCAL_AVATAR_COLOR.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = YOCAL_AVATAR_COLOR)
        }
    } else {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(colorForUser(msg.userId), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                msg.senderName.firstOrNull()?.uppercase() ?: "?",
                style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.Black, fontSize = 12.sp, color = Color.White),
            )
        }
    }
}

@Composable
private fun YocalTypingRow(streamingText: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(YOCAL_AVATAR_COLOR.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp), tint = YOCAL_AVATAR_COLOR)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .background(Color(0xFFFFF2EF), RoundedCornerShape(16.dp))
                .border(1.dp, AppColors.Coral.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (streamingText.isNotEmpty()) {
                Text(
                    streamingText,
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = AppColors.Ink,
                        lineHeight = 19.sp,
                    ),
                )
            } else {
                Text(
                    "Thinking…",
                    style = TextStyle(fontFamily = AppFonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AppColors.Ink3),
                )
            }
        }
    }
}
