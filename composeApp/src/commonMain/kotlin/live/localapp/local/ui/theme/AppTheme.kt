package live.localapp.local.ui.theme

import live.localapp.local.ui.common.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.composeapp.generated.resources.Res
import local.composeapp.generated.resources.fraunces_italic_variable
import local.composeapp.generated.resources.fraunces_variable
import local.composeapp.generated.resources.nunito_italic_variable
import local.composeapp.generated.resources.nunito_variable
import org.jetbrains.compose.resources.Font

// ── Color tokens from CSS :root ──

object AppColors {
    val Coral = Color(0xFFFF6B6B)
    val CoralDk = Color(0xFFE85555)
    val CoralLt = Color(0xFFFFE8E8)

    val Sage = Color(0xFF4ECBA0)
    val SageDk = Color(0xFF3AAD85)
    val SageLt = Color(0xFFE6F9F2)

    // "NEW" ribbon / freshness accent (Bo pull-to-refresh). Distinct, punchier
    // green than Sage so newly-fetched cards read as new at a glance.
    val NewGreen = Color(0xFF33B36B)

    val Sun = Color(0xFFFFB830)
    val SunDk = Color(0xFFE6A020)
    val SunLt = Color(0xFFFFF4DC)

    val Sky = Color(0xFF5B9CF6)
    val SkyDk = Color(0xFF3A7CE8)
    val SkyLt = Color(0xFFEBF2FF)

    val Plum = Color(0xFF8B5CF6)
    val PlumDk = Color(0xFF6D45C4)
    val PlumLt = Color(0xFFF3EEFF)

    val Orange = Color(0xFFFF9B4E)
    val OrangeDk = Color(0xFFE07030)
    val OrangeLt = Color(0xFFFFEEDF)

    // Reel-safe variants used by SplashScreen at 44sp italic on Cream.
    // The brand Sun (#FFB830) and Orange (#FF9B4E) fail WCAG AA large-text
    // (3:1) on Cream — these darker versions clear it while staying in the
    // same hue family. Identity surfaces keep the brand tokens above.
    val SplashSunReel = Color(0xFFB86E00)
    val SplashOrangeReel = Color(0xFFB85000)

    // ── Plan Detail V5 palette — warm, scoped to the Plan Detail screen only.
    //    Kept separate from the app-wide tokens above so we don't shift other
    //    screens' coral/bg during the V5 rollout.
    val V5Coral = Color(0xFFF56B63)
    val V5CoralDk = Color(0xFFE94F47)
    val V5CoralSoft = Color(0xFFFBE4E1)
    val V5CoralInk = Color(0xFF7A231F)
    val V5Ink = Color(0xFF1A1715)
    val V5Ink2 = Color(0xFF3D3834)
    val V5Ink3 = Color(0xFF6B6560)
    val V5Ink4 = Color(0xFFA49E98)
    val V5Line = Color(0xFFE8E3DD)
    val V5LineSoft = Color(0xFFF1ECE6)
    val V5Paper = Color(0xFFFBF8F4)
    val V5Shade = Color(0xFFF5EFE8)
    val V5Green = Color(0xFF4AA27A)

    val Ink = Color(0xFF1A1523)
    val Ink2 = Color(0xFF4A4458)
    val Ink3 = Color(0xFF8A849A)

    val Pebble = Color(0xFFC8C3D4)
    val Mist = Color(0xFFF0EDF6)
    val Snow = Color(0xFFFAF8FC)
    val White = Color(0xFFFFFFFF)
    val Cream = Color(0xFFFAFAF8)

    // Card backgrounds (gradients defined at usage site)
    val BgCoralStart = Color(0xFFFFF0EE)
    val BgCoralEnd = Color(0xFFFFE0DC)
    val BgSageStart = Color(0xFFEDFAF5)
    val BgSageEnd = Color(0xFFD5F5E8)
    val BgSunStart = Color(0xFFFFF8EC)
    val BgSunEnd = Color(0xFFFFF0CC)
    val BgSkyStart = Color(0xFFEEF5FF)
    val BgSkyEnd = Color(0xFFDCEEFF)
    val BgPlumStart = Color(0xFFF5F0FF)
    val BgPlumEnd = Color(0xFFEDE0FF)

    // Progress screen extras
    val PebbleProgress = Color(0xFFE8E4F0)
    val MistProgress = Color(0xFFF4F2F8)
    val Ink3Progress = Color(0xFF7A7390)
    val HeroBgDark = Color(0xFF0D0B12)
}

// ── Font families via CompositionLocal ──

val LocalHeadingFont = staticCompositionLocalOf<FontFamily> { FontFamily.Serif }
val LocalBodyFont = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }

object AppFonts {
    val Heading: FontFamily @Composable get() = LocalHeadingFont.current
    val Body: FontFamily @Composable get() = LocalBodyFont.current
}

// ── Typography ──

object AppType {
    val Logo: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Heading,
            fontWeight = FontWeight.Black,
            fontSize = 28.sp,
            color = AppColors.Ink,
            letterSpacing = (-1).sp,
        )

    val SectionTitle: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Heading,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = AppColors.Ink,
        )

    val GreetingBig: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Heading,
            fontWeight = FontWeight.Black,
            fontSize = 24.sp,
            color = AppColors.Ink,
            lineHeight = (24 * 1.2).sp,
        )

    val AiTitle: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Heading,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = AppColors.White,
            lineHeight = (20 * 1.25).sp,
        )

    val EventCardTitle: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Heading,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = AppColors.Ink,
            lineHeight = (13 * 1.3).sp,
        )

    val LabelSmall: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 12.sp,
        )

    val LabelTiny: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 11.sp,
        )

    val LabelMicro: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 9.sp,
        )

    val GreetingSmall: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = AppColors.Ink3,
        )

    val PointsCounter: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Heading,
            fontWeight = FontWeight.Black,
            fontSize = 64.sp,
            color = AppColors.White,
            letterSpacing = (-3).sp,
            lineHeight = 64.sp,
        )

    val StreakValue: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Heading,
            fontWeight = FontWeight.Black,
            fontSize = 22.sp,
            color = AppColors.Ink,
            lineHeight = 22.sp,
        )

    val EarnAction: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 13.sp,
            color = AppColors.Ink,
        )

    val EarnDesc: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            color = AppColors.Ink3,
        )

    val EarnPts: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Heading,
            fontWeight = FontWeight.Black,
            fontSize = 15.sp,
            color = AppColors.Coral,
        )

    val LevelName: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Heading,
            fontWeight = FontWeight.Black,
            fontSize = 14.sp,
            color = AppColors.Ink,
        )

    val LevelPts: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = AppColors.Ink3,
        )

    val TabLabel: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 9.sp,
        )

    val SegmentButton: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 13.sp,
        )

    val PlanName: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 13.sp,
            color = AppColors.Ink,
        )

    val PlanMeta: TextStyle
        @Composable get() = TextStyle(
            fontFamily = AppFonts.Body,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            color = AppColors.Ink3,
        )
}

// ── Shape tokens ──

object AppShapes {
    val CardLarge = RoundedCornerShape(20.dp)
    val CardMedium = RoundedCornerShape(18.dp)
    val CardSmall = RoundedCornerShape(16.dp)
    val Pill = RoundedCornerShape(20.dp)
    val IconBox = RoundedCornerShape(12.dp)
    val CategoryBox = RoundedCornerShape(18.dp)
    val SegmentControl = RoundedCornerShape(14.dp)
    val SegmentButton = RoundedCornerShape(11.dp)
    val AiInput = RoundedCornerShape(12.dp)
    val Avatar = RoundedCornerShape(11.dp)
}

// ── Material3 color scheme override ──

private val LocalColorScheme = lightColorScheme(
    primary = AppColors.Coral,
    onPrimary = AppColors.White,
    secondary = AppColors.Sage,
    background = AppColors.Snow,
    surface = AppColors.White,
    onBackground = AppColors.Ink,
    onSurface = AppColors.Ink,
    outline = AppColors.Pebble,
)

@Composable
fun LocalAppTheme(content: @Composable () -> Unit) {
    val headingFamily = FontFamily(
        Font(Res.font.fraunces_variable, FontWeight.Normal),
        Font(Res.font.fraunces_variable, FontWeight.Medium),
        Font(Res.font.fraunces_variable, FontWeight.Bold),
        Font(Res.font.fraunces_variable, FontWeight.Black),
        Font(Res.font.fraunces_italic_variable, FontWeight.Normal, FontStyle.Italic),
        Font(Res.font.fraunces_italic_variable, FontWeight.Medium, FontStyle.Italic),
        Font(Res.font.fraunces_italic_variable, FontWeight.Bold, FontStyle.Italic),
        Font(Res.font.fraunces_italic_variable, FontWeight.Black, FontStyle.Italic),
    )
    val bodyFamily = FontFamily(
        Font(Res.font.nunito_variable, FontWeight.Normal),
        Font(Res.font.nunito_variable, FontWeight.SemiBold),
        Font(Res.font.nunito_variable, FontWeight.Bold),
        Font(Res.font.nunito_variable, FontWeight.ExtraBold),
        Font(Res.font.nunito_variable, FontWeight.Black),
        Font(Res.font.nunito_italic_variable, FontWeight.Normal, FontStyle.Italic),
    )

    CompositionLocalProvider(
        LocalHeadingFont provides headingFamily,
        LocalBodyFont provides bodyFamily,
    ) {
        MaterialTheme(
            colorScheme = LocalColorScheme,
            content = content,
        )
    }
}
