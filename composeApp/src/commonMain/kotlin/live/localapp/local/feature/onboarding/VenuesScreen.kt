package live.localapp.local.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.domain.repository.VenueSummary
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

private const val PAGE_SIZE = 30
private val CARD_HEIGHT = 120.dp

@Composable
fun VenuesScreen(
    venues: List<VenueSummary>,
    selectedNeighborhoods: Set<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }

    val cityToId: (String) -> String = { it.lowercase().replace(" ", "-") }

    // Sort: selected-area venues by public interest rank, then rest by distance + rank
    val items = remember(venues, selectedNeighborhoods) {
        val favoriteCity = mutableListOf<Pair<VenueSummary, Int>>()
        val other = mutableListOf<Pair<VenueSummary, Int>>()

        venues.forEachIndexed { index, venue ->
            val isFavoriteCity = selectedNeighborhoods.isNotEmpty() &&
                cityToId(venue.city) in selectedNeighborhoods
            if (isFavoriteCity) favoriteCity.add(venue to index) else other.add(venue to index)
        }

        // Sort favorite-area venues by rank (highest first)
        val sortedFavorites = favoriteCity.sortedByDescending { it.first.publicInterestRank ?: 0 }
        // Sort others by combined rank + distance
        val sortedOther = other.sortedWith(
            compareByDescending<Pair<VenueSummary, Int>> { it.first.publicInterestRank ?: 0 }
                .thenBy { it.first.distanceMiles ?: Double.MAX_VALUE }
        )

        (sortedFavorites + sortedOther).map { (venue, index) ->
            val distText = venue.distanceDisplay
            val subtitle = if (distText.isNotEmpty()) "${venue.city} \u00B7 $distText" else venue.city
            SelectionItem(
                id = venue.name.lowercase().replace(" ", "-"),
                icon = if (venue.photoUrl == null) OnboardingViewModel.venueIcon(index) else null,
                name = venue.name,
                subtitle = subtitle,
                imageUrl = venue.photoUrl,
            )
        }
    }

    val filtered = if (searchQuery.isBlank()) items
    else items.filter { it.name.contains(searchQuery, ignoreCase = true) }

    val displayItems = if (showAll || searchQuery.isNotBlank()) filtered
    else filtered.take(PAGE_SIZE)

    val hasMore = searchQuery.isBlank() && !showAll && filtered.size > PAGE_SIZE

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
                        append("Any favorite ")
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = AppColors.Plum)) {
                            append("spots?")
                        }
                    },
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Black,
                        fontSize = 24.sp,
                        color = AppColors.Ink,
                    ),
                    maxLines = 1,
                )

                Text(
                    "We'll notify you about events at the venues you love.",
                    modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = AppColors.Ink2,
                        lineHeight = 20.sp,
                    ),
                )

                // Search
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    textStyle = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = AppColors.Ink,
                    ),
                    cursorBrush = SolidColor(AppColors.Plum),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .background(AppColors.White, RoundedCornerShape(14.dp))
                                .border(2.dp, AppColors.Pebble, RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    "Search venues...",
                                    style = TextStyle(
                                        fontFamily = AppFonts.Body,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = AppColors.Pebble,
                                    ),
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier.padding(bottom = 10.dp),
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(displayItems, key = { it.id }) { item ->
                        SelectionCard(
                            item = item,
                            isSelected = item.id in selected,
                            onClick = { onToggle(item.id) },
                            fixedHeight = CARD_HEIGHT,
                        )
                    }

                    if (hasMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(CARD_HEIGHT)
                                    .background(AppColors.Mist, RoundedCornerShape(16.dp))
                                    .border(2.dp, AppColors.Pebble, RoundedCornerShape(16.dp))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { showAll = true },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Show ${filtered.size - PAGE_SIZE}\nmore",
                                    style = TextStyle(
                                        fontFamily = AppFonts.Body,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 11.sp,
                                        color = AppColors.Plum,
                                        lineHeight = 15.sp,
                                    ),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floating CTA
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            OnboardingButton(
                text = "Next: Event types \u2192",
                color = AppColors.Plum,
                shadowColor = AppColors.PlumDk,
                onClick = onNext,
            )
        }
    }
}
