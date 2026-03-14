package live.localapp.local.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import live.localapp.local.domain.model.Event
import live.localapp.local.ui.chat.ChatScreen
import live.localapp.local.ui.chat.ChatViewModel
import live.localapp.local.ui.detail.EventDetailScreen
import live.localapp.local.ui.home.HomeScreen
import live.localapp.local.ui.home.HomeViewModel
import live.localapp.local.ui.progress.ProgressScreen
import live.localapp.local.ui.progress.ProgressViewModel
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts
import org.koin.mp.KoinPlatform

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home),
    PLACES("Places", Icons.Outlined.Place),
    PLANS("Plans", Icons.Outlined.CalendarMonth),
    CHAT("Chat", Icons.Outlined.AutoAwesome), // AI concierge — not shown in tab bar
    PROFILE("Profile", Icons.Outlined.Person),
}

@Composable
fun AppNavigation(userLat: Double? = null, userLng: Double? = null) {
    var selectedTab by remember { mutableStateOf(Tab.HOME) }
    var detailEvent by remember { mutableStateOf<Event?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.White)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // Main tab content — always composed so scroll state is preserved
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    Tab.HOME -> {
                        val koin = KoinPlatform.getKoin()
                        val vm: HomeViewModel = viewModel {
                            koin.get<HomeViewModel>().also { vm ->
                                if (userLat != null && userLng != null) {
                                    vm.setUserLocation(userLat, userLng)
                                }
                            }
                        }
                        HomeScreen(
                            vm,
                            onEventClick = { detailEvent = it },
                            onConciergeClick = { selectedTab = Tab.CHAT },
                        )
                    }
                    Tab.CHAT -> {
                        val koin = KoinPlatform.getKoin()
                        val chatVm: ChatViewModel = viewModel { koin.get<ChatViewModel>() }
                        ChatScreen(chatVm)
                    }
                    Tab.PROFILE -> {
                        val vm: ProgressViewModel = viewModel { ProgressViewModel() }
                        ProgressScreen(vm)
                    }
                    else -> {
                        Box(
                            modifier = Modifier.fillMaxSize().background(AppColors.Snow),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                selectedTab.label,
                                style = TextStyle(
                                    fontFamily = AppFonts.Heading,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 24.sp,
                                    color = AppColors.Ink3,
                                ),
                            )
                        }
                    }
                }
            }

            BottomTabBar(selectedTab) { selectedTab = it }
        }

        // Detail overlay — rendered on top, home screen stays composed underneath
        if (detailEvent != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.White),
            ) {
                EventDetailScreen(
                    event = detailEvent!!,
                    onBack = { detailEvent = null },
                )
            }
        }
    }
}

/** Tabs rendered in the bar: 4 standard + AI concierge on the right. */
private val NAV_TABS = listOf(Tab.HOME, Tab.PLACES, Tab.PLANS, Tab.PROFILE)

@Composable
private fun BottomTabBar(selectedTab: Tab, onSelect: (Tab) -> Unit) {
    val glassBg = Color.White.copy(alpha = 0.82f)
    val glassBorder = Color.White.copy(alpha = 0.45f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(glassBg)
            .border(width = 0.5.dp, color = glassBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(64.dp)
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            // Standard tabs
            NAV_TABS.forEach { tab ->
                val isSelected = tab == selectedTab
                val tint = if (isSelected) AppColors.Coral else AppColors.Ink3

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(tab) },
                        )
                        .padding(horizontal = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .then(
                                if (isSelected) {
                                    Modifier.background(AppColors.CoralLt, RoundedCornerShape(10.dp))
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(20.dp), tint = tint)
                    }

                    Text(
                        tab.label,
                        style = TextStyle(
                            fontFamily = AppFonts.Body,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 9.sp,
                            color = tint,
                        ),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // AI Concierge button — inline, rightmost
            val aiSelected = selectedTab == Tab.CHAT
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(Tab.CHAT) },
                    )
                    .padding(horizontal = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = if (aiSelected) listOf(AppColors.CoralDk, AppColors.Coral)
                                else listOf(AppColors.Coral, Color(0xFFFF8787)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = "AI Concierge",
                        modifier = Modifier.size(18.dp),
                        tint = Color.White,
                    )
                }

                Text(
                    "AI",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 9.sp,
                        color = if (aiSelected) AppColors.Coral else AppColors.Ink3,
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
