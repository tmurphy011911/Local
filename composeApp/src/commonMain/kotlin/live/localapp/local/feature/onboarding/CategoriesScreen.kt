package live.localapp.local.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

@Composable
fun CategoriesScreen(
    categoryCounts: Map<String, Int>,
    categoryPhotos: Map<String, String> = emptyMap(),
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    isValid: Boolean,
    onBack: () -> Unit,
) {
    val items = remember(categoryCounts, categoryPhotos) {
        categoryCounts.entries
            .filter { it.key != "no-public-interest" && it.key != "cafe" && it.key != "other" }
            .sortedByDescending { it.value }
            .map { (key, count) ->
                SelectionItem(
                    id = key,
                    icon = if (categoryPhotos.containsKey(key)) null else OnboardingViewModel.categoryIcon(key),
                    name = OnboardingViewModel.categoryDisplayName(key),
                    subtitle = "${formatNumber(count)} events",
                    imageUrl = categoryPhotos[key],
                )
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFFF8F5FF), Color(0xFFFAF8FC), Color(0xFFF0F5FF)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 20.dp),
        ) {
            // Back / Skip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "\u2190 Back",
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack,
                    ),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        color = AppColors.Ink3,
                    ),
                )
                Text(
                    "Skip \u2192",
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSkip,
                    ),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        color = AppColors.Ink3,
                    ),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp),
            ) {
                Text(
                    buildAnnotatedString {
                        append("What are you ")
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = AppColors.Plum)) {
                            append("into?")
                        }
                    },
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Black,
                        fontSize = 24.sp,
                        color = AppColors.Ink,
                    ),
                )

                Text(
                    "Select your interests \u2014 your feed updates instantly.",
                    modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = AppColors.Ink2,
                        lineHeight = 20.sp,
                    ),
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(bottom = 140.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(items, key = { it.id }) { item ->
                        SelectionCard(
                            item = item,
                            isSelected = item.id in selected,
                            onClick = { onToggle(item.id) },
                            fixedHeight = 96.dp,
                        )
                    }
                }
            }
        }

        // Floating CTA — content scrolls underneath
        Column(
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            OnboardingButton(
                text = "Continue \u2192",
                color = AppColors.Plum,
                shadowColor = AppColors.PlumDk,
                onClick = onNext,
            )
        }
    }
}

private fun formatNumber(n: Int): String {
    val s = n.toString()
    val result = StringBuilder()
    for (i in s.indices) {
        if (i > 0 && (s.length - i) % 3 == 0) result.append(',')
        result.append(s[i])
    }
    return result.toString()
}
