package live.localapp.local.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

/**
 * Small green "NEW" ribbon pinned to the top-left of a card. Marks items that
 * first appeared on the last Bo pull-to-refresh; the screen clears the backing
 * id set on the next scroll. Must be placed inside a [BoxScope] (the card root).
 */
@Composable
fun BoxScope.NewRibbon(modifier: Modifier = Modifier) {
    Text(
        text = "NEW",
        style = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 10.sp,
            color = AppColors.White,
            letterSpacing = 0.6.sp,
        ),
        modifier = modifier
            .align(Alignment.TopStart)
            .padding(8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(AppColors.NewGreen)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
