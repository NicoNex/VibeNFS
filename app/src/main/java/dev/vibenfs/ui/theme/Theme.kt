package dev.vibenfs.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.vibenfs.R

// Google Sans Flex is variable: wdth makes posters condensed, ROND softens UI text.
private fun flex(weight: Int, width: Float = 100f, round: Float = 100f) = Font(
    R.font.google_sans_flex,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
        FontVariation.Setting("ROND", round),
    ),
)

/** Condensed heavy cut for editorial headlines (volume names, hero numbers). */
private val PosterFamily = FontFamily(flex(800, width = 25f, round = 0f), flex(900, width = 25f, round = 0f))
private val FlexFamily = FontFamily(flex(400), flex(500), flex(600), flex(700))
val MonoFamily = FontFamily(
    Font(R.font.google_sans_code, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.google_sans_code, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.google_sans_code, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private val Base = Typography()
private fun TextStyle.flex() = copy(fontFamily = FlexFamily)
private fun TextStyle.poster(size: Int, line: Int) =
    copy(fontFamily = PosterFamily, fontWeight = FontWeight(900), letterSpacing = (-0.02).em, fontSize = size.sp, lineHeight = line.sp)
private fun TextStyle.semi() = copy(fontFamily = FlexFamily, fontWeight = FontWeight.SemiBold)

private val EditorialTypography = Typography(
    displayLarge = Base.displayLarge.poster(88, 84),
    displayMedium = Base.displayMedium.poster(64, 62),
    displaySmall = Base.displaySmall.poster(48, 48),
    headlineLarge = Base.headlineLarge.semi(),
    headlineMedium = Base.headlineMedium.semi(),
    headlineSmall = Base.headlineSmall.semi(),
    titleLarge = Base.titleLarge.semi(),
    titleMedium = Base.titleMedium.flex(),
    titleSmall = Base.titleSmall.flex(),
    bodyLarge = Base.bodyLarge.flex(),
    bodyMedium = Base.bodyMedium.flex(),
    bodySmall = Base.bodySmall.flex(),
    labelLarge = Base.labelLarge.flex(),
    labelMedium = Base.labelMedium.flex(),
    labelSmall = Base.labelSmall.flex(),
)

// ponytail: minSdk 34 always has Material You, so colors come only from the system wallpaper.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VibeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = remember(dark, context) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = EditorialTypography,
        motionScheme = remember { MotionScheme.expressive() },
        content = content,
    )
}
