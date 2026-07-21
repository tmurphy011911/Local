package live.localapp.local.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/**
 * Chunky "Duolingo" CTA — a darker edge layer peeking 4dp below a face with a
 * matching stroke, for the pressed-depth look. Mirrors the iOS `duoButton`.
 *
 * This is the shared house style for prominent CTAs; it's the same treatment
 * used by the chat draft-plan tray, onboarding/tutorial, phone auth, and the
 * leaderboard. Prefer this over an ad-hoc `Box` + `Text` so depth, corner, and
 * label styling stay identical everywhere.
 *
 * @param primary coral face + white label (the main action); `false` gives the
 *   light-coral secondary face + coral label.
 */
@Composable
fun DuoButton(
    title: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val face = if (primary) AppColors.Coral else AppColors.CoralLt
    val edge = if (primary) Color(0xFFE85D5D) else AppColors.Coral.copy(alpha = 0.30f)
    Box(
        modifier = modifier
            .height(54.dp) // 50 face + 4 lip
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        // 3D lip — behind the face, peeking 4dp below it.
        Box(
            Modifier.fillMaxWidth().height(50.dp).align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(14.dp)).background(edge),
        )
        // Face.
        Box(
            Modifier.fillMaxWidth().height(50.dp).align(Alignment.TopCenter)
                .clip(RoundedCornerShape(14.dp)).background(face)
                .border(1.5.dp, edge, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    color = if (primary) Color.White else AppColors.Coral,
                ),
            )
        }
    }
}
