package live.localapp.local.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import live.localapp.local.ui.common.pressable
import live.localapp.local.ui.theme.AppColors
import live.localapp.local.ui.theme.AppFonts

@Composable
fun PremiumPaywall(
    onSubscribe: (isAnnual: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onRestore: () -> Unit = {},
    monthlyPrice: String? = null,
    annualPrice: String? = null,
    introPrice: String? = null,
) {
    val hasIntro = introPrice != null
    val displayMonthly = if (hasIntro) introPrice!! else (monthlyPrice ?: "$6.99")
    val displayMonthlySubtext = if (hasIntro) "then ${monthlyPrice ?: "$6.99"}/mo" else "/month"
    val displayMonthlyBadge = if (hasIntro) "First month" else null
    val displayAnnual = annualPrice ?: "$49.99"
    val annualPerMonth = try {
        val raw = displayAnnual.replace(Regex("[^0-9.]"), "").toDouble()
        val perMonth = raw / 12
        val whole = perMonth.toInt()
        val frac = ((perMonth - whole) * 100).toInt()
        "$$whole.${frac.toString().padStart(2, '0')}/mo"
    } catch (_: Exception) { "$4.17/mo" }
    var isAnnual by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1523)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Close button
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Close",
                    modifier = Modifier
                        .size(24.dp)
                        .pressable(onClick = onDismiss),
                    tint = Color.White.copy(alpha = 0.5f),
                )
            }

            Spacer(Modifier.height(16.dp))

            // App icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(AppColors.Coral, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "L",
                    style = TextStyle(
                        fontFamily = AppFonts.Heading,
                        fontWeight = FontWeight.Black,
                        fontSize = 28.sp,
                        color = Color.White,
                    ),
                )
            }

            Spacer(Modifier.height(20.dp))

            // Headline
            Text(
                "Yocal Premium",
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 28.sp,
                    color = Color.White,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Plan together, effortlessly",
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    fontStyle = FontStyle.Italic,
                    color = AppColors.Coral,
                ),
            )

            Spacer(Modifier.height(28.dp))

            // Benefits
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                BenefitRow(Icons.Outlined.CalendarMonth, "Unlimited shareable plans", "Build as many plans as you want")
                BenefitRow(Icons.Outlined.Restaurant, "One-tap reservations", "Book tables directly from your plan")
                BenefitRow(Icons.Outlined.Star, "Priority recommendations", "First access to trending events")
                BenefitRow(Icons.Outlined.Share, "Group coordination", "Share, vote, and confirm with your crew")
            }

            Spacer(Modifier.weight(1f))

            // Pricing toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Annual option
                PricingCard(
                    modifier = Modifier.weight(1f),
                    isSelected = isAnnual,
                    label = "Annual",
                    price = displayAnnual,
                    subtext = annualPerMonth,
                    badge = "Best value",
                    onClick = { isAnnual = true },
                )
                // Monthly option
                PricingCard(
                    modifier = Modifier.weight(1f),
                    isSelected = !isAnnual,
                    label = "Monthly",
                    price = displayMonthly,
                    subtext = displayMonthlySubtext,
                    badge = displayMonthlyBadge,
                    onClick = { isAnnual = false },
                )
            }

            Spacer(Modifier.height(16.dp))

            // Subscribe CTA
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.linearGradient(listOf(AppColors.Coral, Color(0xFFE85D5D))),
                    )
                    .pressable(onClick = { onSubscribe(isAnnual) })
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (isAnnual) "Start Premium" else if (hasIntro) "Try for $displayMonthly" else "Start Premium",
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        color = Color.White,
                    ),
                )
            }

            Spacer(Modifier.height(10.dp))

            // Restore + terms
            Text(
                "Restore purchase",
                modifier = Modifier.pressable(onClick = onRestore),
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.4f),
                    textDecoration = TextDecoration.Underline,
                ),
            )
        }
    }
}

@Composable
private fun BenefitRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(AppColors.Coral.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = AppColors.Coral)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                    color = Color.White,
                ),
            )
            Text(
                subtitle,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.5f),
                ),
            )
        }
    }
}

@Composable
private fun PricingCard(
    modifier: Modifier = Modifier,
    isSelected: Boolean,
    label: String,
    price: String,
    subtext: String,
    badge: String?,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) AppColors.Coral else Color.White.copy(alpha = 0.12f)
    val bgColor = if (isSelected) AppColors.Coral.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.04f)

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bgColor)
                .border(2.dp, borderColor, RoundedCornerShape(14.dp))
                .pressable(onClick = onClick)
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (badge != null) {
                Text(
                    badge,
                    modifier = Modifier
                        .background(AppColors.Coral, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = TextStyle(
                        fontFamily = AppFonts.Body,
                        fontWeight = FontWeight.Black,
                        fontSize = 9.sp,
                        color = Color.White,
                    ),
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                label,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                price,
                style = TextStyle(
                    fontFamily = AppFonts.Heading,
                    fontWeight = FontWeight.Black,
                    fontSize = 24.sp,
                    color = Color.White,
                ),
            )
            Text(
                subtext,
                style = TextStyle(
                    fontFamily = AppFonts.Body,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.4f),
                ),
            )
        }
    }
}
