package live.localapp.local.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import live.localapp.local.ui.theme.AppShapes
import live.localapp.local.ui.theme.AppType

/**
 * Shared header used on all four main tabs.
 *
 * Single-row layout: logo | location pill | streak badge | avatar.
 *
 * The logo renders [title] with all lowercase "l"s in coral.
 *
 * An optional [extraContent] slot is rendered below the header row (e.g. radius picker).
 */
@Composable
fun AppHeader(
    title: String = "",
    // Legacy params — ignored if title is set
    titlePrefix: String = "",
    coralLetter: String = "",
    titleSuffix: String = "",
    locationName: String,
    radiusFilter: Double?,
    streakDays: Int,
    userInitial: String,
    onProfileClick: () -> Unit,
    onLocationClick: () -> Unit,
    onLogoClick: () -> Unit = {},
    showLocationPill: Boolean = true,
    isLoggedIn: Boolean = true,
    bottomPadding: Boolean = true,
    extraContent: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.White),
    ) {
        // Single header row: logo + location + streak + avatar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = 4.dp,
                    bottom = if (extraContent != null && !bottomPadding) 8.dp else 14.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Logo — all lowercase "l"s in coral
            val logoText = if (title.isNotEmpty()) title else "$titlePrefix$coralLetter$titleSuffix"
            val annotated = buildAnnotatedString {
                logoText.forEach { ch ->
                    if (ch == 'l') {
                        withStyle(SpanStyle(color = AppColors.Coral)) { append(ch) }
                    } else {
                        append(ch)
                    }
                }
            }
            Text(
                annotated,
                style = AppType.Logo.copy(fontSize = 24.sp),
                modifier = Modifier
                    .heightIn(min = 28.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onLogoClick,
                    ),
            )

            Spacer(Modifier.width(10.dp))

            // Location pill — hidden when logged out
            if (showLocationPill && isLoggedIn) {
                val radiusLabel = radiusFilter?.let { " \u00B7 ${it.toInt()} mi" } ?: ""
                Row(
                    modifier = Modifier
                        .heightIn(min = 28.dp)
                        .background(AppColors.SkyLt, AppShapes.Pill)
                        .border(1.5.dp, AppColors.Sky, AppShapes.Pill)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onLocationClick,
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.size(5.dp).background(AppColors.Sky, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "$locationName$radiusLabel",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 10.sp,
                            color = AppColors.Sky,
                        ),
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Streak badge — hidden until feature is finalized
            val showStreakBadge = false
            if (showStreakBadge && isLoggedIn) {
                Row(
                    modifier = Modifier
                        .background(AppColors.SunLt, AppShapes.Pill)
                        .border(1.5.dp, AppColors.Sun, AppShapes.Pill)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(12.dp), tint = AppColors.SunDk)
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "$streakDays days",
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 10.sp,
                            color = AppColors.SunDk,
                        ),
                    )
                }
                Spacer(Modifier.width(8.dp))
            }

            // Avatar — enforced minimum size
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 32.dp, minHeight = 32.dp)
                    .size(32.dp)
                    .background(AppColors.Coral, AppShapes.Avatar)
                    .border(2.dp, AppColors.CoralDk, AppShapes.Avatar)
                    .clip(AppShapes.Avatar)
                    .clickable { onProfileClick() },
                contentAlignment = Alignment.Center,
            ) {
                if (userInitial.isNotEmpty()) {
                    Text(
                        userInitial,
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                            color = AppColors.White,
                        ),
                    )
                } else {
                    Icon(
                        Icons.Outlined.Person,
                        contentDescription = "Account",
                        modifier = Modifier.size(18.dp),
                        tint = AppColors.White,
                    )
                }
            }
        }

        // Optional extra content (e.g. radius picker)
        if (extraContent != null) {
            extraContent()
        }

        // Bottom border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(AppColors.Mist),
        )
    }
}
